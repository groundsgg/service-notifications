package gg.grounds.notifications.outbox

import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import javax.sql.DataSource
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationOutboxProcessor(private val dataSource: DataSource) {
    @Scheduled(every = "10s")
    fun processPendingEvents() {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT count(*)
                    FROM notification_outbox
                    WHERE status = 'pending' AND available_at <= now()
                    """
                        .trimIndent()
                )
                .use { statement ->
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        val pendingCount = resultSet.getInt(1)
                        if (pendingCount > 0) {
                            LOG.infof("Polled outbox events (pendingCount=%d)", pendingCount)
                        }
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }
}

private val LOG: Logger = Logger.getLogger(NotificationOutboxProcessor::class.java)
