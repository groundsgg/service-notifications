package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.time.OffsetDateTime
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NatsNotificationLiveEventBusTest {
    private val objectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Test
    fun `publish sends notification live event to NATS subject as JSON`() {
        val client = RecordingNatsClient()
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, client)
        val event = notificationLiveEvent()

        val accepted = bus.publish(event)

        assertTrue(accepted)
        assertEquals(1, client.published.size)
        val published = client.published.single()
        assertEquals("grounds.internal.notifications.changed", published.subject)
        assertArrayEquals(objectMapper.writeValueAsBytes(event), published.payload)
    }

    @Test
    fun `publish does not propagate NATS client failures`() {
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, FailingNatsClient())

        assertDoesNotThrow { assertFalse(bus.publish(notificationLiveEvent())) }
    }

    @Test
    fun `publish uses client initialized by scheduled reconnect after earlier connection failure`() {
        val client = RecordingNatsClient()
        val factory = FailsOnceNatsClientFactory(client)
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)
        val event = notificationLiveEvent()

        assertEquals(1, factory.attempts)

        bus.connectIfNeeded()
        assertDoesNotThrow { assertTrue(bus.publish(event)) }

        assertEquals(2, factory.attempts)
        assertEquals(1, client.published.size)
        assertEquals("grounds.internal.notifications.changed", client.published.single().subject)
    }

    @Test
    fun `scheduled reconnect retries client initialization without local publish`() {
        val client = RecordingNatsClient()
        val factory = FailsOnceNatsClientFactory(client)
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)

        assertEquals(1, factory.attempts)

        bus.connectIfNeeded()

        assertEquals(2, factory.attempts)
        assertEquals(0, client.published.size)
    }

    @Test
    fun `publish does not repeatedly retry connection after recent connection failure`() {
        val factory = AlwaysFailingNatsClientFactory()
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)

        assertEquals(1, factory.attempts)

        assertDoesNotThrow { assertFalse(bus.publish(notificationLiveEvent())) }
        assertDoesNotThrow { assertFalse(bus.publish(notificationLiveEvent())) }

        assertEquals(1, factory.attempts)
    }

    @Test
    fun `connection failure log does not expose NATS credentials`() {
        val credential = "test-nats-password"
        val records =
            captureLogs(NatsNotificationLiveEventBus::class.java.name) {
                NatsNotificationLiveEventBus.ForTests(
                    objectMapper,
                    CredentialBearingFailureFactory(credential),
                )
            }

        val failure =
            records.single {
                it.message.startsWith("Failed to initialize notification NATS live event bus")
            }
        val renderedFailure = failure.message + (failure.thrown?.message ?: "")
        assertFalse(renderedFailure.contains(credential))
        assertNull(failure.thrown)
        assertEquals(Level.SEVERE, failure.level)
        assertEquals(
            "Failed to initialize notification NATS live event bus (reason=IOException)",
            failure.message,
        )
    }

    @Test
    fun `publish returns false when NATS client factory is disabled`() {
        val factory = DisabledNatsClientFactory()
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)

        assertDoesNotThrow { assertFalse(bus.publish(notificationLiveEvent())) }

        assertEquals(0, factory.attempts)
    }

    @Test
    fun `forced reconnect replaces inactive existing client`() {
        val inactiveClient = RecordingNatsClient(isActive = false)
        val activeClient = RecordingNatsClient()
        val factory = SequenceNatsClientFactory(inactiveClient, activeClient)
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)
        val event = notificationLiveEvent()

        assertEquals(1, factory.attempts)

        bus.connectIfNeeded()
        val accepted = bus.publish(event)

        assertTrue(accepted)
        assertEquals(2, factory.attempts)
        assertEquals(1, inactiveClient.closeCount)
        assertEquals(0, inactiveClient.published.size)
        assertEquals(1, activeClient.published.size)
    }

    @Test
    fun `close prevents later reconnect`() {
        val client = RecordingNatsClient()
        val replacementClient = RecordingNatsClient()
        val factory = SequenceNatsClientFactory(client, replacementClient)
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)

        assertEquals(1, factory.attempts)

        bus.close()
        bus.connectIfNeeded()
        assertDoesNotThrow { assertFalse(bus.publish(notificationLiveEvent())) }

        assertEquals(1, factory.attempts)
        assertEquals(1, client.closeCount)
        assertEquals(0, client.published.size)
        assertEquals(0, replacementClient.published.size)
    }

    private fun notificationLiveEvent() =
        NotificationLiveEvent(
            type = "notifications.changed",
            userId = "user-123",
            notificationId = "notif-123",
            reason = "read",
            occurredAt = OffsetDateTime.parse("2026-06-02T12:34:56.789Z"),
        )

    private class RecordingNatsClient(override val isActive: Boolean = true) :
        NatsNotificationLiveEventBus.NatsClient {
        val published = mutableListOf<PublishedMessage>()
        var closeCount = 0
            private set

        override fun publish(subject: String, payload: ByteArray) {
            published += PublishedMessage(subject, payload)
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FailingNatsClient : NatsNotificationLiveEventBus.NatsClient {
        override val isActive = true

        override fun publish(subject: String, payload: ByteArray) {
            throw IllegalStateException("nats unavailable")
        }

        override fun close() = Unit
    }

    private class FailsOnceNatsClientFactory(
        private val client: NatsNotificationLiveEventBus.NatsClient
    ) : NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = true

        var attempts = 0
            private set

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            attempts += 1
            if (attempts == 1) {
                throw IllegalStateException("nats unavailable")
            }
            return client
        }
    }

    private class AlwaysFailingNatsClientFactory : NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = true

        var attempts = 0
            private set

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            attempts += 1
            throw IllegalStateException("nats unavailable")
        }
    }

    private class DisabledNatsClientFactory : NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = false

        var attempts = 0
            private set

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            attempts += 1
            return null
        }
    }

    private class CredentialBearingFailureFactory(private val credential: String) :
        NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = true

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            throw IOException(
                "Unable to connect to NATS server nats://service-notifications:$credential@nats.example:4222"
            )
        }
    }

    private class SequenceNatsClientFactory(
        private vararg val clients: NatsNotificationLiveEventBus.NatsClient
    ) : NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = true

        var attempts = 0
            private set

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            return clients[attempts++]
        }
    }

    private data class PublishedMessage(val subject: String, val payload: ByteArray)

    private fun captureLogs(loggerName: String, block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val logger = Logger.getLogger(loggerName)
        val previousLevel = logger.level
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        handler.level = Level.ALL
        logger.level = Level.ALL
        logger.addHandler(handler)
        return try {
            block()
            records
        } finally {
            logger.removeHandler(handler)
            logger.level = previousLevel
        }
    }
}
