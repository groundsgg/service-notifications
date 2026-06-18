package gg.grounds.notifications.outbox

import gg.grounds.notifications.live.NotificationLiveEvent
import gg.grounds.notifications.live.NotificationLiveEventPublisher
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationOutboxProcessor(
    private val dataSource: DataSource,
    private val liveEventPublisher: NotificationLiveEventPublisher,
) {
    @Scheduled(every = "10s")
    fun processPendingEvents() {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                claimPendingEvents(connection).forEach { event ->
                    when (event.eventType) {
                        "notification.created" -> processNotificationCreated(connection, event)
                        else -> {
                            // Unknown event types are treated as handled so a producer bug cannot
                            // keep the oldest outbox rows cycling forever.
                            LOG.warnf(
                                "Skipping unknown notification outbox event type (id=%s, eventType=%s)",
                                event.id,
                                event.eventType,
                            )
                            markProcessed(connection, event.id)
                        }
                    }
                }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun claimPendingEvents(connection: Connection): List<OutboxEvent> =
        connection
            .prepareStatement(
                """
                SELECT id, event_type, aggregate_id, payload::text, attempts
                FROM notification_outbox
                WHERE status = 'pending' AND available_at <= now()
                ORDER BY created_at ASC
                LIMIT 25
                FOR UPDATE SKIP LOCKED
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                OutboxEvent(
                                    id = resultSet.getObject("id", UUID::class.java),
                                    eventType = resultSet.getString("event_type"),
                                    aggregateId = resultSet.getObject("aggregate_id", UUID::class.java),
                                )
                            )
                        }
                    }
                }
            }

    private fun processNotificationCreated(
        connection: Connection,
        event: OutboxEvent,
    ) {
        loadRecipients(connection, event.aggregateId).forEach { userId ->
            liveEventPublisher.publish(
                NotificationLiveEvent(
                    type = "notifications.changed",
                    userId = userId,
                    notificationId = event.aggregateId.toString(),
                    reason = "created",
                    occurredAt = OffsetDateTime.now(),
                )
            )
        }
        markProcessed(connection, event.id)
    }

    private fun loadRecipients(
        connection: Connection,
        notificationId: UUID,
    ): List<String> =
        connection
            .prepareStatement(
                """
                SELECT user_id
                FROM notification_recipients
                WHERE notification_id = ?
                ORDER BY created_at ASC
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, notificationId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.getString("user_id"))
                        }
                    }
                }
            }

    private fun markProcessed(
        connection: Connection,
        eventId: UUID,
    ) {
        connection
            .prepareStatement(
                """
                UPDATE notification_outbox
                SET status = 'processed', processed_at = now(), last_error = NULL
                WHERE id = ?
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, eventId)
                statement.executeUpdate()
            }
    }

    private data class OutboxEvent(
        val id: UUID,
        val eventType: String,
        val aggregateId: UUID,
    )
}

private val LOG: Logger = Logger.getLogger(NotificationOutboxProcessor::class.java)
