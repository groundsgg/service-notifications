package gg.grounds.notifications.api

import gg.grounds.notifications.channel.InMemoryMinecraftPlayerResolver
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class NotificationResourceTest {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var playerResolver: InMemoryMinecraftPlayerResolver

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
        playerResolver.clear()
    }

    @Test
    fun notificationEventCreatesNotificationAndRecipientRows() {
        val token = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(idempotencyKey = "event-create-1", userId = "user-alpha"))
            .`when`()
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .body("id", notNullValue())

        assertRowCount("notifications", 1)
        assertRowCount("notification_recipients", 1)
    }

    @Test
    fun replayingNotificationEventReturnsExistingNotificationWithoutDuplicates() {
        val token = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val body = notificationEventJson(idempotencyKey = "event-replay-1", userId = "user-alpha")

        val firstId =
            given()
                .header("Authorization", "Bearer $token")
                .contentType("application/json")
                .body(body)
                .post("/v1/notification-events")
                .then()
                .statusCode(201)
                .extract()
                .path<String>("id")

        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(body)
            .post("/v1/notification-events")
            .then()
            .statusCode(200)
            .body("id", equalTo(firstId))

        assertRowCount("notifications", 1)
        assertRowCount("notification_recipients", 1)
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun listNotificationsOnlyReturnsRowsForAuthenticatedUser() {
        val token = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        postNotificationEvent(token, "event-visible-1", "user-alpha")
        postNotificationEvent(token, "event-hidden-1", "user-beta")

        given()
            .`when`()
            .get("/v1/notifications")
            .then()
            .statusCode(200)
            .body("items.size()", equalTo(1))
            .body("items[0].title", equalTo("Project invite"))
            .body("items[0].recipientUserId", equalTo("user-alpha"))
    }

    @Test
    @TestSecurity(user = "user-beta")
    fun notificationActionRejectsNonRecipients() {
        val token = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val notificationId = postNotificationEvent(token, "event-action-1", "user-alpha")

        given()
            .header("X-Request-Id", "request-action-1")
            .contentType("application/json")
            .body("{}")
            .`when`()
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(403)
    }

    @Test
    fun minecraftBatchRequiresValidChannelTokenAndReturnsMappedPlayerNotifications() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(channel = "minecraft", scopes = listOf("notifications:read"))
        val mappedPlayerUuid = UUID.randomUUID().toString()
        val unmappedPlayerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(mappedPlayerUuid, "user-alpha")
        postNotificationEvent(apiToken, "event-minecraft-visible-1", "user-alpha")
        postNotificationEvent(apiToken, "event-minecraft-hidden-1", "user-beta")

        given()
            .header("Authorization", "Bearer invalid-token")
            .contentType("application/json")
            .body("""{"playerUuids":["$mappedPlayerUuid"]}""")
            .`when`()
            .post("/v1/channel/minecraft/notifications/batch")
            .then()
            .statusCode(401)

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuids":["$mappedPlayerUuid","$unmappedPlayerUuid"]}""")
            .`when`()
            .post("/v1/channel/minecraft/notifications/batch")
            .then()
            .statusCode(200)
            .body("players.size()", equalTo(1))
            .body("players[0].playerUuid", equalTo(mappedPlayerUuid))
            .body("players[0].notifications.size()", equalTo(1))
            .body("players[0].notifications[0].recipientUserId", equalTo("user-alpha"))
    }

    private fun postNotificationEvent(
        token: String,
        idempotencyKey: String,
        userId: String,
    ): String =
        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(idempotencyKey, userId))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")

    private fun notificationEventJson(idempotencyKey: String, userId: String): String =
        """
        {
          "idempotencyKey":"$idempotencyKey",
          "type":"project_invite",
          "category":"project",
          "priority":"normal",
          "scope":{"type":"project","id":"project-1"},
          "actor":{"type":"user","id":"user-owner"},
          "entity":{"type":"project_invite","id":"invite-1"},
          "title":"Project invite",
          "body":"You were invited to Project One.",
          "data":{"inviteId":"invite-1"},
          "recipients":[{"userId":"$userId"}],
          "actions":[
            {"actionKey":"project_invite.accept","label":"Accept","style":"primary","command":"project_invite.accept","payload":{"inviteId":"invite-1"}},
            {"actionKey":"project_invite.decline","label":"Decline","style":"secondary","command":"project_invite.decline","payload":{"inviteId":"invite-1"}}
          ]
        }
        """
            .trimIndent()

    private fun seedChannelClient(channel: String, scopes: List<String>): String {
        val token = "test-${UUID.randomUUID()}"
        val tokenHash = hashToken(token)
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_channel_clients (id, channel, token_hash, scopes)
                    VALUES (?, ?, ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, channel)
                    statement.setString(3, tokenHash)
                    statement.setArray(4, connection.createArrayOf("text", scopes.toTypedArray()))
                    statement.executeUpdate()
                }
        }
        return token
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun assertRowCount(table: String, expected: Int) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val resultSet = statement.executeQuery("SELECT count(*) FROM $table")
                try {
                    resultSet.next()
                    assertEquals(expected, resultSet.getInt(1))
                } finally {
                    resultSet.close()
                }
            }
        }
    }
}
