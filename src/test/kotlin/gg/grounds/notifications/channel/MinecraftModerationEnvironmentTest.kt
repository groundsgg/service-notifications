package gg.grounds.notifications.channel

import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.core.NotificationActor
import gg.grounds.notifications.core.NotificationEventRequest
import gg.grounds.notifications.core.NotificationRecipientRequest
import gg.grounds.notifications.core.NotificationScope
import gg.grounds.notifications.db.NotificationRepository
import gg.grounds.notifications.moderation.CaptureStatus
import gg.grounds.notifications.moderation.ModerationEnvironment
import gg.grounds.notifications.moderation.ReportReadyForReviewData
import gg.grounds.notifications.moderation.ReportReadyForReviewEvent
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated

@QuarkusTest
@Isolated
class MinecraftModerationEnvironmentTest {
    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var dataSource: DataSource
    @Inject lateinit var resolver: InMemoryMinecraftPlayerResolver
    private val player = UUID.randomUUID().toString()
    private val audience =
        ForgeAudienceSnapshot(
            listOf("supporter"),
            Instant.parse("2026-09-08T18:00:00Z"),
            "a".repeat(64),
        )

    @BeforeEach
    fun resetTestDatabase() {
        dataSource.connection.use {
            it.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE notification_outbox, notifications, notification_channel_clients CASCADE"
                )
            }
        }
        resolver.clear()
        resolver.mapPlayer(player, "supporter")
    }

    @Test
    fun `stage prod and legacy credentials receive only their own moderation items alongside generic items`() {
        val stage = createCase(ModerationEnvironment.STAGE)
        val prod = createCase(ModerationEnvironment.PROD)
        val generic = createGeneric()
        assertEquals(setOf(stage, generic), batchIds(token("stage")))
        assertEquals(setOf(prod, generic), batchIds(token("prod"), forgedEnvironment = "stage"))
        assertEquals(setOf(generic), batchIds(token(null), forgedEnvironment = "prod"))
    }

    @Test
    fun `known prod action cannot be accessed using a stage credential`() {
        val prod = createCase(ModerationEnvironment.PROD)
        given()
            .header("Authorization", "Bearer ${token("stage")}")
            .contentType("application/json")
            .body(
                mapOf(
                    "serverId" to "server-1",
                    "playerUuid" to player,
                    "moderationEnvironment" to "prod",
                )
            )
            .post("/v1/channel/minecraft/notifications/$prod/actions/open_case")
            .then()
            .statusCode(403)
    }

    @Test
    fun `wrong environment notifications cannot starve the limited stage inbox`() {
        val stage = createCase(ModerationEnvironment.STAGE)
        repeat(26) { createCase(ModerationEnvironment.PROD) }
        assertEquals(setOf(stage), batchIds(token("stage")))
    }

    private fun createCase(environment: ModerationEnvironment): String =
        repository
            .projectModerationCase(
                ReportReadyForReviewEvent(
                    UUID.randomUUID(),
                    "moderation.report.ready-for-review",
                    2,
                    Instant.now(),
                    ReportReadyForReviewData(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        CaptureStatus.COMPLETE,
                        1,
                        environment,
                    ),
                ),
                audience,
            )
            .notificationId
            .toString()

    private fun createGeneric(): String =
        repository
            .createEvent(
                NotificationEventRequest(
                    UUID.randomUUID().toString(),
                    "TEST_GENERIC",
                    "SYSTEM",
                    "NORMAL",
                    NotificationScope("network"),
                    NotificationActor("SYSTEM"),
                    title = "Safe fixture",
                    body = "Safe fixture",
                    recipients = listOf(NotificationRecipientRequest("supporter")),
                )
            )
            .id
            .toString()

    private fun token(environment: String?): String {
        val token = "test-" + UUID.randomUUID()
        val hash =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO notification_channel_clients(id,channel,token_hash,server_id,scopes,moderation_environment) VALUES (?,'minecraft',?,'server-1',?,?)"
                )
                .use {
                    it.setObject(1, UUID.randomUUID())
                    it.setString(2, hash)
                    it.setArray(
                        3,
                        connection.createArrayOf(
                            "text",
                            arrayOf(
                                "minecraft.notifications.read",
                                "minecraft.notifications.action",
                            ),
                        ),
                    )
                    it.setString(4, environment)
                    it.executeUpdate()
                }
        }
        return token
    }

    private fun batchIds(token: String, forgedEnvironment: String? = null): Set<String> =
        given()
            .header("Authorization", "Bearer $token")
            .contentType("application/json")
            .body(
                mapOf(
                    "serverId" to "server-1",
                    "playerUuids" to listOf(player),
                    "moderationEnvironment" to forgedEnvironment,
                )
            )
            .post("/v1/channel/minecraft/notifications/batch")
            .then()
            .statusCode(200)
            .extract()
            .jsonPath()
            .getList<String>("players[0].notifications.id")
            .toSet()
}
