# Notifications HA NATS Design

## Context

`service-notifications` currently stores notification state in Postgres and exposes live updates to
Portal clients through server-sent events. SSE listeners are held in memory by
`NotificationLiveBroadcaster`, so live delivery is local to a single pod.

After the Rackspace migration the service ran with multiple replicas. A notification create request
could land on pod A while the user's SSE connection was registered on pod B. The database rows were
created correctly, but the live update only reached pod A. `notification_outbox` also stayed pending
because `NotificationOutboxProcessor` only counts rows and logs `pendingCount`.

The temporary infrastructure workaround pins `service-notifications` to one replica. The durable fix
is to make live notification fanout replica-safe.

## Goals

- Allow `service-notifications` to run with multiple replicas.
- Keep Postgres as the authoritative store for notification state and outbox retry state.
- Use the existing platform NATS broker for cross-pod live invalidation events.
- Send all live notification changes through the same path: `created`, `read`, `unread`, and `action`.
- Treat live events as invalidation signals. Clients refetch or invalidate local queries after
  receiving `notifications.changed`.
- Process `notification.created` outbox rows safely across multiple pods with retry/backoff.
- Avoid introducing a separate NATS broker or JetStream dependency for live SSE fanout.

## Non-Goals

- Do not make SSE delivery itself durable. If a browser is disconnected, it catches up by reading the
  inbox from Postgres.
- Do not replace the Postgres outbox with NATS or JetStream.
- Do not introduce sticky sessions.
- Do not expose internal notification subjects to user workloads or plugin event subjects.
- Do not implement email, Discord, or Minecraft delivery channels in this change.

## Architecture

Use the existing platform NATS broker with a dedicated internal notification subject:

```text
grounds.internal.notifications.changed
```

`service-notifications` gets a dedicated NATS service identity with publish and subscribe permission
for this exact subject. This keeps internal control-plane events separate from user/plugin event
subjects without operating a second broker.

Core NATS is sufficient for this live signal. Durability remains in Postgres:

- notification data is stored in `notifications`, `notification_recipients`, and related tables
- `notification_outbox` tracks durable `notification.created` processing
- NATS only wakes all pods so the pod that owns a user's SSE sink can emit a local SSE event

## Components

### NotificationLiveEventPublisher

Small application-scoped service responsible for publishing `NotificationLiveEvent` payloads to NATS.

It is used by:

- `NotificationOutboxProcessor` for `created`
- `NotificationResource` for `read` and `unread`
- `NotificationActionService` for `action`

Direct calls to `NotificationLiveBroadcaster.publish(...)` are removed from HTTP/action code.

### NotificationLiveEventSubscriber

Application-scoped NATS subscriber. On startup it connects to NATS and subscribes to:

```text
grounds.internal.notifications.changed
```

For every message it deserializes `NotificationLiveEvent` and calls local
`NotificationLiveBroadcaster.publish(event)`.

Every pod subscribes. Pods without listeners for the target user do nothing.

### NotificationOutboxProcessor

The outbox processor becomes the durable dispatcher for `notification.created`.

Each scheduled run:

1. Claims a bounded batch of pending rows where `available_at <= now()`.
2. Uses `FOR UPDATE SKIP LOCKED` so multiple pods can process concurrently without double-claiming.
3. Supports only known event types. Unknown event types are marked failed with `last_error`.
4. For `notification.created`, loads recipients from `notification_recipients`.
5. Publishes one `notifications.changed` NATS event per recipient.
6. Marks the outbox row `processed` after successful publishes.
7. On failure increments `attempts`, stores `last_error`, and moves `available_at` forward using
   bounded exponential backoff. After the max attempts threshold the row is marked `failed`.

The processor should publish after the notification transaction has committed. It must not run inside
the original create transaction.

## Data Flow

### Created

```text
Forge/API producer
  -> POST /v1/notification-events
  -> Postgres transaction creates notification + recipients + actions + outbox
  -> outbox processor claims notification.created
  -> loads recipients
  -> publishes grounds.internal.notifications.changed for each recipient
  -> all pods receive NATS message
  -> local broadcaster emits SSE only where listeners exist
  -> Portal invalidates/refetches notification queries
```

### Read / Unread

