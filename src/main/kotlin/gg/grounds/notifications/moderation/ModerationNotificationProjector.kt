package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceResolver
import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.audience.RetryableForgeAudienceException
import gg.grounds.notifications.audience.TerminalForgeAudienceException
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.time.Instant
import java.util.UUID

object ModerationNotificationContent {
    const val TYPE = "MODERATION_CASE_READY"
    const val CATEGORY = "MODERATION"
    const val TITLE = "Moderation case ready for review"
    const val BODY = "A moderation case is ready for review."
}

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
    private val metrics: ModerationNotificationMetrics? = null,
) : ModerationReadyEventHandler {
    override fun handle(event: ReportReadyForReviewEvent) {
        projectionStore.findProcessedModerationEvent(event)?.let { result ->
            record(result, event)
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
        record(projectionStore.projectModerationCase(event, audience), event)
    }

    private fun record(result: ModerationProjectionResult, event: ReportReadyForReviewEvent) {
        metrics?.recordProjection(result.status, Duration.between(event.occurredAt, Instant.now()))
    }
}
