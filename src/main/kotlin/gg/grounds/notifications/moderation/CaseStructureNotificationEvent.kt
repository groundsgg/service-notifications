package gg.grounds.notifications.moderation

import io.quarkus.runtime.annotations.RegisterForReflection
import java.time.Instant
import java.util.UUID

@RegisterForReflection
data class CaseStructureNotificationEvent(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val data: CaseStructureNotificationData,
)

@RegisterForReflection
data class CaseStructureNotificationData(
    val environment: ModerationEnvironment,
    val operationId: UUID,
    val structureSequence: Long,
    val kind: CaseStructureKind,
    val sourceCaseId: UUID,
    val destinationCaseId: UUID,
    val sourceRevision: Long,
    val destinationRevision: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val reports: List<CaseReportRouting>,
)

@RegisterForReflection
data class CaseReportRouting(
    val reportId: UUID,
    val caseId: UUID,
    val reportType: CaseReportType,
    val evidenceCaptureId: UUID?,
    val captureStatus: CaseCaptureStatus?,
    val readinessEventId: UUID?,
)

enum class CaseStructureKind {
    MERGE,
    SPLIT,
}

enum class CaseReportType {
    PLAYER_REPORT,
    SYSTEM_MESSAGE_REPORT,
}

enum class CaseCaptureStatus {
    OPEN,
    COMPLETE,
    INCOMPLETE,
}

fun interface ModerationStructureEventHandler {
    fun handle(event: CaseStructureNotificationEvent)
}

fun ModerationEnvironment.structureSubject(): String = "moderation-v2.$wireValue.case.restructured"

@RegisterForReflection
data class CaseStructureProjectionResult(
    val operationId: UUID,
    val complete: Boolean,
    val replay: Boolean,
    val notificationIds: List<UUID> = emptyList(),
)

fun interface ModerationStructureProjectionStore {
    fun projectCaseStructure(
        event: CaseStructureNotificationEvent,
        audience: gg.grounds.notifications.audience.ForgeAudienceSnapshot,
    ): CaseStructureProjectionResult

    fun findProcessedCaseStructure(
        event: CaseStructureNotificationEvent
    ): CaseStructureProjectionResult? = null
}
