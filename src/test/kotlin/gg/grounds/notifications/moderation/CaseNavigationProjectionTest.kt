package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.db.NotificationRepository
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
class CaseNavigationProjectionTest {
    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var source: DataSource
    private val audience =
        ForgeAudienceSnapshot(
            listOf("supporter"),
            Instant.parse("2026-11-03T12:00:00Z"),
            "a".repeat(64),
        )
    private val environment = ModerationEnvironment.STAGE

    @BeforeEach
    fun reset() {
        source.connection.use { c ->
            c.createStatement().use {
                it.execute("TRUNCATE notification_outbox,notifications CASCADE")
            }
            for (table in
                listOf(
                    "moderation_case_navigation",
                    "moderation_report_navigation",
                    "moderation_ready_navigation_event",
                    "moderation_case_navigation_state",
                    "moderation_structure_operation",
                    "moderation_structure_chunk",
                    "moderation_structure_received_event",
                )) {
                val exists =
                    c.prepareStatement("SELECT to_regclass(?) IS NOT NULL").use { q ->
                        q.setString(1, table)
                        q.executeQuery().use { r ->
                            r.next()
                            r.getBoolean(1)
                        }
                    }
                if (exists) c.createStatement().use { it.execute("TRUNCATE $table CASCADE") }
            }
        }
    }

    private fun ready(
        case: UUID = UUID.randomUUID(),
        revision: Long = 1,
        env: ModerationEnvironment = environment,
    ) =
        ReportReadyForReviewEvent(
            UUID.randomUUID(),
            ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
            2,
            Instant.parse("2026-11-03T12:00:00Z"),
            ReportReadyForReviewData(
                UUID.randomUUID(),
                case,
                UUID.randomUUID(),
                CaptureStatus.COMPLETE,
                revision,
                env,
            ),
        )

    private fun location(event: ReportReadyForReviewEvent, case: UUID) =
        CaseReportRouting(
            event.data.reportId,
            case,
            CaseReportType.PLAYER_REPORT,
            event.data.evidenceCaptureId,
            CaseCaptureStatus.COMPLETE,
            event.eventId,
        )

    private fun structure(
        a: UUID,
        b: UUID,
        sequence: Long,
        reports: List<CaseReportRouting>,
        kind: CaseStructureKind = CaseStructureKind.MERGE,
        sourceRevision: Long = 2,
        destinationRevision: Long = 2,
        index: Int = 0,
        count: Int = 1,
        op: UUID = UUID.randomUUID(),
    ) =
        CaseStructureNotificationEvent(
            UUID.randomUUID(),
            "moderation.case.restructured",
            2,
            Instant.parse("2026-11-03T12:05:00Z"),
            CaseStructureNotificationData(
                environment,
                op,
                sequence,
                kind,
                a,
                b,
                sourceRevision,
                destinationRevision,
                index,
                count,
                reports,
            ),
        )

    private fun project(event: CaseStructureNotificationEvent) =
        repository.projectCaseStructure(event, audience)

    private fun active() = repository.listForUser("supporter")

    private fun count(table: String) =
        source.connection.use { c ->
            c.createStatement().use { q ->
                q.executeQuery("SELECT count(*) FROM $table").use { r ->
                    r.next()
                    r.getLong(1)
                }
            }
        }

    private fun rawAction(id: UUID) =
        source.connection.use { c ->
            c.prepareStatement("SELECT entity_id FROM notification_actions WHERE notification_id=?")
                .use { q ->
                    q.setObject(1, id)
                    q.executeQuery().use { r ->
                        r.next()
                        r.getString(1)
                    }
                }
        }

    @Test
    fun `merge keeps canonical destination and historical records without duplicate active inbox rows`() {
        val a = ready()
        val b = ready()
        val original = repository.projectModerationCase(a, audience)
        val destination = repository.projectModerationCase(b, audience)
        val merge =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        assertTrue(project(merge).complete)
        assertEquals(listOf(destination.notificationId), active().map { it.id })
        assertEquals(b.data.caseId.toString(), active().single().actions.single().entityId)
        assertEquals(a.data.caseId.toString(), rawAction(original.notificationId))
        assertEquals(2, count("notifications"))
        val outbox = count("notification_outbox")
        assertTrue(project(merge).replay)
        assertEquals(outbox, count("notification_outbox"))
        assertEquals(2, count("notification_recipients"))
    }

