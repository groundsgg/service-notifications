package gg.grounds.notifications.moderation

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

enum class ModerationRetryReason(val tag: String) {
    PROJECTION("projection")
}

enum class ModerationFailureReason(val tag: String) {
    INVALID_EVENT("invalid_event"),
    TERMINAL_PROJECTION("terminal_projection"),
}

@ApplicationScoped
class ModerationNotificationMetrics(private val registry: MeterRegistry) {
    private val consumerLag = AtomicLong()
    private val deduplicated =
        Counter.builder("notifications.moderation.deduplicated")
            .description("Moderation events deduplicated by the canonical projection")
            .register(registry)
    private val captureToNotification =
        Timer.builder("notifications.moderation.capture_to_notification")
            .description("Time from terminal evidence capture event to notification projection")
            .register(registry)

    init {
        Gauge.builder("notifications.moderation.consumer.lag", consumerLag) { it.get().toDouble() }
            .description("Pending moderation ready events reported by JetStream")
            .register(registry)
    }

    fun updateConsumerLag(value: Long) {
        consumerLag.set(value.coerceAtLeast(0))
    }

    fun recordProjection(status: ModerationProjectionStatus, latency: Duration) {
        Counter.builder("notifications.moderation.projection")
            .description("Canonical moderation notification projection results")
            .tag("result", status.name.lowercase())
            .register(registry)
            .increment()
        if (status == ModerationProjectionStatus.DUPLICATE) deduplicated.increment()
        if (
            status == ModerationProjectionStatus.CREATED ||
                status == ModerationProjectionStatus.UPDATED
        ) {
            captureToNotification.record(if (latency.isNegative) Duration.ZERO else latency)
        }
    }

    fun recordRetry(reason: ModerationRetryReason) {
        Counter.builder("notifications.moderation.retry")
            .description("Moderation notification event retries")
            .tag("reason", reason.tag)
            .register(registry)
            .increment()
    }

    fun recordFailure(reason: ModerationFailureReason) {
        Counter.builder("notifications.moderation.failure")
            .description("Terminal moderation notification event failures")
            .tag("reason", reason.tag)
            .register(registry)
            .increment()
    }
}
