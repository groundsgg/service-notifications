package gg.grounds.notifications.admin

import gg.grounds.notifications.auth.AdminAuthorizationService
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.util.UUID

@Path("/v1/admin/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class NotificationAdminResource(
    private val repository: NotificationAdminRepository,
    private val authorization: AdminAuthorizationService,
    private val identity: SecurityIdentity,
) {
    @GET
    @Path("/diagnostics")
    @Authenticated
    fun diagnostics(): NotificationAdminDiagnosticsResponse {
        authorization.requireNotificationsAdmin(identity)
        return repository.diagnostics()
    }

    @GET
    @Path("/types")
    @Authenticated
    fun notificationTypes(): NotificationAdminListResponse<NotificationTypeAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(repository.listNotificationTypes())
    }

    @GET
    @Path("/channel-clients")
    @Authenticated
    fun channelClients(): NotificationAdminListResponse<ChannelClientAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(repository.listChannelClients())
    }

    @POST
    @Path("/channel-clients")
    @Authenticated
    fun createChannelClient(request: CreateChannelClientRequest): Response {
        val actorUserId = authorization.requireNotificationsAdmin(identity)
        val sanitizedRequest = request.validated()
        return Response.status(Response.Status.CREATED)
            .entity(repository.createChannelClient(sanitizedRequest, actorUserId))
            .build()
    }

    @POST
    @Path("/channel-clients/{id}/rotate")
    @Consumes(MediaType.WILDCARD)
    @Authenticated
    fun rotateChannelClient(@PathParam("id") id: String): ChannelClientSecretResponse {
        val actorUserId = authorization.requireNotificationsAdmin(identity)
        return repository.rotateChannelClient(parseUuid(id), actorUserId)
    }

    @POST
    @Path("/channel-clients/{id}/revoke")
    @Consumes(MediaType.WILDCARD)
    @Authenticated
    fun revokeChannelClient(@PathParam("id") id: String): ChannelClientResponse {
        val actorUserId = authorization.requireNotificationsAdmin(identity)
        return repository.revokeChannelClient(parseUuid(id), actorUserId)
    }

    @GET
    @Path("/deliveries")
    @Authenticated
    fun deliveryAttempts(
        @QueryParam("status") status: String?,
        @QueryParam("channel") channel: String?,
        @QueryParam("notificationId") notificationId: String?,
        @QueryParam("userId") userId: String?,
        @QueryParam("limit") limit: String?,
    ): NotificationAdminListResponse<DeliveryAttemptAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(
            repository.listDeliveryAttempts(
                status = status.queryValue(),
                channel = channel.queryValue(),
                notificationId = parseOptionalUuid(notificationId),
                userId = userId.queryValue(),
                limit = parseLimit(limit),
            )
        )
    }

    @GET
    @Path("/action-results")
    @Authenticated
    fun actionResults(
        @QueryParam("status") status: String?,
        @QueryParam("notificationId") notificationId: String?,
        @QueryParam("userId") userId: String?,
        @QueryParam("limit") limit: String?,
    ): NotificationAdminListResponse<ActionResultAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(
            repository.listActionResults(
                status = status.queryValue(),
                notificationId = parseOptionalUuid(notificationId),
                userId = userId.queryValue(),
                limit = parseLimit(limit),
            )
        )
    }

    @GET
    @Path("/recipients")
    @Authenticated
    fun recipients(
        @QueryParam("notificationId") notificationId: String?,
        @QueryParam("userId") userId: String?,
        @QueryParam("limit") limit: String?,
    ): NotificationAdminListResponse<RecipientResolutionAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(
            repository.listRecipientResolutions(
                notificationId = parseOptionalUuid(notificationId),
                userId = userId.queryValue(),
                limit = parseLimit(limit),
            )
        )
    }

    @GET
    @Path("/team-defaults")
    @Authenticated
    fun teamDefaults(
        @QueryParam("teamId") teamId: String?
    ): NotificationAdminListResponse<TeamNotificationDefaultAdminItem> {
        authorization.requireNotificationsAdmin(identity)
        return NotificationAdminListResponse(repository.listTeamDefaults(teamId.queryValue()))
    }

    @PUT
    @Path("/team-defaults/{teamId}/{category}")
    @Authenticated
    fun upsertTeamDefault(
        @PathParam("teamId") teamId: String,
        @PathParam("category") category: String,
        request: UpsertTeamNotificationDefaultRequest,
    ): TeamNotificationDefaultAdminItem {
        val actorUserId = authorization.requireNotificationsAdmin(identity)
        val sanitizedTeamId =
            teamId.trim().ifBlank { throw BadRequestException("team_id_required") }
        val sanitizedCategory =
            category.trim().ifBlank { throw BadRequestException("category_required") }
        val sanitizedNotificationType = request.notificationType?.trim()?.ifBlank { null }
        return repository.upsertTeamDefault(
            teamId = sanitizedTeamId,
            category = sanitizedCategory,
            request = request.copy(notificationType = sanitizedNotificationType),
            actorUserId = actorUserId,
        )
    }

    private fun CreateChannelClientRequest.validated(): CreateChannelClientRequest {
        val sanitizedChannel = channel.trim()
        if (sanitizedChannel.isBlank()) {
            throw BadRequestException("channel_required")
        }
        val sanitizedScopes = scopes.map { it.trim() }
        if (sanitizedScopes.isEmpty() || sanitizedScopes.any { it.isBlank() }) {
            throw BadRequestException("scopes_required")
        }
        return copy(
            channel = sanitizedChannel,
            projectId = projectId?.trim()?.ifBlank { null },
            serverId = serverId?.trim()?.ifBlank { null },
            deploymentId = deploymentId?.trim()?.ifBlank { null },
            scopes = sanitizedScopes,
        )
    }

    private fun parseOptionalUuid(value: String?): UUID? = value.queryValue()?.let { parseUuid(it) }

    private fun parseUuid(value: String): UUID =
        try {
            UUID.fromString(value)
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("invalid_uuid")
        }

    private fun parseLimit(value: String?): Int {
        val limitValue = value.queryValue() ?: return DEFAULT_LIMIT
        val limit = limitValue.toIntOrNull() ?: throw BadRequestException("invalid_limit")
        if (limit !in MIN_LIMIT..MAX_LIMIT) {
            throw BadRequestException("invalid_limit")
        }
        return limit
    }

    private fun String?.queryValue(): String? = this?.trim()?.ifBlank { null }

    private companion object {
        private const val DEFAULT_LIMIT = 50
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 200
    }
}
