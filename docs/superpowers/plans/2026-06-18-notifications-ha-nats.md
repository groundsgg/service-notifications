# Notifications HA NATS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `service-notifications` safe to run with multiple replicas by routing all live notification invalidation through NATS and processing `notification.created` outbox rows durably.

**Architecture:** Postgres remains the authoritative notification and outbox store. Core NATS is used only as a cross-pod live invalidation bus on `grounds.internal.notifications.changed`; each pod subscribes and forwards matching events to its local SSE broadcaster.

**Tech Stack:** Kotlin, Quarkus, JDBC/Postgres, Quarkus Scheduler, Core NATS via `io.nats:jnats`, JUnit/Quarkus tests.

---

## File Map

- Modify `build.gradle.kts` to add the JVM NATS client dependency.
- Create `src/main/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisher.kt` for the app-facing live event publisher interface.
- Create `src/main/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBus.kt` for NATS publish/subscribe wiring.
- Keep `src/main/kotlin/gg/grounds/notifications/live/NotificationLiveBroadcaster.kt` NATS-agnostic. It remains the local SSE-only component.
- Modify `src/main/kotlin/gg/grounds/notifications/api/NotificationResource.kt` to publish `read` and `unread` through `NotificationLiveEventPublisher` and remove direct create broadcast.
- Modify `src/main/kotlin/gg/grounds/notifications/actions/NotificationActionService.kt` to publish `action` through `NotificationLiveEventPublisher`.
- Modify `src/main/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessor.kt` to claim/process/retry `notification.created` rows and publish `created`.
- Add or update tests:
  - `src/test/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessorTest.kt`
  - `src/test/kotlin/gg/grounds/notifications/api/NotificationResourceTest.kt`
  - `src/test/kotlin/gg/grounds/notifications/actions/ProjectInviteActionResourceTest.kt`
  - `src/test/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBusTest.kt`

## Task 1: Add NATS Dependency And Live Event Publisher Boundary

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisher.kt`
- Test: `src/test/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisherTest.kt`

- [ ] **Step 1: Write the boundary test**

Create `src/test/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisherTest.kt`:

```kotlin
package gg.grounds.notifications.live

