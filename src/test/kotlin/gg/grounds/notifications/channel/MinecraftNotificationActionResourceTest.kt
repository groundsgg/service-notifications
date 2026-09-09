package gg.grounds.notifications.channel

import gg.grounds.notifications.actions.FakeForgeActionServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(FakeForgeActionServer::class)
class MinecraftNotificationActionResourceTest {
    @Inject lateinit var dataSource: DataSource

    @Inject lateinit var playerResolver: InMemoryMinecraftPlayerResolver

    @BeforeEach
    fun resetDatabase() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM notification_action_results")
                statement.executeUpdate("DELETE FROM notification_actions")
                statement.executeUpdate("DELETE FROM notification_recipients")
                statement.executeUpdate("DELETE FROM notification_audiences")
                statement.executeUpdate("DELETE FROM notification_workflow_state")
                statement.executeUpdate("DELETE FROM notification_outbox")
                statement.executeUpdate("DELETE FROM notifications")
                statement.executeUpdate("DELETE FROM notification_channel_clients")
            }
        }
        playerResolver.clear()
    }

    @Test
    fun minecraftChannelActionExecutesForAuthorizedRecipientAndScope() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")
        val notificationId =
            postNotificationEvent(
                apiToken,
                "event-minecraft-action-success",
                "user-alpha",
                inviteId = "invite-success",
                scopeType = "server",
                scopeId = "server-1",
            )

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .header("X-Request-Id", "request-minecraft-action-1")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/$notificationId/actions/project_invite.accept"
            )
            .then()
            .statusCode(200)
            .body("status", equalTo("succeeded"))

        assertActionResultStatus(notificationId, "succeeded")
        assertRecipientReadAtPresent(notificationId)
    }

    @Test
    fun minecraftChannelActionRejectsClientWithoutActionScope() {
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.read"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/${UUID.randomUUID()}/actions/project_invite.accept"
            )
            .then()
            .statusCode(401)
    }

    @Test
    fun minecraftChannelActionRejectsNotificationOutsideAuthorizedScope() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")
        val notificationId =
            postNotificationEvent(
                apiToken,
                "event-minecraft-action-wrong-scope",
                "user-alpha",
                inviteId = "invite-wrong-scope",
                scopeType = "server",
                scopeId = "server-2",
            )

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/$notificationId/actions/project_invite.accept"
            )
            .then()
            .statusCode(403)

        assertActionResultCount(notificationId, 0)
    }

    @Test
    fun minecraftChannelActionRejectsRequestsOutsideChannelClientScope() {
        val serverToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val projectToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                projectId = "project-1",
            )
        val deploymentToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                deploymentId = "deployment-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")

        assertMinecraftActionForbidden(
            serverToken,
            """{"playerUuid":"$playerUuid","serverId":"server-2"}""",
        )
        assertMinecraftActionForbidden(
            projectToken,
            """{"playerUuid":"$playerUuid","serverId":"server-1","projectId":"project-2"}""",
        )
        assertMinecraftActionForbidden(
            deploymentToken,
            """{"playerUuid":"$playerUuid","serverId":"server-1","deploymentId":"deployment-2"}""",
        )
    }

    @Test
    fun minecraftChannelActionRejectsUnmappedPlayerUuid() {
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/${UUID.randomUUID()}/actions/project_invite.accept"
            )
            .then()
            .statusCode(404)
    }

    @Test
    fun minecraftChannelActionRejectsNotificationOwnedByDifferentUser() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-beta")
        val notificationId =
            postNotificationEvent(
                apiToken,
                "event-minecraft-action-wrong-user",
                "user-alpha",
                inviteId = "invite-wrong-user",
                scopeType = "server",
                scopeId = "server-1",
            )

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/$notificationId/actions/project_invite.accept"
            )
            .then()
            .statusCode(403)

        assertActionResultCount(notificationId, 0)
    }

    @Test
    fun minecraftChannelActionRejectsNetworkScopedCommand() {
        val apiToken = seedChannelClient(channel = "api", scopes = listOf("notifications:write"))
        val minecraftToken =
            seedChannelClient(
                channel = "minecraft",
                scopes = listOf("minecraft.notifications.action"),
                serverId = "server-1",
            )
        val playerUuid = UUID.randomUUID().toString()
        playerResolver.mapPlayer(playerUuid, "user-alpha")
        val notificationId =
            postNotificationEvent(
                apiToken,
                "event-network-command-rejected",
                "user-alpha",
                inviteId = "invite-network",
                scopeType = "network",
                scopeId = null,
            )

        given()
            .header("Authorization", "Bearer $minecraftToken")
            .contentType("application/json")
            .body("""{"playerUuid":"$playerUuid","serverId":"server-1"}""")
            .post(
                "/v1/channel/minecraft/notifications/$notificationId/actions/project_invite.accept"
            )
            .then()
            .statusCode(403)

        assertActionResultCount(notificationId, 0)
    }

    private fun assertMinecraftActionForbidden(token: String, body: String) {
        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(body)
            .post(
                "/v1/channel/minecraft/notifications/${UUID.randomUUID()}/actions/project_invite.accept"
            )
            .then()
            .statusCode(403)
    }

    private fun postNotificationEvent(
        token: String,
        idempotencyKey: String,
        userId: String,
        inviteId: String,
        scopeType: String,
        scopeId: String?,
    ): String =
        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(idempotencyKey, userId, inviteId, scopeType, scopeId))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")

    private fun notificationEventJson(
        idempotencyKey: String,
        userId: String,
        inviteId: String,
        scopeType: String,
        scopeId: String?,
    ): String =
        """
        {
          "idempotencyKey":"$idempotencyKey",
          "type":"project_invite",
          "category":"project",
          "priority":"normal",
          "scope":${if (scopeId == null) "{\"type\":\"$scopeType\"}" else "{\"type\":\"$scopeType\",\"id\":\"$scopeId\"}"},
          "actor":{"type":"user","id":"user-owner"},
          "entity":{"type":"project_invite","id":"$inviteId"},
          "title":"Project invite",
          "body":"You were invited to Project One.",
          "data":{"inviteId":"$inviteId"},
          "recipients":[{"userId":"$userId"}],
          "actions":[
            {"actionKey":"project_invite.accept","label":"Accept","style":"primary","command":"project_invite.accept","payload":{"inviteId":"$inviteId"}}
          ]
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

    private fun assertActionResultStatus(notificationId: String, expectedStatus: String) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT status FROM notification_action_results WHERE notification_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, UUID.fromString(notificationId))
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        assertEquals(expectedStatus, resultSet.getString("status"))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }

    private fun assertRecipientReadAtPresent(notificationId: String) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT read_at FROM notification_recipients WHERE notification_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, UUID.fromString(notificationId))
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        assertNotNull(resultSet.getObject("read_at"))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }

    private fun assertActionResultCount(notificationId: String, expectedCount: Int) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT count(*) FROM notification_action_results WHERE notification_id = ?"
                )
                .use { statement ->
                    statement.setObject(1, UUID.fromString(notificationId))
                    val resultSet = statement.executeQuery()
                    try {
                        resultSet.next()
                        assertEquals(expectedCount, resultSet.getInt(1))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
