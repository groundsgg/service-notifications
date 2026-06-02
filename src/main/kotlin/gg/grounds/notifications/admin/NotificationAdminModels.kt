package gg.grounds.notifications.admin

data class NotificationAdminDiagnosticsResponse(
    val notificationCount: Long,
    val recipientCount: Long,
    val activeChannelClientCount: Long,
    val failedDeliveryCount: Long,
    val failedActionCount: Long,
)
