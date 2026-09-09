package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.audience.RetryableForgeAudienceException
import gg.grounds.notifications.db.NotificationRepository
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
class ModerationNotificationProjectorTest {
    @Inject lateinit var repository: NotificationRepository

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
    fun `creates one safe canonical notification and audience snapshot`() {
        val event = event(revision = 1)
        val result = repository.projectModerationCase(event, audience("alpha", "beta"))

        assertEquals(ModerationProjectionStatus.CREATED, result.status)
        assertEquals(listOf("alpha", "beta"), result.notifiedUserIds)
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT n.type, n.category, n.priority, n.scope_type, n.scope_id,
                           n.actor_type, n.actor_id, n.entity_type, n.entity_id,
                           n.title, n.body, n.data::text,
                           p.source_event_id, p.last_case_revision,
                           encode(p.audience_fingerprint, 'hex') AS fingerprint
                    FROM moderation_notification_projection p
                    JOIN notifications n ON n.id = p.notification_id
                    WHERE p.case_id = ?
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, event.data.caseId)
                    statement.executeQuery().use { row ->
                        check(row.next())
                        assertEquals("MODERATION_CASE_READY", row.getString("type"))
                        assertEquals("MODERATION", row.getString("category"))
                        assertEquals("NORMAL", row.getString("priority"))
                        assertEquals("network", row.getString("scope_type"))
                        assertEquals(null, row.getString("scope_id"))
                        assertEquals("SYSTEM", row.getString("actor_type"))
                        assertEquals(null, row.getString("actor_id"))
                        assertEquals("CASE", row.getString("entity_type"))
                        assertEquals(event.data.caseId.toString(), row.getString("entity_id"))
                        assertEquals("Moderation case ready for review", row.getString("title"))
                        assertEquals(
                            "A moderation case is ready for review.",
                            row.getString("body"),
                        )
                        assertEquals("{}", row.getString("data"))
                        assertEquals(
                            event.eventId,
                            row.getObject("source_event_id", UUID::class.java),
                        )
                        assertEquals(1, row.getLong("last_case_revision"))
                        assertEquals("a".repeat(64), row.getString("fingerprint"))
                    }
                }
        }
        assertEquals(listOf("alpha", "beta"), activeRecipients(result.notificationId))
        assertEquals(1, count("notification_actions"))
        assertEquals(1, count("notification_outbox"))
    }

    @Test
    fun `deduplicates event and rejects same or stale revisions`() {
        val first = event(revision = 3)
        val created = repository.projectModerationCase(first, audience("alpha"))

        val duplicate = repository.projectModerationCase(first, audience("beta"))
        val sameRevision = repository.projectModerationCase(event(revision = 3), audience("beta"))
        val stale = repository.projectModerationCase(event(revision = 2), audience("beta"))

        assertEquals(ModerationProjectionStatus.DUPLICATE, duplicate.status)
        assertEquals(ModerationProjectionStatus.STALE, sameRevision.status)
        assertEquals(ModerationProjectionStatus.STALE, stale.status)
        assertEquals(created.notificationId, duplicate.notificationId)
        assertEquals(listOf("alpha"), activeRecipients(created.notificationId))
        assertEquals(1, count("notification_outbox"))
    }

    @Test
    fun `newer revision keeps id and read state while notifying only audience delta`() {
        val caseId = UUID.randomUUID()
        val created =
            repository.projectModerationCase(
                event(caseId = caseId, revision = 1),
                audience("alpha", "beta"),
            )
        markRead(created.notificationId, "alpha")

        val updated =
            repository.projectModerationCase(
                event(caseId = caseId, reportId = UUID.randomUUID(), revision = 2),
                audience("alpha", "charlie", fingerprint = "b".repeat(64)),
            )

        assertEquals(ModerationProjectionStatus.UPDATED, updated.status)
        assertEquals(created.notificationId, updated.notificationId)
        assertEquals(listOf("beta", "charlie"), updated.notifiedUserIds)
        assertEquals(listOf("alpha", "charlie"), activeRecipients(updated.notificationId))
        assertEquals(true, isRead(updated.notificationId, "alpha"))
        assertEquals(false, isRead(updated.notificationId, "charlie"))
        assertEquals(true, isArchived(updated.notificationId, "beta"))
        assertEquals(2, count("notification_outbox"))
        assertEquals(listOf("beta", "charlie"), latestOutboxRecipients())
    }

    @Test
    fun `concurrent first events converge on one notification`() {
        val caseId = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val tasks =
            listOf(1, 2).map {
                Callable {
                    start.await()
                    repository.projectModerationCase(
                        event(caseId = caseId, revision = 1),
                        audience("alpha"),
                    )
                }
            }
        val futures = tasks.map(pool::submit)
        start.countDown()
        val results = futures.map { it.get() }
        pool.shutdown()

        assertEquals(1, results.map { it.notificationId }.distinct().size)
        assertEquals(1, count("notifications"))
        assertEquals(1, count("moderation_notification_projection"))
    }

    private fun event(
        caseId: UUID = UUID.randomUUID(),
        reportId: UUID = UUID.randomUUID(),
        revision: Long,
    ) =
        ReportReadyForReviewEvent(
            eventId = UUID.randomUUID(),
            eventType = ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
            schemaVersion = 1,
            occurredAt = Instant.parse("2026-07-22T10:15:30Z"),
            data =
                ReportReadyForReviewData(
                    reportId = reportId,
                    caseId = caseId,
                    evidenceCaptureId = UUID.randomUUID(),
                    captureStatus = CaptureStatus.COMPLETE,
                    caseRevision = revision,
                ),
        )

    private fun audience(vararg users: String, fingerprint: String = "a".repeat(64)) =
        ForgeAudienceSnapshot(
            userIds = users.toList().sorted(),
            resolvedAt = Instant.parse("2026-07-22T10:15:31Z"),
            fingerprint = fingerprint,
        )

    private fun activeRecipients(notificationId: UUID): List<String> =
        strings(
            "SELECT user_id FROM notification_recipients WHERE notification_id = ? AND archived_at IS NULL ORDER BY user_id",
            notificationId,
        )

    private fun latestOutboxRecipients(): List<String> =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT payload->'recipientUserIds' FROM notification_outbox ORDER BY created_at DESC LIMIT 1"
                    )
                    .use { result ->
                        check(result.next())
                        val node =
                            com.fasterxml.jackson.databind
                                .ObjectMapper()
                                .readTree(result.getString(1))
                        node.map { it.asText() }
                    }
            }
        }

    private fun strings(sql: String, id: UUID): List<String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, id)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString(1)) }
                }
            }
        }

    private fun markRead(notificationId: UUID, userId: String) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE notification_recipients SET read_at = now() WHERE notification_id = ? AND user_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.setString(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun isRead(notificationId: UUID, userId: String): Boolean =
        timestampPresent(notificationId, userId, "read_at")

    private fun isArchived(notificationId: UUID, userId: String): Boolean =
        timestampPresent(notificationId, userId, "archived_at")

    private fun timestampPresent(notificationId: UUID, userId: String, column: String): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT $column IS NOT NULL FROM notification_recipients WHERE notification_id = ? AND user_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.setString(2, userId)
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getBoolean(1)
                    }
                }
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

