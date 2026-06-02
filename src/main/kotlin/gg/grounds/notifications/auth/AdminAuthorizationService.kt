package gg.grounds.notifications.auth

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.json.JsonString
import jakarta.ws.rs.ForbiddenException
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class AdminAuthorizationService(
    private val webUserResolver: WebUserResolver,
    private val jwt: JsonWebToken,
) {
    fun requireNotificationsAdmin(identity: SecurityIdentity): String {
        val userId = webUserResolver.requireUser(identity)
        if (!hasNotificationManagePermission(identity)) {
            throw ForbiddenException("missing_permission")
        }
        return userId
    }

    private fun hasNotificationManagePermission(identity: SecurityIdentity): Boolean =
        ADMIN_PERMISSION in identity.roles ||
            JWT_PERMISSION_CLAIMS.any { claimName -> claimContainsPermission(claimName) }

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
