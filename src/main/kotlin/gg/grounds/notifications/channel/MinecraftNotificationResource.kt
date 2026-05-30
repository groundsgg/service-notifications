package gg.grounds.notifications.channel

import gg.grounds.notifications.auth.ChannelClientAuthService
import gg.grounds.notifications.core.NotificationInboxItem
import gg.grounds.notifications.db.ChannelClient
import gg.grounds.notifications.db.NotificationRepository
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType

data class MinecraftNotificationBatchRequest(
    val serverId: String?,
    val deploymentId: String? = null,
    val projectId: String? = null,
    val playerUuids: List<String>,
)

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
        if (request.serverId.isNullOrBlank()) {
            throw BadRequestException("serverId is required")
        }
        if (request.playerUuids.size > MAX_PLAYER_UUIDS) {
            throw BadRequestException("playerUuids exceeds maximum batch size")
        }
        val client =
            authService.requireClient(
                headers,
                channel = "minecraft",
                requiredScope = MINECRAFT_READ_SCOPE,
            )
        val scope = authorizedScope(client, request)
        val playerUuids = request.playerUuids.distinct()
        val players =
            playerUuids.mapNotNull { playerUuid ->
                val userId = playerResolver.resolveUserId(playerUuid) ?: return@mapNotNull null
                val notifications =
                    notificationRepository.listForUserInScope(
                        userId = userId,
                        scopeType = scope.type,
                        scopeId = scope.id,
                    )
                if (notifications.isEmpty()) {
                    return@mapNotNull null
                }
                MinecraftPlayerNotifications(playerUuid, notifications)
            }
        return MinecraftNotificationBatchResponse(players)
    }

    private fun authorizedScope(
        client: ChannelClient,
        request: MinecraftNotificationBatchRequest,
    ): NotificationScopeFilter {
        client.deploymentId?.let { deploymentId ->
            if (request.deploymentId != deploymentId) {
                throw ForbiddenException("Channel client is not authorized for deployment")
            }
            return NotificationScopeFilter("deployment", deploymentId)
        }
        client.serverId?.let { serverId ->
            if (request.serverId != serverId) {
                throw ForbiddenException("Channel client is not authorized for server")
            }
            return NotificationScopeFilter("server", serverId)
        }
        client.projectId?.let { projectId ->
            if (request.projectId != projectId) {
                throw ForbiddenException("Channel client is not authorized for project")
            }
            return NotificationScopeFilter("project", projectId)
        }
        throw ForbiddenException("Channel client has no notification scope")
    }

    companion object {
        const val MINECRAFT_READ_SCOPE = "minecraft.notifications.read"
        const val MINECRAFT_ACTION_SCOPE = "minecraft.notifications.action"
        private const val MAX_PLAYER_UUIDS = 100
    }
}

private data class NotificationScopeFilter(val type: String, val id: String)