    @Test
    fun `split creates only new ready case and old readiness replay never revives an archived source`() {
        val a = ready()
        val b = ready()
        val c = UUID.randomUUID()
        repository.projectModerationCase(a, audience)
        repository.projectModerationCase(b, audience)
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        )
        project(
            structure(
                b.data.caseId,
                c,
                2,
                listOf(location(a, c), location(b, b.data.caseId)),
                CaseStructureKind.SPLIT,
                3,
                2,
            )
        )
        assertEquals(
            setOf(b.data.caseId.toString(), c.toString()),
            active().map { it.actions.single().entityId }.toSet(),
        )
        assertEquals(2, active().size)
        assertEquals(3, count("notifications"))
        val outbox = count("notification_outbox")
        repository.projectModerationCase(a, audience)
        assertEquals(2, active().size)
        assertEquals(outbox, count("notification_outbox"))
    }

    @Test
    fun `out of order split and merge followed by delayed ready converge on current report membership`() {
        val a = ready()
        val b = ready()
        val c = UUID.randomUUID()
        val merge =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        val split =
            structure(
                b.data.caseId,
                c,
                2,
                listOf(location(a, c), location(b, b.data.caseId)),
                CaseStructureKind.SPLIT,
                3,
                2,
            )
        project(split)
        project(merge)
        repository.projectModerationCase(a, audience)
        repository.projectModerationCase(b, audience)
        assertEquals(
            setOf(b.data.caseId.toString(), c.toString()),
            active().map { it.actions.single().entityId }.toSet(),
        )
        assertEquals(2, active().size)
        assertEquals(2, count("notifications"))
    }

    @Test
    fun `chunks finalize only together and conflicting operation identities roll back`() {
        val a = ready()
        val b = ready()
        val op = UUID.randomUUID()
        val first =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId)),
                index = 0,
                count = 2,
                op = op,
            )
        val second =
            first.copy(
                eventId = UUID.randomUUID(),
                data = first.data.copy(chunkIndex = 1, reports = listOf(location(b, b.data.caseId))),
            )
        assertFalse(project(second).complete)
        assertTrue(active().isEmpty())
        assertTrue(project(first).complete)
        assertEquals(1, active().size)
        val outbox = count("notification_outbox")
        assertTrue(project(first.copy(eventId = UUID.randomUUID())).replay)
        assertEquals(outbox, count("notification_outbox"))
        assertThrows(TerminalModerationNotificationException::class.java) {
            project(first.copy(data = first.data.copy(destinationRevision = 9)))
        }
        assertEquals(outbox, count("notification_outbox"))
    }

    @Test
    fun `same case identities remain isolated across environments and original large source revision cannot replace destination revision`() {
        val a = ready(revision = 100)
        val b = ready(revision = 2)
        val original = repository.projectModerationCase(a, audience)
        val destination = repository.projectModerationCase(b, audience)
        val prod = a.copy(data = a.data.copy(environment = ModerationEnvironment.PROD))
        val production = repository.projectModerationCase(prod, audience)
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
                sourceRevision = 101,
                destinationRevision = 3,
            )
        )
        val items = active().associateBy { it.id }
        assertEquals(setOf(destination.notificationId, production.notificationId), items.keys)
        assertEquals(
            a.data.caseId.toString(),
            items.getValue(production.notificationId).actions.single().entityId,
        )
        assertEquals(
            b.data.caseId.toString(),
            repository.findAction(original.notificationId, "open_case")!!.entityId,
        )
        assertEquals(
            ModerationEnvironment.STAGE,
            repository.findAction(original.notificationId, "open_case")!!.environment,
        )
    }

    @Test
    fun `retained old source event binds pre migration notifications without invented report history`() {
        val a = ready()
        val b = ready()
        val c = UUID.randomUUID()
        val old = repository.projectModerationCase(a, audience)
        repository.projectModerationCase(b, audience)
        source.connection.use { db ->
            db.createStatement().use {
                it.execute(
                    "UPDATE moderation_notification_projection SET navigation_report_id=NULL"
                )
                it.execute(
                    "TRUNCATE moderation_report_navigation,moderation_ready_navigation_event,moderation_case_navigation_state"
                )
            }
        }
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        )
        project(
            structure(
                b.data.caseId,
                c,
                2,
                listOf(location(a, c), location(b, b.data.caseId)),
                CaseStructureKind.SPLIT,
                3,
                2,
            )
        )
        assertEquals(
            c.toString(),
            repository.findAction(old.notificationId, "open_case")!!.entityId,
        )
        assertEquals(a.data.caseId.toString(), rawAction(old.notificationId))
        assertEquals(2, active().size)
    }

    @Test
    fun `later merge arriving first flattens old case edges without recreating historical inbox rows`() {
        val a = ready()
        val b = ready()
        val c = ready()
        project(
            structure(
                b.data.caseId,
                c.data.caseId,
                2,
                listOf(
                    location(a, c.data.caseId),
                    location(b, c.data.caseId),
                    location(c, c.data.caseId),
                ),
                sourceRevision = 3,
            )
        )
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        )
        repository.projectModerationCase(a, audience)
        assertEquals(1, active().size)
        assertEquals(c.data.caseId.toString(), active().single().actions.single().entityId)
        assertEquals(1, count("notifications"))
    }

    @Test
    fun `known readiness event identity cannot be reused by an unrelated structure operation`() {
        val a = ready()
        val b = ready()
        val z = ready()
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        )
        val other = UUID.randomUUID()
        val forged =
            structure(z.data.caseId, other, 2, listOf(location(z, other))).copy(eventId = a.eventId)
        assertThrows(TerminalModerationNotificationException::class.java) { project(forged) }
        assertEquals(1, count("moderation_structure_operation"))
    }

    @Test
    fun `failed last chunk rolls back navigation and inbox while retaining earlier incomplete chunk`() {
        val a = ready()
        val b = ready()
        val op = UUID.randomUUID()
        val original = repository.projectModerationCase(a, audience)
        val first =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId)),
                index = 0,
                count = 2,
                op = op,
            )
        val last =
            first.copy(
                eventId = UUID.randomUUID(),
                data = first.data.copy(chunkIndex = 1, reports = listOf(location(b, b.data.caseId))),
            )
        assertFalse(project(first).complete)
        source.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    "CREATE FUNCTION fixture_fail_case_navigation_outbox() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''controlled routing outbox failure''; END'"
                )
                it.execute(
                    "CREATE TRIGGER fixture_fail_case_navigation_outbox BEFORE INSERT ON notification_outbox FOR EACH ROW EXECUTE FUNCTION fixture_fail_case_navigation_outbox()"
                )
            }
        }
        try {
            assertThrows(java.sql.SQLException::class.java) { project(last) }
            assertEquals(1, count("moderation_structure_chunk"))
            assertEquals(0, count("moderation_case_navigation"))
            assertEquals(listOf(original.notificationId), active().map { it.id })
            assertEquals(1, count("notifications"))
        } finally {
            source.connection.use { c ->
                c.createStatement().use {
                    it.execute("DROP FUNCTION fixture_fail_case_navigation_outbox() CASCADE")
                }
            }
        }
        assertTrue(project(last).complete)
        assertEquals(1, active().size)
        assertEquals(b.data.caseId.toString(), active().single().actions.single().entityId)
        assertEquals(2, count("notifications"))
    }

    @Test
    fun `fresh audience gains only current canonical case and loses retired historical recipients`() {
        val a = ready()
        val b = ready()
        val old = repository.projectModerationCase(a, audience)
        val destination = repository.projectModerationCase(b, audience)
        val fresh =
            ForgeAudienceSnapshot(
                listOf("new-supporter"),
                audience.resolvedAt.plusSeconds(300),
                "b".repeat(64),
            )
        repository.projectCaseStructure(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            ),
            fresh,
        )
        assertTrue(active().isEmpty())
        assertEquals(
            listOf(destination.notificationId),
            repository.listForUser("new-supporter").map { it.id },
        )
        assertEquals(3, count("notification_recipients"))
        assertEquals(a.data.caseId.toString(), rawAction(old.notificationId))
    }

    @Test
    fun `conflicting terminal capture and cycle cannot overwrite prior committed routing`() {
        val a = ready()
        val b = ready()
        repository.projectModerationCase(a, audience)
        assertThrows(TerminalModerationNotificationException::class.java) {
            repository.projectModerationCase(
                a.copy(
                    eventId = UUID.randomUUID(),
                    data = a.data.copy(captureStatus = CaptureStatus.INCOMPLETE),
                ),
                audience,
            )
        }
        project(
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        )
        assertThrows(TerminalModerationNotificationException::class.java) {
            project(
                structure(
                    b.data.caseId,
                    a.data.caseId,
                    2,
                    listOf(location(a, a.data.caseId), location(b, a.data.caseId)),
                    sourceRevision = 3,
                    destinationRevision = 3,
                )
            )
        }
        assertEquals(1, count("moderation_case_navigation"))
        assertEquals(b.data.caseId.toString(), active().single().actions.single().entityId)
    }

    @Test
    fun `inbox materialization waits for structural transaction before reading actions`() {
        val a = ready()
        repository.projectModerationCase(a, audience)
        val classLoader = Thread.currentThread().contextClassLoader
        java.util.concurrent.Executors.newSingleThreadExecutor().use { pool ->
            source.connection.use { blocker ->
                blocker.autoCommit = false
                blocker
                    .prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")
                    .use {
                        it.setString(1, "moderation-navigation:stage")
                        it.executeQuery().close()
                    }
                val pid =
                    blocker.createStatement().use { q ->
                        q.executeQuery("SELECT pg_backend_pid()").use { r ->
                            r.next()
                            r.getInt(1)
                        }
                    }
                val future =
                    pool.submit<List<gg.grounds.notifications.core.NotificationInboxItem>> {
                        Thread.currentThread().contextClassLoader = classLoader
                        active()
                    }
                try {
                    assertTrue(blockedBy(pid))
                } finally {
                    blocker.rollback()
                }
                assertEquals(1, future.get(15, java.util.concurrent.TimeUnit.SECONDS).size)
            }
        }
    }

    @Test
    fun `persisted receipt fast path validates identity and remembers duplicate chunk event ids`() {
        val a = ready()
        val b = ready()
        val event =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        assertNull(repository.findProcessedCaseStructure(event))
        project(event)
        assertTrue(repository.findProcessedCaseStructure(event)!!.replay)
        val duplicate = event.copy(eventId = UUID.randomUUID())
        assertNull(repository.findProcessedCaseStructure(duplicate))
        assertTrue(project(duplicate).replay)
        assertTrue(repository.findProcessedCaseStructure(duplicate)!!.replay)
        assertEquals(2, count("moderation_structure_received_event"))
        assertThrows(TerminalModerationNotificationException::class.java) {
            repository.findProcessedCaseStructure(
                event.copy(data = event.data.copy(destinationRevision = 99))
            )
        }
        assertEquals(1, active().size)
    }

    @Test
    fun `two concurrent consumer instances project one canonical notification and one structure receipt`() {
        val a = ready()
        val b = ready()
        val event =
            structure(
                a.data.caseId,
                b.data.caseId,
                1,
                listOf(location(a, b.data.caseId), location(b, b.data.caseId)),
            )
        val bothResolving = java.util.concurrent.CyclicBarrier(2)
        val resolver =
            object : gg.grounds.notifications.audience.ForgeAudienceResolver {
                override fun resolveModerationAudience():
                    gg.grounds.notifications.audience.ForgeAudienceSnapshot =
                    error("Legacy resolution forbidden")

                override fun resolveModerationAudience(
                    environment: ModerationEnvironment?
                ): gg.grounds.notifications.audience.ForgeAudienceSnapshot {
                    assertEquals(ModerationEnvironment.STAGE, environment)
                    bothResolving.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    return audience
                }
            }
        val classLoader = Thread.currentThread().contextClassLoader
        java.util.concurrent.Executors.newFixedThreadPool(2).use { pool ->
            val futures =
                (1..2).map {
                    pool.submit<Unit> {
                        Thread.currentThread().contextClassLoader = classLoader
                        CaseStructureNotificationProjector(resolver, repository).handle(event)
                    }
                }
            futures.forEach { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
        }
        assertEquals(1, active().size)
        assertEquals(1, count("notifications"))
        assertEquals(1, count("moderation_structure_operation"))
        assertEquals(1, count("moderation_structure_received_event"))
        val outbox = count("notification_outbox")
        project(event)
        assertEquals(outbox, count("notification_outbox"))
    }

    private fun blockedBy(pid: Int): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        source.connection.use { c ->
            while (System.nanoTime() < deadline) {
                c.prepareStatement(
                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))"
                    )
                    .use {
                        it.setInt(1, pid)
                        it.executeQuery().use { r ->
                            r.next()
                            if (r.getBoolean(1)) return true
                        }
                    }
                Thread.sleep(20)
            }
        }
        return false
    }
}
