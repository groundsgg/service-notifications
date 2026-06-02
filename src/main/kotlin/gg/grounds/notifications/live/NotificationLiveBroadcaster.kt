package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import jakarta.ws.rs.sse.SseEventSink
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import org.jboss.logging.Logger

@ApplicationScoped
class NotificationLiveBroadcaster {
    @Inject lateinit var sse: Sse

    @Inject lateinit var objectMapper: ObjectMapper

    private val sinks = ConcurrentHashMap<String, MutableSet<SseEventSink>>()

    fun register(userId: String, sink: SseEventSink) {
        sinks.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(sink)
        LOG.infof(
            "Registered notification live stream (userId=%s, listenerCount=%d)",
            userId,
            listenerCount(userId),
        )
    }

    fun publish(event: NotificationLiveEvent) {
        val listeners = sinks[event.userId] ?: return
        val outbound = buildEvent(event)
        val failed = mutableListOf<SseEventSink>()
        listeners.forEach { sink ->
            try {
                sink.send(outbound)
            } catch (exception: Exception) {
                failed += sink
                runCatching { sink.close() }
            }
        }
        if (failed.isNotEmpty()) {
            listeners.removeAll(failed.toSet())
            if (listeners.isEmpty()) sinks.remove(event.userId)
        }
    }

    @Scheduled(every = "15s")
    fun heartbeat() {
        val now = OffsetDateTime.now()
        sinks.keys.forEach { userId ->
            publish(
                NotificationLiveEvent(
                    type = "notifications.heartbeat",
                    userId = userId,
                    notificationId = "",
                    reason = "heartbeat",
                    occurredAt = now,
                )
            )
        }
    }

    fun listenerCount(userId: String): Int = sinks[userId]?.size ?: 0

    private fun buildEvent(event: NotificationLiveEvent): OutboundSseEvent {
        val json = objectMapper.writeValueAsString(event)
        return sse.newEventBuilder()
            .name(event.type)
            .mediaType(MediaType.APPLICATION_JSON_TYPE)
            .data(String::class.java, json)
            .build()
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(NotificationLiveBroadcaster::class.java)
    }
}
