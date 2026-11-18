package gg.grounds.notifications.safety

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.moderation.ModerationEnvironment
import io.quarkus.runtime.annotations.RegisterForReflection
import jakarta.enterprise.context.ApplicationScoped
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.eclipse.microprofile.config.Config

internal const val SAFETY_TYPE = "MODERATION_SAFETY_SIGNAL"
internal const val SAFETY_ACTION = "OPEN_SAFETY_SIGNAL"
internal val envs = ModerationEnvironment.entries

internal fun uuid(value: String?): UUID? =
    value?.let {
        runCatching { UUID.fromString(it) }.getOrNull()?.takeIf { id -> id.toString() == value }
    }

internal fun utc(value: JsonNode?): Instant? =
    value
        ?.takeIf {
            it.isTextual &&
                it.textValue()
                    .matches(
                        Regex(
                            "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z"
                        )
                    )
        }
        ?.let {
            runCatching { java.time.OffsetDateTime.parse(it.textValue()).toInstant() }.getOrNull()
        }

internal fun strictObject(mapper: ObjectMapper, bytes: ByteArray, fields: Set<String>): JsonNode {
    val node =
        mapper
            .readerFor(JsonNode::class.java)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .readValue<JsonNode>(bytes)
    require(node.isObject && node.fieldNames().asSequence().toSet() == fields)
    return node
}

internal fun version(node: JsonNode) =
    node.path("schemaVersion").let {
        it.isIntegralNumber && it.bigIntegerValue() == java.math.BigInteger.ONE
    }

internal data class SafetyEnvelope(
    val environment: ModerationEnvironment,
    val eventId: UUID,
    val signalRef: UUID,
    val occurredAt: Instant,
) {
    companion object {
        fun parse(mapper: ObjectMapper, bytes: ByteArray): SafetyEnvelope {
            val n =
                strictObject(
                    mapper,
                    bytes,
                    setOf(
                        "schemaVersion",
                        "environment",
                        "eventId",
                        "signalRef",
                        "occurredAt",
                        "mode",
                    ),
                )
            require(version(n) && n.path("mode").textValue() == "ACTIVE")
            return SafetyEnvelope(
                requireNotNull(ModerationEnvironment.parse(n.path("environment").textValue())),
                requireNotNull(uuid(n.path("eventId").textValue())),
                requireNotNull(uuid(n.path("signalRef").textValue())),
                requireNotNull(utc(n["occurredAt"])),
            )
        }
    }
}

@ApplicationScoped
class SafetyGate(private val config: Config) {
    fun enabled(env: ModerationEnvironment): Boolean =
        config
            .getOptionalValue("notifications.safety.${env.wireValue}.enabled", String::class.java)
            .orElse("false") == "true"

    /**
     * Only the two explicitly enabled deployment secrets are consulted. Comparison uses fixed-size
     * digests.
     */
    internal fun authenticate(token: String?): ModerationEnvironment? {
        if (token == null || token.startsWith("gnc_") || !token.matches(Regex("[!-~]{32,256}")))
            return null
        fun digest(value: String) =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.US_ASCII))
        val submitted = digest(token)
        val matches =
            envs.filter { env ->
                if (!enabled(env)) false
                else {
                    val secret =
                        config
                            .getOptionalValue(
                                "notifications.safety.${env.wireValue}.producer-token",
                                String::class.java,
                            )
                            .orElse("")
                    secret.matches(Regex("[!-~]{32,256}")) &&
                        MessageDigest.isEqual(submitted, digest(secret))
                }
            }
        return matches.singleOrNull()
    }
}

@RegisterForReflection data class SafetyAccepted(val eventId: UUID, val accepted: Boolean = true)

@RegisterForReflection
data class OpenSafetySignal(
    val environment: ModerationEnvironment,
    val signalRef: UUID,
    val kind: String = SAFETY_ACTION,
)

@RegisterForReflection
data class SafetyInboxItem(
    val id: UUID,
    val createdAt: Instant,
    val readAt: Instant?,
    val action: OpenSafetySignal,
    val type: String = SAFETY_TYPE,
    val priority: String = "HIGH",
    val title: String = "Confidential safety signal",
    val body: String = "Open the safety workspace to review this signal.",
)

@RegisterForReflection
data class SafetyInboxPage(val items: List<SafetyInboxItem>, val nextCursor: UUID?)

@RegisterForReflection data class SafetyUnreadCount(val unreadCount: Long)
