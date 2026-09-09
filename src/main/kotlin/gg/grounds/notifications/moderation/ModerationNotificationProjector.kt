package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceResolver
import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.audience.RetryableForgeAudienceException
import gg.grounds.notifications.audience.TerminalForgeAudienceException
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

enum class ModerationProjectionStatus {
    CREATED,
    UPDATED,
    DUPLICATE,
    STALE,
}

data class ModerationProjectionResult(
    val notificationId: UUID,
    val status: ModerationProjectionStatus,
    val notifiedUserIds: List<String> = emptyList(),
)

fun interface ModerationProjectionStore {
    fun projectModerationCase(
        event: ReportReadyForReviewEvent,
        audience: ForgeAudienceSnapshot,
    ): ModerationProjectionResult

    fun findProcessedModerationEvent(
        event: ReportReadyForReviewEvent
    ): ModerationProjectionResult? = null
}

@ApplicationScoped
class ModerationNotificationProjector(
    private val audienceResolver: ForgeAudienceResolver,
    private val projectionStore: ModerationProjectionStore,
) : ModerationReadyEventHandler {
    override fun handle(event: ReportReadyForReviewEvent) {
        projectionStore.findProcessedModerationEvent(event)?.let {
            return
        }
        val audience =
            try {
                audienceResolver.resolveModerationAudience()
            } catch (exception: RetryableForgeAudienceException) {
                throw RetryableModerationNotificationException(
                    "Moderation audience resolution is temporarily unavailable",
                    exception,
                )
            } catch (exception: TerminalForgeAudienceException) {
                throw TerminalModerationNotificationException(
                    "Moderation audience resolution failed terminally",
                    exception,
                )
            }
        projectionStore.projectModerationCase(event, audience)
    }
}
