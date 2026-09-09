package gg.grounds.notifications.channel

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.arc.profile.IfBuildProfile
import io.quarkus.arc.profile.UnlessBuildProfile
import jakarta.enterprise.context.ApplicationScoped
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.microprofile.config.inject.ConfigProperty

interface MinecraftPlayerResolver {
    fun resolveUserIds(playerUuids: List<String>): Map<String, String>

    fun resolveUserId(playerUuid: String): String? = resolveUserIds(listOf(playerUuid))[playerUuid]
}

class ForgeMinecraftIdentityException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

@ApplicationScoped
@UnlessBuildProfile("test")
class ForgeMinecraftPlayerResolver(
    @ConfigProperty(name = "notifications.forge.base-url") baseUrl: String,
    @param:ConfigProperty(name = "notifications.forge.internal-token")
    private val internalToken: String,
    @ConfigProperty(name = "notifications.forge.connect-timeout", defaultValue = "PT2S")
    connectTimeout: Duration,
    @param:ConfigProperty(name = "notifications.forge.read-timeout", defaultValue = "PT3S")
    private val readTimeout: Duration,
    private val objectMapper: ObjectMapper,
) : MinecraftPlayerResolver {
    private val endpoint =
        runCatching {
                URI.create(baseUrl.trimEnd('/') + "/v1/internal/minecraft-identities/resolve")
            }
            .getOrElse { throw IllegalArgumentException("Forge base URL is invalid", it) }
    private val httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build()

    init {
        require(endpoint.scheme in setOf("http", "https")) { "Forge base URL must use HTTP" }
        require(internalToken.isNotBlank()) { "Forge internal token is required" }
        require(connectTimeout > Duration.ZERO && readTimeout > Duration.ZERO) {
            "Forge identity timeouts must be positive"
        }
    }

    override fun resolveUserIds(playerUuids: List<String>): Map<String, String> {
        val requested = playerUuids.distinct().sorted()
        require(requested.size == playerUuids.size && requested.size <= MAX_PLAYER_UUIDS) {
            "Minecraft identity batch must be unique and bounded"
        }
        requested.forEach { requireCanonicalUuid(it) }
        if (requested.isEmpty()) return emptyMap()
        val payload =
            objectMapper.createObjectNode().apply {
                set<JsonNode>(
                    "playerUuids",
                    objectMapper.createArrayNode().apply { requested.forEach { add(it) } },
                )
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
                throw ForgeMinecraftIdentityException(
                    "Forge Minecraft identity request was interrupted",
                    exception,
                )
            } catch (exception: Exception) {
                throw ForgeMinecraftIdentityException(
                    "Forge Minecraft identity request failed",
                    exception,
                )
            }
        if (response.statusCode() !in 200..299) {
            throw ForgeMinecraftIdentityException("Forge Minecraft identity request was rejected")
        }
        return parseMappings(response.body(), requested.toSet())
    }

    private fun parseMappings(body: String, requested: Set<String>): Map<String, String> {
        if (body.length > MAX_RESPONSE_LENGTH) invalidContract()
        val root = runCatching { objectMapper.readTree(body) }.getOrElse { invalidContract(it) }
        if (!root.isObject || root.fieldNames().asSequence().toSet() != ROOT_FIELDS) {
            invalidContract()
        }
        val mappings = root.path("mappings")
        if (!mappings.isArray) invalidContract()
        val entries =
            mappings.map { mapping ->
                if (
                    !mapping.isObject ||
                        mapping.fieldNames().asSequence().toSet() != MAPPING_FIELDS ||
                        !mapping.path("playerUuid").isTextual ||
                        !mapping.path("userId").isTextual
                ) {
                    invalidContract()
                }
                val playerUuid = mapping.path("playerUuid").asText()
                val userId = mapping.path("userId").asText()
                requireCanonicalUuid(playerUuid, contractError = true)
                if (
                    playerUuid !in requested ||
                        userId.isBlank() ||
                        userId != userId.trim() ||
                        userId.length > MAX_USER_ID_LENGTH
                ) {
                    invalidContract()
                }
                playerUuid to userId
            }
        if (
            entries.map { it.first } != entries.map { it.first }.sorted() ||
                entries.map { it.first }.distinct().size != entries.size
        ) {
            invalidContract()
        }
        return entries.toMap()
    }

    private fun requireCanonicalUuid(value: String, contractError: Boolean = false) {
        val valid =
            UUID_PATTERN.matches(value) &&
                runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        if (!valid && contractError) invalidContract()
        require(valid) { "Minecraft player UUID must be canonical" }
    }

    private fun invalidContract(cause: Throwable? = null): Nothing =
        throw ForgeMinecraftIdentityException(
            "Forge Minecraft identity response contract is invalid",
            cause,
        )

    private companion object {
        const val MAX_PLAYER_UUIDS = 100
        const val MAX_RESPONSE_LENGTH = 1_048_576
        const val MAX_USER_ID_LENGTH = 255
        val ROOT_FIELDS = setOf("mappings")
        val MAPPING_FIELDS = setOf("playerUuid", "userId")
        val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

@ApplicationScoped
@IfBuildProfile("test")
class InMemoryMinecraftPlayerResolver : MinecraftPlayerResolver {
    private val mappings = ConcurrentHashMap<String, String>()

    override fun resolveUserIds(playerUuids: List<String>): Map<String, String> =
        playerUuids.mapNotNull { uuid -> mappings[uuid]?.let { uuid to it } }.toMap()

    fun mapPlayer(playerUuid: String, userId: String) {
        mappings[playerUuid] = userId
    }

    fun clear() {
        mappings.clear()
    }
}
