package gg.grounds.notifications.moderation

import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID

@ApplicationScoped
class EnvironmentModerationEventLedger {
    fun verifyVersion(event: ReportReadyForReviewEvent) {
        if (
            (event.schemaVersion == 1 && event.data.environment == null) ||
                (event.schemaVersion == 2 && event.data.environment != null)
        )
            return
        throw TerminalModerationNotificationException(
            "Moderation event version and environment disagree"
        )
    }

    fun lock(connection: Connection, event: ReportReadyForReviewEvent) {
        val environment = event.data.environment ?: return
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))").use {
            it.setString(1, "moderation-event:${environment.wireValue}:${event.eventId}")
            it.executeQuery().use { row -> check(row.next()) }
        }
    }

    fun findProcessed(
        connection: Connection,
        event: ReportReadyForReviewEvent,
    ): ModerationProjectionResult? {
        verifyVersion(event)
        val environment = event.data.environment ?: return null
        return connection
            .prepareStatement(
                "SELECT notification_id,payload_fingerprint,result_status FROM moderation_notification_processed_event WHERE environment = ? AND event_id = ?"
            )
            .use {
                it.setString(1, environment.wireValue)
                it.setObject(2, event.eventId)
                it.executeQuery().use { row ->
                    if (!row.next()) return@use null
                    if (
                        !MessageDigest.isEqual(
                            row.getBytes("payload_fingerprint"),
                            fingerprint(event),
                        )
                    ) {
                        throw TerminalModerationNotificationException(
                            "Moderation event identity was reused with different content"
                        )
                    }
                    val status =
                        if (row.getString("result_status") == "STALE")
                            ModerationProjectionStatus.STALE
                        else ModerationProjectionStatus.DUPLICATE
                    ModerationProjectionResult(
                        row.getObject("notification_id", UUID::class.java),
                        status,
                    )
                }
            }
    }

    fun record(
        connection: Connection,
        event: ReportReadyForReviewEvent,
        result: ModerationProjectionResult,
    ) {
        val environment = event.data.environment ?: return
        connection
            .prepareStatement(
                "INSERT INTO moderation_notification_processed_event (environment,event_id,case_id,case_revision,payload_fingerprint,notification_id,result_status) VALUES (?,?,?,?,?,?,?)"
            )
            .use {
                it.setString(1, environment.wireValue)
                it.setObject(2, event.eventId)
                it.setObject(3, event.data.caseId)
                it.setLong(4, event.data.caseRevision)
                it.setBytes(5, fingerprint(event))
                it.setObject(6, result.notificationId)
                it.setString(7, result.status.name)
                it.executeUpdate()
            }
    }

    private fun fingerprint(event: ReportReadyForReviewEvent): ByteArray {
        // Every accepted field is a fixed taxonomy, number, UUID or canonical Instant;
        // none can contain this delimiter, and equivalent JSON ordering/casing is normalized.
        val data = event.data
        val canonical =
            listOf(
                    event.eventId,
                    event.eventType,
                    event.schemaVersion,
                    event.occurredAt,
                    data.environment?.wireValue,
                    data.reportId,
                    data.caseId,
                    data.evidenceCaptureId,
                    data.captureStatus,
                    data.caseRevision,
                )
                .joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
    }
}
