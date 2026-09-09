package gg.grounds.notifications.channel

import gg.grounds.notifications.actions.NotificationActionService
import gg.grounds.notifications.auth.ChannelClientAuthService
import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.core.NotificationActionKind
import gg.grounds.notifications.core.NotificationInboxItem
import gg.grounds.notifications.db.ChannelClient
import gg.grounds.notifications.db.NotificationRepository
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import java.util.UUID

data class MinecraftNotificationBatchRequest(
    override val serverId: String?,
    override val deploymentId: String? = null,
    override val projectId: String? = null,
    val playerUuids: List<String>,
) : MinecraftNotificationScopeRequest

data class MinecraftNotificationActionRequest(
    val playerUuid: String?,
    override val serverId: String?,
    override val deploymentId: String? = null,
    override val projectId: String? = null,
) : MinecraftNotificationScopeRequest

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
    private val actionService: NotificationActionService,
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
        val normalizedPlayerUuids =
            playerUuids.associateWith { playerUuid ->
                runCatching { UUID.fromString(playerUuid).toString() }
                    .getOrElse { throw BadRequestException("playerUuid must be a UUID") }
            }
        val userIds = playerResolver.resolveUserIds(normalizedPlayerUuids.values.distinct())
        val players =
            playerUuids.mapNotNull { playerUuid ->
                val userId =
                    userIds[normalizedPlayerUuids.getValue(playerUuid)] ?: return@mapNotNull null
                val notifications =
                    notificationRepository.listUnreadForUserForMinecraft(
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

    @POST
    @Path("/notifications/{notificationId}/actions/{actionKey}")
    fun executeAction(
        @PathParam("notificationId") notificationId: UUID,
        @PathParam("actionKey") actionKey: String,
        request: MinecraftNotificationActionRequest,
        @Context headers: HttpHeaders,
    ): ActionExecutionResponse {
        if (request.serverId.isNullOrBlank()) {
            throw BadRequestException("serverId is required")
        }
        val playerUuid =
            request.playerUuid?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("playerUuid is required")
        val client =
            authService.requireClient(
                headers,
                channel = "minecraft",
                requiredScope = MINECRAFT_ACTION_SCOPE,
            )
        val scope = authorizedScope(client, request)
        val userId =
            playerResolver.resolveUserId(
                runCatching { UUID.fromString(playerUuid).toString() }
                    .getOrElse { throw BadRequestException("playerUuid must be a UUID") }
            ) ?: throw NotFoundException("Minecraft player was not mapped")
        val action =
            notificationRepository.findAction(notificationId, actionKey)
                ?: throw NotFoundException("Notification action was not found")
        if (
            !notificationRepository.recipientExistsInScope(
                notificationId = notificationId,
                userId = userId,
                scopeType = scope.type,
                scopeId = scope.id,
                allowNetworkScope = action.kind == NotificationActionKind.OPEN_PORTAL_CASE,
            )
        ) {
            throw ForbiddenException("Channel client is not authorized for notification scope")
        }
        val requestId = headers.getHeaderString("X-Request-Id") ?: UUID.randomUUID().toString()
        return actionService.execute(notificationId, actionKey, userId, requestId)
    }

    private fun authorizedScope(
        client: ChannelClient,
        request: MinecraftNotificationScopeRequest,
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

interface MinecraftNotificationScopeRequest {
    val serverId: String?
    val deploymentId: String?
    val projectId: String?
}
