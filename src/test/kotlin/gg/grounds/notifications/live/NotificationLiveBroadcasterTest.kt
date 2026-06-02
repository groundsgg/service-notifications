package gg.grounds.notifications.live

import jakarta.inject.Inject
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.SseEventSink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import io.quarkus.test.junit.QuarkusTest

@QuarkusTest
class NotificationLiveBroadcasterTest {
    @Inject lateinit var broadcaster: NotificationLiveBroadcaster

    @Test
    fun `publishes changed events to registered listeners`() {
        val sink = RecordingSseEventSink()

        broadcaster.register("user-123", sink)
        broadcaster.publish(
            NotificationLiveEvent(
                type = "notifications.changed",
                userId = "user-123",
                notificationId = "notif-123",
                reason = "read",
                occurredAt = java.time.OffsetDateTime.parse("2026-06-02T12:34:56.789Z"),
            ),
        )

        assertEquals(1, sink.events.size)
        val event = sink.events.single()
        assertEquals("notifications.changed", event.getName())
        assertEquals(
            "{\"type\":\"notifications.changed\",\"userId\":\"user-123\",\"notificationId\":\"notif-123\",\"reason\":\"read\",\"occurredAt\":\"2026-06-02T12:34:56.789Z\"}",
            event.getData(),
        )
    }

    private class RecordingSseEventSink : SseEventSink {
        val events = mutableListOf<OutboundSseEvent>()

        override fun isClosed(): Boolean = false

        override fun send(event: OutboundSseEvent): CompletionStage<*> {
            events += event
            return CompletableFuture.completedFuture(null)
        }

        override fun close() = Unit
    }
}
