package gg.grounds.notifications.db

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class ModerationNotificationProjectionTest {
    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun resetDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE moderation_notification_projection CASCADE")
                statement.execute("TRUNCATE notifications CASCADE")
            }
        }
    }

    @Test
    fun `projection uniquely owns case type source event and notification`() {
        val caseId = UUID.randomUUID()
        val notificationId = insertNotification("moderation-case-1")
        val eventId = UUID.randomUUID()
        insertProjection(caseId, notificationId, eventId, 1)

        val duplicateCaseNotification = insertNotification("moderation-case-2")
        assertUniqueViolation {
            insertProjection(caseId, duplicateCaseNotification, UUID.randomUUID(), 2)
        }

        val duplicateEventNotification = insertNotification("moderation-case-3")
        assertUniqueViolation {
            insertProjection(UUID.randomUUID(), duplicateEventNotification, eventId, 1)
        }
        assertEquals(1, count("moderation_notification_projection"))
    }

    @Test
    fun `typed action constraint rejects executable portal navigation`() {
        val notificationId = insertNotification("moderation-action-1")
        val caseId = UUID.randomUUID().toString()

        insertAction(notificationId, null, "OPEN_PORTAL_CASE", "CASE", caseId, "{}")

        assertCheckViolation {
            insertAction(
                notificationId,
                "case open $caseId",
                "OPEN_PORTAL_CASE",
                "CASE",
                caseId,
                "{}",
            )
        }
        assertCheckViolation {
            insertAction(
                notificationId,
                null,
                "OPEN_PORTAL_CASE",
                "CASE",
                UUID.randomUUID().toString(),
                "{\"url\":\"https://portal.invalid\"}",
            )
        }
    }

    private fun insertNotification(idempotencyKey: String): UUID {
        val id = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notifications (
                        id, idempotency_key, type, category, priority, scope_type,
                        actor_type, title, body
                    ) VALUES (?, ?, 'MODERATION_CASE_READY', 'MODERATION', 'NORMAL', 'network',
                              'SYSTEM', 'Moderation case ready', 'A case is ready for review')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, idempotencyKey)
                    statement.executeUpdate()
                }
        }
        return id
    }

    private fun insertProjection(
        caseId: UUID,
        notificationId: UUID,
        eventId: UUID,
        revision: Long,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO moderation_notification_projection (
                        case_id, notification_type, notification_id, source_event_id,
                        last_case_revision, audience_fingerprint, audience_resolved_at
                    ) VALUES (?, 'MODERATION_CASE_READY', ?, ?, ?, ?, transaction_timestamp())
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, caseId)
                    statement.setObject(2, notificationId)
                    statement.setObject(3, eventId)
                    statement.setLong(4, revision)
                    statement.setBytes(5, ByteArray(32))
                    statement.executeUpdate()
                }
        }
    }

    private fun insertAction(
        notificationId: UUID,
        command: String?,
        kind: String,
        entityType: String,
        entityId: String,
        payload: String,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_actions (
                        id, notification_id, action_key, label, style, command, payload,
                        action_kind, entity_type, entity_id
                    ) VALUES (?, ?, ?, 'Open case', 'primary', ?, CAST(? AS jsonb), ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, notificationId)
                    statement.setString(3, UUID.randomUUID().toString())
                    statement.setString(4, command)
                    statement.setString(5, payload)
                    statement.setString(6, kind)
                    statement.setString(7, entityType)
                    statement.setString(8, entityId)
                    statement.executeUpdate()
                }
        }
    }

    private fun assertUniqueViolation(operation: () -> Unit) {
        val exception = assertThrows(SQLException::class.java, operation)
        assertEquals("23505", exception.sqlState)
    }

    private fun assertCheckViolation(operation: () -> Unit) {
        val exception = assertThrows(SQLException::class.java, operation)
        assertEquals("23514", exception.sqlState)
    }

    private fun count(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }
}
