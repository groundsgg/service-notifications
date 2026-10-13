package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvironmentModerationConsumerTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val fixture =
        javaClass.classLoader
            .getResourceAsStream("moderation/v2/moderation.report.ready-for-review.json")!!
            .use { it.readAllBytes() }

    @Test
    fun `disabled environments never open a subscription`() {
        val runtime = EnvironmentModerationNotificationRuntime(false, false)
        val remote = RecordingSubscriber()
        val state = EnvironmentModerationConsumerState(runtime)
        val consumer =
            EnvironmentModerationNotificationConsumer(
                mapper,
                ModerationReadyEventHandler {},
                remote,
                runtime,
                state,
            )
        consumer.start(StartupEvent())
        consumer.reconnect()
        assertEquals(emptyList<ModerationEnvironment>(), remote.attempts)
    }

    @Test
    fun `stage only subscribes to its exact stream subject and durable`() {
        val runtime = EnvironmentModerationNotificationRuntime(true, false)
        val remote = RecordingSubscriber()
        val state = EnvironmentModerationConsumerState(runtime)
        val consumer =
            EnvironmentModerationNotificationConsumer(
                mapper,
                ModerationReadyEventHandler {},
                remote,
                runtime,
                state,
            )
        consumer.start(StartupEvent())
        assertEquals(listOf(ModerationEnvironment.STAGE), remote.attempts)
        val binding = runtime.bindings.single()
        assertEquals("MODERATION_READY_STAGE_V2", binding.streamName)
        assertEquals("service-notifications-moderation-ready-stage-v2", binding.consumerName)
        assertEquals(
            "moderation-v2.stage.report.ready-for-review",
            environmentModerationConsumerConfiguration(binding).filterSubject,
        )
        assertTrue(state.snapshot(ModerationEnvironment.STAGE).connected)
        assertFalse(state.snapshot(ModerationEnvironment.PROD).enabled)
        consumer.close()
        assertFalse(state.snapshot(ModerationEnvironment.STAGE).connected)
    }

    @Test
    fun `one stream failure does not disable the other environment and reconnect retains its durable`() {
        val runtime = EnvironmentModerationNotificationRuntime(true, true)
        val remote = RecordingSubscriber().apply { failStage = true }
        val state = EnvironmentModerationConsumerState(runtime)
        val consumer =
            EnvironmentModerationNotificationConsumer(
                mapper,
                ModerationReadyEventHandler {},
                remote,
                runtime,
                state,
            )
        consumer.start(StartupEvent())
        assertFalse(state.snapshot(ModerationEnvironment.STAGE).connected)
        assertTrue(state.snapshot(ModerationEnvironment.PROD).connected)
        remote.failStage = false
        consumer.reconnect()
        assertEquals(
            listOf(
                ModerationEnvironment.STAGE,
                ModerationEnvironment.PROD,
                ModerationEnvironment.STAGE,
            ),
            remote.attempts,
        )
        assertTrue(state.snapshot(ModerationEnvironment.STAGE).connected)
        assertTrue(state.snapshot(ModerationEnvironment.PROD).connected)
        consumer.close()
    }

    @Test
    fun `stage handler commits before ack and rejects a valid prod event`() {
        val calls = mutableListOf<String>()
        val processor =
            ModerationMessageProcessor(
                ModerationEventValidator(mapper),
                expectedEnvironment = ModerationEnvironment.STAGE,
            ) {
                calls += "commit"
            }
        processor.process(
            RecordingMessage("moderation-v2.stage.report.ready-for-review", fixture, calls)
        )
        assertEquals(listOf("commit", "ack"), calls)
        calls.clear()
        val prod = mapper.readTree(fixture)
        (prod.path("data") as com.fasterxml.jackson.databind.node.ObjectNode).put(
            "environment",
            "prod",
        )
        processor.process(
            RecordingMessage(
                "moderation-v2.prod.report.ready-for-review",
                mapper.writeValueAsBytes(prod),
                calls,
            )
        )
        assertEquals(listOf("term"), calls)
    }

    @Test
    fun `failed stage commit is retried without ack and legacy handler never accepts v2`() {
        val calls = mutableListOf<String>()
        val processor =
            ModerationMessageProcessor(
                ModerationEventValidator(mapper),
                expectedEnvironment = ModerationEnvironment.STAGE,
            ) {
                throw RetryableModerationNotificationException("fixture")
            }
        processor.process(
            RecordingMessage("moderation-v2.stage.report.ready-for-review", fixture, calls)
        )
        assertEquals(listOf("nak"), calls)
        calls.clear()
        ModerationMessageProcessor(ModerationEventValidator(mapper)) { calls += "legacy" }
            .process(
                RecordingMessage("moderation-v2.stage.report.ready-for-review", fixture, calls)
            )
        assertEquals(listOf("term"), calls)
    }

    @Test
    fun `readiness preserves independent connection states and reports combined pending work`() {
        val state =
            EnvironmentModerationConsumerState(EnvironmentModerationNotificationRuntime(true, true))
        state.connected(ModerationEnvironment.STAGE)
        state.updateLag(ModerationEnvironment.STAGE, 3)
        state.updateLag(ModerationEnvironment.PROD, 7)
        assertEquals(10, state.totalLag())
        val data = EnvironmentModerationNotificationReadiness(state).call().data.get()
        assertEquals("connected", data["stage.state"])
        assertEquals("degraded", data["prod.state"])
    }

    private class RecordingMessage(
        override val subject: String,
        override val data: ByteArray,
        private val calls: MutableList<String>,
    ) : ModerationIncomingMessage {
        override fun ack() {
            calls += "ack"
        }

        override fun nak(delay: Duration) {
            calls += "nak"
        }

        override fun term() {
            calls += "term"
        }
    }

    private class RecordingSubscriber : EnvironmentModerationSubscriber {
        val attempts = mutableListOf<ModerationEnvironment>()
        var failStage = false

        override fun subscribe(
            binding: EnvironmentModerationBinding,
            processor: ModerationMessageProcessor,
        ): EnvironmentModerationSubscription {
            attempts += binding.environment
            if (failStage && binding.environment == ModerationEnvironment.STAGE)
                throw IllegalStateException("fixture")
            return object : EnvironmentModerationSubscription {
                override val connected = true

                override fun close() {}
            }
        }
    }
}
