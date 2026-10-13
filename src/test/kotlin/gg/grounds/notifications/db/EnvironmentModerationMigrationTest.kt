package gg.grounds.notifications.db

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
class EnvironmentModerationMigrationTest {
    @Inject lateinit var dataSource: DataSource

    @Test
    fun `upgrading a populated legacy schema retains notification IDs read state and unknown environment`() {
        val schema = "notification_env_upgrade_" + UUID.randomUUID().toString().replace("-", "")
        val notificationId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        try {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target("5")
                .load()
                .migrate()
            dataSource.connection.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO $schema.notifications (id,idempotency_key,type,category,priority,scope_type,actor_type,title,body) VALUES (?, 'legacy-migration-fixture','MODERATION_CASE_READY','MODERATION','NORMAL','network','SYSTEM','Case ready','Safe fixture')"
                    )
                    .use {
                        it.setObject(1, notificationId)
                        it.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO $schema.notification_recipients (id,notification_id,user_id,read_at) VALUES (?,?,'supporter','2026-09-08T18:00:00Z')"
                    )
                    .use {
                        it.setObject(1, UUID.randomUUID())
                        it.setObject(2, notificationId)
                        it.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO $schema.moderation_notification_projection (case_id,notification_type,notification_id,source_event_id,last_case_revision,audience_fingerprint,audience_resolved_at) VALUES (?,'MODERATION_CASE_READY',?,?,1,?,now())"
                    )
                    .use {
                        it.setObject(1, caseId)
                        it.setObject(2, notificationId)
                        it.setObject(3, eventId)
                        it.setBytes(4, ByteArray(32))
                        it.executeUpdate()
                    }
            }
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .target("6")
                .load()
                .migrate()
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT p.notification_id,p.source_event_id,p.environment,r.read_at FROM $schema.moderation_notification_projection p JOIN $schema.notification_recipients r ON r.notification_id=p.notification_id"
                        )
                        .use {
                            check(it.next())
                            assertEquals(
                                notificationId,
                                it.getObject("notification_id", UUID::class.java),
                            )
                            assertEquals(eventId, it.getObject("source_event_id", UUID::class.java))
                            assertEquals(null, it.getString("environment"))
                            assertEquals(
                                "2026-09-08T18:00:00Z",
                                it.getTimestamp("read_at").toInstant().toString(),
                            )
                        }
                }
            }
        } finally {
            dataSource.connection.use {
                it.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                }
            }
        }
    }
}
