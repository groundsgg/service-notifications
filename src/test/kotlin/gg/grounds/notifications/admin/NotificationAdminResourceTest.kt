package gg.grounds.notifications.admin

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
                statement.executeUpdate("DELETE FROM team_notification_settings")
                statement.executeUpdate("DELETE FROM notification_type_defaults")
                statement.executeUpdate("DELETE FROM notification_admin_audit_events")
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

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun listsNotificationTypesFromConfiguredDefaultsAndObservedNotifications() {
        val observedDefaultId = UUID.randomUUID()
        val observedOnlyId = UUID.randomUUID()
        seedNotificationTypeDefault()
        seedNotification(
            notificationId = observedDefaultId,
            idempotencyKey = "admin-type-default-observed",
            type = "project_invite",
            category = "project",
        )
        seedNotification(
            notificationId = observedOnlyId,
            idempotencyKey = "admin-type-observed-only",
            type = "runtime_alert",
            category = "runtime",
        )

        given()
            .get("/v1/admin/notifications/types")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(2))
            .body("items.find { it.type == 'project_invite' }.category", equalTo("project"))
            .body("items.find { it.type == 'project_invite' }.webEnabled", equalTo(true))
            .body("items.find { it.type == 'project_invite' }.minecraftEnabled", equalTo(true))
            .body("items.find { it.type == 'project_invite' }.emailEnabled", equalTo(false))
            .body("items.find { it.type == 'project_invite' }.discordEnabled", equalTo(false))
            .body("items.find { it.type == 'project_invite' }.pushEnabled", equalTo(false))
            .body(
                "items.find { it.type == 'project_invite' }.allowedProducers",
                equalTo(listOf("forge", "portal")),
            )
            .body("items.find { it.type == 'project_invite' }.observedCount", equalTo(1))
            .body("items.find { it.type == 'runtime_alert' }.category", equalTo("runtime"))
            .body("items.find { it.type == 'runtime_alert' }.observedCount", equalTo(1))
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun createsRotatesAndRevokesChannelClientWithoutListingToken() {
        val createResponse =
            given()
                .contentType("application/json")
                .body(
                    """
                    {
                      "channel": "minecraft",
                      "projectId": "project-alpha",
                      "serverId": "server-alpha",
                      "scopes": ["minecraft.notifications.read"]
                    }
                    """
                        .trimIndent()
                )
                .post("/v1/admin/notifications/channel-clients")
                .then()
                .statusCode(201)
                .body("client.channel", equalTo("minecraft"))
                .body("client.projectId", equalTo("project-alpha"))
                .body("client.serverId", equalTo("server-alpha"))
                .body("client.scopes", equalTo(listOf("minecraft.notifications.read")))
                .body("token", notNullValue())
                .extract()

        val clientId = createResponse.path<String>("client.id")
        val originalToken = createResponse.path<String>("token")
        assertTrue(originalToken.startsWith("gnc_"))

        given()
            .get("/v1/admin/notifications/channel-clients")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].id", equalTo(clientId))
            .body("items[0].token", equalTo(null))

        val rotatedResponse =
            given()
                .post("/v1/admin/notifications/channel-clients/$clientId/rotate")
                .then()
                .statusCode(200)
                .body("client.id", equalTo(clientId))
                .body("client.revokedAt", equalTo(null))
                .body("token", notNullValue())
                .extract()

        val rotatedToken = rotatedResponse.path<String>("token")
        assertTrue(rotatedToken.startsWith("gnc_"))
        assertNotEquals(originalToken, rotatedToken)

        given()
            .post("/v1/admin/notifications/channel-clients/$clientId/revoke")
            .then()
            .statusCode(200)
            .body("client.id", equalTo(clientId))
            .body("client.revokedAt", notNullValue())

        assertEquals(1, auditCount("channel_client.created"))
        assertEquals(1, auditCount("channel_client.rotated"))
        assertEquals(1, auditCount("channel_client.revoked"))
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun deliveryFiltersReturnFailedDeliveryByStatusChannelNotificationAndUser() {
        val ids = seedDeliveryFixture()

        given()
            .queryParam("status", "failed")
            .queryParam("channel", "minecraft")
            .queryParam("notificationId", ids.notificationId)
            .queryParam("userId", "user-alpha")
            .get("/v1/admin/notifications/deliveries")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].id", equalTo(ids.deliveryId.toString()))
            .body("items[0].notificationId", equalTo(ids.notificationId.toString()))
            .body("items[0].userId", equalTo("user-alpha"))
            .body("items[0].channel", equalTo("minecraft"))
            .body("items[0].status", equalTo("failed"))
            .body("items[0].provider", equalTo("paper"))
            .body("items[0].attempts", equalTo(3))
            .body("items[0].lastError", equalTo("network_timeout"))
            .body("items[0].createdAt", notNullValue())
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun deliveryFiltersRejectMalformedLimit() {
        given()
            .queryParam("limit", "abc")
            .get("/v1/admin/notifications/deliveries")
            .then()
            .statusCode(400)
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun actionResultFiltersIncludeActionKeyAndReason() {
        val ids = seedActionResultFixture()

        given()
            .queryParam("status", "failed")
            .queryParam("notificationId", ids.notificationId)
            .queryParam("userId", "user-alpha")
            .get("/v1/admin/notifications/action-results")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].notificationId", equalTo(ids.notificationId.toString()))
            .body("items[0].actionKey", equalTo("project_invite.accept"))
            .body("items[0].userId", equalTo("user-alpha"))
            .body("items[0].status", equalTo("failed"))
            .body("items[0].reason", equalTo("invite_expired"))
            .body("items[0].createdAt", notNullValue())
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun recipientResolutionShowsAudienceTypeIdAndRoleForRecipient() {
        val ids = seedRecipientResolutionFixture()

        given()
            .queryParam("notificationId", ids.notificationId)
            .queryParam("userId", "user-alpha")
            .get("/v1/admin/notifications/recipients")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].notificationId", equalTo(ids.notificationId.toString()))
            .body("items[0].userId", equalTo("user-alpha"))
            .body("items[0].audienceType", equalTo("project"))
            .body("items[0].audienceId", equalTo("project-alpha"))
            .body("items[0].role", equalTo("owner"))
            .body("items[0].readAt", equalTo(null))
            .body("items[0].archivedAt", equalTo(null))
            .body("items[0].createdAt", notNullValue())
    }

    @Test
    @TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
    fun teamDefaultUpsertWritesAndListReturnsValues() {
        given()
            .contentType("application/json")
            .body(
                """
                {
                  "notificationType": "project_invite",
                  "webDefault": true,
                  "minecraftDefault": false,
                  "emailDefault": true,
                  "discordDefault": false
                }
                """
                    .trimIndent()
            )
            .put("/v1/admin/notifications/team-defaults/team-alpha/project")
            .then()
            .statusCode(200)
            .body("teamId", equalTo("team-alpha"))
            .body("category", equalTo("project"))
            .body("notificationType", equalTo("project_invite"))
            .body("webDefault", equalTo(true))
            .body("minecraftDefault", equalTo(false))
            .body("emailDefault", equalTo(true))
            .body("discordDefault", equalTo(false))

        given()
            .queryParam("teamId", "team-alpha")
            .get("/v1/admin/notifications/team-defaults")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].teamId", equalTo("team-alpha"))
            .body("items[0].category", equalTo("project"))
            .body("items[0].notificationType", equalTo("project_invite"))
            .body("items[0].emailDefault", equalTo(true))

        assertEquals(1, auditCount("team_default.upserted"))
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

    private fun seedNotificationTypeDefault() {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_type_defaults
                      (id, notification_type, category, web_enabled, minecraft_enabled,
                       email_enabled, discord_enabled, push_enabled, allowed_producers)
                    VALUES (?, 'project_invite', 'project', true, true, false, false, false, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setArray(
                        2,
                        connection.createArrayOf("text", arrayOf("forge", "portal")),
                    )
                    statement.executeUpdate()
                }
        }
    }

    private fun seedNotification(
        notificationId: UUID,
        idempotencyKey: String,
        type: String,
        category: String,
    ) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notifications
                      (id, idempotency_key, type, category, priority, scope_type,
                       actor_type, title, body)
                    VALUES (?, ?, ?, ?, 'normal', 'project', 'system', 'Project invite', 'Invite body')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, notificationId)
                    statement.setString(2, idempotencyKey)
                    statement.setString(3, type)
                    statement.setString(4, category)
                    statement.executeUpdate()
                }
        }
    }

    private fun seedDeliveryFixture(): DeliveryFixtureIds {
        val notificationId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()
        val deliveryId = UUID.randomUUID()
        seedNotification(notificationId, "admin-delivery-filter-1", "project_invite", "project")
        dataSource.connection.use { connection ->
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
                    INSERT INTO notification_deliveries
                      (id, notification_id, recipient_id, user_id, channel, status, provider,
                       attempts, last_error)
                    VALUES (?, ?, ?, 'user-alpha', 'minecraft', 'failed', 'paper', 3, 'network_timeout'),
                           (?, ?, ?, 'user-beta', 'web', 'sent', 'portal', 1, null)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, deliveryId)
                    statement.setObject(2, notificationId)
                    statement.setObject(3, recipientId)
                    statement.setObject(4, UUID.randomUUID())
                    statement.setObject(5, notificationId)
                    statement.setObject(6, recipientId)
                    statement.executeUpdate()
                }
        }
        return DeliveryFixtureIds(notificationId, deliveryId)
    }

    private fun seedActionResultFixture(): NotificationFixtureIds {
        val notificationId = UUID.randomUUID()
        val actionId = UUID.randomUUID()
        seedNotification(notificationId, "admin-action-filter-1", "project_invite", "project")
        dataSource.connection.use { connection ->
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
                      (id, notification_id, action_id, user_id, status, reason)
                    VALUES (?, ?, ?, 'user-alpha', 'failed', 'invite_expired'),
                           (?, ?, ?, 'user-beta', 'succeeded', null)
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
                    statement.executeUpdate()
                }
        }
        return NotificationFixtureIds(notificationId)
    }

    private fun seedRecipientResolutionFixture(): NotificationFixtureIds {
        val notificationId = UUID.randomUUID()
        val audienceId = UUID.randomUUID()
        val recipientId = UUID.randomUUID()
        seedNotification(notificationId, "admin-recipient-filter-1", "project_invite", "project")
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_audiences
                      (id, notification_id, audience_type, audience_id, role)
                    VALUES (?, ?, 'project', 'project-alpha', 'owner')
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, audienceId)
                    statement.setObject(2, notificationId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_recipients
                      (id, notification_id, user_id, resolved_from_audience_id)
                    VALUES (?, ?, 'user-alpha', ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, recipientId)
                    statement.setObject(2, notificationId)
                    statement.setObject(3, audienceId)
                    statement.executeUpdate()
                }
        }
        return NotificationFixtureIds(notificationId)
    }

    private fun auditCount(eventType: String): Int =
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) FROM notification_admin_audit_events WHERE event_type = ?"
                )
                .use { statement ->
                    statement.setString(1, eventType)
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        resultSet.getInt(1)
                    } finally {
                        resultSet.close()
                    }
                }
        }

    private data class NotificationFixtureIds(val notificationId: UUID)

    private data class DeliveryFixtureIds(val notificationId: UUID, val deliveryId: UUID)
}
