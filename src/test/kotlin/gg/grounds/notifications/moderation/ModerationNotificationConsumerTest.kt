package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModerationNotificationConsumerTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules().registerKotlinModule()
    private val fixture = resource("moderation/moderation.report.ready-for-review.json")

    @Test
    fun `acks only after successful handler completion including duplicate delivery`() {
        val calls = mutableListOf<String>()
        val processor =
            ModerationMessageProcessor(ModerationEventValidator(objectMapper)) {
                calls += "handle:${it.eventId}"
            }
        val first = RecordingMessage(fixture, calls)
        val duplicate = RecordingMessage(fixture, calls)

        processor.process(first)
        processor.process(duplicate)

        assertEquals(
            listOf(
                "handle:1d0221ed-e1f9-4d14-8ccd-3309d2759c20",
                "ack",
                "handle:1d0221ed-e1f9-4d14-8ccd-3309d2759c20",
                "ack",
            ),
            calls,
        )
    }

    @Test
    fun `naks retryable handler failure without ack`() {
        val calls = mutableListOf<String>()
        val processor =
            ModerationMessageProcessor(ModerationEventValidator(objectMapper)) {
                throw RetryableModerationNotificationException("temporary")
            }
        val message = RecordingMessage(fixture, calls)

        processor.process(message)

        assertEquals(listOf("nak"), calls)
    }

    @Test
    fun `terminates invalid envelope without invoking handler`() {
        val calls = mutableListOf<String>()
        val processor =
            ModerationMessageProcessor(ModerationEventValidator(objectMapper)) { calls += "handle" }
        val message = RecordingMessage("{}".toByteArray(), calls)

        processor.process(message)

        assertEquals(listOf("term"), calls)
    }

    private class RecordingMessage(
        override val data: ByteArray,
        private val calls: MutableList<String>,
        override val subject: String = ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
    ) : ModerationIncomingMessage {
        override fun ack() {
            calls += "ack"
        }

        override fun nak() {
            calls += "nak"
        }

        override fun term() {
            calls += "term"
        }
    }

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing $path" }
            .use { it.readAllBytes() }
}
