package gg.grounds.notifications.live

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NatsTokenResolverTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `resolve returns null when no token is configured`() {
        val resolver = NatsTokenResolver(inlineToken = " ", tokenFile = "")

        assertNull(resolver.resolve())
    }

    @Test
    fun `resolve returns trimmed inline token when no token file is configured`() {
        val resolver = NatsTokenResolver(inlineToken = " inline-token\n", tokenFile = "")

        assertArrayEquals("inline-token".toCharArray(), resolver.resolve())
    }

    @Test
    fun `resolve prefers trimmed token file over inline token`() {
        val tokenFile = tempDir.resolve("token")
        Files.writeString(tokenFile, " file-token\n")
        val resolver =
            NatsTokenResolver(inlineToken = "inline-token", tokenFile = tokenFile.toString())

        assertArrayEquals("file-token".toCharArray(), resolver.resolve())
    }

    @Test
    fun `resolve reads token file on every call`() {
        val tokenFile = tempDir.resolve("token")
        Files.writeString(tokenFile, "first-token")
        val resolver = NatsTokenResolver(inlineToken = "", tokenFile = tokenFile.toString())

        assertArrayEquals("first-token".toCharArray(), resolver.resolve())

        Files.writeString(tokenFile, "second-token")

        assertArrayEquals("second-token".toCharArray(), resolver.resolve())
    }

    @Test
    fun `resolve fails closed when configured token file is missing`() {
        val resolver =
            NatsTokenResolver(
                inlineToken = "inline-token",
                tokenFile = tempDir.resolve("missing-token").toString(),
            )

        assertThrows(Exception::class.java) { resolver.resolve() }
    }

    @Test
    fun `resolve fails closed when configured token file is empty`() {
        val tokenFile = tempDir.resolve("token")
        Files.writeString(tokenFile, "\n")
        val resolver =
            NatsTokenResolver(inlineToken = "inline-token", tokenFile = tokenFile.toString())

        assertThrows(IllegalArgumentException::class.java) { resolver.resolve() }
    }
}
