package gg.grounds.notifications.db

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.core.NotificationActionItem
import gg.grounds.notifications.core.NotificationEventRequest
import gg.grounds.notifications.core.NotificationEventResponse
import gg.grounds.notifications.core.NotificationInboxItem
import gg.grounds.notifications.core.StoredNotificationAction
import jakarta.enterprise.context.ApplicationScoped
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationRepository(
    private val dataSource: DataSource,
    private val objectMapper: ObjectMapper,
) {
    fun createEvent(request: NotificationEventRequest): NotificationEventResponse {
        require(request.recipients.isNotEmpty()) { "At least one recipient is required" }

        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                findNotificationIdByIdempotencyKey(connection, request.idempotencyKey)?.let {
                    existingId ->
                    connection.commit()
                    return NotificationEventResponse(existingId, created = false)
                }

                val notificationId = UUID.randomUUID()
                insertNotification(connection, notificationId, request)
                val audienceIds = insertAudiences(connection, notificationId, request)
                insertRecipients(connection, notificationId, request, audienceIds.firstOrNull())
                insertActions(connection, notificationId, request)
                insertWorkflowState(connection, notificationId)
                insertOutboxEvent(connection, notificationId, request)
                connection.commit()
                LOG.infof(
                    "Created notification event (notificationId=%s, recipientCount=%d)",
                    notificationId,
                    request.recipients.size,
                )
                return NotificationEventResponse(notificationId, created = true)
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            }
        }
    }

    fun listForUser(userId: String): List<NotificationInboxItem> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT n.id, r.id AS recipient_id, r.user_id, n.type, n.category, n.priority,
                           n.title, n.body, n.data::text, n.created_at, r.read_at
                    FROM notification_recipients r
                    JOIN notifications n ON n.id = r.notification_id
                    WHERE r.user_id = ? AND r.archived_at IS NULL
                      AND (n.expires_at IS NULL OR n.expires_at > now())
                    ORDER BY n.created_at DESC
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setString(1, userId)
                    val items = mutableListOf<NotificationInboxItem>()
                    val resultSet = statement.executeQuery()
                    try {
                        while (resultSet.next()) {
                            val notificationId = resultSet.getObject("id", UUID::class.java)
                            items +=
                                NotificationInboxItem(
                                    id = notificationId,
                                    recipientId =
                                        resultSet.getObject("recipient_id", UUID::class.java),
                                    recipientUserId = resultSet.getString("user_id"),
                                    type = resultSet.getString("type"),
                                    category = resultSet.getString("category"),
                                    priority = resultSet.getString("priority"),
                                    title = resultSet.getString("title"),
                                    body = resultSet.getString("body"),
                                    data = objectMapper.readTree(resultSet.getString("data")),
                                    createdAt =
                                        resultSet.getObject(
                                            "created_at",
                                            OffsetDateTime::class.java,
                                        ),
                                    readAt =
                                        resultSet.getObject("read_at", OffsetDateTime::class.java),
                                    actions = listActions(connection, notificationId),
                                )
                        }
                    } finally {
                        resultSet.close()
                    }
                    items
                }
        }

    fun recipientExists(notificationId: UUID, userId: String): Boolean =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT 1 FROM notification_recipients WHERE notification_id = ? AND user_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.setString(2, userId)
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                    } finally {
                        resultSet.close()
                    }
                }
        }

    fun findAction(notificationId: UUID, actionKey: String): StoredNotificationAction? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, notification_id, action_key, command, payload::text
                    FROM notification_actions
                    WHERE notification_id = ? AND action_key = ?
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.setString(2, actionKey)
                    val resultSet = statement.executeQuery()
                    try {
                        if (!resultSet.next()) {
                            return null
                        }
                        StoredNotificationAction(
                            id = resultSet.getObject("id", UUID::class.java),
                            notificationId =
                                resultSet.getObject("notification_id", UUID::class.java),
                            actionKey = resultSet.getString("action_key"),
                            command = resultSet.getString("command"),
                            payload = objectMapper.readTree(resultSet.getString("payload")),
                        )
                    } finally {
                        resultSet.close()
                    }
                }
        }

    fun storeActionResult(
        action: StoredNotificationAction,
        userId: String,
        status: String,
        reason: String?,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_action_results
                      (id, notification_id, action_id, user_id, status, reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, action.notificationId)
                    statement.setObject(3, action.id)
                    statement.setString(4, userId)
                    statement.setString(5, status)
                    statement.setString(6, reason)
                    statement.executeUpdate()
                }
        }
    }

    private fun findNotificationIdByIdempotencyKey(
        connection: Connection,
        idempotencyKey: String,
    ): UUID? =
        connection.prepareStatement("SELECT id FROM notifications WHERE idempotency_key = ?").use {
            statement ->
            statement.setString(1, idempotencyKey)
            val resultSet = statement.executeQuery()
            try {
                if (resultSet.next()) resultSet.getObject("id", UUID::class.java) else null
            } finally {
                resultSet.close()
            }
        }

    private fun insertNotification(
        connection: Connection,
        notificationId: UUID,
        request: NotificationEventRequest,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO notifications
                  (id, idempotency_key, type, category, priority, scope_type, scope_id,
                   actor_type, actor_id, entity_type, entity_id, title, body, data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, notificationId)
                statement.setString(2, request.idempotencyKey)
                statement.setString(3, request.type)
                statement.setString(4, request.category)
                statement.setString(5, request.priority)
                statement.setString(6, request.scope.type)
                statement.setString(7, request.scope.id)
                statement.setString(8, request.actor.type)
                statement.setString(9, request.actor.id)
                statement.setString(10, request.entity?.type)
                statement.setString(11, request.entity?.id)
                statement.setString(12, request.title)
                statement.setString(13, request.body)
                statement.setJson(14, request.data ?: objectMapper.createObjectNode())
                statement.executeUpdate()
            }
    }

    private fun insertAudiences(
        connection: Connection,
        notificationId: UUID,
        request: NotificationEventRequest,
    ): List<UUID> =
        request.recipients.map { recipient ->
            val audienceId = UUID.randomUUID()
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_audiences (id, notification_id, audience_type, audience_id)
                    VALUES (?, ?, 'user', ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, audienceId)
                    statement.setObject(2, notificationId)
                    statement.setString(3, recipient.userId)
                    statement.executeUpdate()
                }
            audienceId
        }

    private fun insertRecipients(
        connection: Connection,
        notificationId: UUID,
        request: NotificationEventRequest,
        fallbackAudienceId: UUID?,
    ) {
        request.recipients.forEach { recipient ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_recipients
                      (id, notification_id, user_id, resolved_from_audience_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (notification_id, user_id) DO NOTHING
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, notificationId)
                    statement.setString(3, recipient.userId)
                    statement.setObject(4, fallbackAudienceId)
                    statement.executeUpdate()
                }
        }
    }

    private fun insertActions(
        connection: Connection,
        notificationId: UUID,
        request: NotificationEventRequest,
    ) {
        request.actions.forEach { action ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_actions
                      (id, notification_id, action_key, label, style, command, payload, requires_fresh_check)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, notificationId)
                    statement.setString(3, action.actionKey)
                    statement.setString(4, action.label)
                    statement.setString(5, action.style)
                    statement.setString(6, action.command)
                    statement.setJson(7, action.payload ?: objectMapper.createObjectNode())
                    statement.setBoolean(8, action.requiresFreshCheck)
                    statement.executeUpdate()
                }
        }
    }

    private fun insertWorkflowState(connection: Connection, notificationId: UUID) {
        connection
            .prepareStatement(
                "INSERT INTO notification_workflow_state (notification_id, status) VALUES (?, 'pending')"
            )
            .use { statement ->
                statement.setObject(1, notificationId)
                statement.executeUpdate()
            }
    }

    private fun insertOutboxEvent(
        connection: Connection,
        notificationId: UUID,
        request: NotificationEventRequest,
    ) {
        val payload =
            objectMapper
                .createObjectNode()
                .put("notificationId", notificationId.toString())
                .put("type", request.type)
        connection
            .prepareStatement(
                """
                INSERT INTO notification_outbox (id, event_type, aggregate_id, payload)
                VALUES (?, 'notification.created', ?, ?)
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setObject(2, notificationId)
                statement.setJson(3, payload)
                statement.executeUpdate()
            }
    }

    private fun listActions(
        connection: Connection,
        notificationId: UUID,
    ): List<NotificationActionItem> =
        connection
            .prepareStatement(
                """
                SELECT action_key, label, style
                FROM notification_actions
                WHERE notification_id = ?
                ORDER BY created_at ASC
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, notificationId)
                val resultSet = statement.executeQuery()
                val actions = mutableListOf<NotificationActionItem>()
                try {
                    while (resultSet.next()) {
                        actions +=
                            NotificationActionItem(
                                actionKey = resultSet.getString("action_key"),
                                label = resultSet.getString("label"),
                                style = resultSet.getString("style"),
                            )
                    }
                } finally {
                    resultSet.close()
                }
                actions
            }

    private fun PreparedStatement.setJson(index: Int, value: JsonNode) {
        setObject(index, objectMapper.writeValueAsString(value), Types.OTHER)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(NotificationRepository::class.java)
    }
}
