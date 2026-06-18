package gg.grounds.notifications.outbox

import gg.grounds.notifications.live.NotificationLiveEvent
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class NotificationOutboxProcessorTest {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var outboxProcessor: NotificationOutboxProcessor

    @Inject lateinit var liveEventPublisher: RecordingNotificationLiveEventPublisher

    @BeforeEach
    fun resetDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM notification_outbox")
                statement.executeUpdate("DELETE FROM notification_recipients")
                statement.executeUpdate("DELETE FROM notifications")
            }
        }
        liveEventPublisher.reset()
    }

    @Test
    fun pendingNotificationCreatedEventsPublishRecipientsAndMarkProcessed() {
        val notificationId = UUID.randomUUID()
        val outboxId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notifications
                      (id, idempotency_key, type, category, priority, scope_type, actor_type, title, body)
                    VALUES (?, 'outbox-created-1', 'project_invite', 'project', 'normal',
                            'project', 'system', 'Project invite', 'Body')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.executeUpdate()
                }
            insertRecipient(connection, notificationId, "user-alpha")
            insertRecipient(connection, notificationId, "user-beta")
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_outbox (id, event_type, aggregate_id, payload)
                    VALUES (?, 'notification.created', ?, '{}'::jsonb)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, outboxId)
                    statement.setObject(2, notificationId)
                    statement.executeUpdate()
                }
        }

        outboxProcessor.processPendingEvents()

        assertEquals(2, liveEventPublisher.events.size)
        assertEquals(listOf("user-alpha", "user-beta"), liveEventPublisher.events.map { it.userId })
        liveEventPublisher.events.forEach { event ->
            assertEquals(
                NotificationLiveEvent(
                    type = "notifications.changed",
                    userId = event.userId,
                    notificationId = notificationId.toString(),
                    reason = "created",
                    occurredAt = event.occurredAt,
                ),
                event,
            )
        }

        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT status, attempts, processed_at FROM notification_outbox WHERE id = ?"
                )
                .use { statement ->
                    statement.setObject(1, outboxId)
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        assertEquals("processed", resultSet.getString("status"))
                        assertEquals(0, resultSet.getInt("attempts"))
                        assertNotNull(resultSet.getObject("processed_at"))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }

    private fun insertRecipient(
        connection: Connection,
        notificationId: UUID,
        userId: String,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO notification_recipients (id, notification_id, user_id)
                VALUES (?, ?, ?)
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, notificationId)
                statement.setString(3, userId)
                statement.executeUpdate()
            }
    }
}
