package gg.grounds.notifications.admin

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

@ApplicationScoped
class NotificationAdminRepository(private val dataSource: DataSource) {
    fun diagnostics(): NotificationAdminDiagnosticsResponse =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT
                      (SELECT count(*) FROM notifications) AS notification_count,
                      (SELECT count(*) FROM notification_recipients) AS recipient_count,
                      (SELECT count(*) FROM notification_channel_clients WHERE revoked_at IS NULL)
                        AS active_channel_client_count,
                      (SELECT count(*) FROM notification_deliveries WHERE status = 'failed')
                        AS failed_delivery_count,
                      (SELECT count(*) FROM notification_action_results
                       WHERE status IN ('failed', 'rejected')) AS failed_action_count
                    """
                        .trimIndent()
                )
                .use { statement ->
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        NotificationAdminDiagnosticsResponse(
                            notificationCount = resultSet.getLong("notification_count"),
                            recipientCount = resultSet.getLong("recipient_count"),
                            activeChannelClientCount =
                                resultSet.getLong("active_channel_client_count"),
                            failedDeliveryCount = resultSet.getLong("failed_delivery_count"),
                            failedActionCount = resultSet.getLong("failed_action_count"),
                        )
                    } finally {
                        resultSet.close()
                    }
                }
        }

    fun listNotificationTypes(): List<NotificationTypeAdminItem> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    WITH observed AS (
                      SELECT type, min(category) AS category, count(*) AS observed_count
                      FROM notifications
                      GROUP BY type
                    )
                    SELECT
                      COALESCE(defaults.notification_type, observed.type) AS type,
                      COALESCE(defaults.category, observed.category) AS category,
                      COALESCE(defaults.web_enabled, true) AS web_enabled,
                      COALESCE(defaults.minecraft_enabled, false) AS minecraft_enabled,
                      COALESCE(defaults.email_enabled, false) AS email_enabled,
                      COALESCE(defaults.discord_enabled, false) AS discord_enabled,
                      COALESCE(defaults.push_enabled, false) AS push_enabled,
                      COALESCE(defaults.allowed_producers, ARRAY[]::TEXT[]) AS allowed_producers,
                      COALESCE(observed.observed_count, 0) AS observed_count
                    FROM notification_type_defaults defaults
                    FULL OUTER JOIN observed ON observed.type = defaults.notification_type
                    ORDER BY type
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().useRows { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    NotificationTypeAdminItem(
                                        type = resultSet.getString("type"),
                                        category = resultSet.getString("category"),
                                        webEnabled = resultSet.getBoolean("web_enabled"),
                                        minecraftEnabled =
                                            resultSet.getBoolean("minecraft_enabled"),
                                        emailEnabled = resultSet.getBoolean("email_enabled"),
                                        discordEnabled = resultSet.getBoolean("discord_enabled"),
                                        pushEnabled = resultSet.getBoolean("push_enabled"),
                                        allowedProducers =
                                            resultSet.getTextArray("allowed_producers"),
                                        observedCount = resultSet.getLong("observed_count"),
                                    )
                                )
                            }
                        }
                    }
                }
        }

    fun listChannelClients(): List<ChannelClientAdminItem> =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, channel, project_id, server_id, deployment_id, scopes, created_at, revoked_at
                    FROM notification_channel_clients
                    ORDER BY created_at DESC
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.executeQuery().useRows { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(resultSet.channelClientAdminItem())
                            }
                        }
                    }
                }
        }

    fun createChannelClient(
        request: CreateChannelClientRequest,
        actorUserId: String,
    ): ChannelClientSecretResponse {
        val token = generateChannelToken()
        val clientId = UUID.randomUUID()
        return inTransaction { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_channel_clients
                      (id, channel, token_hash, project_id, server_id, deployment_id, scopes)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, clientId)
                    statement.setString(2, request.channel.trim())
                    statement.setString(3, hashToken(token))
                    statement.setString(4, request.projectId)
                    statement.setString(5, request.serverId)
                    statement.setString(6, request.deploymentId)
                    statement.setArray(
                        7,
                        connection.createArrayOf("text", request.scopes.toTypedArray()),
                    )
                    statement.executeUpdate()
                }
            insertAuditEvent(
                connection = connection,
                actorUserId = actorUserId,
                eventType = "channel_client.created",
                targetType = "channel_client",
                targetId = clientId.toString(),
                metadata =
                    mapOf(
                        "channel" to request.channel.trim(),
                        "projectId" to request.projectId,
                        "serverId" to request.serverId,
                        "deploymentId" to request.deploymentId,
                        "scopeCount" to request.scopes.size.toString(),
                    ),
            )
            ChannelClientSecretResponse(
                client = findChannelClient(connection, clientId),
                token = token,
            )
        }
    }

    fun rotateChannelClient(clientId: UUID, actorUserId: String): ChannelClientSecretResponse {
        val token = generateChannelToken()
        return inTransaction { connection ->
            val updated =
                connection
                    .prepareStatement(
                        """
                        UPDATE notification_channel_clients
                        SET token_hash = ?, revoked_at = NULL
                        WHERE id = ?
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        statement.setString(1, hashToken(token))
                        statement.setObject(2, clientId)
                        statement.executeUpdate()
                    }
            if (updated == 0) {
                throw NotFoundException("channel_client_not_found")
            }
            val client = findChannelClient(connection, clientId)
            insertAuditEvent(
                connection = connection,
                actorUserId = actorUserId,
                eventType = "channel_client.rotated",
                targetType = "channel_client",
                targetId = clientId.toString(),
                metadata = mapOf("channel" to client.channel),
            )
            ChannelClientSecretResponse(client = client, token = token)
        }
    }

    fun revokeChannelClient(clientId: UUID, actorUserId: String): ChannelClientResponse =
        inTransaction { connection ->
            val updated =
                connection
                    .prepareStatement(
                        """
                        UPDATE notification_channel_clients
                        SET revoked_at = now()
                        WHERE id = ?
                        """
                            .trimIndent()
                    )
                    .use { statement ->
                        statement.setObject(1, clientId)
                        statement.executeUpdate()
                    }
            if (updated == 0) {
                throw NotFoundException("channel_client_not_found")
            }
            val client = findChannelClient(connection, clientId)
            insertAuditEvent(
                connection = connection,
                actorUserId = actorUserId,
                eventType = "channel_client.revoked",
                targetType = "channel_client",
                targetId = clientId.toString(),
                metadata = mapOf("channel" to client.channel),
            )
            ChannelClientResponse(client)
        }

    fun listDeliveryAttempts(
        status: String?,
        channel: String?,
        notificationId: UUID?,
        userId: String?,
        limit: Int,
    ): List<DeliveryAttemptAdminItem> =
        dataSource.connection.use { connection ->
            val filters = mutableListOf<String>()
            val params = mutableListOf<Any>()
            if (status != null) {
                filters += "status = ?"
                params += status
            }
            if (channel != null) {
                filters += "channel = ?"
                params += channel
            }
            if (notificationId != null) {
                filters += "notification_id = ?"
                params += notificationId
            }
            if (userId != null) {
                filters += "user_id = ?"
                params += userId
            }
            val sql =
                """
                SELECT id, notification_id, user_id, channel, status, provider, attempts,
                       last_error, sent_at, created_at
                FROM notification_deliveries
                ${whereClause(filters)}
                ORDER BY created_at DESC
                LIMIT ?
                """
                    .trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.bind(params + limit)
                statement.executeQuery().useRows { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                DeliveryAttemptAdminItem(
                                    id = resultSet.getObject("id").toString(),
                                    notificationId =
                                        resultSet.getObject("notification_id").toString(),
                                    userId = resultSet.getString("user_id"),
                                    channel = resultSet.getString("channel"),
                                    status = resultSet.getString("status"),
                                    provider = resultSet.getString("provider"),
                                    attempts = resultSet.getInt("attempts"),
                                    lastError = resultSet.getString("last_error"),
                                    sentAt = resultSet.instantString("sent_at"),
                                    createdAt = resultSet.instantString("created_at")!!,
                                )
                            )
                        }
                    }
                }
            }
        }

    fun listActionResults(
        status: String?,
        notificationId: UUID?,
        userId: String?,
        limit: Int,
    ): List<ActionResultAdminItem> =
        dataSource.connection.use { connection ->
            val filters = mutableListOf<String>()
            val params = mutableListOf<Any>()
            if (status != null) {
                filters += "ar.status = ?"
                params += status
            }
            if (notificationId != null) {
                filters += "ar.notification_id = ?"
                params += notificationId
            }
            if (userId != null) {
                filters += "ar.user_id = ?"
                params += userId
            }
            val sql =
                """
                SELECT ar.id, ar.notification_id, actions.action_key, ar.user_id, ar.status,
                       ar.reason, ar.created_at
                FROM notification_action_results ar
                JOIN notification_actions actions ON actions.id = ar.action_id
                ${whereClause(filters)}
                ORDER BY ar.created_at DESC
                LIMIT ?
                """
                    .trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.bind(params + limit)
                statement.executeQuery().useRows { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                ActionResultAdminItem(
                                    id = resultSet.getObject("id").toString(),
                                    notificationId =
                                        resultSet.getObject("notification_id").toString(),
                                    actionKey = resultSet.getString("action_key"),
                                    userId = resultSet.getString("user_id"),
                                    status = resultSet.getString("status"),
                                    reason = resultSet.getString("reason"),
                                    createdAt = resultSet.instantString("created_at")!!,
                                )
                            )
                        }
                    }
                }
            }
        }

    fun listRecipientResolutions(
        notificationId: UUID?,
        userId: String?,
        limit: Int,
    ): List<RecipientResolutionAdminItem> =
        dataSource.connection.use { connection ->
            val filters = mutableListOf<String>()
            val params = mutableListOf<Any>()
            if (notificationId != null) {
                filters += "recipients.notification_id = ?"
                params += notificationId
            }
            if (userId != null) {
                filters += "recipients.user_id = ?"
                params += userId
            }
            val sql =
                """
                SELECT recipients.notification_id, recipients.user_id, audiences.audience_type,
                       audiences.audience_id, audiences.role, recipients.read_at,
                       recipients.archived_at, recipients.created_at
                FROM notification_recipients recipients
                LEFT JOIN notification_audiences audiences
                  ON audiences.id = recipients.resolved_from_audience_id
                ${whereClause(filters)}
                ORDER BY recipients.created_at DESC
                LIMIT ?
                """
                    .trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.bind(params + limit)
                statement.executeQuery().useRows { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                RecipientResolutionAdminItem(
                                    notificationId =
                                        resultSet.getObject("notification_id").toString(),
                                    userId = resultSet.getString("user_id"),
                                    audienceType = resultSet.getString("audience_type"),
                                    audienceId = resultSet.getString("audience_id"),
                                    role = resultSet.getString("role"),
                                    readAt = resultSet.instantString("read_at"),
                                    archivedAt = resultSet.instantString("archived_at"),
                                    createdAt = resultSet.instantString("created_at")!!,
                                )
                            )
                        }
                    }
                }
            }
        }

    fun listTeamDefaults(teamId: String?): List<TeamNotificationDefaultAdminItem> =
        dataSource.connection.use { connection ->
            val filters = mutableListOf<String>()
            val params = mutableListOf<Any>()
            if (teamId != null) {
                filters += "team_id = ?"
                params += teamId
            }
            val sql =
                """
                SELECT team_id, category, notification_type, web_default, minecraft_default,
                       email_default, discord_default
                FROM team_notification_settings
                ${whereClause(filters)}
                ORDER BY team_id, category, notification_type NULLS FIRST
                """
                    .trimIndent()
            connection.prepareStatement(sql).use { statement ->
                statement.bind(params)
                statement.executeQuery().useRows { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(resultSet.teamNotificationDefaultAdminItem())
                        }
                    }
                }
            }
        }

    fun upsertTeamDefault(
        teamId: String,
        category: String,
        request: UpsertTeamNotificationDefaultRequest,
        actorUserId: String,
    ): TeamNotificationDefaultAdminItem = inTransaction { connection ->
        val item =
            connection
                .prepareStatement(
                    """
                    INSERT INTO team_notification_settings
                      (id, team_id, category, notification_type, web_default,
                       minecraft_default, email_default, discord_default)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (team_id, category, (COALESCE(notification_type, '')))
                    DO UPDATE SET
                      web_default = EXCLUDED.web_default,
                      minecraft_default = EXCLUDED.minecraft_default,
                      email_default = EXCLUDED.email_default,
                      discord_default = EXCLUDED.discord_default,
                      updated_at = now()
                    RETURNING team_id, category, notification_type, web_default,
                              minecraft_default, email_default, discord_default
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, teamId)
                    statement.setString(3, category)
                    statement.setString(4, request.notificationType)
                    statement.setBoolean(5, request.webDefault)
                    statement.setBoolean(6, request.minecraftDefault)
                    statement.setBoolean(7, request.emailDefault)
                    statement.setBoolean(8, request.discordDefault)
                    statement.executeQuery().useRows { resultSet ->
                        resultSet.next()
                        resultSet.teamNotificationDefaultAdminItem()
                    }
                }
        insertAuditEvent(
            connection = connection,
            actorUserId = actorUserId,
            eventType = "team_default.upserted",
            targetType = "team_notification_default",
            targetId = "$teamId/$category",
            metadata =
                mapOf(
                    "teamId" to teamId,
                    "category" to category,
                    "notificationType" to request.notificationType,
                ),
        )
        item
    }

    private fun findChannelClient(connection: Connection, clientId: UUID): ChannelClientAdminItem =
        connection
            .prepareStatement(
                """
                SELECT id, channel, project_id, server_id, deployment_id, scopes, created_at, revoked_at
                FROM notification_channel_clients
                WHERE id = ?
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, clientId)
                statement.executeQuery().useRows { resultSet ->
                    if (!resultSet.next()) {
                        throw NotFoundException("channel_client_not_found")
                    }
                    resultSet.channelClientAdminItem()
                }
            }

    private fun insertAuditEvent(
        connection: Connection,
        actorUserId: String,
        eventType: String,
        targetType: String,
        targetId: String,
        metadata: Map<String, String?>,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO notification_admin_audit_events
                  (id, actor_user_id, event_type, target_type, target_id, metadata)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """
                    .trimIndent()
            )
            .use { statement ->
                statement.setObject(1, UUID.randomUUID())
                statement.setString(2, actorUserId)
                statement.setString(3, eventType)
                statement.setString(4, targetType)
                statement.setString(5, targetId)
                statement.setString(6, jsonObject(metadata))
                statement.executeUpdate()
            }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }

    private fun ResultSet.channelClientAdminItem(): ChannelClientAdminItem =
        ChannelClientAdminItem(
            id = getObject("id").toString(),
            channel = getString("channel"),
            projectId = getString("project_id"),
            serverId = getString("server_id"),
            deploymentId = getString("deployment_id"),
            scopes = getTextArray("scopes"),
            createdAt = instantString("created_at")!!,
            revokedAt = instantString("revoked_at"),
        )

    private fun ResultSet.teamNotificationDefaultAdminItem(): TeamNotificationDefaultAdminItem =
        TeamNotificationDefaultAdminItem(
            teamId = getString("team_id"),
            category = getString("category"),
            notificationType = getString("notification_type"),
            webDefault = getBoolean("web_default"),
            minecraftDefault = getBoolean("minecraft_default"),
            emailDefault = getBoolean("email_default"),
            discordDefault = getBoolean("discord_default"),
        )

    private fun PreparedStatement.bind(params: List<Any>) {
        params.forEachIndexed { index, value ->
            when (value) {
                is UUID -> setObject(index + 1, value)
                is Int -> setInt(index + 1, value)
                is String -> setString(index + 1, value)
                else -> setObject(index + 1, value)
            }
        }
    }

    private fun ResultSet.getTextArray(columnName: String): List<String> {
        val sqlArray = getArray(columnName) ?: return emptyList()
        return (sqlArray.array as Array<*>).filterIsInstance<String>()
    }

    private fun ResultSet.instantString(columnName: String): String? =
        getTimestamp(columnName)?.toInstant()?.toString()

    private fun <T> ResultSet.useRows(block: (ResultSet) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun whereClause(filters: List<String>): String =
        if (filters.isEmpty()) {
            ""
        } else {
            "WHERE ${filters.joinToString(" AND ")}"
        }

    private fun generateChannelToken(): String {
        val bytes = ByteArray(CHANNEL_TOKEN_BYTES)
        SECURE_RANDOM.nextBytes(bytes)
        return CHANNEL_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun jsonObject(values: Map<String, String?>): String =
        values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val jsonValue = value?.let { "\"${jsonEscape(it)}\"" } ?: "null"
            "\"${jsonEscape(key)}\":$jsonValue"
        }

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
                }
            }
        }
    }

    private companion object {
        private const val CHANNEL_TOKEN_PREFIX = "gnc_"
        private const val CHANNEL_TOKEN_BYTES = 32
        private val SECURE_RANDOM = SecureRandom()
    }
}
