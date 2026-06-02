package gg.grounds.notifications.admin

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class NotificationAdminResourceTest {
    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun resetDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM notification_outbox")
                statement.executeUpdate("DELETE FROM notification_action_results")
                statement.executeUpdate("DELETE FROM notification_actions")
                statement.executeUpdate("DELETE FROM notification_deliveries")
                statement.executeUpdate("DELETE FROM notification_recipients")
                statement.executeUpdate("DELETE FROM notification_audiences")
                statement.executeUpdate("DELETE FROM notification_workflow_state")
                statement.executeUpdate("DELETE FROM notifications")
                statement.executeUpdate("DELETE FROM notification_channel_clients")
            }
        }
    }

    @Test
    fun diagnosticsRejectsAnonymousRequests() {
        given().get("/v1/admin/notifications/diagnostics").then().statusCode(401)
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun diagnosticsRejectsAuthenticatedUsersWithoutNotificationManagePermission() {
        given().get("/v1/admin/notifications/diagnostics").then().statusCode(403)
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun diagnosticsReturnsCountsForNotificationAdmins() {
        seedDiagnosticsRows()

        given()
            .get("/v1/admin/notifications/diagnostics")
            .then()
            .statusCode(200)
            .body("notificationCount", equalTo(1))
            .body("recipientCount", equalTo(1))
            .body("activeChannelClientCount", equalTo(1))
            .body("failedDeliveryCount", equalTo(1))
            .body("failedActionCount", equalTo(2))
    }

    private fun seedDiagnosticsRows() {
        val notificationId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()
        val actionId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notifications
                      (id, idempotency_key, type, category, priority, scope_type,
                       actor_type, title, body)
                    VALUES (?, 'admin-diagnostics-1', 'project_invite', 'project', 'normal',
                            'project', 'system', 'Project invite', 'Invite body')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_recipients (id, notification_id, user_id)
                    VALUES (?, ?, 'user-alpha')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, recipientId)
                    statement.setObject(2, notificationId)
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_channel_clients (id, channel, token_hash, scopes)
                    VALUES (?, 'minecraft', ?, ARRAY['minecraft.notifications.read']::TEXT[])
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, "admin-diagnostics-token-hash")
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_channel_clients
                      (id, channel, token_hash, scopes, revoked_at)
                    VALUES (?, 'minecraft', ?, ARRAY['minecraft.notifications.read']::TEXT[], now())
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, "admin-diagnostics-revoked-token-hash")
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_deliveries
                      (id, notification_id, recipient_id, user_id, channel, status)
                    VALUES (?, ?, ?, 'user-alpha', 'minecraft', 'failed'),
                           (?, ?, ?, 'user-alpha', 'web', 'sent')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, notificationId)
                    statement.setObject(3, recipientId)
                    statement.setObject(4, UUID.randomUUID())
                    statement.setObject(5, notificationId)
                    statement.setObject(6, recipientId)
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_actions
                      (id, notification_id, action_key, label, style, command)
                    VALUES (?, ?, 'project_invite.accept', 'Accept', 'primary',
                            'project_invite.accept')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, actionId)
                    statement.setObject(2, notificationId)
                    statement.executeUpdate()
                }

            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_action_results
                      (id, notification_id, action_id, user_id, status)
                    VALUES (?, ?, ?, 'user-alpha', 'failed'),
                           (?, ?, ?, 'user-beta', 'rejected'),
                           (?, ?, ?, 'user-gamma', 'succeeded')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, notificationId)
                    statement.setObject(3, actionId)
                    statement.setObject(4, UUID.randomUUID())
                    statement.setObject(5, notificationId)
                    statement.setObject(6, actionId)
                    statement.setObject(7, UUID.randomUUID())
                    statement.setObject(8, notificationId)
                    statement.setObject(9, actionId)
                    statement.executeUpdate()
                }
        }
    }
}
