package gg.grounds.notifications.auth

import gg.grounds.notifications.db.ChannelClient
import gg.grounds.notifications.db.ChannelClientRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotAuthorizedException
import jakarta.ws.rs.core.HttpHeaders
import java.security.MessageDigest
import org.jboss.logging.Logger

@ApplicationScoped
class ChannelClientAuthService(private val channelClientRepository: ChannelClientRepository) {
    fun requireClient(headers: HttpHeaders, channel: String, requiredScope: String): ChannelClient {
        val token = bearerToken(headers) ?: throw NotAuthorizedException("Bearer")
        val client =
            channelClientRepository.findActiveByTokenHash(hashToken(token))
                ?: throw NotAuthorizedException("Bearer")
        if (client.channel != channel || requiredScope !in client.scopes) {
            LOG.warnf(
                "Rejected channel client authorization (clientId=%s, channel=%s, requiredScope=%s)",
                client.id,
                channel,
                requiredScope,
            )
            throw NotAuthorizedException("Bearer")
        }
        return client
    }

    private fun bearerToken(headers: HttpHeaders): String? {
        val authorization = headers.getHeaderString(HttpHeaders.AUTHORIZATION) ?: return null
        if (!authorization.startsWith("Bearer ")) {
            return null
        }
        return authorization.removePrefix("Bearer ").trim().ifBlank { null }
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(ChannelClientAuthService::class.java)
    }
}
