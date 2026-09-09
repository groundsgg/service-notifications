package gg.grounds.notifications.audience

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import org.eclipse.microprofile.config.inject.ConfigProperty

data class ForgeAudienceSnapshot(
    val userIds: List<String>,
    val resolvedAt: Instant,
    val fingerprint: String,
)

fun interface ForgeAudienceResolver {
    fun resolveModerationAudience(): ForgeAudienceSnapshot
}

sealed class ForgeAudienceException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class RetryableForgeAudienceException(message: String, cause: Throwable? = null) :
    ForgeAudienceException(message, cause)

class TerminalForgeAudienceException(message: String, cause: Throwable? = null) :
    ForgeAudienceException(message, cause)

@ApplicationScoped
class ForgeAudienceClient(
    @ConfigProperty(name = "notifications.forge.base-url") baseUrl: String,
    @param:ConfigProperty(name = "notifications.forge.internal-token")
    private val internalToken: String,
    @ConfigProperty(name = "notifications.forge.connect-timeout", defaultValue = "PT2S")
    connectTimeout: Duration,
    @param:ConfigProperty(name = "notifications.forge.read-timeout", defaultValue = "PT3S")
    private val readTimeout: Duration,
    @param:ConfigProperty(
        name = "notifications.moderation-projector.enabled",
        defaultValue = "false",
    )
    private val projectorEnabled: Boolean,
    private val objectMapper: ObjectMapper,
) : ForgeAudienceResolver {
    private val endpoint =
        runCatching {
                URI.create(baseUrl.trimEnd('/') + "/v1/internal/notification-audiences/resolve")
            }
            .getOrElse { throw IllegalArgumentException("Forge audience base URL is invalid", it) }
    private val httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    init {
        require(!projectorEnabled || endpoint.scheme in setOf("http", "https")) {
            "Forge audience base URL must use HTTP when moderation projector is enabled"
        }
        require(!projectorEnabled || internalToken.isNotBlank()) {
            "Forge internal token is required when moderation projector is enabled"
        }
        require(connectTimeout > Duration.ZERO && readTimeout > Duration.ZERO) {
            "Forge audience timeouts must be positive"
        }
    }

    override fun resolveModerationAudience(): ForgeAudienceSnapshot {
        if (!projectorEnabled) {
            throw TerminalForgeAudienceException("Moderation notification projector is disabled")
        }
        val payload =
            objectMapper.createObjectNode().apply {
                set<JsonNode>(
                    "permissionKeys",
                    objectMapper
                        .createArrayNode()
                        .add("MODERATION_NOTIFICATIONS_VIEW")
                        .add("MODERATION_REPORTS_VIEW"),
                )
                put("match", "ALL")
            }
        val request =
            HttpRequest.newBuilder(endpoint)
                .timeout(readTimeout)
                .header("Authorization", "Bearer $internalToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RetryableForgeAudienceException(
                    "Forge audience request was interrupted",
                    exception,
                )
            } catch (exception: Exception) {
                throw RetryableForgeAudienceException("Forge audience request failed", exception)
            }
        if (response.statusCode() == 429 || response.statusCode() >= 500) {
            throw RetryableForgeAudienceException(
                "Forge audience request is temporarily unavailable"
            )
        }
        if (response.statusCode() !in 200..299) {
            throw TerminalForgeAudienceException("Forge audience request was rejected")
        }
        return parseSnapshot(response.body())
    }

    private fun parseSnapshot(body: String): ForgeAudienceSnapshot {
        if (body.length > MAX_RESPONSE_LENGTH) invalidContract()
        val root = runCatching { objectMapper.readTree(body) }.getOrElse { invalidContract(it) }
        if (root.fieldNames().asSequence().toSet() != ROOT_FIELDS) invalidContract()
        val recipients = root.path("recipients")
        if (!recipients.isArray) invalidContract()
        val userIds =
            recipients.map { recipient ->
                if (
                    recipient.fieldNames().asSequence().toSet() != RECIPIENT_FIELDS ||
                        !recipient.path("userId").isTextual
                ) {
                    invalidContract()
                }
                recipient.path("userId").asText().also { userId ->
                    if (userId.isBlank() || userId != userId.trim() || userId.length > 255) {
                        invalidContract()
                    }
                }
            }
        if (userIds.toSet().size != userIds.size || userIds != userIds.sorted()) invalidContract()
        val resolvedAt =
            runCatching { Instant.parse(root.path("resolvedAt").asText()) }
                .getOrElse { invalidContract(it) }
        val fingerprint = root.path("resolutionFingerprint").asText()
        if (!FINGERPRINT.matches(fingerprint)) invalidContract()
        return ForgeAudienceSnapshot(userIds, resolvedAt, fingerprint)
    }

    private fun invalidContract(cause: Throwable? = null): Nothing =
        throw TerminalForgeAudienceException("Forge audience response contract is invalid", cause)

    private companion object {
        const val MAX_RESPONSE_LENGTH = 1_048_576
        val ROOT_FIELDS = setOf("recipients", "resolvedAt", "resolutionFingerprint")
        val RECIPIENT_FIELDS = setOf("userId")
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}