class ModerationNotificationProjectorUnitTest {
    @Test
    fun `processed event bypasses audience resolution`() {
        val notificationId = UUID.randomUUID()
        val event = readyEvent()
        var resolved = false
        val store =
            object : ModerationProjectionStore {
                override fun findProcessedModerationEvent(event: ReportReadyForReviewEvent) =
                    ModerationProjectionResult(notificationId, ModerationProjectionStatus.DUPLICATE)

                override fun projectModerationCase(
                    event: ReportReadyForReviewEvent,
                    audience: ForgeAudienceSnapshot,
                ): ModerationProjectionResult = error("must not project")
            }
        val projector =
            ModerationNotificationProjector(
                audienceResolver = {
                    resolved = true
                    error("must not resolve")
                },
                projectionStore = store,
            )

        projector.handle(event)

        assertEquals(false, resolved)
    }

    @Test
    fun `resolver retry failure prevents persistence and remains retryable`() {
        var persisted = false
        val projector =
            ModerationNotificationProjector(
                audienceResolver = {
                    throw RetryableForgeAudienceException("forge temporarily unavailable")
                },
                projectionStore = { _, _ ->
                    persisted = true
                    error("must not persist")
                },
            )

        assertThrows(RetryableModerationNotificationException::class.java) {
            projector.handle(readyEvent())
        }
        assertEquals(false, persisted)
    }

    private fun readyEvent() =
        ReportReadyForReviewEvent(
            UUID.randomUUID(),
            ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
            1,
            Instant.now(),
            ReportReadyForReviewData(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CaptureStatus.COMPLETE,
                1,
            ),
        )
}
