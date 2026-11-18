package gg.grounds.notifications.safety

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.audience.ForgeAudienceClient
import gg.grounds.notifications.moderation.ModerationEnvironment
import jakarta.enterprise.context.ApplicationScoped
import java.time.Instant
import java.util.UUID

internal data class SafetyAudience(val recipients: Set<UUID>, val resolvedAt: Instant) {
    fun fresh(now: Instant = Instant.now()) =
        !resolvedAt.isAfter(now) && resolvedAt.plusSeconds(5).isAfter(now)
}

@ApplicationScoped
class SafetyAudienceAuthority(
    private val forge: ForgeAudienceClient,
    private val mapper: ObjectMapper,
) {
    internal fun resolve(env: ModerationEnvironment, user: UUID?): SafetyAudience? {
        return try {
            val nonce = UUID.randomUUID()
            val request =
                mapper.createObjectNode().apply {
                    put("schemaVersion", 1)
                    put("environment", env.wireValue)
                    put("nonce", nonce.toString())
                    if (user == null) putNull("userId") else put("userId", user.toString())
                }
            val body = forge.requestSafetyAudience(mapper.writeValueAsBytes(request))
            val root =
                strictObject(
                    mapper,
                    body,
                    setOf("schemaVersion", "environment", "nonce", "resolvedAt", "recipients"),
                )
            require(
                version(root) &&
                    root.path("environment").textValue() == env.wireValue &&
                    root.path("nonce").textValue() == nonce.toString()
            )
            val recipients = root.path("recipients")
            require(recipients.isArray && recipients.size() <= 1000)
            val ids =
                recipients
                    .map { row ->
                        require(row.isObject && row.size() == 1)
                        requireNotNull(uuid(row.path("userId").textValue()))
                    }
                    .toSet()
            require(user == null || ids.all { it == user })
            SafetyAudience(ids, requireNotNull(utc(root["resolvedAt"]))).takeIf { it.fresh() }
        } catch (_: Exception) {
            null
        }
    }
}
