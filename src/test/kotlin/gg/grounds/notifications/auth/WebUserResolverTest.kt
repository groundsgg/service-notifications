package gg.grounds.notifications.auth

import io.quarkus.security.credential.Credential
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.ws.rs.NotAuthorizedException
import java.security.Permission
import java.security.Principal
import org.eclipse.microprofile.jwt.JsonWebToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WebUserResolverTest {
    @Test
    fun productionReturnsVerifiedJwtSubject() {
        val resolver = WebUserResolver(FakeJwt(subject = "grounds-user-1"), false)

        val userId = resolver.requireUser(FakeSecurityIdentity("claim-dependent-name"))

        assertEquals("grounds-user-1", userId)
    }

    @Test
    fun productionRejectsIdentityWithoutJwtSubject() {
        val resolver = WebUserResolver(FakeJwt(subject = null), false)

        assertThrows(NotAuthorizedException::class.java) {
            resolver.requireUser(FakeSecurityIdentity("claim-dependent-name"))
        }
    }

    @Test
    fun testFallbackUsesPrincipalNameOnlyWhenExplicitlyEnabled() {
        val resolver = WebUserResolver(FakeJwt(subject = null), true)

        val userId = resolver.requireUser(FakeSecurityIdentity("test-user"))

        assertEquals("test-user", userId)
    }

    @Test
    fun testFallbackHandlesNonJwtTestSecurityPrincipal() {
        val resolver = WebUserResolver(NonJwtPrincipal(), true)

        val userId = resolver.requireUser(FakeSecurityIdentity("test-user"))

        assertEquals("test-user", userId)
    }
}

private class FakeJwt(private val subject: String?) : JsonWebToken {
    override fun getName(): String = subject ?: "claim-dependent-name"

    override fun getSubject(): String? = subject

    override fun getClaimNames(): Set<String> = if (subject == null) emptySet() else setOf("sub")

    override fun <T : Any?> getClaim(claimName: String): T? {
        @Suppress("UNCHECKED_CAST")
        return if (claimName == "sub") subject as T? else null
    }
}

private class NonJwtPrincipal : JsonWebToken {
    override fun getName(): String = "test-user"

    override fun getSubject(): String =
        throw IllegalStateException("Current principal is not a JWT")

    override fun getClaimNames(): Set<String> = emptySet()

    override fun <T : Any?> getClaim(claimName: String): T? = null
}

private class FakeSecurityIdentity(name: String) : SecurityIdentity {
    private val principal = Principal { name }

    override fun getPrincipal(): Principal = principal

    override fun isAnonymous(): Boolean = false

    override fun getRoles(): Set<String> = emptySet()

    override fun hasRole(role: String): Boolean = false

    override fun getPermissions(): Set<Permission> = emptySet()

    override fun <T : Credential?> getCredential(credentialType: Class<T>): T? = null

    override fun getCredentials(): Set<Credential> = emptySet()

    override fun <T : Any?> getAttribute(name: String): T? = null

    override fun getAttributes(): Map<String, Any> = emptyMap()

    override fun checkPermission(permission: Permission): Uni<Boolean> =
        Uni.createFrom().item(false)
}
