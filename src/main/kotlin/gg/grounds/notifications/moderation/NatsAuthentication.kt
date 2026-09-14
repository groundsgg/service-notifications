package gg.grounds.notifications.moderation

import io.nats.client.ErrorListener
import io.nats.client.Options
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.function.Supplier

internal class NatsAuthenticationTokenException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

internal enum class NatsAuthenticationMode(val configValue: String) {
    NONE("none"),
    TOKEN_FILE("token-file"),
    URL_CREDENTIALS("url-credentials");

    companion object {
        fun parse(value: String): NatsAuthenticationMode =
            entries.firstOrNull { it.configValue == value.trim().lowercase() }
                ?: throw IllegalArgumentException(
                    "NATS authentication mode must be one of: none, token-file, url-credentials"
                )
    }
}

internal class NatsTokenFileSupplier(private val tokenFile: Path) : Supplier<CharArray> {
    override fun get(): CharArray {
        val token =
            try {
                Files.readString(tokenFile).trim()
            } catch (_: IOException) {
                throw NatsAuthenticationTokenException("Failed to read NATS authentication token")
            } catch (_: SecurityException) {
                throw NatsAuthenticationTokenException("Failed to read NATS authentication token")
            }
        if (token.isEmpty()) {
            throw NatsAuthenticationTokenException("NATS authentication token file is empty")
        }
        return token.toCharArray()
    }

    fun validate() {
        get().fill('\u0000')
    }
}

internal fun buildNatsConnectionOptions(
    url: String,
    connectionTimeout: Duration,
    reconnectWait: Duration,
    maxReconnects: Int,
    authenticationMode: NatsAuthenticationMode,
    tokenFile: String?,
    allowUnauthenticated: Boolean,
): Options {
    validateNatsAuthenticationConfiguration(
        url,
        authenticationMode,
        tokenFile,
        allowUnauthenticated,
    )
    val builder =
        Options.builder()
            .server(url)
            .connectionName("service-notifications-moderation")
            .connectionTimeout(connectionTimeout)
            .reconnectWait(reconnectWait)
            .maxReconnects(maxReconnects)
            .useTimeoutException()
            .errorListener(object : ErrorListener {})
    if (authenticationMode == NatsAuthenticationMode.TOKEN_FILE) {
        val normalizedTokenFile =
            requireNotNull(tokenFile?.takeIf { it.isNotBlank() }) {
                "NATS token file is required for token-file authentication"
            }
        val supplier =
            try {
                NatsTokenFileSupplier(Path.of(normalizedTokenFile))
            } catch (_: InvalidPathException) {
                throw NatsAuthenticationTokenException("Failed to read NATS authentication token")
            }
        supplier.validate()
        builder.tokenSupplier(supplier)
    }
    return builder.build()
}

internal fun validateNatsAuthenticationConfiguration(
    url: String,
    authenticationMode: NatsAuthenticationMode,
    tokenFile: String?,
    allowUnauthenticated: Boolean,
) {
    val normalizedTokenFile = tokenFile?.takeIf { it.isNotBlank() }
    val uri =
        runCatching { URI(url) }
            .getOrElse { throw IllegalArgumentException("NATS URL must be a valid nats URI") }
    require(uri.scheme == "nats" && !uri.host.isNullOrBlank()) {
        "NATS URL must be a valid nats URI"
    }
    val userInfo = uri.rawUserInfo
    val separator = userInfo?.indexOf(':') ?: -1
    val hasCompleteUrlCredentials =
        userInfo != null && separator > 0 && separator < userInfo.length - 1
    when (authenticationMode) {
        NatsAuthenticationMode.NONE -> {
            require(allowUnauthenticated) {
                "Unauthenticated NATS is not allowed in this runtime profile"
            }
            require(userInfo == null && normalizedTokenFile == null) {
                "NATS credentials must not be configured for unauthenticated mode"
            }
        }
        NatsAuthenticationMode.TOKEN_FILE -> {
            require(userInfo == null) {
                "NATS URL credentials must not be configured for token-file authentication"
            }
            require(normalizedTokenFile != null) {
                "NATS token file is required for token-file authentication"
            }
        }
        NatsAuthenticationMode.URL_CREDENTIALS -> {
            require(normalizedTokenFile == null) {
                "NATS token file must not be configured for URL credentials authentication"
            }
            require(hasCompleteUrlCredentials) {
                "NATS URL credentials must include a username and password"
            }
        }
    }
}