import java.time.OffsetDateTime
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationLiveEventPublisherTest {
    @Test
    fun `recording publisher stores emitted events`() {
        val publisher = RecordingNotificationLiveEventPublisher()
        val event =
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = "user-1",
                notificationId = UUID.randomUUID().toString(),
                reason = "created",
                occurredAt = OffsetDateTime.parse("2026-06-18T00:00:00Z"),
            )

        publisher.publish(event)

        assertEquals(listOf(event), publisher.events)
    }

    private class RecordingNotificationLiveEventPublisher : NotificationLiveEventPublisher {
        val events = mutableListOf<NotificationLiveEvent>()

        override fun publish(event: NotificationLiveEvent) {
            events += event
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.live.NotificationLiveEventPublisherTest
```

Expected: FAIL because `NotificationLiveEventPublisher` does not exist.

- [ ] **Step 3: Add the NATS dependency**

Modify `build.gradle.kts` dependencies:

```kotlin
implementation("io.nats:jnats:2.25.3")
```

- [ ] **Step 4: Add the publisher interface**

Create `src/main/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisher.kt`:

```kotlin
package gg.grounds.notifications.live

interface NotificationLiveEventPublisher {
    fun publish(event: NotificationLiveEvent)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.live.NotificationLiveEventPublisherTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts src/main/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisher.kt src/test/kotlin/gg/grounds/notifications/live/NotificationLiveEventPublisherTest.kt
git commit -S -m "feat: add notification live event publisher"
```

## Task 2: Implement NATS Live Event Bus

**Files:**
- Create: `src/main/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBus.kt`
- Test: `src/test/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBusTest.kt`

- [ ] **Step 1: Write the serialization/publish test**

Create `src/test/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBusTest.kt`:

```kotlin
package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NatsNotificationLiveEventBusTest {
    @Test
    fun `publishes changed events to the internal subject`() {
        val nats = RecordingNatsClient()
        val bus = NatsNotificationLiveEventBus.ForTests(ObjectMapper().registerKotlinModule(), nats)
        val event =
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = "user-1",
                notificationId = "notification-1",
                reason = "read",
                occurredAt = OffsetDateTime.parse("2026-06-18T00:00:00Z"),
            )

        bus.publish(event)

        assertEquals("grounds.internal.notifications.changed", nats.published.single().subject)
        assertEquals(
            """{"type":"notifications.changed","userId":"user-1","notificationId":"notification-1","reason":"read","occurredAt":"2026-06-18T00:00:00Z"}""",
            nats.published.single().payload.toString(StandardCharsets.UTF_8),
        )
    }

    private class RecordingNatsClient : NatsNotificationLiveEventBus.NatsClient {
        val published = mutableListOf<PublishedMessage>()

        override fun publish(subject: String, payload: ByteArray) {
            published += PublishedMessage(subject, payload)
        }
    }

    private data class PublishedMessage(val subject: String, val payload: ByteArray)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.live.NatsNotificationLiveEventBusTest
```

Expected: FAIL because `NatsNotificationLiveEventBus` does not exist.

- [ ] **Step 3: Implement the NATS bus with a test constructor**

Create `src/main/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBus.kt`:

```kotlin
package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Connection
import io.nats.client.Nats
import io.nats.client.Options
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class NatsNotificationLiveEventBus(
    private val objectMapper: ObjectMapper,
    private val broadcaster: NotificationLiveBroadcaster,
    @param:ConfigProperty(name = "notifications.nats.url", defaultValue = "")
    private val natsUrl: String,
    @param:ConfigProperty(name = "notifications.nats.token", defaultValue = "")
    private val natsToken: String,
) : NotificationLiveEventPublisher {
    private var connection: Connection? = null
    private var client: NatsClient? = null

    @PostConstruct
    fun start() {
        if (natsUrl.isBlank()) {
            LOG.warn("NATS live notification bus disabled because notifications.nats.url is not configured")
            return
        }
        val optionsBuilder = Options.Builder().server(natsUrl).connectionName("service-notifications")
        if (natsToken.isNotBlank()) {
            optionsBuilder.token(natsToken.toCharArray())
        }
        val options = optionsBuilder.build()
        val connected = Nats.connect(options)
        connection = connected
        client = ConnectionNatsClient(connected)
        connected.createDispatcher { message ->
            val event = objectMapper.readValue(message.data, NotificationLiveEvent::class.java)
            broadcaster.publish(event)
        }.subscribe(SUBJECT)
        LOG.infof("Subscribed to notification live NATS subject (subject=%s)", SUBJECT)
    }

    override fun publish(event: NotificationLiveEvent) {
        val payload = objectMapper.writeValueAsBytes(event)
        client?.publish(SUBJECT, payload)
            ?: throw IllegalStateException("NATS live notification bus is not connected")
    }

    @PreDestroy
    fun stop() {
        connection?.drain(java.time.Duration.ofSeconds(2))
        connection?.close()
    }

    interface NatsClient {
        fun publish(subject: String, payload: ByteArray)
    }

    private class ConnectionNatsClient(private val connection: Connection) : NatsClient {
        override fun publish(subject: String, payload: ByteArray) {
            connection.publish(subject, payload)
        }
    }

    class ForTests(
        objectMapper: ObjectMapper,
        private val natsClient: NatsClient,
    ) : NotificationLiveEventPublisher {
        private val mapper = objectMapper

        override fun publish(event: NotificationLiveEvent) {
            natsClient.publish(SUBJECT, mapper.writeValueAsBytes(event))
        }
    }

    companion object {
        const val SUBJECT = "grounds.internal.notifications.changed"
        private val LOG: Logger = Logger.getLogger(NatsNotificationLiveEventBus::class.java)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.live.NatsNotificationLiveEventBusTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBus.kt src/test/kotlin/gg/grounds/notifications/live/NatsNotificationLiveEventBusTest.kt
git commit -S -m "feat: publish notification live events over nats"
```

## Task 3: Process `notification.created` Outbox Rows

**Files:**
- Modify: `src/main/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessor.kt`
- Modify: `src/test/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessorTest.kt`

- [ ] **Step 1: Replace the old no-op test with a processing test**

In `NotificationOutboxProcessorTest`, replace `pollingPendingEventsDoesNotMarkUndeliveredEventsProcessed` with:

```kotlin
@Test
fun pendingNotificationCreatedEventsPublishRecipientsAndMarkProcessed() {
    val notificationId = UUID.randomUUID()
    val outboxId = UUID.randomUUID()
    seedNotificationWithRecipient(notificationId, "user-1")
    seedOutbox(outboxId, notificationId, "notification.created", """{"notificationId":"$notificationId"}""")

    outboxProcessor.processPendingEvents()

    val events = recordingPublisher.events
    assertEquals(1, events.size)
    assertEquals("user-1", events.single().userId)
    assertEquals(notificationId.toString(), events.single().notificationId)
    assertEquals("created", events.single().reason)
    assertOutboxStatus(outboxId, "processed", attempts = 0)
}
```

Add test helpers in the same class:

```kotlin
private fun seedNotificationWithRecipient(notificationId: UUID, userId: String) {
    dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                INSERT INTO notifications
                  (id, idempotency_key, type, category, priority, scope_type, actor_type, title, body, data)
                VALUES
                  ('$notificationId', 'test-$notificationId', 'project.invite.created', 'project', 'normal',
                   'project', 'user', 'Project invite', 'Body', '{}'::jsonb)
                """
                    .trimIndent()
            )
            statement.executeUpdate(
                """
                INSERT INTO notification_recipients (id, notification_id, user_id)
                VALUES ('${UUID.randomUUID()}', '$notificationId', '$userId')
                """
                    .trimIndent()
            )
        }
    }
}

private fun seedOutbox(outboxId: UUID, notificationId: UUID, eventType: String, payload: String) {
    dataSource.connection.use { connection ->
        connection.prepareStatement(
            """
            INSERT INTO notification_outbox (id, event_type, aggregate_id, payload)
            VALUES (?, ?, ?, ?::jsonb)
            """
                .trimIndent()
        ).use { statement ->
            statement.setObject(1, outboxId)
            statement.setString(2, eventType)
            statement.setObject(3, notificationId)
            statement.setString(4, payload)
            statement.executeUpdate()
        }
    }
}
```

- [ ] **Step 2: Inject a test publisher**

Add a Quarkus test alternative in the test file:

```kotlin
@jakarta.enterprise.inject.Alternative
@jakarta.annotation.Priority(1)
@jakarta.enterprise.context.ApplicationScoped
class RecordingNotificationLiveEventPublisher : NotificationLiveEventPublisher {
    val events = mutableListOf<NotificationLiveEvent>()

    override fun publish(event: NotificationLiveEvent) {
        events += event
    }

    fun reset() {
        events.clear()
    }
}
```

Inject it:

```kotlin
@Inject lateinit var recordingPublisher: RecordingNotificationLiveEventPublisher
```

Reset it in `resetDatabase()`.

- [ ] **Step 3: Run the test to verify it fails**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.outbox.NotificationOutboxProcessorTest
```

Expected: FAIL because the processor only counts pending rows.

- [ ] **Step 4: Implement outbox claim/process**

Replace `NotificationOutboxProcessor` with a processor that:

```kotlin
@ApplicationScoped
class NotificationOutboxProcessor(
    private val dataSource: DataSource,
    private val liveEventPublisher: NotificationLiveEventPublisher,
) {
    @Scheduled(every = "10s")
    fun processPendingEvents() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val events = claimPendingEvents(connection)
                events.forEach { event -> processEvent(connection, event) }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }
}
```

Use this claim query:

```sql
SELECT id, event_type, aggregate_id, payload::text, attempts
FROM notification_outbox
WHERE status = 'pending' AND available_at <= now()
ORDER BY created_at ASC
LIMIT 25
FOR UPDATE SKIP LOCKED
```

For `notification.created`, load recipients:

```sql
SELECT user_id
FROM notification_recipients
WHERE notification_id = ?
ORDER BY created_at ASC
```

Publish:

```kotlin
NotificationLiveEvent(
    type = "notifications.changed",
    userId = userId,
    notificationId = event.aggregateId.toString(),
    reason = "created",
    occurredAt = OffsetDateTime.now(),
)
```

Mark processed:

```sql
UPDATE notification_outbox
SET status = 'processed', processed_at = now(), last_error = NULL
WHERE id = ?
```

- [ ] **Step 5: Run the test to verify it passes**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.outbox.NotificationOutboxProcessorTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessor.kt src/test/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessorTest.kt
git commit -S -m "feat: process notification created outbox events"
```

## Task 4: Add Outbox Retry And Failed State

**Files:**
- Modify: `src/main/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessor.kt`
- Modify: `src/test/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessorTest.kt`

- [ ] **Step 1: Add a failing retry test**

Add to `NotificationOutboxProcessorTest`:

```kotlin
@Test
fun failedPublishIncrementsAttemptsAndSchedulesRetry() {
    recordingPublisher.failWith = IllegalStateException("nats unavailable")
    val notificationId = UUID.randomUUID()
    val outboxId = UUID.randomUUID()
    seedNotificationWithRecipient(notificationId, "user-1")
    seedOutbox(outboxId, notificationId, "notification.created", """{"notificationId":"$notificationId"}""")

    outboxProcessor.processPendingEvents()

    assertOutboxRetry(outboxId, attempts = 1, expectedStatus = "pending")
}
```

Update the recording publisher:

```kotlin
var failWith: RuntimeException? = null

override fun publish(event: NotificationLiveEvent) {
    failWith?.let { throw it }
    events += event
}
```

- [ ] **Step 2: Run the retry test to verify it fails**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.outbox.NotificationOutboxProcessorTest
```

Expected: FAIL because publish failure rolls back or leaves attempts unchanged.

- [ ] **Step 3: Implement retry update**

Catch per-event processing failures and update the row in the same transaction:

```sql
UPDATE notification_outbox
SET attempts = attempts + 1,
    last_error = ?,
    available_at = now() + (? * interval '1 second'),
    status = CASE WHEN attempts + 1 >= ? THEN 'failed' ELSE 'pending' END
WHERE id = ?
```

Use:

```kotlin
private const val MAX_ATTEMPTS = 5

private fun backoffSeconds(nextAttempts: Int): Int =
    minOf(300, 5 * (1 shl (nextAttempts - 1).coerceAtLeast(0)))
```

- [ ] **Step 4: Run tests to verify retry passes**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.outbox.NotificationOutboxProcessorTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessor.kt src/test/kotlin/gg/grounds/notifications/outbox/NotificationOutboxProcessorTest.kt
git commit -S -m "feat: retry failed notification outbox events"
```

## Task 5: Route Create/Read/Unread/Action Through The Publisher

**Files:**
- Modify: `src/main/kotlin/gg/grounds/notifications/api/NotificationResource.kt`
- Modify: `src/main/kotlin/gg/grounds/notifications/actions/NotificationActionService.kt`
- Modify tests:
  - `src/test/kotlin/gg/grounds/notifications/api/NotificationResourceTest.kt`
  - `src/test/kotlin/gg/grounds/notifications/actions/ProjectInviteActionResourceTest.kt`

- [ ] **Step 1: Update create test to assert no direct broadcaster event**

In `NotificationResourceTest`, update the notification create test so it verifies:

```kotlin
assertEquals(0, liveBroadcaster.listenerCount("recipient-user"))
```

and assert no SSE event is sent during create. The live created event must come from outbox processing.

- [ ] **Step 2: Add read/unread publisher tests**

Add assertions that `markNotificationRead` and `markNotificationUnread` publish through the recording
publisher with reasons `read` and `unread`.

Expected assertions:

```kotlin
val event = recordingPublisher.events.single()
assertEquals("notifications.changed", event.type)
assertEquals("user-alpha", event.userId)
assertEquals(notificationId.toString(), event.notificationId)
assertEquals("read", event.reason)
```

- [ ] **Step 3: Add action publisher test**

In `ProjectInviteActionResourceTest`, assert a successful action publishes:

```kotlin
assertEquals("action", recordingPublisher.events.single().reason)
assertEquals(notificationId.toString(), recordingPublisher.events.single().notificationId)
```

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.api.NotificationResourceTest --tests gg.grounds.notifications.actions.ProjectInviteActionResourceTest
```

Expected: FAIL because code still calls `NotificationLiveBroadcaster` directly.

- [ ] **Step 5: Modify resources/services**

In `NotificationResource`, inject `NotificationLiveEventPublisher` instead of using
`NotificationLiveBroadcaster` for create/read/unread. Keep `NotificationLiveBroadcaster` only for
`streamNotifications`.

For create:

```kotlin
// No live publish here. The outbox processor publishes created events after commit.
```

For read/unread:

```kotlin
liveEventPublisher.publish(
    NotificationLiveEvent(
        type = "notifications.changed",
        userId = userId,
        notificationId = id.toString(),
        reason = "read",
        occurredAt = OffsetDateTime.now(),
    )
)
```

In `NotificationActionService`, replace direct broadcaster publish with `liveEventPublisher.publish(...)`
and reason `action`.

- [ ] **Step 6: Run tests to verify they pass**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.api.NotificationResourceTest --tests gg.grounds.notifications.actions.ProjectInviteActionResourceTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/gg/grounds/notifications/api/NotificationResource.kt src/main/kotlin/gg/grounds/notifications/actions/NotificationActionService.kt src/test/kotlin/gg/grounds/notifications/api/NotificationResourceTest.kt src/test/kotlin/gg/grounds/notifications/actions/ProjectInviteActionResourceTest.kt
git commit -S -m "feat: publish notification changes through nats"
```

## Task 6: Add Runtime Configuration And Pulumi Wiring

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify in `grounds-pulumi`: `platform/src/platform/notifications.ts`

- [ ] **Step 1: Add application properties**

In `service-notifications/src/main/resources/application.properties` add:

```properties
notifications.nats.url=${NATS_URL:}
notifications.nats.token=${NATS_TOKEN:}
```

- [ ] **Step 2: Commit service config**

```bash
git add src/main/resources/application.properties
git commit -S -m "chore: configure notifications nats"
```

- [ ] **Step 3: Wire Pulumi after service release**

In `grounds-pulumi/platform/src/platform/notifications.ts`, add env values to the Helm release:

```ts
env: [
  { name: "NATS_URL", value: "nats://nats.nats.svc.cluster.local:4222" },
  {
    name: "NATS_TOKEN",
    valueFrom: {
      secretKeyRef: {
        name: "service-notifications-nats",
        key: "token",
      },
    },
  },
]
```

Create a Kubernetes secret named `service-notifications-nats` with the key `token` from the platform
NATS service identity token. Keep `replicaCount: 1` until the service release is deployed and
live-tested.

- [ ] **Step 4: Commit Pulumi config**

```bash
git add platform/src/platform/notifications.ts
git commit -S -m "chore: wire notifications nats config"
```

## Task 7: Full Verification And Rollout

**Files:**
- No new code files.
- Later modify: `grounds-pulumi/platform/src/platform/notifications.ts` to restore multi-replica after service release.

- [ ] **Step 1: Run focused service tests**

Run:

```bash
./gradlew test --tests gg.grounds.notifications.outbox.NotificationOutboxProcessorTest --tests gg.grounds.notifications.api.NotificationResourceTest --tests gg.grounds.notifications.actions.ProjectInviteActionResourceTest --tests gg.grounds.notifications.live.NatsNotificationLiveEventBusTest
```

Expected: PASS.

- [ ] **Step 2: Run full build**

Run:

```bash
./gradlew build
```

Expected: PASS.

- [ ] **Step 3: Open PR using central template**

Use the shared `groundsgg/.github` pull request template. Include:

- Summary of NATS live fanout
- Summary of outbox processing/retry
- Test commands
- Link to `groundsgg/service-notifications#15`

- [ ] **Step 4: Merge and release service-notifications**

After CI is green and the PR is approved, merge and let release-please create the release PR. Merge the
release PR, then wait for the image/tag to publish.

- [ ] **Step 5: Update Pulumi image tag and deploy**

Update `grounds-pulumi` to the new `service-notifications` image tag while keeping `replicaCount: 1`.
Deploy platform stack.

- [ ] **Step 6: Verify live outbox processing**

Run in the platform cluster:

```bash
kubectl --context hbr-grounds-platform-oidc -n notifications logs deploy/service-notifications --since=10m
kubectl --context hbr-grounds-platform-oidc -n notifications exec notifications-db-1 -- psql -U postgres -d notifications -c "select status, count(*) from notification_outbox group by status order by status;"
```

Expected:

- processor logs show claimed/processed rows
- no growing `pending` backlog for normal new notifications

- [ ] **Step 7: Restore multiple replicas**

In `grounds-pulumi/platform/src/platform/notifications.ts`, raise `replicaCount` back above 1 or remove
the temporary pin if the chart default is correct.

Commit:

```bash
git add platform/src/platform/notifications.ts
git commit -S -m "fix: restore notifications replicas"
```

- [ ] **Step 8: Manual HA verification**

With `replicaCount = 2`:

1. Open two Portal tabs as the same user.
2. Create a project invite for that user.
3. Mark it read in one tab.
4. Mark it unread in the other tab.
5. Execute an invite action if available.

Expected:

- all tabs receive update signals
- notification queries refetch to the same state
- `notification_outbox` rows for created events are processed
- logs show NATS publish/receive activity
