package gg.grounds.notifications.outbox

import com.fasterxml.jackson.databind.ObjectMapper
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
    private val objectMapper: ObjectMapper,
) {
    @Scheduled(every = "10s")
    fun processPendingEvents() {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                claimPendingEvents(connection).forEach { event -> processEvent(connection, event) }
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    private fun processEvent(connection: Connection, event: OutboxEvent) {
        val savepoint = connection.setSavepoint()
        try {
            when (event.eventType) {
                "notification.created" -> processNotificationCreated(connection, event)
                else -> {
                    LOG.warnf(
                        "Rejecting unknown notification outbox event type (id=%s, eventType=%s)",
                        event.id,
                        event.eventType,
                    )
                    throw IllegalArgumentException(
                        "Unsupported notification outbox event type: ${event.eventType}"
                    )
                }
            }
            connection.releaseSavepoint(savepoint)
        } catch (exception: Exception) {
            connection.rollback(savepoint)
            markRetry(connection, event, exception.message ?: exception::class.java.simpleName)
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
                                    aggregateId =
                                        resultSet.getObject("aggregate_id", UUID::class.java),
                                    payload = resultSet.getString("payload"),
                                    attempts = resultSet.getInt("attempts"),
                                )
                            )
                        }
                    }
                }
            }

    private fun processNotificationCreated(connection: Connection, event: OutboxEvent) {
        val payload = parseNotificationPayload(event.payload)
        var failureMessage: String? = null
        val recipients = payload.recipientUserIds ?: loadRecipients(connection, event.aggregateId)
        recipients.forEach { userId ->
            val liveEvent =
                NotificationLiveEvent(
                    type = "notifications.changed",
                    userId = userId,
                    notificationId = event.aggregateId.toString(),
                    reason = payload.reason,
                    occurredAt = OffsetDateTime.now(),
                )
            val accepted =
                try {
                    liveEventPublisher.publish(liveEvent)
                } catch (exception: Exception) {
                    failureMessage = exception.message ?: exception::class.java.simpleName
                    false
                }
            if (!accepted && failureMessage == null) {
                failureMessage = "Notification live event publisher did not accept event"
            }
            // Retrying after partial fanout can duplicate earlier live change events.
            // These events are idempotent because clients refetch notification state.
        }
        val finalFailureMessage = failureMessage
        if (finalFailureMessage == null) {
            markProcessed(connection, event.id)
        } else {
            markRetry(connection, event, finalFailureMessage)
        }
    }

    private fun parseNotificationPayload(rawPayload: String): NotificationOutboxPayload {
        val root =
            runCatching { objectMapper.readTree(rawPayload) }
                .getOrElse { throw IllegalArgumentException("Invalid notification outbox payload") }
        if (!root.isObject) throw IllegalArgumentException("Invalid notification outbox payload")
        val reasonNode = root.path("reason")
        val reason =
            if (reasonNode.isMissingNode) {
                "created"
            } else {
                if (!reasonNode.isTextual || reasonNode.asText() !in SAFE_REASONS) {
                    throw IllegalArgumentException("Invalid notification outbox payload")
                }
                reasonNode.asText()
            }
        val recipientsNode = root.path("recipientUserIds")
        val recipients =
            if (recipientsNode.isMissingNode) {
                null
            } else {
                if (!recipientsNode.isArray) {
                    throw IllegalArgumentException("Invalid notification outbox payload")
                }
                recipientsNode
                    .map { node ->
                        if (!node.isTextual || node.asText().isBlank()) {
                            throw IllegalArgumentException("Invalid notification outbox payload")
                        }
                        node.asText()
                    }
                    .also { users ->
                        if (users != users.distinct().sorted()) {
                            throw IllegalArgumentException("Invalid notification outbox payload")
                        }
                    }
            }
        return NotificationOutboxPayload(reason, recipients)
    }

    private fun loadRecipients(connection: Connection, notificationId: UUID): List<String> =
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

    private fun markProcessed(connection: Connection, eventId: UUID) {
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

    private fun markRetry(connection: Connection, event: OutboxEvent, failureMessage: String) {
        val nextAttempts = event.attempts + 1
        // Rows become a dead-letter after MAX_ATTEMPTS. Operators can inspect last_error and reset
        // status/available_at if a live fanout outage needs to be replayed manually.
        connection
            .prepareStatement(
                """
                UPDATE notification_outbox
                SET attempts = attempts + 1,
                    last_error = ?,
                    available_at = now() + (? * interval '1 second'),
                    status = CASE WHEN attempts + 1 >= ? THEN 'failed' ELSE 'pending' END
                WHERE id = ?
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setString(1, failureMessage)
                statement.setInt(2, backoffSeconds(nextAttempts))
                statement.setInt(3, MAX_ATTEMPTS)
                statement.setObject(4, event.id)
                statement.executeUpdate()
            }
    }

    private data class OutboxEvent(
        val id: UUID,
        val eventType: String,
        val aggregateId: UUID,
        val payload: String,
        val attempts: Int,
    )

    private data class NotificationOutboxPayload(
        val reason: String,
        val recipientUserIds: List<String>?,
    )

    private companion object {
        val SAFE_REASONS = setOf("created", "audience_changed")
    }
}

private const val MAX_ATTEMPTS = 5

private fun backoffSeconds(nextAttempts: Int): Int =
    minOf(300, 5 * (1 shl (nextAttempts - 1).coerceAtLeast(0)))

private val LOG: Logger = Logger.getLogger(NotificationOutboxProcessor::class.java)
