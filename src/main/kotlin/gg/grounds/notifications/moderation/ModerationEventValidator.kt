package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class ModerationEventValidator(private val objectMapper: ObjectMapper) {
    fun validate(subject: String, payload: ByteArray): ReportReadyForReviewEvent {
        if (
            subject != ModerationEventSubjects.REPORT_READY_FOR_REVIEW ||
                payload.isEmpty() ||
                payload.size > MAX_EVENT_BYTES
        ) {
            invalid()
        }
        val root = runCatching { objectMapper.readTree(payload) }.getOrElse { invalid(it) }
        if (!root.isObject || root.fieldsSet() != ROOT_FIELDS) invalid()
        val data = root.path("data")
        if (!data.isObject || data.fieldsSet() != DATA_FIELDS) invalid()
        if (root.path("eventType").text() != ModerationEventSubjects.REPORT_READY_FOR_REVIEW) {
            invalid()
        }
        val schemaVersion = root.path("schemaVersion")
        if (!schemaVersion.isIntegralNumber || schemaVersion.asInt() != 1) invalid()
        val captureStatus =
            when (data.path("captureStatus").text()) {
                "COMPLETE" -> CaptureStatus.COMPLETE
                "INCOMPLETE" -> CaptureStatus.INCOMPLETE
                else -> invalid()
            }
        val revision = data.path("caseRevision")
        if (!revision.isIntegralNumber || !revision.canConvertToLong() || revision.asLong() < 1) {
            invalid()
        }
        return ReportReadyForReviewEvent(
            eventId = root.path("eventId").uuid(),
            eventType = ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
            schemaVersion = 1,
            occurredAt = root.path("occurredAt").instant(),
            data =
                ReportReadyForReviewData(
                    reportId = data.path("reportId").uuid(),
                    caseId = data.path("caseId").uuid(),
                    evidenceCaptureId = data.path("evidenceCaptureId").uuid(),
                    captureStatus = captureStatus,
                    caseRevision = revision.asLong(),
                ),
        )
    }

    private fun JsonNode.fieldsSet(): Set<String> = fieldNames().asSequence().toSet()

    private fun JsonNode.text(): String {
        if (!isTextual) invalid()
        return asText()
    }

    private fun JsonNode.uuid(): UUID {
        val raw = text()
        if (!UUID_PATTERN.matches(raw)) invalid()
        return runCatching { UUID.fromString(raw) }.getOrElse { invalid(it) }
    }

    private fun JsonNode.instant(): Instant =
        runCatching { Instant.parse(text()) }.getOrElse { invalid(it) }

    private fun invalid(cause: Throwable? = null): Nothing =
        throw InvalidModerationEventException(cause)

    private companion object {
        const val MAX_EVENT_BYTES = 64 * 1024
        val ROOT_FIELDS = setOf("eventId", "eventType", "schemaVersion", "occurredAt", "data")
        val DATA_FIELDS =
            setOf("reportId", "caseId", "evidenceCaptureId", "captureStatus", "caseRevision")
        val UUID_PATTERN =
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
            )
    }
}
