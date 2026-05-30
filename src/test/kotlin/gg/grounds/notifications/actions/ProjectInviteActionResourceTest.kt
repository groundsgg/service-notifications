package gg.grounds.notifications.actions

import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@QuarkusTest
@QuarkusTestResource(FakeForgeActionServer::class)
class ProjectInviteActionResourceTest {
    @Inject lateinit var dataSource: DataSource

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
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun projectInviteAcceptStoresSucceededResultWhenForgeAcceptsAction() {
        val notificationId = createNotificationWithInvite("invite-success")

        given()
            .header("X-Request-Id", "request-success-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(200)
            .body("status", equalTo("succeeded"))

        assertActionResultStatus(notificationId, "succeeded")
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun projectInviteAcceptStoresRejectedResultWhenForgeReportsStaleInvite() {
        val notificationId = createNotificationWithInvite("invite-stale")

        given()
            .header("X-Request-Id", "request-stale-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(200)
            .body("status", equalTo("rejected"))
            .body("reason", equalTo("stale_invite"))

        assertActionResultStatus(notificationId, "rejected")
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun projectInviteAcceptStoresRejectedResultWhenForgeReportsWrongRecipient() {
        val notificationId = createNotificationWithInvite("invite-wrong")

        given()
            .header("X-Request-Id", "request-wrong-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(200)
            .body("status", equalTo("rejected"))
            .body("reason", equalTo("wrong_recipient"))

        assertActionResultStatus(notificationId, "rejected")
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun projectInviteAcceptStoresFailedResultWhenForgeFailsAction() {
        val notificationId = createNotificationWithInvite("invite-error")

        given()
            .header("X-Request-Id", "request-error-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(200)
            .body("status", equalTo("failed"))

        assertActionResultStatus(notificationId, "failed")
    }

    private fun createNotificationWithInvite(inviteId: String): String {
        val token = seedChannelClient()
        return given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(inviteId))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")
    }

    private fun notificationEventJson(inviteId: String): String =
        """
        {
          "idempotencyKey":"event-$inviteId",
          "type":"project_invite",
          "category":"project",
          "priority":"normal",
          "scope":{"type":"project","id":"project-1"},
          "actor":{"type":"user","id":"user-owner"},
          "entity":{"type":"project_invite","id":"$inviteId"},
          "title":"Project invite",
          "body":"You were invited to Project One.",
          "data":{"inviteId":"$inviteId"},
          "recipients":[{"userId":"user-alpha"}],
          "actions":[
            {"actionKey":"project_invite.accept","label":"Accept","style":"primary","command":"project_invite.accept","payload":{"inviteId":"$inviteId"}}
          ]
        }
        """
            .trimIndent()

    private fun seedChannelClient(): String {
        val token = "test-${UUID.randomUUID()}"
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO notification_channel_clients (id, channel, token_hash, scopes)
                    VALUES (?, 'api', ?, ?)
                    """
                        .trimIndent()
                )
                .use { statement ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setString(2, hashToken(token))
                    statement.setArray(
                        3,
                        connection.createArrayOf("text", arrayOf("notifications:write")),
                    )
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

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

class FakeForgeActionServer : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val status =
                when {
                    exchange.requestURI.path.contains("invite-success") -> 200
                    exchange.requestURI.path.contains("invite-stale") -> 409
                    exchange.requestURI.path.contains("invite-wrong") -> 403
                    else -> 500
                }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        return mapOf("notifications.forge.base-url" to "http://localhost:${server.address.port}")
    }

    override fun stop() {
        server.stop(0)
    }
}
