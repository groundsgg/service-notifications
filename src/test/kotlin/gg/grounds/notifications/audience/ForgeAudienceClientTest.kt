package gg.grounds.notifications.audience

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import gg.grounds.notifications.moderation.EnvironmentModerationNotificationRuntime
import gg.grounds.notifications.moderation.ModerationEnvironment
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ForgeAudienceClientTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `requests exact all permission audience with internal bearer token`() {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/internal/notification-audiences/resolve") { exchange ->
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    requestBody.set(exchange.requestBody.bufferedReader().readText())
                    val body =
                        """{"recipients":[{"userId":"a-user"},{"userId":"b-user"}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"308360c38170195c9edcf99e6448eea5f85ff3d00881ce3f2688c4db309807c1"}"""
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }
        val client = client()

        val snapshot = client.resolveModerationAudience()

        assertEquals("Bearer internal-token", authorization.get())
        assertEquals(
            """{"permissionKeys":["MODERATION_NOTIFICATIONS_VIEW","MODERATION_REPORTS_VIEW"],"match":"ALL"}""",
            requestBody.get(),
        )
        assertEquals(listOf("a-user", "b-user"), snapshot.userIds)
        assertEquals(Instant.parse("2026-09-08T18:00:00Z"), snapshot.resolvedAt)
        assertEquals(
            "308360c38170195c9edcf99e6448eea5f85ff3d00881ce3f2688c4db309807c1",
            snapshot.fingerprint,
        )
    }

    @Test
    fun `environment audience requires support area report notification and matching environment rights`() {
        val requestBody = AtomicReference<String>()
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/internal/notification-audiences/resolve") { exchange ->
                    requestBody.set(exchange.requestBody.bufferedReader().readText())
                    val body =
                        """{"recipients":[{"userId":"a-user"},{"userId":"b-user"}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"308360c38170195c9edcf99e6448eea5f85ff3d00881ce3f2688c4db309807c1"}"""
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }
        for ((environment, permission) in
            listOf(
                ModerationEnvironment.STAGE to "MODERATION_STAGE_ACCESS",
                ModerationEnvironment.PROD to "MODERATION_PROD_ACCESS",
            )) {
            client(readyRuntime = EnvironmentModerationNotificationRuntime(true, true))
                .resolveModerationAudience(environment)
            val json = ObjectMapper().readTree(requestBody.get())
            assertEquals(
                setOf(
                    "SUPPORT_AREA_ACCESS",
                    "MODERATION_REPORTS_VIEW",
                    "MODERATION_NOTIFICATIONS_VIEW",
                    permission,
                ),
                json.path("permissionKeys").map { it.asText() }.toSet(),
            )
            assertEquals(4, json.path("permissionKeys").size())
            assertEquals("ALL", json.path("match").asText())
        }
    }

    @Test
    fun `classifies retryable and terminal http failures`() {
        serve(429, "{}")
        assertThrows(RetryableForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
        server?.stop(0)
        serve(500, "{}")
        assertThrows(RetryableForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
        server?.stop(0)
        serve(401, "{}")
        assertThrows(RetryableForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
        server?.stop(0)
        serve(403, "{}")
        assertThrows(RetryableForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
        server?.stop(0)
        serve(400, "{}")
        assertThrows(TerminalForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
    }

    @Test
    fun `rejects oversized response before parsing`() {
        serve(200, "x".repeat(1_048_577))

        assertThrows(TerminalForgeAudienceException::class.java) {
            client().resolveModerationAudience()
        }
    }

    @Test
    fun `rejects malformed duplicate blank and extra recipient data`() {
        listOf(
                """{"recipients":[{"userId":"a"},{"userId":"a"}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"${"a".repeat(64)}"}""",
                """{"recipients":[{"userId":" "}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"${"a".repeat(64)}"}""",
                """{"recipients":[{"userId":"a","name":"Private"}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"${"a".repeat(64)}"}""",
            )
            .forEach { body ->
                serve(200, body)
                assertThrows(TerminalForgeAudienceException::class.java) {
                    client().resolveModerationAudience()
                }
                server?.stop(0)
            }
    }

    @Test
    fun `times out as retryable`() {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/internal/notification-audiences/resolve") { exchange ->
                    Thread.sleep(200)
                    exchange.sendResponseHeaders(200, 0)
                    exchange.close()
                }
                start()
            }

        assertThrows(RetryableForgeAudienceException::class.java) {
            client(readTimeout = Duration.ofMillis(25)).resolveModerationAudience()
        }
    }

    @Test
    fun `validates enabled configuration but permits disabled bootstrap`() {
        assertThrows(IllegalArgumentException::class.java) { client(internalToken = "") }
        assertThrows(IllegalArgumentException::class.java) {
            client(baseUrl = "ftp://forge.invalid")
        }
        val disabled = client(internalToken = "", projectorEnabled = false)
        assertThrows(TerminalForgeAudienceException::class.java) {
            disabled.resolveModerationAudience()
        }
    }

    @Test
    fun `stage projector can resolve its audience while legacy and prod remain disabled`() {
        serve(
            200,
            """{"recipients":[{"userId":"a-user"},{"userId":"b-user"}],"resolvedAt":"2026-09-08T18:00:00Z","resolutionFingerprint":"308360c38170195c9edcf99e6448eea5f85ff3d00881ce3f2688c4db309807c1"}""",
        )
        val stageOnly =
            client(
                projectorEnabled = false,
                readyRuntime = EnvironmentModerationNotificationRuntime(true, false),
            )
        assertEquals(
            listOf("a-user", "b-user"),
            stageOnly.resolveModerationAudience(ModerationEnvironment.STAGE).userIds,
        )
        assertThrows(TerminalForgeAudienceException::class.java) {
            stageOnly.resolveModerationAudience(ModerationEnvironment.PROD)
        }
        assertThrows(TerminalForgeAudienceException::class.java) {
            stageOnly.resolveModerationAudience()
        }
        assertThrows(IllegalArgumentException::class.java) {
            client(
                internalToken = "",
                projectorEnabled = false,
                readyRuntime = EnvironmentModerationNotificationRuntime(true, false),
            )
        }
    }

    private fun serve(status: Int, body: String) {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/internal/notification-audiences/resolve") { exchange ->
                    exchange.requestBody.use { it.readAllBytes() }
                    exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                start()
            }
    }

    private fun client(
        baseUrl: String = "http://127.0.0.1:${server?.address?.port ?: 9}",
        internalToken: String = "internal-token",
        readTimeout: Duration = Duration.ofSeconds(1),
        projectorEnabled: Boolean = true,
        readyRuntime: EnvironmentModerationNotificationRuntime =
            EnvironmentModerationNotificationRuntime(false, false),
    ) =
        ForgeAudienceClient(
            baseUrl = baseUrl,
            internalToken = internalToken,
            connectTimeout = Duration.ofSeconds(1),
            readTimeout = readTimeout,
            projectorEnabled = projectorEnabled,
            objectMapper = ObjectMapper(),
            environmentRuntime = readyRuntime,
        )
}
