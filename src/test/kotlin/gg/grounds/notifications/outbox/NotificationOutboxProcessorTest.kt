package gg.grounds.notifications.outbox

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class NotificationOutboxProcessorTest {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var outboxProcessor: NotificationOutboxProcessor

    @BeforeEach
    fun resetDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM notification_outbox")
            }
        }
    }

    @Test
    fun pollingPendingEventsDoesNotMarkUndeliveredEventsProcessed() {
        val outboxId = UUID.randomUUID()
        dataSource.connection.use { connection ->
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
                    statement.setObject(2, UUID.randomUUID())
                    statement.executeUpdate()
                }
        }

        outboxProcessor.processPendingEvents()

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
                        assertEquals("pending", resultSet.getString("status"))
                        assertEquals(0, resultSet.getInt("attempts"))
                        assertEquals(null, resultSet.getObject("processed_at"))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }
}