```text
Portal tab A
  -> POST /v1/notifications/{id}/read or unread
  -> Postgres updates recipient read_at
  -> publisher sends grounds.internal.notifications.changed
  -> all pods receive NATS message
  -> every open tab/device for that user receives SSE
  -> Portal invalidates/refetches notification queries
```

`read` and `unread` do not require durable outbox rows in this design. The committed Postgres state is
the source of truth. If the live NATS publish fails, the initiating request should still succeed after
the DB update, but the failure must be logged and counted. The next poll/refetch still observes the
correct state.

### Action

```text
Portal tab
  -> POST /v1/notifications/{id}/actions/{actionKey}
  -> action service stores result and updates read state as needed
  -> publisher sends grounds.internal.notifications.changed with reason action
  -> all tabs/devices refetch
```

## Event Payload

The NATS payload is the existing live event shape:

```json
{
  "type": "notifications.changed",
  "userId": "40f46f10-0b38-49e3-b216-fb7dffe40928",
  "notificationId": "5a44bbac-4c95-4c54-a37d-9c108abd43fb",
  "reason": "created",
  "occurredAt": "2026-06-18T00:00:00Z"
}
```

Allowed reasons:

- `created`
- `read`
- `unread`
- `action`
- `heartbeat` remains local to `NotificationLiveBroadcaster` and is not sent through NATS.

## Configuration

Required runtime configuration:

- `NATS_URL`
- credentials or token for the `service-notifications` NATS identity
- optional reconnect settings

The identity should be least-privilege:

```text
publish:   grounds.internal.notifications.changed
subscribe: grounds.internal.notifications.changed
```

Pulumi should provide the NATS URL and credentials to the `service-notifications` Helm release. The
existing one-replica workaround remains until this is deployed and verified, then `replicaCount` can be
raised again.

## Error Handling

### NATS unavailable during created processing

The outbox row remains unprocessed. The processor increments `attempts`, stores `last_error`, and
reschedules with backoff. When NATS recovers, the live invalidation may arrive late. That is acceptable
because the inbox state is already durable in Postgres.

### NATS unavailable during read/unread/action

The DB mutation succeeds. The publish failure is logged and counted, but the HTTP request does not roll
back. Connected clients may miss the immediate live invalidation, but the next refetch reads correct
Postgres state.

### Subscriber failure

The subscriber reconnects to NATS. Because live events are non-durable, missed messages are acceptable.
Portal must rely on query refetches and normal navigation to catch up.

### Duplicate live events

Duplicate invalidation events are acceptable. Portal should treat them as refetch triggers, not as
commands that mutate state blindly.

## Observability

Add logs and metrics for:

- NATS publisher connect/disconnect
- NATS subscriber connect/disconnect
- live events published by reason
- live events received by reason
- outbox rows claimed, processed, retried, failed
- outbox processing duration
- NATS publish failures

Existing logs such as "Polled outbox events" should be replaced with stateful processor logs that show
claimed and processed counts.

## Testing

### Unit / Quarkus Tests

- `NotificationOutboxProcessor` processes a pending `notification.created` row and marks it processed.
- Processor emits NATS live events for recipients instead of directly using the local broadcaster.
- Processor retry path increments `attempts`, sets `last_error`, and updates `available_at`.
- Unknown outbox event types are handled deterministically.
- `read`, `unread`, and `action` publish through `NotificationLiveEventPublisher`.
- Direct HTTP create path no longer calls `NotificationLiveBroadcaster.publish(...)`.

### Integration-Style Test Shape

Use a fake in-process NATS publisher/subscriber abstraction in tests rather than requiring a real NATS
broker for every unit test. If a real broker test is added, keep it focused on connection/subscription
behavior and make it skippable in environments without a container runtime.

### Manual Verification

1. Deploy with `replicaCount = 2`.
2. Open two Portal sessions for the same user.
3. Confirm `created`, `read`, `unread`, and `action` changes propagate across tabs.
4. Confirm `notification_outbox` does not accumulate pending rows after normal notification creation.
5. Restart one pod and confirm the remaining pod continues to publish and receive live updates.

## Rollout

1. Implement NATS publisher/subscriber and outbox processing while keeping `replicaCount = 1`.
2. Deploy and verify outbox rows are processed.
3. Raise `service-notifications` back to multiple replicas in Pulumi.
4. Verify cross-pod live notification behavior.
5. Close issue `groundsgg/service-notifications#15`.

