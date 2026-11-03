package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

@ApplicationScoped
class CaseStructureEventValidator(private val mapper: ObjectMapper) {
    fun validate(subject: String, payload: ByteArray): CaseStructureNotificationEvent {
        val environment =
            ModerationEnvironment.entries.firstOrNull { it.structureSubject() == subject }
                ?: invalid()
        if (payload.isEmpty() || payload.size > 64 * 1024) invalid()
        val root = runCatching { mapper.readTree(payload) }.getOrElse { invalid() }
        root.exactFields(ROOT_FIELDS)
        if (
            root.path("eventType").text() != "moderation.case.restructured" ||
                root.path("schemaVersion").integer(2, 2) != 2L
        )
            invalid()
        val data = root.path("data")
        data.exactFields(DATA_FIELDS)
        if (data.path("environment").text() != environment.wireValue) invalid()
        val source = data.path("sourceCaseId").uuid()
        val destination = data.path("destinationCaseId").uuid()
        if (source == destination) invalid()
        val kind =
            CaseStructureKind.entries.firstOrNull { it.name == data.path("kind").text() }
                ?: invalid()
        val sequence = data.path("structureSequence").integer(1, 9007199254740991L)
        val count = data.path("chunkCount").integer(1, Int.MAX_VALUE.toLong()).toInt()
        val index = data.path("chunkIndex").integer(0, count.toLong() - 1).toInt()
        val rawReports = data.path("reports")
        if (!rawReports.isArray || rawReports.size() > 100 || (rawReports.isEmpty && count != 1))
            invalid()
        val reports =
            rawReports.map { report ->
                report.exactFields(REPORT_FIELDS)
                val caseId = report.path("caseId").uuid()
                if (caseId != destination && !(kind == CaseStructureKind.SPLIT && caseId == source))
                    invalid()
                val type =
                    CaseReportType.entries.firstOrNull {
                        it.name == report.path("reportType").text()
                    } ?: invalid()
                val capture = report.path("evidenceCaptureId").nullableUuid()
                val status =
                    if (report.path("captureStatus").isNull) null
                    else
                        CaseCaptureStatus.entries.firstOrNull {
                            it.name == report.path("captureStatus").text()
                        } ?: invalid()
                val readiness = report.path("readinessEventId").nullableUuid()
                if (
                    (capture == null) != (status == null) ||
                        (readiness != null &&
                            (type != CaseReportType.PLAYER_REPORT ||
                                status !in
                                    setOf(
                                        CaseCaptureStatus.COMPLETE,
                                        CaseCaptureStatus.INCOMPLETE,
                                    )))
                )
                    invalid()
                CaseReportRouting(
                    report.path("reportId").uuid(),
                    caseId,
                    type,
                    capture,
                    status,
                    readiness,
                )
            }
        if (reports.map { it.reportId }.distinct().size != reports.size) invalid()
        return CaseStructureNotificationEvent(
            root.path("eventId").uuid(),
            "moderation.case.restructured",
            2,
            runCatching { Instant.parse(root.path("occurredAt").text()) }.getOrElse { invalid() },
            CaseStructureNotificationData(
                environment,
                data.path("operationId").uuid(),
                sequence,
                kind,
                source,
                destination,
                data.path("sourceRevision").integer(1, Long.MAX_VALUE),
                data.path("destinationRevision").integer(1, Long.MAX_VALUE),
                index,
                count,
                reports.sortedBy { it.reportId.toString() },
            ),
        )
    }

    private fun JsonNode.exactFields(expected: Set<String>) {
        if (!isObject || fieldNames().asSequence().toSet() != expected) invalid()
    }

    private fun JsonNode.text(): String {
        if (!isTextual) invalid()
        return asText()
    }

    private fun JsonNode.integer(min: Long, max: Long): Long {
        if (!isIntegralNumber || !canConvertToLong() || asLong() !in min..max) invalid()
        return asLong()
    }

    private fun JsonNode.uuid(): UUID {
        val value = text()
        if (!UUID_PATTERN.matches(value)) invalid()
        return runCatching { UUID.fromString(value) }.getOrElse { invalid() }
    }

    private fun JsonNode.nullableUuid(): UUID? = if (isNull) null else uuid()

    private fun invalid(): Nothing = throw InvalidModerationEventException()

    private companion object {
        val ROOT_FIELDS = setOf("eventId", "eventType", "schemaVersion", "occurredAt", "data")
        val DATA_FIELDS =
            setOf(
                "environment",
                "operationId",
                "structureSequence",
                "kind",
                "sourceCaseId",
                "destinationCaseId",
                "sourceRevision",
                "destinationRevision",
                "chunkIndex",
                "chunkCount",
                "reports",
            )
        val REPORT_FIELDS =
            setOf(
                "reportId",
                "caseId",
                "reportType",
                "evidenceCaptureId",
                "captureStatus",
                "readinessEventId",
            )
        val UUID_PATTERN =
            Regex(
                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
            )
    }
}
