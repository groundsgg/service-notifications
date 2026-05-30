package gg.grounds.notifications.actions

import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.db.NotificationRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import java.util.UUID
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationActionService(
    private val notificationRepository: NotificationRepository,
    private val projectInviteActionAdapter: ProjectInviteActionAdapter,
) {
    fun execute(
        notificationId: UUID,
        actionKey: String,
        userId: String,
        requestId: String,
    ): ActionExecutionResponse {
        if (!notificationRepository.recipientExists(notificationId, userId)) {
            throw ForbiddenException("Authenticated user is not a notification recipient")
        }

        val action =
            notificationRepository.findAction(notificationId, actionKey)
                ?: throw NotFoundException("Notification action was not found")
        val result =
            when (action.command) {
                "project_invite.accept",
                "project_invite.decline" ->
                    projectInviteActionAdapter.execute(action, userId, requestId)
                else -> ActionExecutionResponse("failed", "unsupported_action")
            }
        notificationRepository.storeActionResult(action, userId, result.status, result.reason)
        LOG.infof(
            "Stored notification action result (notificationId=%s, actionKey=%s, userId=%s, status=%s)",
            notificationId,
            actionKey,
            userId,
            result.status,
        )
        return result
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(NotificationActionService::class.java)
    }
}
