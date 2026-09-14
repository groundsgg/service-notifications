package gg.grounds.notifications.moderation

import io.nats.client.api.AckPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NatsAuthenticationTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `consumer configuration is durable filtered pull with explicit ack`() {
        val configuration = moderationConsumerConfiguration("notifications-ready-v1")

        assertEquals("notifications-ready-v1", configuration.durable)
        assertEquals(AckPolicy.Explicit, configuration.ackPolicy)
        assertEquals(ModerationEventSubjects.REPORT_READY_FOR_REVIEW, configuration.filterSubject)
        assertNull(configuration.deliverSubject)
    }

    @Test
    fun `rejects incomplete or mixed authentication configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateNatsAuthenticationConfiguration(
                "nats://nats:4222",
                NatsAuthenticationMode.NONE,
                null,
                false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateNatsAuthenticationConfiguration(
                "nats://user:password@nats:4222",
                NatsAuthenticationMode.TOKEN_FILE,
                "/token",
                false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateNatsAuthenticationConfiguration(
                "nats://user@nats:4222",
                NatsAuthenticationMode.URL_CREDENTIALS,
                null,
                false,
            )
        }
    }

    @Test
    fun `token supplier rereads rotated token file`() {
        val tokenFile = tempDir.resolve("nats-token")
        Files.writeString(tokenFile, "first-token\n")
        val supplier = NatsTokenFileSupplier(tokenFile)

        assertEquals("first-token", String(supplier.get()))
        Files.writeString(tokenFile, "rotated-token")
        assertEquals("rotated-token", String(supplier.get()))
    }

    @Test
    fun `builds supported authenticated connection options`() {
        val options =
            buildNatsConnectionOptions(
                url = "nats://user:password@nats:4222",
                connectionTimeout = Duration.ofSeconds(2),
                reconnectWait = Duration.ofSeconds(1),
                maxReconnects = 10,
                authenticationMode = NatsAuthenticationMode.URL_CREDENTIALS,
                tokenFile = null,
                allowUnauthenticated = false,
            )

        assertEquals("service-notifications-moderation", options.connectionName)
    }
}
