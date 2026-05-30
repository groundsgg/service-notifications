package gg.grounds.notifications.outbox

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationOutboxProcessor(private val dataSource: DataSource) {
    @Scheduled(every = "10s")
    fun processPendingEvents() {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val events =
                connection
                    .prepareStatement(
                        """
                        SELECT id, event_type, aggregate_id
                        FROM notification_outbox
                        WHERE status = 'pending' AND available_at <= now()
                        ORDER BY created_at ASC
                        LIMIT 25
                        FOR UPDATE SKIP LOCKED
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        val resultSet = statement.executeQuery()
                        val rows = mutableListOf<OutboxRow>()
                        try {
                            while (resultSet.next()) {
                                rows +=
                                    OutboxRow(
                                        id = resultSet.getObject("id", UUID::class.java),
                                        eventType = resultSet.getString("event_type"),
                                        aggregateId =
                                            resultSet.getObject("aggregate_id", UUID::class.java),
                                    )
                            }
                        } finally {
                            resultSet.close()
                        }
                        rows
                    }

            events.forEach { event ->
                connection
                    .prepareStatement(
                        """
                        UPDATE notification_outbox
                        SET status = 'processed', processed_at = ?, attempts = attempts + 1
                        WHERE id = ? AND status = 'pending'
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        statement.setObject(1, OffsetDateTime.now())
                        statement.setObject(2, event.id)
                        val updated = statement.executeUpdate()
                        if (updated == 1) {
                            LOG.infof(
                                "Processed outbox event (outboxId=%s, eventType=%s, aggregateId=%s)",
                                event.id,
                                event.eventType,
                                event.aggregateId,
                            )
                        }
                    }
            }
            connection.commit()
        }
    }
}

private data class OutboxRow(val id: UUID, val eventType: String, val aggregateId: UUID)

private val LOG: Logger = Logger.getLogger(NotificationOutboxProcessor::class.java)
