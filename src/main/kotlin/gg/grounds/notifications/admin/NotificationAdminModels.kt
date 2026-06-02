package gg.grounds.notifications.admin

data class NotificationAdminDiagnosticsResponse(
    val notificationCount: Long,
    val recipientCount: Long,
    val activeChannelClientCount: Long,
    val failedDeliveryCount: Long,
    val failedActionCount: Long,
)

data class NotificationAdminListResponse<T>(val items: List<T>)

data class NotificationTypeAdminItem(
    val type: String,
    val category: String,
    val webEnabled: Boolean,
    val minecraftEnabled: Boolean,
    val emailEnabled: Boolean,
    val discordEnabled: Boolean,
    val pushEnabled: Boolean,
    val allowedProducers: List<String>,
    val observedCount: Long,
)

data class ChannelClientAdminItem(
    val id: String,
    val channel: String,
    val projectId: String?,
    val serverId: String?,
    val deploymentId: String?,
    val scopes: List<String>,
    val createdAt: String,
    val revokedAt: String?,
)

data class CreateChannelClientRequest(
    val channel: String,
    val projectId: String? = null,
    val serverId: String? = null,
    val deploymentId: String? = null,
    val scopes: List<String>,
)

data class ChannelClientSecretResponse(val client: ChannelClientAdminItem, val token: String)

data class ChannelClientResponse(val client: ChannelClientAdminItem)

data class DeliveryAttemptAdminItem(
    val id: String,
    val notificationId: String,
    val userId: String,
    val channel: String,
    val status: String,
    val provider: String?,
    val attempts: Int,
    val lastError: String?,
    val sentAt: String?,
    val createdAt: String,
)

data class ActionResultAdminItem(
    val id: String,
    val notificationId: String,
    val actionKey: String,
    val userId: String,
    val status: String,
    val reason: String?,
    val createdAt: String,
)

data class RecipientResolutionAdminItem(
    val notificationId: String,
    val userId: String,
    val audienceType: String?,
    val audienceId: String?,
    val role: String?,
    val readAt: String?,
    val archivedAt: String?,
    val createdAt: String,
)

data class TeamNotificationDefaultAdminItem(
    val teamId: String,
    val category: String,
    val notificationType: String?,
    val webDefault: Boolean,
    val minecraftDefault: Boolean,
    val emailDefault: Boolean,
    val discordDefault: Boolean,
)

data class UpsertTeamNotificationDefaultRequest(
    val notificationType: String? = null,
    val webDefault: Boolean,
    val minecraftDefault: Boolean,
    val emailDefault: Boolean,
    val discordDefault: Boolean,
)
