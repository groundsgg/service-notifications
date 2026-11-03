package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

/** All methods participate in the caller's single manual JDBC transaction. */
@ApplicationScoped
class CaseNavigationRepository(private val mapper: ObjectMapper) {
    fun lock(c: Connection, environment: ModerationEnvironment, exclusive: Boolean = false) {
        val function = if (exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
        c.prepareStatement("SELECT $function(hashtextextended(?,0))").use {
            it.setString(1, "moderation-navigation:${environment.wireValue}")
            it.executeQuery().use { r -> check(r.next()) }
        }
    }

    fun leader(c: Connection, env: ModerationEnvironment, id: UUID): UUID =
        c.prepareStatement("SELECT moderation_notification_leader(?,?)").use {
            it.setString(1, env.wireValue)
            it.setObject(2, id)
            it.executeQuery().use { r ->
                check(r.next())
                r.getObject(1, UUID::class.java)
            }
        }

    fun observeReady(c: Connection, event: ReportReadyForReviewEvent): ReportReadyForReviewEvent {
        val data = event.data
        val env = data.environment ?: return event
        bindReady(
            c,
            env,
            event.eventId,
            data.reportId,
            data.evidenceCaptureId,
            CaseCaptureStatus.valueOf(data.captureStatus.name),
        )
        upsertReport(
            c,
            env,
            CaseReportRouting(
                data.reportId,
                data.caseId,
                CaseReportType.PLAYER_REPORT,
                data.evidenceCaptureId,
                CaseCaptureStatus.valueOf(data.captureStatus.name),
                event.eventId,
            ),
            0,
        )
        val raw = currentReportCase(c, env, data.reportId)
        val root = leader(c, env, raw)
        if (root == data.caseId) {
            c.prepareStatement(
                    """INSERT INTO moderation_case_navigation_state(environment,case_id,last_case_revision)
                VALUES (?,?,?) ON CONFLICT(environment,case_id) DO UPDATE
                SET last_case_revision=GREATEST(moderation_case_navigation_state.last_case_revision,EXCLUDED.last_case_revision)"""
                )
                .use {
                    it.setString(1, env.wireValue)
                    it.setObject(2, root)
                    it.setLong(3, data.caseRevision)
                    it.executeUpdate()
                }
        }
        val revision = caseRevision(c, env, root)
        return event.copy(data = data.copy(caseId = root, caseRevision = revision))
    }

    fun processed(
        c: Connection,
        event: CaseStructureNotificationEvent,
    ): CaseStructureProjectionResult? {
        verify(event)
        rejectReadinessIdentity(c, event)
        return c.prepareStatement(
                """SELECT receipt.operation_id,receipt.chunk_index,receipt.semantic_hash,
            operation.header_hash,operation.result_json::text FROM moderation_structure_received_event receipt
            JOIN moderation_structure_operation operation ON operation.environment=receipt.environment
                AND operation.operation_id=receipt.operation_id WHERE receipt.environment=? AND receipt.event_id=?"""
            )
            .use {
                it.setString(1, event.data.environment.wireValue)
                it.setObject(2, event.eventId)
                it.executeQuery().use { r ->
                    if (!r.next()) return@use null
                    if (
                        r.getObject(1, UUID::class.java) != event.data.operationId ||
                            r.getInt(2) != event.data.chunkIndex ||
                            !r.getBytes(3).contentEquals(chunkHash(event)) ||
                            !r.getBytes(4).contentEquals(headerHash(event))
                    )
                        conflict("Structure event identity changed")
                    r.getString(5)?.let { json ->
                        mapper
                            .readValue(json, CaseStructureProjectionResult::class.java)
                            .copy(replay = true)
                    } ?: CaseStructureProjectionResult(event.data.operationId, false, true)
                }
            }
    }

    private fun rejectReadinessIdentity(c: Connection, event: CaseStructureNotificationEvent) {
        val d = event.data
        if (
            exists(
                c,
                "SELECT 1 FROM moderation_notification_processed_event WHERE environment=? AND event_id=?",
                d.environment,
                event.eventId,
            ) ||
                exists(
                    c,
                    "SELECT 1 FROM moderation_ready_navigation_event WHERE environment=? AND event_id=?",
                    d.environment,
                    event.eventId,
                )
        )
            conflict("Event identity was already used by readiness")
    }

    fun accept(
        c: Connection,
        event: CaseStructureNotificationEvent,
    ): CaseStructureProjectionResult? {
        val d = event.data
        verify(event)
        rejectReadinessIdentity(c, event)
        val header = headerHash(event)
        c.prepareStatement(
                "SELECT operation_id FROM moderation_structure_operation WHERE environment=? AND structure_sequence=?"
            )
            .use {
                it.setString(1, d.environment.wireValue)
                it.setLong(2, d.structureSequence)
                it.executeQuery().use { r ->
                    if (r.next() && r.getObject(1, UUID::class.java) != d.operationId)
                        conflict("Structure sequence was reused")
                }
            }
        c.prepareStatement(
                """INSERT INTO moderation_structure_operation(environment,operation_id,structure_sequence,header_hash,chunk_count)
            VALUES (?,?,?,?,?) ON CONFLICT(environment,operation_id) DO NOTHING"""
            )
            .use {
                it.setString(1, d.environment.wireValue)
                it.setObject(2, d.operationId)
                it.setLong(3, d.structureSequence)
                it.setBytes(4, header)
                it.setInt(5, d.chunkCount)
                it.executeUpdate()
            }
        val result =
            c.prepareStatement(
                    "SELECT header_hash,result_json::text FROM moderation_structure_operation WHERE environment=? AND operation_id=? FOR UPDATE"
                )
                .use {
                    it.setString(1, d.environment.wireValue)
                    it.setObject(2, d.operationId)
                    it.executeQuery().use { r ->
                        check(r.next())
                        if (!r.getBytes(1).contentEquals(header))
                            conflict("Structure operation header changed")
                        r.getString(2)?.let { json ->
                            mapper.readValue(json, CaseStructureProjectionResult::class.java)
                        }
                    }
                }
        val hash = chunkHash(event)
        var duplicate = false
        c.prepareStatement(
                "SELECT operation_id,chunk_index,semantic_hash FROM moderation_structure_received_event WHERE environment=? AND event_id=?"
            )
            .use {
                it.setString(1, d.environment.wireValue)
                it.setObject(2, event.eventId)
                it.executeQuery().use { r ->
                    if (r.next()) {
                        if (
                            r.getObject(1, UUID::class.java) != d.operationId ||
                                r.getInt(2) != d.chunkIndex ||
                                !r.getBytes(3).contentEquals(hash)
                        )
                            conflict("Structure event identity changed")
                        duplicate = true
                    }
                }
            }
        c.prepareStatement(
                "SELECT semantic_hash FROM moderation_structure_chunk WHERE environment=? AND operation_id=? AND chunk_index=?"
            )
            .use {
                it.setString(1, d.environment.wireValue)
                it.setObject(2, d.operationId)
                it.setInt(3, d.chunkIndex)
                it.executeQuery().use { r ->
                    if (r.next()) {
                        if (!r.getBytes(1).contentEquals(hash)) conflict("Structure chunk changed")
                        duplicate = true
                    }
                }
            }
        if (!duplicate)
            c.prepareStatement(
                    "INSERT INTO moderation_structure_chunk(environment,operation_id,chunk_index,semantic_hash,reports) VALUES (?,?,?,?,CAST(? AS jsonb))"
                )
                .use {
                    it.setString(1, d.environment.wireValue)
                    it.setObject(2, d.operationId)
                    it.setInt(3, d.chunkIndex)
                    it.setBytes(4, hash)
                    it.setString(
                        5,
                        mapper.writeValueAsString(
                            d.reports.sortedBy { report -> report.reportId.toString() }
                        ),
                    )
                    it.executeUpdate()
                }
        c.prepareStatement(
                "INSERT INTO moderation_structure_received_event(environment,event_id,operation_id,chunk_index,semantic_hash) VALUES (?,?,?,?,?) ON CONFLICT(environment,event_id) DO NOTHING"
            )
            .use {
                it.setString(1, d.environment.wireValue)
                it.setObject(2, event.eventId)
                it.setObject(3, d.operationId)
                it.setInt(4, d.chunkIndex)
                it.setBytes(5, hash)
                it.executeUpdate()
            }
        if (result != null) return result.copy(replay = true)
        val received =
            c.prepareStatement(
                    "SELECT count(*) FROM moderation_structure_chunk WHERE environment=? AND operation_id=?"
                )
                .use {
                    it.setString(1, d.environment.wireValue)
                    it.setObject(2, d.operationId)
                    it.executeQuery().use { r ->
                        r.next()
                        r.getInt(1)
                    }
                }
        if (received < d.chunkCount)
            return CaseStructureProjectionResult(d.operationId, false, duplicate)
        return null
    }

    fun reports(c: Connection, event: CaseStructureNotificationEvent): List<CaseReportRouting> =
        c.prepareStatement(
                "SELECT reports::text FROM moderation_structure_chunk WHERE environment=? AND operation_id=? ORDER BY chunk_index"
            )
            .use {
                it.setString(1, event.data.environment.wireValue)
                it.setObject(2, event.data.operationId)
                it.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            for (row in mapper.readTree(r.getString(1))) add(
                                CaseReportRouting(
                                    UUID.fromString(row.path("reportId").asText()),
                                    UUID.fromString(row.path("caseId").asText()),
                                    CaseReportType.valueOf(row.path("reportType").asText()),
                                    row.path("evidenceCaptureId")
                                        .takeUnless { node -> node.isNull }
                                        ?.asText()
                                        ?.let(UUID::fromString),
                                    row.path("captureStatus")
                                        .takeUnless { node -> node.isNull }
                                        ?.asText()
                                        ?.let(CaseCaptureStatus::valueOf),
                                    row.path("readinessEventId")
                                        .takeUnless { node -> node.isNull }
                                        ?.asText()
                                        ?.let(UUID::fromString),
                                )
                            )
                        }
                    }
                }
            }
            .also {
                if (it.map { report -> report.reportId }.distinct().size != it.size)
                    conflict("Report was repeated across chunks")
            }

    fun apply(
        c: Connection,
        event: CaseStructureNotificationEvent,
        reports: List<CaseReportRouting>,
    ): List<UUID> {
        val d = event.data
        structureRevision(c, d.environment, d.sourceCaseId, d.sourceRevision, d.structureSequence)
        structureRevision(
            c,
            d.environment,
            d.destinationCaseId,
            d.destinationRevision,
            d.structureSequence,
        )
        if (d.kind == CaseStructureKind.MERGE) {
            val destination = leader(c, d.environment, d.destinationCaseId)
            if (destination == d.sourceCaseId) conflict("Case navigation would create a cycle")
            c.prepareStatement(
                    "SELECT merged_into_case_id,operation_id FROM moderation_case_navigation WHERE environment=? AND case_id=?"
                )
                .use {
                    it.setString(1, d.environment.wireValue)
                    it.setObject(2, d.sourceCaseId)
                    it.executeQuery().use { r ->
                        if (
                            r.next() &&
                                (r.getObject(1, UUID::class.java) != d.destinationCaseId ||
                                    r.getObject(2, UUID::class.java) != d.operationId)
                        )
                            conflict("Merged case edge changed")
                    }
                }
            c.prepareStatement(
                    """INSERT INTO moderation_case_navigation(environment,case_id,merged_into_case_id,leader_case_id,structure_sequence,operation_id)
                VALUES (?,?,?,?,?,?) ON CONFLICT(environment,case_id) DO NOTHING"""
                )
                .use {
                    it.setString(1, d.environment.wireValue)
                    it.setObject(2, d.sourceCaseId)
                    it.setObject(3, d.destinationCaseId)
                    it.setObject(4, destination)
                    it.setLong(5, d.structureSequence)
                    it.setObject(6, d.operationId)
                    it.executeUpdate()
                }
            c.prepareStatement(
                    "UPDATE moderation_case_navigation SET leader_case_id=? WHERE environment=? AND leader_case_id=?"
                )
                .use {
                    it.setObject(1, destination)
                    it.setString(2, d.environment.wireValue)
                    it.setObject(3, d.sourceCaseId)
                    it.executeUpdate()
                }
        }
        reports
            .sortedBy { it.reportId.toString() }
            .forEach { upsertReport(c, d.environment, it, d.structureSequence) }
        return (listOf(
                leader(c, d.environment, d.sourceCaseId),
                leader(c, d.environment, d.destinationCaseId),
            ) +
                reports.map {
                    leader(c, d.environment, currentReportCase(c, d.environment, it.reportId))
                })
            .distinct()
            .sortedBy(UUID::toString)
    }

    fun readyFor(
        c: Connection,
        environment: ModerationEnvironment,
        caseId: UUID,
        event: CaseStructureNotificationEvent,
    ): ReportReadyForReviewEvent? =
        c.prepareStatement(
                """SELECT report_id,capture_id,capture_status FROM moderation_report_navigation
            WHERE environment=? AND moderation_notification_leader(environment,case_id)=?
            AND report_type='PLAYER_REPORT' AND capture_status IN ('COMPLETE','INCOMPLETE') ORDER BY report_id LIMIT 1"""
            )
            .use {
                it.setString(1, environment.wireValue)
                it.setObject(2, caseId)
                it.executeQuery().use { r ->
                    if (!r.next()) null
                    else
                        ReportReadyForReviewEvent(
                            UUID.nameUUIDFromBytes(
                                "grounds:moderation-case-routing:${environment.wireValue}:${event.data.operationId}:$caseId"
                                    .toByteArray(Charsets.UTF_8)
                            ),
                            ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
                            2,
                            event.occurredAt,
                            ReportReadyForReviewData(
                                r.getObject(1, UUID::class.java),
                                caseId,
                                r.getObject(2, UUID::class.java),
                                CaptureStatus.valueOf(r.getString(3)),
                                caseRevision(c, environment, caseId),
                                environment,
                            ),
                        )
                }
            }

    fun finish(
        c: Connection,
        event: CaseStructureNotificationEvent,
        ids: List<UUID>,
    ): CaseStructureProjectionResult {
        val result =
            CaseStructureProjectionResult(event.data.operationId, true, false, ids.distinct())
        c.prepareStatement(
                "UPDATE moderation_structure_operation SET result_json=CAST(? AS jsonb),finalized_at=transaction_timestamp() WHERE environment=? AND operation_id=? AND finalized_at IS NULL"
            )
            .use {
                it.setString(1, mapper.writeValueAsString(result))
                it.setString(2, event.data.environment.wireValue)
                it.setObject(3, event.data.operationId)
                check(it.executeUpdate() == 1)
            }
        return result
    }

    private fun bindReady(
        c: Connection,
        env: ModerationEnvironment,
        id: UUID,
        report: UUID,
        capture: UUID,
        status: CaseCaptureStatus,
    ) {
        if (
            exists(
                c,
                "SELECT 1 FROM moderation_structure_received_event WHERE environment=? AND event_id=?",
                env,
                id,
            )
        )
            conflict("Readiness identity was used by structure")
        c.prepareStatement(
                "SELECT report_id,capture_id,capture_status FROM moderation_ready_navigation_event WHERE environment=? AND event_id=?"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, id)
                it.executeQuery().use { r ->
                    if (r.next()) {
                        if (
                            r.getObject(1, UUID::class.java) != report ||
                                r.getObject(2, UUID::class.java) != capture ||
                                r.getString(3) != status.name
                        )
                            conflict("Readiness reference changed")
                    }
                }
            }
        c.prepareStatement(
                "INSERT INTO moderation_ready_navigation_event(environment,event_id,report_id,capture_id,capture_status) VALUES (?,?,?,?,?) ON CONFLICT(environment,event_id) DO NOTHING"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, id)
                it.setObject(3, report)
                it.setObject(4, capture)
                it.setString(5, status.name)
                it.executeUpdate()
            }
    }

    private fun upsertReport(
        c: Connection,
        env: ModerationEnvironment,
        report: CaseReportRouting,
        sequence: Long,
    ) {
        var caseId = report.caseId
        var seq = sequence
        var capture = report.evidenceCaptureId
        var status = report.captureStatus
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))").use {
            it.setString(1, "moderation-report-navigation:${env.wireValue}:${report.reportId}")
            it.executeQuery().use { r -> check(r.next()) }
        }
        c.prepareStatement(
                "SELECT * FROM moderation_report_navigation WHERE environment=? AND report_id=? FOR UPDATE"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, report.reportId)
                it.executeQuery().use { r ->
                    if (r.next()) {
                        if (r.getString("report_type") != report.reportType.name)
                            conflict("Report origin type changed")
                        val previousCapture = r.getObject("capture_id", UUID::class.java)
                        val previousStatus =
                            r.getString("capture_status")?.let(CaseCaptureStatus::valueOf)
                        if (
                            previousCapture != null && capture != null && previousCapture != capture
                        )
                            conflict("Report capture changed")
                        if (
                            previousStatus in
                                setOf(CaseCaptureStatus.COMPLETE, CaseCaptureStatus.INCOMPLETE)
                        ) {
                            if (
                                status in
                                    setOf(
                                        CaseCaptureStatus.COMPLETE,
                                        CaseCaptureStatus.INCOMPLETE,
                                    ) && status != previousStatus
                            )
                                conflict("Terminal capture status changed")
                            status = previousStatus
                        }
                        if (previousCapture != null) capture = previousCapture
                        if (status == null) status = previousStatus
                        val previousSeq = r.getLong("structure_sequence")
                        if (sequence == 0L || previousSeq > sequence) {
                            caseId = r.getObject("case_id", UUID::class.java)
                            seq = previousSeq
                            if (
                                sequence == 0L &&
                                    previousSeq == 0L &&
                                    leader(c, env, caseId) != leader(c, env, report.caseId)
                            )
                                conflict("Readiness cannot move a report")
                        } else if (
                            previousSeq == sequence &&
                                r.getObject("case_id", UUID::class.java) != caseId
                        )
                            conflict("Report structure revision changed")
                    }
                }
            }
        c.prepareStatement(
                """INSERT INTO moderation_report_navigation(environment,report_id,case_id,structure_sequence,report_type,capture_id,capture_status)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT(environment,report_id) DO UPDATE SET case_id=EXCLUDED.case_id,
            structure_sequence=EXCLUDED.structure_sequence,capture_id=EXCLUDED.capture_id,capture_status=EXCLUDED.capture_status"""
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, report.reportId)
                it.setObject(3, caseId)
                it.setLong(4, seq)
                it.setString(5, report.reportType.name)
                it.setObject(6, capture)
                it.setString(7, status?.name)
                it.executeUpdate()
            }
        report.readinessEventId?.let { ready ->
            bindReady(c, env, ready, report.reportId, checkNotNull(capture), checkNotNull(status))
        }
    }

    private fun currentReportCase(c: Connection, env: ModerationEnvironment, report: UUID): UUID =
        c.prepareStatement(
                "SELECT case_id FROM moderation_report_navigation WHERE environment=? AND report_id=?"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, report)
                it.executeQuery().use { r ->
                    check(r.next())
                    r.getObject(1, UUID::class.java)
                }
            }

    private fun caseRevision(c: Connection, env: ModerationEnvironment, id: UUID): Long =
        c.prepareStatement(
                "SELECT last_case_revision FROM moderation_case_navigation_state WHERE environment=? AND case_id=?"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, id)
                it.executeQuery().use { r ->
                    if (!r.next())
                        throw RetryableModerationNotificationException(
                            "Current case revision is unavailable"
                        )
                    r.getLong(1)
                }
            }

    private fun structureRevision(
        c: Connection,
        env: ModerationEnvironment,
        id: UUID,
        revision: Long,
        sequence: Long,
    ) {
        c.prepareStatement(
                "SELECT structure_sequence,structure_revision FROM moderation_case_navigation_state WHERE environment=? AND case_id=? FOR UPDATE"
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, id)
                it.executeQuery().use { r ->
                    if (r.next()) {
                        val seq = r.getLong(1)
                        val rev = r.getLong(2)
                        if (
                            (seq == sequence && rev != revision) ||
                                (seq > sequence && revision >= rev) ||
                                (seq in 1 until sequence && revision <= rev)
                        )
                            conflict("Case workflow revision disagrees with structure order")
                    }
                }
            }
        c.prepareStatement(
                """INSERT INTO moderation_case_navigation_state(environment,case_id,last_case_revision,structure_sequence,structure_revision)
            VALUES (?,?,?,?,?) ON CONFLICT(environment,case_id) DO UPDATE
            SET last_case_revision=GREATEST(moderation_case_navigation_state.last_case_revision,EXCLUDED.last_case_revision),
                structure_revision=CASE WHEN EXCLUDED.structure_sequence>moderation_case_navigation_state.structure_sequence THEN EXCLUDED.structure_revision ELSE moderation_case_navigation_state.structure_revision END,
                structure_sequence=GREATEST(moderation_case_navigation_state.structure_sequence,EXCLUDED.structure_sequence)"""
            )
            .use {
                it.setString(1, env.wireValue)
                it.setObject(2, id)
                it.setLong(3, revision)
                it.setLong(4, sequence)
                it.setLong(5, revision)
                it.executeUpdate()
            }
    }

    private fun exists(c: Connection, sql: String, env: ModerationEnvironment, id: UUID): Boolean =
        c.prepareStatement(sql).use {
            it.setString(1, env.wireValue)
            it.setObject(2, id)
            it.executeQuery().use { r -> r.next() }
        }

    private fun headerHash(event: CaseStructureNotificationEvent) =
        hash(
            listOf(
                    event.occurredAt,
                    event.data.environment.wireValue,
                    event.data.operationId,
                    event.data.structureSequence,
                    event.data.kind,
                    event.data.sourceCaseId,
                    event.data.destinationCaseId,
                    event.data.sourceRevision,
                    event.data.destinationRevision,
                    event.data.chunkCount,
                )
                .joinToString("|")
        )

    private fun chunkHash(event: CaseStructureNotificationEvent) =
        hash(
            headerHash(event).joinToString("") { "%02x".format(it) } +
                "|" +
                event.data.chunkIndex +
                "|" +
                event.data.reports
                    .sortedBy { it.reportId.toString() }
                    .joinToString(";") {
                        listOf(
                                it.reportId,
                                it.caseId,
                                it.reportType,
                                it.evidenceCaptureId,
                                it.captureStatus,
                                it.readinessEventId,
                            )
                            .joinToString("|")
                    }
        )

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun verify(event: CaseStructureNotificationEvent) {
        val d = event.data
        if (
            event.eventType != "moderation.case.restructured" ||
                event.schemaVersion != 2 ||
                d.sourceCaseId == d.destinationCaseId ||
                d.structureSequence !in 1..9007199254740991L ||
                d.sourceRevision < 1 ||
                d.destinationRevision < 1 ||
                d.chunkCount < 1 ||
                d.chunkIndex !in 0 until d.chunkCount ||
                d.reports.size > 100 ||
                (d.reports.isEmpty() && d.chunkCount != 1) ||
                d.reports.map { it.reportId }.distinct().size != d.reports.size ||
                d.reports.any {
                    (it.caseId != d.destinationCaseId &&
                        !(d.kind == CaseStructureKind.SPLIT && it.caseId == d.sourceCaseId)) ||
                        (it.evidenceCaptureId == null) != (it.captureStatus == null) ||
                        (it.readinessEventId != null &&
                            (it.reportType != CaseReportType.PLAYER_REPORT ||
                                it.captureStatus !in
                                    setOf(
                                        CaseCaptureStatus.COMPLETE,
                                        CaseCaptureStatus.INCOMPLETE,
                                    )))
                }
        )
            conflict("Invalid typed structure event")
    }

    private fun conflict(message: String): Nothing =
        throw TerminalModerationNotificationException(message)
}
