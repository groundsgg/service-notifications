package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.db.NotificationRepository
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
class EnvironmentModerationProjectionTest {
    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var dataSource: DataSource
    private val audience =
        ForgeAudienceSnapshot(
            listOf("supporter"),
            Instant.parse("2026-09-08T18:00:00Z"),
            "a".repeat(64),
        )

    @BeforeEach
    fun resetTestDatabase() {
        dataSource.connection.use {
            it.createStatement().use { statement ->
                statement.execute("TRUNCATE notification_outbox, notifications CASCADE")
            }
        }
    }

    @Test
    fun `identical case and event IDs produce distinct stage and prod notifications`() {
        val stage = event()
        val prod = stage.copy(data = stage.data.copy(environment = ModerationEnvironment.PROD))
        val stageResult = repository.projectModerationCase(stage, audience)
        val prodResult = repository.projectModerationCase(prod, audience)
        assertEquals(ModerationProjectionStatus.CREATED, stageResult.status)
        assertEquals(ModerationProjectionStatus.CREATED, prodResult.status)
        assertNotEquals(stageResult.notificationId, prodResult.notificationId)
        assertEquals(2, repository.listForUser("supporter").size)
    }

    @Test
    fun `v2 notification action carries its fixed environment without changing legacy projections`() {
        val stage = event()
        val legacy =
            stage.copy(
                eventId = UUID.randomUUID(),
                schemaVersion = 1,
                data = stage.data.copy(environment = null),
            )
        val stageResult = repository.projectModerationCase(stage, audience)
        val legacyResult = repository.projectModerationCase(legacy, audience)
        assertNotEquals(stageResult.notificationId, legacyResult.notificationId)
        val items = repository.listForUser("supporter").associateBy { it.id }
        assertEquals(
            ModerationEnvironment.STAGE,
            items.getValue(stageResult.notificationId).actions.single().environment,
        )
        assertEquals(
            "stage",
            items.getValue(stageResult.notificationId).data.path("environment").asText(),
        )
        assertEquals(null, items.getValue(legacyResult.notificationId).actions.single().environment)
    }

    @Test
    fun `event deduplication history survives a later case revision`() {
        val initial = event()
        val result = repository.projectModerationCase(initial, audience)
        val update =
            initial.copy(eventId = UUID.randomUUID(), data = initial.data.copy(caseRevision = 2))
        assertEquals(
            ModerationProjectionStatus.UPDATED,
            repository.projectModerationCase(update, audience).status,
        )
        val replay = repository.findProcessedModerationEvent(initial)!!
        assertEquals(result.notificationId, replay.notificationId)
        assertEquals(ModerationProjectionStatus.DUPLICATE, replay.status)
        assertEquals(1, repository.listForUser("supporter").size)
    }

    @Test
    fun `reused event identity with different payload is rejected without replacing the projection`() {
        val initial = event()
        repository.projectModerationCase(initial, audience)
        val forged = initial.copy(data = initial.data.copy(caseRevision = 2))
        assertThrows(TerminalModerationNotificationException::class.java) {
            repository.findProcessedModerationEvent(forged)
        }
        assertThrows(TerminalModerationNotificationException::class.java) {
            repository.projectModerationCase(forged, audience)
        }
        assertEquals(1, repository.listForUser("supporter").size)
    }

    @Test
    fun `parallel v2 delivery creates one canonical notification and one delivery event`() {
        val ready = event()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val ids =
                pool
                    .invokeAll(
                        (1..4).map {
                            Callable {
                                repository.projectModerationCase(ready, audience).notificationId
                            }
                        }
                    )
                    .map { it.get() }
                    .distinct()
            assertEquals(1, ids.size)
        } finally {
            pool.shutdownNow()
        }
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery("SELECT count(*) FROM notification_outbox").use { result ->
                    result.next()
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `ledger persistence failure rolls back notification audience projection and delivery together`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute(
                    "CREATE FUNCTION fixture_fail_env_ledger() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''fixture ledger failure''; END;'"
                )
                it.execute(
                    "CREATE TRIGGER fixture_fail_env_ledger BEFORE INSERT ON moderation_notification_processed_event FOR EACH ROW EXECUTE FUNCTION fixture_fail_env_ledger()"
                )
            }
        }
        try {
            assertThrows(java.sql.SQLException::class.java) {
                repository.projectModerationCase(event(), audience)
            }
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    for (table in
                        listOf(
                            "notifications",
                            "notification_recipients",
                            "moderation_notification_projection",
                            "moderation_notification_processed_event",
                            "notification_outbox",
                        )) {
                        statement.executeQuery("SELECT count(*) FROM $table").use { result ->
                            check(result.next())
                            assertEquals(0, result.getInt(1))
                        }
                    }
                }
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "DROP TRIGGER fixture_fail_env_ledger ON moderation_notification_processed_event"
                    )
                    it.execute("DROP FUNCTION fixture_fail_env_ledger()")
                }
            }
        }
    }

    private fun event() =
        ReportReadyForReviewEvent(
            UUID.randomUUID(),
            "moderation.report.ready-for-review",
            2,
            Instant.parse("2026-09-08T18:00:00Z"),
            ReportReadyForReviewData(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CaptureStatus.COMPLETE,
                1,
                ModerationEnvironment.STAGE,
            ),
        )
}
