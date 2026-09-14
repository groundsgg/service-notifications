package gg.grounds.notifications.moderation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
import org.eclipse.microprofile.health.HealthCheckResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ModerationNotificationMetricsTest {
    @Test
    fun `records bounded projection retry failure lag and latency metrics`() {
        val registry = SimpleMeterRegistry()
        val metrics = ModerationNotificationMetrics(registry)

        metrics.updateConsumerLag(7)
        metrics.recordProjection(ModerationProjectionStatus.CREATED, Duration.ofMillis(250))
        metrics.recordProjection(ModerationProjectionStatus.DUPLICATE, Duration.ZERO)
        metrics.recordRetry(ModerationRetryReason.PROJECTION)
        metrics.recordFailure(ModerationFailureReason.INVALID_EVENT)

        assertEquals(7.0, registry.get("notifications.moderation.consumer.lag").gauge().value())
        assertEquals(
            1.0,
            registry
                .get("notifications.moderation.projection")
                .tag("result", "created")
                .counter()
                .count(),
        )
        assertEquals(1.0, registry.get("notifications.moderation.deduplicated").counter().count())
        assertEquals(
            1.0,
            registry
                .get("notifications.moderation.retry")
                .tag("reason", "projection")
                .counter()
                .count(),
        )
        assertEquals(
            1.0,
            registry
                .get("notifications.moderation.failure")
                .tag("reason", "invalid_event")
                .counter()
                .count(),
        )
        assertEquals(
            250.0,
            registry
                .get("notifications.moderation.capture_to_notification")
                .timer()
                .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        registry.meters
            .flatMap { it.id.tags }
            .forEach { tag -> assertFalse(tag.value.contains("1d0221ed")) }
    }

    @Test
    fun `transient disconnect is visible but does not fail readiness`() {
        val state = ModerationConsumerState(enabled = true)
        state.disconnected(consumerLag = 12)

        val response = ModerationNotificationReadiness(state).call()

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        assertEquals("degraded", response.data.orElseThrow()["state"])
        assertEquals(12L, response.data.orElseThrow()["consumerLag"])
    }
}
