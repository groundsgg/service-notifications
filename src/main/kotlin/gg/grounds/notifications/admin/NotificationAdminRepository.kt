package gg.grounds.notifications.admin

import jakarta.enterprise.context.ApplicationScoped
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
}
