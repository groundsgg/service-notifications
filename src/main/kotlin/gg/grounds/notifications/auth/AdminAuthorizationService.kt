package gg.grounds.notifications.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.json.JsonString
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class AdminAuthorizationService(
    private val webUserResolver: WebUserResolver,
    private val jwt: JsonWebToken,
    private val objectMapper: ObjectMapper,
    @param:ConfigProperty(name = "notifications.forge.base-url") private val forgeBaseUrl: String,
) {
    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun requireNotificationsAdmin(identity: SecurityIdentity, headers: HttpHeaders): String {
        val userId = webUserResolver.requireUser(identity)
        if (!hasNotificationManagePermission(identity, headers)) {
            throw ForbiddenException("missing_permission")
        }
        return userId
    }

    private fun hasNotificationManagePermission(
        identity: SecurityIdentity,
        headers: HttpHeaders,
    ): Boolean =
        ADMIN_PERMISSION in identity.roles ||
            JWT_PERMISSION_CLAIMS.any { claimName -> claimContainsPermission(claimName) } ||
            forgeEffectiveAccessContainsPermission(headers)

    private fun forgeEffectiveAccessContainsPermission(headers: HttpHeaders): Boolean {
        val authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION)?.trim()
        if (authorization.isNullOrBlank()) {
            return false
        }

        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("${forgeBaseUrl.trimEnd('/')}/v1/control-center/access/me"))
                .timeout(Duration.ofSeconds(3))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .GET()
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (_: Exception) {
                return false
            }

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return false
        }
        if (response.statusCode() !in 200..299) {
            return false
        }

        return parsePermissions(response.body()).contains(ADMIN_PERMISSION)
    }

    private fun parsePermissions(body: String): Set<String> {
        val root =
            try {
                objectMapper.readTree(body)
            } catch (_: Exception) {
                return emptySet()
            }
        val permissions = root.get("permissions")
        if (permissions == null || !permissions.isArray) {
            return emptySet()
        }
        return permissions.mapNotNull(JsonNode::asText).toSet()
    }

    private fun claimContainsPermission(claimName: String): Boolean =
        when (val claim = jwtClaim(claimName)) {
            is String -> claim == ADMIN_PERMISSION
            is Iterable<*> -> claim.any { permissionValue(it) == ADMIN_PERMISSION }
            is Array<*> -> claim.any { permissionValue(it) == ADMIN_PERMISSION }
            else -> false
        }

    private fun permissionValue(value: Any?): String? =
        when (value) {
            is JsonString -> value.string
            else -> value?.toString()
        }

    private fun jwtClaim(claimName: String): Any? =
        try {
            jwt.getClaim<Any>(claimName)
        } catch (_: IllegalStateException) {
            null
        }

    private companion object {
        private const val ADMIN_PERMISSION = "NOTIFICATIONS_MANAGE"
        private val JWT_PERMISSION_CLAIMS = listOf("permissions", "platform_permissions", "groups")
    }
}
