package gg.grounds.notifications.actions

import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.db.NotificationRepository
import jakarta.enterprise.context.ApplicationScoped
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
        val result =
            notificationRepository.executeActionOnce(notificationId, actionKey, userId) { action ->
                when (action.command) {
                    "project_invite.accept",
                    "project_invite.decline" ->
                        projectInviteActionAdapter.execute(action, userId, requestId)
                    else -> ActionExecutionResponse("failed", "unsupported_action")
                }
            }
        LOG.infof(
            "Handled notification action (notificationId=%s, actionKey=%s, userId=%s, status=%s)",
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
