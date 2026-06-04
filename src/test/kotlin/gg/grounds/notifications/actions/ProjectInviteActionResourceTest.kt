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
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import javax.sql.DataSource
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertRecipientReadAtPresent(notificationId)
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun repeatedProjectInviteAcceptReturnsExistingResultWithoutCallingForgeAgain() {
        val notificationId = createNotificationWithInvite("invite-idempotent")

        repeat(2) {
            given()
                .header("X-Request-Id", "request-idempotent-$it")
                .contentType("application/json")
                .body("{}")
                .post("/v1/notifications/$notificationId/actions/project_invite.accept")
                .then()
                .statusCode(200)
                .body("status", equalTo("succeeded"))
        }

        assertActionResultStatus(notificationId, "succeeded")
        assertActionResultCount(notificationId, 1)
        assertRecipientReadAtPresent(notificationId)
        assertEquals(1, FakeForgeActionServer.requestCount("invite-idempotent", "accept"))
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun successfulProjectInviteActionRemovesActionsFromInbox() {
        val notificationId =
            createNotificationWithInvite(
                inviteId = "invite-success-actions-hidden",
                additionalActionKey = "project_invite.decline",
            )

        given()
            .get("/v1/notifications")
            .then()
            .statusCode(200)
            .body("items[0].actions.size()", equalTo(2))

        given()
            .header("X-Request-Id", "request-actions-hidden-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.accept")
            .then()
            .statusCode(200)
            .body("status", equalTo("succeeded"))

        given()
            .get("/v1/notifications")
            .then()
            .statusCode(200)
            .body("items[0].readAt", notNullValue())
            .body("items[0].actions.size()", equalTo(0))
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun projectInviteDeclineStoresSucceededResultWhenForgeAcceptsDeclineAction() {
        val notificationId =
            createNotificationWithInvite(
                inviteId = "invite-decline-success",
                actionKey = "project_invite.decline",
            )

        given()
            .header("X-Request-Id", "request-decline-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/project_invite.decline")
            .then()
            .statusCode(200)
            .body("status", equalTo("succeeded"))

        assertActionResultStatus(notificationId, "succeeded")
        assertRecipientReadAtPresent(notificationId)
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun clusterResumeStoresSucceededResultWhenForgeResumesCluster() {
        val notificationId = createNotificationWithCluster("cluster-success")

        given()
            .header("X-Request-Id", "request-cluster-resume-1")
            .contentType("application/json")
            .body("{}")
            .post("/v1/notifications/$notificationId/actions/cluster.resume")
            .then()
            .statusCode(200)
            .body("status", equalTo("succeeded"))

        assertActionResultStatus(notificationId, "succeeded")
        assertEquals(1, FakeForgeActionServer.requestCount("cluster-success", "resume"))
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
        assertRecipientReadAtMissing(notificationId)
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
        assertRecipientReadAtMissing(notificationId)
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
        assertRecipientReadAtMissing(notificationId)
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun failedProjectInviteActionLogsFailureOutcomeWithRequestContext() {
        val notificationId = createNotificationWithInvite("invite-error-logged")
        val records =
            captureLogs(NotificationActionService::class.java.name) {
                given()
                    .header("X-Request-Id", "request-error-logged-1")
                    .contentType("application/json")
                    .body("{}")
                    .post("/v1/notifications/$notificationId/actions/project_invite.accept")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("failed"))
            }

        assertFalse(
            records.any { it.level == Level.INFO && it.message.contains("status=failed") },
            "Failed notification actions must not be logged at INFO",
        )
        assertTrue(
            records.any {
                it.level == Level.SEVERE &&
                    it.message.contains("Failed to handle notification action") &&
                    it.message.contains("notificationId=$notificationId") &&
                    it.message.contains("actionKey=project_invite.accept") &&
                    it.message.contains("userId=user-alpha") &&
                    it.message.contains("requestId=request-error-logged-1") &&
                    it.message.contains("reason=forge_status_500")
            },
            "Failed notification action log must be ERROR-level and include request context",
        )
    }

    @Test
    @TestSecurity(user = "user-alpha")
    fun unavailableForgeLogsActionFailureWithInviteAndUserContext() {
        val notificationId = createNotificationWithInvite("invite-unavailable")
        val records =
            captureLogs(ProjectInviteActionAdapter::class.java.name) {
                given()
                    .header("X-Request-Id", "request-unavailable-1")
                    .contentType("application/json")
                    .body("{}")
                    .post("/v1/notifications/$notificationId/actions/project_invite.accept")
                    .then()
                    .statusCode(200)
                    .body("status", equalTo("failed"))
            }

        assertTrue(
            records.any {
                it.level == Level.SEVERE &&
                    it.message.contains("Failed to execute project invite action") &&
                    it.message.contains("notificationId=$notificationId") &&
                    it.message.contains("actionKey=project_invite.accept") &&
                    it.message.contains("userId=user-alpha") &&
                    it.message.contains("requestId=request-unavailable-1") &&
                    it.message.contains("inviteId=invite-unavailable")
            },
            "Forge action failure log must include notification, request, user, and invite context",
        )
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

    private fun assertRecipientReadAtMissing(notificationId: String) {
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
                        assertNull(resultSet.getObject("read_at"))
                    } finally {
                        resultSet.close()
                    }
                }
        }
    }

    private fun createNotificationWithInvite(
        inviteId: String,
        actionKey: String = "project_invite.accept",
        additionalActionKey: String? = null,
    ): String {
        val token = seedChannelClient()
        return given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(notificationEventJson(inviteId, actionKey, additionalActionKey))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")
    }

    private fun createNotificationWithCluster(devClusterId: String): String {
        val token = seedChannelClient()
        return given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(clusterNotificationEventJson(devClusterId))
            .post("/v1/notification-events")
            .then()
            .statusCode(201)
            .extract()
            .path("id")
    }

    private fun notificationEventJson(
        inviteId: String,
        actionKey: String,
        additionalActionKey: String? = null,
    ): String {
        val additionalAction =
            additionalActionKey?.let {
                """,
            {"actionKey":"$it","label":"Additional action","style":"secondary","command":"$it","payload":{"inviteId":"$inviteId"}}"""
            } ?: ""
        return """
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
            {"actionKey":"$actionKey","label":"Action","style":"primary","command":"$actionKey","payload":{"inviteId":"$inviteId"}}$additionalAction
          ]
        }
        """
            .trimIndent()
    }

    private fun clusterNotificationEventJson(devClusterId: String): String {
        return """
        {
          "idempotencyKey":"event-$devClusterId",
          "type":"dev_cluster.paused",
          "category":"cluster",
          "priority":"normal",
          "scope":{"type":"project","id":"project-1"},
          "actor":{"type":"system","id":"pause-janitor"},
          "entity":{"type":"dev_cluster","id":"$devClusterId"},
          "title":"Workspace paused",
          "body":"The workspace was paused.",
          "data":{"devClusterId":"$devClusterId"},
          "recipients":[{"userId":"user-alpha"}],
          "actions":[
            {"actionKey":"cluster.resume","label":"Resume","style":"primary","command":"cluster.resume","payload":{"devClusterId":"$devClusterId"}}
          ]
        }
        """
            .trimIndent()
    }

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

    private fun captureLogs(loggerName: String, block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(loggerName)
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        return try {
            block()
            records.toList()
        } finally {
            logger.removeHandler(handler)
        }
    }
}

