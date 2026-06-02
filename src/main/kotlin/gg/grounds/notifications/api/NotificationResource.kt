package gg.grounds.notifications.api

import gg.grounds.notifications.actions.NotificationActionService
import gg.grounds.notifications.auth.ChannelClientAuthService
import gg.grounds.notifications.auth.WebUserResolver
import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.core.NotificationEventRequest
import gg.grounds.notifications.core.NotificationEventResponse
import gg.grounds.notifications.core.NotificationInboxResponse
import gg.grounds.notifications.db.ChannelClient
import gg.grounds.notifications.db.NotificationRepository
import gg.grounds.notifications.live.NotificationLiveBroadcaster
import gg.grounds.notifications.live.NotificationLiveEvent
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import jakarta.ws.rs.sse.SseEventSink
import java.util.UUID

@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class NotificationResource(
    private val notificationRepository: NotificationRepository,
    private val channelClientAuthService: ChannelClientAuthService,
    private val webUserResolver: WebUserResolver,
    private val actionService: NotificationActionService,
    private val liveBroadcaster: NotificationLiveBroadcaster,
    private val identity: SecurityIdentity,
) {
    @POST
    @Path("/notification-events")
    fun createNotificationEvent(
        request: NotificationEventRequest,
        @Context headers: HttpHeaders,
    ): Response {
        val client =
            channelClientAuthService.requireClient(
                headers,
                channel = "api",
                requiredScope = "notifications:write",
            )
        requireProducerScope(client, request)
        val response = notificationRepository.createEvent(request)
        if (response.created) {
            request.recipients.forEach { recipient ->
                liveBroadcaster.publish(
                    NotificationLiveEvent(
                        type = "notifications.changed",
                        userId = recipient.userId,
                        notificationId = response.id.toString(),
                        reason = "created",
                        occurredAt = java.time.OffsetDateTime.now(),
                    )
                )
            }
        }
        val status = if (response.created) Response.Status.CREATED else Response.Status.OK
        return Response.status(status)
            .entity(NotificationEventResponse(response.id, response.created))
            .build()
    }

    @GET
    @Path("/notifications/live")
    @Authenticated
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun streamNotifications(@Context eventSink: SseEventSink) {
        val userId = webUserResolver.requireUser(identity)
        liveBroadcaster.register(userId, eventSink)
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
    @Path("/notifications/{id}/read")
    @Authenticated
    fun markNotificationRead(@PathParam("id") id: UUID, @Context uriInfo: UriInfo): Response {
        rejectUserIdQuery(uriInfo)
        val userId = webUserResolver.requireUser(identity)
        if (!notificationRepository.markRecipientRead(id, userId)) {
            throw NotFoundException("Notification recipient was not found")
        }
        liveBroadcaster.publish(
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = userId,
                notificationId = id.toString(),
                reason = "read",
                occurredAt = java.time.OffsetDateTime.now(),
            )
        )
        return Response.noContent().build()
    }

    @POST
    @Path("/notifications/{id}/unread")
    @Authenticated
    fun markNotificationUnread(@PathParam("id") id: UUID, @Context uriInfo: UriInfo): Response {
        rejectUserIdQuery(uriInfo)
        val userId = webUserResolver.requireUser(identity)
        if (!notificationRepository.markRecipientUnread(id, userId)) {
            throw NotFoundException("Notification recipient was not found")
        }
        liveBroadcaster.publish(
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = userId,
                notificationId = id.toString(),
                reason = "unread",
                occurredAt = java.time.OffsetDateTime.now(),
            )
        )
        return Response.noContent().build()
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

    private fun requireProducerScope(client: ChannelClient, request: NotificationEventRequest) {
        client.deploymentId?.let { deploymentId ->
            requireScope("deployment", deploymentId, request)
            return
        }
        client.serverId?.let { serverId ->
            requireScope("server", serverId, request)
            return
        }
        client.projectId?.let { projectId -> requireScope("project", projectId, request) }
        // Unscoped API producers are intentionally global producers for bootstrap and system
        // emitters.
    }

    private fun requireScope(
        expectedType: String,
        expectedId: String,
        request: NotificationEventRequest,
    ) {
        if (request.scope.type != expectedType || request.scope.id != expectedId) {
            throw ForbiddenException("Channel client is not authorized for notification scope")
        }
    }
}
