package gg.grounds.notifications.admin

import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
@TestSecurity(user = "admin-user", roles = ["NOTIFICATIONS_MANAGE"])
class ChannelClientModerationEnvironmentAdminTest {
    @Inject lateinit var dataSource: DataSource

    @BeforeEach
    fun resetTestDatabase() {
        dataSource.connection.use {
            it.createStatement().use { statement ->
                statement.execute("TRUNCATE notification_channel_clients CASCADE")
            }
        }
    }

    @Test
    fun `authorized provisioning persists explicit stage binding and exposes it in the client record`() {
        val response =
            given()
                .contentType("application/json")
                .body(
                    mapOf(
                        "channel" to "minecraft",
                        "serverId" to "server-1",
                        "scopes" to listOf("minecraft.notifications.read"),
                        "moderationEnvironment" to "stage",
                    )
                )
                .post("/v1/admin/notifications/channel-clients")
                .then()
                .extract()
                .response()
        assertEquals(201, response.statusCode)
        assertEquals("stage", response.jsonPath().getString("client.moderationEnvironment"))
        val id = response.jsonPath().getString("client.id")
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "SELECT moderation_environment FROM notification_channel_clients WHERE id = CAST(? AS uuid)"
                )
                .use {
                    it.setString(1, id)
                    it.executeQuery().use { row ->
                        check(row.next())
                        assertEquals("stage", row.getString(1))
                    }
                }
        }
    }

    @Test
    fun `nonminecraft client cannot acquire a moderation environment binding`() {
        val response =
            given()
                .contentType("application/json")
                .body(
                    mapOf(
                        "channel" to "api",
                        "scopes" to listOf("notifications:write"),
                        "moderationEnvironment" to "stage",
                    )
                )
                .post("/v1/admin/notifications/channel-clients")
                .then()
                .extract()
                .response()
        assertEquals(400, response.statusCode)
    }
}
