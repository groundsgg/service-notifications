package gg.grounds.notifications.actions

import gg.grounds.notifications.core.ActionExecutionResponse
import gg.grounds.notifications.db.NotificationRepository
import gg.grounds.notifications.live.NotificationLiveEvent
import gg.grounds.notifications.live.NotificationLiveEventPublisher
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationActionService(
    private val notificationRepository: NotificationRepository,
    private val projectInviteActionAdapter: ProjectInviteActionAdapter,
    private val clusterResumeActionAdapter: ClusterResumeActionAdapter,
    private val liveEventPublisher: NotificationLiveEventPublisher,
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
                    "cluster.resume" ->
                        clusterResumeActionAdapter.execute(action, userId, requestId)
                    else -> ActionExecutionResponse("failed", "unsupported_action")
                }
            }
        if (result.status == "succeeded") {
            publishLiveEvent(
                NotificationLiveEvent(
                    type = "notifications.changed",
                    userId = userId,
                    notificationId = notificationId.toString(),
                    reason = "action",
                    occurredAt = OffsetDateTime.now(),
                )
            )
        }
        logActionOutcome(notificationId, actionKey, userId, requestId, result)
        return result
    }

    private fun publishLiveEvent(event: NotificationLiveEvent) {
        try {
            liveEventPublisher.publish(event)
        } catch (exception: Exception) {
            LOG.warnf(
                exception,
                "Failed to publish best-effort notification live event (notificationId=%s, userId=%s, reason=%s)",
                event.notificationId,
                event.userId,
                event.reason,
            )
        }
    }

    private fun logActionOutcome(
        notificationId: UUID,
        actionKey: String,
        userId: String,
        requestId: String,
        result: ActionExecutionResponse,
    ) {
        when (result.status) {
            "succeeded" ->
                LOG.infof(
                    "Handled notification action successfully (notificationId=%s, actionKey=%s, userId=%s, requestId=%s, status=%s)",
                    notificationId,
                    actionKey,
                    userId,
                    requestId,
                    result.status,
                )
            "rejected" ->
                LOG.warnf(
                    "Rejected notification action (notificationId=%s, actionKey=%s, userId=%s, requestId=%s, reason=%s)",
                    notificationId,
                    actionKey,
                    userId,
                    requestId,
                    result.reason ?: "unknown",
                )
            else ->
                LOG.errorf(
                    "Failed to handle notification action (notificationId=%s, actionKey=%s, userId=%s, requestId=%s, status=%s, reason=%s)",
                    notificationId,
                    actionKey,
                    userId,
                    requestId,
                    result.status,
                    result.reason ?: "unknown",
                )
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(NotificationActionService::class.java)
    }
}
