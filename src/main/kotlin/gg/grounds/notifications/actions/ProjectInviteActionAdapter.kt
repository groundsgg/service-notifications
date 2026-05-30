package gg.grounds.notifications.actions

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.core.StoredNotificationAction
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class ProjectInviteActionAdapter(
    @param:ConfigProperty(name = "notifications.forge.base-url") private val forgeBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val httpClient = HttpClient.newHttpClient()

    fun execute(
        action: StoredNotificationAction,
        userId: String,
        requestId: String,
    ): ActionExecutionResponse {
        val inviteId =
            action.payload.path("inviteId").asText(null)
                ?: return ActionExecutionResponse("failed", "missing_invite_id")
        val endpoint =
            when (action.command) {
                "project_invite.accept" -> "accept"
                "project_invite.decline" -> "decline"
                else -> return ActionExecutionResponse("failed", "unsupported_action")
            }
        val body =
            objectMapper
                .createObjectNode()
                .put("notificationId", action.notificationId.toString())
                .put("userId", userId)
                .put("requestId", requestId)
        val request =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "${forgeBaseUrl.trimEnd('/')}/v1/internal/notification-actions/project-invites/$inviteId/$endpoint"
                    )
                )
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            when (response.statusCode()) {
                200 -> ActionExecutionResponse("succeeded")
                403 -> ActionExecutionResponse("rejected", "wrong_recipient")
                409 -> ActionExecutionResponse("rejected", "stale_invite")
                else -> ActionExecutionResponse("failed", "forge_status_${response.statusCode()}")
            }
        } catch (exception: Exception) {
            LOG.errorf(
                exception,
                "Failed to execute project invite action (notificationId=%s, actionKey=%s, requestId=%s)",
                action.notificationId,
                action.actionKey,
                requestId,
            )
            ActionExecutionResponse("failed", "forge_unavailable")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(ProjectInviteActionAdapter::class.java)
    }
}
