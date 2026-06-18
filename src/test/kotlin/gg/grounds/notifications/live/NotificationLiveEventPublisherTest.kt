package gg.grounds.notifications.live

import java.time.OffsetDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NotificationLiveEventPublisherTest {
    @Test
    fun `publish records notification live events`() {
        val publisher = RecordingNotificationLiveEventPublisher()
        val event =
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = "user-123",
                notificationId = "notif-123",
                reason = "read",
                occurredAt = OffsetDateTime.parse("2026-06-02T12:34:56.789Z"),
            )

        val accepted = publisher.publish(event)

        assertTrue(accepted)
        assertEquals(listOf(event), publisher.events)
    }

    private class RecordingNotificationLiveEventPublisher : NotificationLiveEventPublisher {
        val events = mutableListOf<NotificationLiveEvent>()

        override fun publish(event: NotificationLiveEvent): Boolean {
            events += event
            return true
        }
    }
}
