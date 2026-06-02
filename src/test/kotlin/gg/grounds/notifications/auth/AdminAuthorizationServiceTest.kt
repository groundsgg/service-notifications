package gg.grounds.notifications.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.core.Cookie
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import jakarta.ws.rs.core.MultivaluedMap
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.security.Principal
import java.util.Date
import java.util.Locale
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AdminAuthorizationServiceTest {
    @Test
    fun acceptsForgeEffectiveAccessPermissionWhenJwtDoesNotContainPermission() {
        val server = forgeAccessServer("""{"permissions":["NOTIFICATIONS_MANAGE"]}""")
        try {
            val service =
                AdminAuthorizationService(
                    WebUserResolver(jsonWebToken(subject = "admin-user"), false),
                    jsonWebToken(subject = "admin-user"),
                    jacksonObjectMapper(),
                    "http://localhost:${server.address.port}",
                )

            val userId =
                service.requireNotificationsAdmin(
                    securityIdentity(),
                    StaticAuthorizationHeaders("Bearer forge-admin-token"),
                )

            assertEquals("admin-user", userId)
        } finally {
            server.stop(0)
        }
    }

    private fun forgeAccessServer(response: String): HttpServer {
        val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        server.createContext("/v1/control-center/access/me") { exchange ->
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    private fun jsonWebToken(subject: String): JsonWebToken =
        Proxy.newProxyInstance(
            JsonWebToken::class.java.classLoader,
            arrayOf(JsonWebToken::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSubject" -> subject
                "getName" -> subject
                "getClaim" -> null
                else -> null
            }
        } as JsonWebToken

    private fun securityIdentity(): SecurityIdentity =
        Proxy.newProxyInstance(
            SecurityIdentity::class.java.classLoader,
            arrayOf(SecurityIdentity::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "isAnonymous" -> false
                "getPrincipal" -> Principal { "admin-user" }
                "getRoles" -> emptySet<String>()
                else -> null
            }
        } as SecurityIdentity
}

private class StaticAuthorizationHeaders(private val authorization: String) : HttpHeaders {
    private val headers =
        MultivaluedHashMap<String, String>().also {
            it.add(HttpHeaders.AUTHORIZATION, authorization)
        }

    override fun getRequestHeader(name: String): List<String>? = headers[name]

    override fun getHeaderString(name: String): String? = getRequestHeader(name)?.joinToString(",")

    override fun getRequestHeaders(): MultivaluedMap<String, String> = headers

    override fun getMediaType(): MediaType? = null

    override fun getAcceptableMediaTypes(): List<MediaType> = emptyList()

    override fun getLanguage(): Locale? = null

    override fun getAcceptableLanguages(): List<Locale> = emptyList()

    override fun getDate(): Date? = null

    override fun getLength(): Int = -1

    override fun getCookies(): Map<String, Cookie> = emptyMap()
}
