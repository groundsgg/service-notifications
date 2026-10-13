# Environment-bound moderation notifications

The legacy projector and the two v2 projectors have independent flags. All default
to false. `NOTIFICATIONS_MODERATION_PROJECTOR_STAGE_ENABLED` and
`NOTIFICATIONS_MODERATION_PROJECTOR_PROD_ENABLED` control only the matching v2
consumer. Keep `NOTIFICATIONS_MODERATION_PROJECTOR_ENABLED` false during the v2
rollout to avoid processing historical legacy ready events.

Stage consumes `moderation-v2.stage.report.ready-for-review` from
`MODERATION_READY_STAGE_V2` using durable
`service-notifications-moderation-ready-stage-v2`; Prod uses the corresponding
`prod` subject, `MODERATION_READY_PROD_V2` stream and `prod-v2` durable. Provision
these disjoint streams and narrowly scoped NATS publisher/subscriber permissions
before enabling a consumer. Existing `moderation.>` streams are not rewritten.

Forge base URL, internal token and NATS authentication must be configured when any
projector is enabled. V2 audience resolution requires ALL of support-area access,
reports-view, notifications-view and the matching Stage/Prod access permission.
Stream failures are independent and appear as per-environment degraded state in
the readiness endpoint; unrelated notifications and administration remain available.

The forward migration leaves existing projections/actions/channel clients without
an environment. It does not assign Stage or Prod to legacy data. V2 case identity,
event-deduplication history and typed Portal actions explicitly include environment.
Reusing an event ID with different accepted content is rejected; replaying the same
event creates no new canonical notification or delivery event.

Minecraft channel clients must be provisioned by an authorized notifications
administrator with `moderationEnvironment` set to `stage` or `prod`. Existing
unbound clients receive generic and legacy items, but no v2 moderation items.
Request fields cannot override this stored binding. Inbox filtering runs before
the page limit and the same binding protects known notification/action IDs.
Keep a channel's server/project/deployment scope as well as its environment binding.

Enable Stage only after the Portal and plugin understand environment-bearing
`OPEN_PORTAL_CASE` actions. Both surfaces must use the stored notification ID and
the route `/moderation/{stage|prod}/cases/{UUID}` on their configured Portal origin.
Legacy actions without an environment must not be guessed into Stage or Prod.
Generic live change hints trigger a refetch through the bound Minecraft API;
they do not grant access to a hinted notification ID.

No producer dispatch, v2 consumer, historical replay or Prod activation is enabled
by this code change. A new manually submitted Stage report is required to prove
live delivery after the complete transport/client configuration is ready.
