package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceResolver
import gg.grounds.notifications.audience.RetryableForgeAudienceException
import gg.grounds.notifications.audience.TerminalForgeAudienceException
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class CaseStructureNotificationProjector
@Inject
constructor(
    private val audienceResolver: ForgeAudienceResolver,
    private val projectionStore: ModerationStructureProjectionStore,
    private val metrics: ModerationNotificationMetrics? = null,
) : ModerationStructureEventHandler {
    override fun handle(event: CaseStructureNotificationEvent) {
        projectionStore.findProcessedCaseStructure(event)?.let {
            metrics?.recordStructure(event.data.kind, it)
            return
        }
        val audience =
            try {
                audienceResolver.resolveModerationAudience(event.data.environment)
            } catch (e: RetryableForgeAudienceException) {
                metrics?.recordAudienceFailure(ModerationAudienceFailureResult.RETRYABLE)
                throw RetryableModerationNotificationException(
                    "Moderation audience resolution is temporarily unavailable",
                    e,
                )
            } catch (e: TerminalForgeAudienceException) {
                metrics?.recordAudienceFailure(ModerationAudienceFailureResult.TERMINAL)
                throw TerminalModerationNotificationException(
                    "Moderation audience resolution failed terminally",
                    e,
                )
            }
        val result = projectionStore.projectCaseStructure(event, audience)
        metrics?.recordStructure(event.data.kind, result)
    }
}
