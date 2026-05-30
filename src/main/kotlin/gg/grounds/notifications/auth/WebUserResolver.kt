package gg.grounds.notifications.auth

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotAuthorizedException

@ApplicationScoped
class WebUserResolver {
    fun requireUser(identity: SecurityIdentity): String {
        if (identity.isAnonymous || identity.principal?.name.isNullOrBlank()) {
            throw NotAuthorizedException("Bearer")
        }
        return identity.principal.name
    }
}
