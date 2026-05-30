package gg.grounds.notifications.api

import gg.grounds.notifications.actions.NotificationActionService
import gg.grounds.notifications.auth.ChannelClientAuthService
import gg.grounds.notifications.auth.WebUserResolver
import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.core.NotificationEventRequest
import gg.grounds.notifications.core.NotificationEventResponse
import gg.grounds.notifications.core.NotificationInboxResponse
import gg.grounds.notifications.db.NotificationRepository
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import java.util.UUID

@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class NotificationResource(
    private val notificationRepository: NotificationRepository,
    private val channelClientAuthService: ChannelClientAuthService,
    private val webUserResolver: WebUserResolver,
    private val actionService: NotificationActionService,
    private val identity: SecurityIdentity,
) {
    @POST
    @Path("/notification-events")
    fun createNotificationEvent(
        request: NotificationEventRequest,
        @Context headers: HttpHeaders,
    ): Response {
        channelClientAuthService.requireClient(
            headers,
            channel = "api",
            requiredScope = "notifications:write",
        )
        val response = notificationRepository.createEvent(request)
        val status = if (response.created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status)
            .entity(NotificationEventResponse(response.id, response.created))
            .build()
    }

    @GET
    @Path("/notifications")
    @Authenticated
    fun listNotifications(@Context uriInfo: UriInfo): NotificationInboxResponse {
        rejectUserIdQuery(uriInfo)
        val userId = webUserResolver.requireUser(identity)
        return NotificationInboxResponse(notificationRepository.listForUser(userId))
    }

    @POST
    @Path("/notifications/{id}/actions/{actionKey}")
    @Authenticated
    fun executeAction(
        @PathParam("id") id: UUID,
        @PathParam("actionKey") actionKey: String,
        @Context headers: HttpHeaders,
        @Context uriInfo: UriInfo,
    ): ActionExecutionResponse {
        rejectUserIdQuery(uriInfo)
        val userId = webUserResolver.requireUser(identity)
        val requestId = headers.getHeaderString("X-Request-Id") ?: UUID.randomUUID().toString()
        return actionService.execute(id, actionKey, userId, requestId)
    }

    private fun rejectUserIdQuery(uriInfo: UriInfo) {
        if (uriInfo.queryParameters.containsKey("userId")) {
            throw BadRequestException("userId query parameter is not accepted")
        }
    }
}
