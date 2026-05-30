package gg.grounds.notifications.auth

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotAuthorizedException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class WebUserResolver(
    private val jwt: JsonWebToken,
    @param:ConfigProperty(
        name = "notifications.auth.allow-test-security-principal",
        defaultValue = "false",
    )
    private val allowTestSecurityPrincipal: Boolean,
) {
    fun requireUser(identity: SecurityIdentity): String {
        val subject = verifiedJwtSubject()
        if (!subject.isNullOrBlank()) {
            return subject
        }

        if (allowTestSecurityPrincipal && !identity.isAnonymous) {
            val principalName = identity.principal?.name
            if (!principalName.isNullOrBlank()) {
                return principalName
            }
        }

        throw NotAuthorizedException("Bearer")
    }

    private fun verifiedJwtSubject(): String? =
        try {
            jwt.subject
        } catch (_: IllegalStateException) {
            null
        }
}
