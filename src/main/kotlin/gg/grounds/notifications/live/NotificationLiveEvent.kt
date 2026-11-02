package gg.grounds.notifications.live

import io.quarkus.runtime.annotations.RegisterForReflection
import java.time.OffsetDateTime

// NATS and SSE serialize/deserialize this event directly with ObjectMapper.
@RegisterForReflection
data class NotificationLiveEvent(
    val type: String,
    val userId: String,
    val notificationId: String,
    val reason: String,
    val occurredAt: OffsetDateTime,
)
