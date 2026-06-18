package gg.grounds.notifications.live

import java.nio.file.Files
import java.nio.file.Path

class NatsTokenResolver(private val inlineToken: String, private val tokenFile: String) {
    fun resolve(): CharArray? {
        if (tokenFile.isNotBlank()) {
            val token = Files.readString(Path.of(tokenFile)).trim()
            require(token.isNotBlank()) {
                "Configured notifications.nats.token-file is empty: $tokenFile"
            }
            return token.toCharArray()
        }
        return inlineToken.trim().takeIf { it.isNotBlank() }?.toCharArray()
    }
}
