package gg.grounds.notifications.moderation

import gg.grounds.notifications.audience.ForgeAudienceResolver
import gg.grounds.notifications.audience.ForgeAudienceSnapshot
import gg.grounds.notifications.audience.RetryableForgeAudienceException
import gg.grounds.notifications.audience.TerminalForgeAudienceException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModerationAudienceFailureMetricsTest {
    @Test
    fun `resolver failures retain classification and emit only bounded result labels`() {
        for ((result, cause) in
            listOf(
                "retryable" to RetryableForgeAudienceException("private-token-and-response"),
                "terminal" to TerminalForgeAudienceException("private-token-and-response"),
            )) {
            val registry = SimpleMeterRegistry()
            val metrics = ModerationNotificationMetrics(registry)
            val projector =
                ModerationNotificationProjector(
                    ForgeAudienceResolver { throw cause },
                    ModerationProjectionStore { _, _ -> error("Must not write a projection") },
                    metrics,
                )
            val expected =
                if (result == "retryable") RetryableModerationNotificationException::class.java
                else TerminalModerationNotificationException::class.java
            val exception = assertThrows(expected) { projector.handle(event()) }
            assertEquals(cause, exception.cause)
            assertEquals(
                1.0,
                registry
                    .find("notifications.moderation.audience.resolution.failures")
                    .tag("result", result)
                    .counter()
                    ?.count(),
            )
            val meters =
                registry.meters.filter {
                    it.id.name == "notifications.moderation.audience.resolution.failures"
                }
            assertEquals(
                setOf("retryable", "terminal"),
                meters.map { it.id.getTag("result") }.toSet(),
            )
            meters.forEach { assertEquals(listOf("result"), it.id.tags.map { tag -> tag.key }) }
        }
    }

    @Test
    fun `resolver failure counters start at zero before any request`() {
        val registry = SimpleMeterRegistry()
        ModerationNotificationMetrics(registry)
        for (result in listOf("retryable", "terminal")) {
            assertEquals(
                0.0,
                registry
                    .find("notifications.moderation.audience.resolution.failures")
                    .tag("result", result)
                    .counter()
                    ?.count(),
            )
        }
    }

    @Test
    fun `successful resolution and unrelated store failure do not count as resolver failures`() {
        for (storeFails in listOf(false, true)) {
            val registry = SimpleMeterRegistry()
            val metrics = ModerationNotificationMetrics(registry)
            val failure = IllegalStateException("unrelated store failure")
            val projector =
                ModerationNotificationProjector(
                    ForgeAudienceResolver {
                        ForgeAudienceSnapshot(emptyList(), Instant.now(), "a".repeat(64))
                    },
                    ModerationProjectionStore { _, _ ->
                        if (storeFails) throw failure
                        ModerationProjectionResult(
                            UUID.randomUUID(),
                            ModerationProjectionStatus.CREATED,
                        )
                    },
                    metrics,
                )
            if (storeFails)
                assertEquals(
                    failure,
                    assertThrows(IllegalStateException::class.java) { projector.handle(event()) },
                )
            else projector.handle(event())
            for (result in listOf("retryable", "terminal")) {
                assertEquals(
                    0.0,
                    registry
                        .get("notifications.moderation.audience.resolution.failures")
                        .tag("result", result)
                        .counter()
                        .count(),
                )
            }
        }
    }

    private fun event() =
        ReportReadyForReviewEvent(
            UUID.randomUUID(),
            ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
            1,
            Instant.now(),
            ReportReadyForReviewData(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CaptureStatus.COMPLETE,
                1,
            ),
        )
}