class FakeForgeActionServer : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        requests.clear()
        server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            if (authorization != "Bearer test-forge-internal-token") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
                return@createContext
            }
            val inviteId =
                exchange.requestURI.path.substringAfter("project-invites/").substringBefore('/')
            val clusterId =
                exchange.requestURI.path.substringAfter("dev-clusters/").substringBefore('/')
            val action = exchange.requestURI.path.substringAfterLast('/')
            if (inviteId == "invite-unavailable") {
                exchange.close()
                return@createContext
            }
            val actionTarget =
                if (exchange.requestURI.path.contains("/dev-clusters/")) clusterId else inviteId
            requests.merge("$actionTarget:$action", 1, Int::plus)
            val status =
                when {
                    exchange.requestURI.path.contains("cluster-success") &&
                        exchange.requestURI.path.endsWith("/resume") -> 200
                    exchange.requestURI.path.contains("invite-decline-success") &&
                        exchange.requestURI.path.endsWith("/decline") -> 200
                    exchange.requestURI.path.contains("invite-success") -> 200
                    exchange.requestURI.path.contains("invite-idempotent") -> 200
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

    companion object {
        private val requests = java.util.concurrent.ConcurrentHashMap<String, Int>()

        fun requestCount(inviteId: String, action: String): Int = requests["$inviteId:$action"] ?: 0
    }
}
