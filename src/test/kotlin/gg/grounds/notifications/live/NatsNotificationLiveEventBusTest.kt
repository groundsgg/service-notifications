package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class NatsNotificationLiveEventBusTest {
    private val objectMapper =
        ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @Test
    fun `publish sends notification live event to NATS subject as JSON`() {
        val client = RecordingNatsClient()
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, client)
        val event = notificationLiveEvent()

        bus.publish(event)

        assertEquals(1, client.published.size)
        val published = client.published.single()
        assertEquals("grounds.internal.notifications.changed", published.subject)
        assertArrayEquals(
            objectMapper.writeValueAsBytes(event),
            published.payload,
        )
    }

    @Test
    fun `publish does not propagate NATS client failures`() {
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, FailingNatsClient())

        assertDoesNotThrow { bus.publish(notificationLiveEvent()) }
    }

    @Test
    fun `publish uses client initialized by scheduled reconnect after earlier connection failure`() {
        val client = RecordingNatsClient()
        val factory = FailsOnceNatsClientFactory(client)
        val bus = NatsNotificationLiveEventBus.ForTests(objectMapper, factory)
        val event = notificationLiveEvent()

        assertEquals(1, factory.attempts)

        bus.connectIfNeeded()
        assertDoesNotThrow { bus.publish(event) }

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

        assertDoesNotThrow { bus.publish(notificationLiveEvent()) }
        assertDoesNotThrow { bus.publish(notificationLiveEvent()) }

        assertEquals(1, factory.attempts)
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
        bus.publish(event)

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
        assertDoesNotThrow { bus.publish(notificationLiveEvent()) }

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

    private class RecordingNatsClient(
        override val isActive: Boolean = true,
    ) : NatsNotificationLiveEventBus.NatsClient {
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
        private val client: NatsNotificationLiveEventBus.NatsClient,
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

    private class SequenceNatsClientFactory(
        private vararg val clients: NatsNotificationLiveEventBus.NatsClient,
    ) : NatsNotificationLiveEventBus.NatsClientFactory {
        override val enabled = true

        var attempts = 0
            private set

        override fun create(): NatsNotificationLiveEventBus.NatsClient? {
            return clients[attempts++]
        }
    }

    private data class PublishedMessage(val subject: String, val payload: ByteArray)
}
