package gg.grounds.notifications.live

import java.time.OffsetDateTime

data class NotificationLiveEvent(
    val type: String,
    val userId: String,
    val notificationId: String,
    val reason: String,
    val occurredAt: OffsetDateTime,
)
