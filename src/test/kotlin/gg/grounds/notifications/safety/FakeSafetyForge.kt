package gg.grounds.notifications.safety

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val STAGE_KEY = "stage-test-only-token-000000000000000"
internal const val PROD_KEY = "prod-test-only-token-0000000000000000"
internal const val STAFF = "a0000000-0000-4000-a000-000000000001"
internal const val OTHER = "a0000000-0000-4000-a000-000000000002"
internal const val ISSUER = "https://issuer.test/realms/grounds"

class FakeSafetyForge : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer
    private val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
    private val held = java.util.concurrent.CountDownLatch(1)
    private val release = java.util.concurrent.CountDownLatch(1)

    override fun start(): Map<String, String> {
        val mapper = ObjectMapper()
        val mode = AtomicReference("ok")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/held") { exchange ->
            exchange.use { it.sendResponseHeaders(if (held.count == 0L) 204 else 404, -1) }
        }
        server.createContext("/release") { exchange ->
            exchange.use {
                release.countDown()
                it.sendResponseHeaders(204, -1)
            }
        }
        server.createContext("/fixture") { x ->
            x.use {
                mode.set(it.requestBody.reader().readText())
                it.sendResponseHeaders(204, -1)
            }
        }
        server.createContext("/v1/internal/moderation-safety-audiences/resolve") { x ->
            x.use {
                if (mode.get() == "hold") {
                    held.countDown()
                    release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
                val n = mapper.readTree(it.requestBody)
                val valid =
                    it.requestMethod == "POST" &&
                        it.requestHeaders.getFirst("Authorization") ==
                            "Bearer test-forge-internal-token" &&
                        n.fieldNames().asSequence().toSet() ==
                            setOf("schemaVersion", "nonce", "environment", "userId") &&
                        n["schemaVersion"].asInt() == 1
                val status = if (!valid || mode.get() == "down") 503 else 200
                val users =
                    when (mode.get()) {
                        "revoked" -> emptyList()
                        "foreign" -> listOf(OTHER)
                        "oversized" -> (1..1001).map { UUID.randomUUID().toString() }
                        "duplicate" -> listOf(STAFF, STAFF)
                        else ->
                            if (n["userId"].isNull || n["userId"].asText() == STAFF) listOf(STAFF)
                            else emptyList()
                    }
                val result =
                    mutableMapOf<String, Any>(
                        "schemaVersion" to 1,
                        "nonce" to
                            (if (mode.get() == "nonce") UUID.randomUUID().toString()
                            else n["nonce"].asText()),
                        "environment" to
                            (if (mode.get() == "env") "prod" else n["environment"].asText()),
                        "resolvedAt" to
                            Instant.now()
                                .minusSeconds(if (mode.get() == "stale") 10 else 0)
                                .toString(),
                        "recipients" to users.map { user -> mapOf("userId" to user) },
                    )
                if (mode.get() == "extra") result["extra"] = true
                val bytes = mapper.writeValueAsBytes(result)
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        return mapOf(
            "notifications.forge.base-url" to "http://127.0.0.1:${server.address.port}",
            "notifications.safety.fixture-port" to server.address.port.toString(),
        )
    }

    override fun stop() {
        release.countDown()
        server.stop(0)
        executor.shutdownNow()
    }
}
