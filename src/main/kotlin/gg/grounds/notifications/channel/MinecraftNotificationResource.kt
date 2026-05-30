package gg.grounds.notifications.channel

import gg.grounds.notifications.auth.ChannelClientAuthService
import gg.grounds.notifications.core.NotificationInboxItem
import gg.grounds.notifications.db.NotificationRepository
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType

data class MinecraftNotificationBatchRequest(val playerUuids: List<String>)

data class MinecraftNotificationBatchResponse(val players: List<MinecraftPlayerNotifications>)

data class MinecraftPlayerNotifications(
    val playerUuid: String,
    val notifications: List<NotificationInboxItem>,
)

@Path("/v1/channel/minecraft")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class MinecraftNotificationResource(
    private val authService: ChannelClientAuthService,
    private val playerResolver: MinecraftPlayerResolver,
    private val notificationRepository: NotificationRepository,
) {
    @POST
    @Path("/notifications/batch")
    fun batch(
        request: MinecraftNotificationBatchRequest,
        @Context headers: HttpHeaders,
    ): MinecraftNotificationBatchResponse {
        authService.requireClient(
            headers,
            channel = "minecraft",
            requiredScope = "notifications:read",
        )
        val players =
            request.playerUuids.mapNotNull { playerUuid ->
                val userId = playerResolver.resolveUserId(playerUuid) ?: return@mapNotNull null
                val notifications = notificationRepository.listForUser(userId)
                if (notifications.isEmpty()) {
                    return@mapNotNull null
                }
                MinecraftPlayerNotifications(playerUuid, notifications)
            }
        return MinecraftNotificationBatchResponse(players)
    }
}
