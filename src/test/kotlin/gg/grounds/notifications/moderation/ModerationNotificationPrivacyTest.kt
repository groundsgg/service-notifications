package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModerationNotificationPrivacyTest {
    @Test
    fun `invalid sensitive payload is never reflected by validator error`() {
        val secret = "private-chat-message-58193"
        val payload =
            """{"eventId":"1d0221ed-e1f9-4d14-8ccd-3309d2759c20","eventType":"moderation.report.ready-for-review","schemaVersion":1,"occurredAt":"2026-07-22T10:15:30Z","data":{"chatText":"$secret"}}"""
                .toByteArray()

        val error =
            assertThrows(InvalidModerationEventException::class.java) {
                ModerationEventValidator(ObjectMapper())
                    .validate(ModerationEventSubjects.REPORT_READY_FOR_REVIEW, payload)
            }

        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.stackTraceToString().contains(secret))
    }

    @Test
    fun `canonical notification copy contains no report evidence or player detail`() {
        val rendered =
            listOf(
                    ModerationNotificationContent.TYPE,
                    ModerationNotificationContent.CATEGORY,
                    ModerationNotificationContent.TITLE,
                    ModerationNotificationContent.BODY,
                )
                .joinToString(" ")
                .lowercase()

        listOf("player", "reporter", "chat", "evidence", "reason", "public reference").forEach {
            forbidden ->
            assertFalse(rendered.contains(forbidden))
        }
    }
}
