package gg.grounds.notifications.db

import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import javax.sql.DataSource

data class ChannelClient(
    val id: UUID,
    val channel: String,
    val scopes: Set<String>,
    val projectId: String?,
    val serverId: String?,
    val deploymentId: String?,
)

@ApplicationScoped
class ChannelClientRepository(private val dataSource: DataSource) {
    fun findActiveByTokenHash(tokenHash: String): ChannelClient? =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT id, channel, project_id, server_id, deployment_id, scopes
                    FROM notification_channel_clients
                    WHERE token_hash = ? AND revoked_at IS NULL
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setString(1, tokenHash)
                    val resultSet = statement.executeQuery()
                    try {
                        if (!resultSet.next()) {
                            return null
                        }
                        val scopes = resultSet.getArray("scopes").array as Array<*>
                        ChannelClient(
                            id = resultSet.getObject("id", UUID::class.java),
                            channel = resultSet.getString("channel"),
                            scopes = scopes.filterIsInstance<String>().toSet(),
                            projectId = resultSet.getString("project_id"),
                            serverId = resultSet.getString("server_id"),
                            deploymentId = resultSet.getString("deployment_id"),
                        )
                    } finally {
                        resultSet.close()
                    }
                }
        }
}
