package gg.grounds.notifications.channel

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
class MinecraftNotificationBatchResourceTest {
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
    fun minecraftBatchExcludesReadNotifications() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.read"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")
        postNotificationEvent(
            apiToken,
            idempotencyKey = "event-minecraft-unread",
            userId = "user-alpha",
            title = "Unread invite",
        )
        val readNotificationId =
            postNotificationEvent(
                apiToken,
                idempotencyKey = "event-minecraft-read",
                userId = "user-alpha",
                title = "Read invite",
            )
        markNotificationRead(readNotificationId, "user-alpha")

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"serverId":"server-1","playerUuids":["$playerUuid"]}""")
            .post("/v1/channel/minecraft/notifications/batch")
            .then()
            .statusCode(200)
            .body("players.size()", equalTo(1))
            .body("players[0].notifications.size()", equalTo(1))
            .body("players[0].notifications[0].title", equalTo("Unread invite"))
    }

    private fun markNotificationRead(notificationId: String, userId: String) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "UPDATE notification_recipients SET read_at = now() WHERE notification_id = ? AND user_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, UUID.fromString(notificationId))
                    statement.setString(2, userId)
                    statement.executeUpdate()
                }
        }
    }

    private fun postNotificationEvent(
        token: String,
        idempotencyKey: String,
        userId: String,
        title: String,
    ): String =
        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(idempotencyKey, userId, title))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")

    private fun notificationEventJson(
        idempotencyKey: String,
        userId: String,
        title: String,
    ): String =
        """
        {
          "idempotencyKey":"$idempotencyKey",
          "type":"project_invite",
          "category":"project",
          "priority":"normal",
          "scope":{"type":"server","id":"server-1"},
          "actor":{"type":"user","id":"user-owner"},
          "entity":{"type":"project_invite","id":"invite-1"},
          "title":"$title",
          "body":"You were invited to Project One.",
          "data":{"inviteId":"invite-1"},
          "recipients":[{"userId":"$userId"}],
          "actions":[]
        }
        """
            .trimIndent()

    private fun seedChannelClient(
        channel: String,
        scopes: List<String>,
        projectId: String? = null,
        serverId: String? = null,
        deploymentId: String? = null,
    ): String {
        val token = "test-${UUID.randomUUID()}"
        dataSource.connection.use { connection ->
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
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, channel)
                    statement.setString(3, hashToken(token))
                    statement.setString(4, projectId)
                    statement.setString(5, serverId)
                    statement.setString(6, deploymentId)
                    statement.setArray(7, connection.createArrayOf("text", scopes.toTypedArray()))
                    statement.executeUpdate()
                }
        }
        return token
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
