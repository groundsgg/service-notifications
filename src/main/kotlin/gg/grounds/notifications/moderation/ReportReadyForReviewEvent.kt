package gg.grounds.notifications.moderation

import java.time.Instant
import java.util.UUID

data class ReportReadyForReviewEvent(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val data: ReportReadyForReviewData,
)

data class ReportReadyForReviewData(
    val reportId: UUID,
    val caseId: UUID,
    val evidenceCaptureId: UUID,
    val captureStatus: CaptureStatus,
    val caseRevision: Long,
)

enum class CaptureStatus {
    COMPLETE,
    INCOMPLETE,
}

object ModerationEventSubjects {
    const val REPORT_READY_FOR_REVIEW = "moderation.report.ready-for-review"
}

class InvalidModerationEventException(cause: Throwable? = null) :
    RuntimeException("Moderation event contract is invalid", cause)
