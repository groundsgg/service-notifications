package gg.grounds.notifications.channel

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ForgeMinecraftPlayerResolverTest {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `resolves exact bounded batch with bearer token`() {
        val requestBody = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        serve { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().readText())
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val response =
                """{"mappings":[{"playerUuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","userId":"user-alpha"}]}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        val resolver = resolver()

        val result =
            resolver.resolveUserIds(
                listOf(
                    "9a122510-849a-44e8-b022-743093a8b1f0",
                    "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                )
            )

        assertEquals("Bearer internal-token", authorization.get())
        assertEquals(
            """{"playerUuids":["069a79f4-44e9-4726-a5be-fca90e38aaf5","9a122510-849a-44e8-b022-743093a8b1f0"]}""",
            requestBody.get(),
        )
        assertEquals(mapOf("069a79f4-44e9-4726-a5be-fca90e38aaf5" to "user-alpha"), result)
    }

    @Test
    fun `rejects response mappings outside request and unsafe shape`() {
        listOf(
                """{"mappings":[{"playerUuid":"9a122510-849a-44e8-b022-743093a8b1f0","userId":"user-beta"}]}""",
                """{"mappings":[{"playerUuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","userId":"user-alpha","email":"private@example.test"}]}""",
                """{"mappings":[{"playerUuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","userId":" "}]}""",
            )
            .forEach { response ->
                serveResponse(200, response)
                assertThrows(ForgeMinecraftIdentityException::class.java) {
                    resolver().resolveUserIds(listOf("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
                }
                server?.stop(0)
            }
    }

    @Test
    fun `classifies timeout and non success without exposing response`() {
        serve { exchange ->
            Thread.sleep(150)
            exchange.sendResponseHeaders(503, 0)
            exchange.close()
        }
        val timeout =
            assertThrows(ForgeMinecraftIdentityException::class.java) {
                resolver(readTimeout = Duration.ofMillis(25))
                    .resolveUserIds(listOf("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
            }
        assertEquals(false, timeout.message.orEmpty().contains("503"))
    }

    @Test
    fun `rejects oversized response bodies`() {
        serveResponse(200, "x".repeat(1_048_577))

        assertThrows(ForgeMinecraftIdentityException::class.java) {
            resolver().resolveUserIds(listOf("069a79f4-44e9-4726-a5be-fca90e38aaf5"))
        }
    }

    private fun serveResponse(status: Int, response: String) {
        serve { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            exchange.sendResponseHeaders(status, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
    }

    private fun serve(handler: com.sun.net.httpserver.HttpHandler) {
        server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/v1/internal/minecraft-identities/resolve", handler)
                start()
            }
    }

    private fun resolver(readTimeout: Duration = Duration.ofSeconds(1)) =
        ForgeMinecraftPlayerResolver(
            baseUrl = "http://127.0.0.1:${server?.address?.port}",
            internalToken = "internal-token",
            connectTimeout = Duration.ofSeconds(1),
            readTimeout = readTimeout,
            objectMapper = ObjectMapper(),
        )
}
