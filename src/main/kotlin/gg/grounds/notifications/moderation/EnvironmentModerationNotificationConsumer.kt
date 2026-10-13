package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger

@ApplicationScoped
class EnvironmentModerationNotificationConsumer
@Inject
constructor(
    private val objectMapper: ObjectMapper,
    private val handler: ModerationReadyEventHandler,
    private val subscriber: EnvironmentModerationSubscriber,
    private val runtime: EnvironmentModerationNotificationRuntime,
    private val state: EnvironmentModerationConsumerState,
    private val metrics: ModerationNotificationMetrics? = null,
) {
    private val subscriptions =
        mutableMapOf<ModerationEnvironment, EnvironmentModerationSubscription>()
    private val lock = Any()
    private var closed = false
    private val logger = Logger.getLogger(EnvironmentModerationNotificationConsumer::class.java)

    fun start(@Observes event: StartupEvent) {
        reconnect()
    }

    @Scheduled(
        identity = "moderation-environment-consumers",
        every = "10s",
        concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
    )
    fun reconnect() {
        synchronized(lock) {
            if (closed) return
            runtime.bindings.forEach { binding ->
                val environment = binding.environment
                if (subscriptions[environment]?.connected == true) return@forEach
                close(environment)
                try {
                    val processor =
                        ModerationMessageProcessor(
                            validator = ModerationEventValidator(objectMapper),
                            metrics = metrics,
                            expectedEnvironment = environment,
                            lagObserver = { lag ->
                                state.updateLag(environment, lag)
                                metrics?.updateConsumerLag(state.totalLag())
                            },
                            handler = handler,
                        )
                    subscriptions[environment] = subscriber.subscribe(binding, processor)
                    state.connected(environment)
                } catch (exception: Exception) {
                    state.disconnected(environment)
                    logger.errorf(
                        "Failed to initialize moderation environment consumer (environment=%s, reason=%s)",
                        environment.wireValue,
                        exception.javaClass.simpleName,
                    )
                }
            }
        }
    }

    @PreDestroy
    fun close() {
        synchronized(lock) {
            closed = true
            runtime.bindings.forEach { close(it.environment) }
        }
    }

    private fun close(environment: ModerationEnvironment) {
        subscriptions.remove(environment)?.let { runCatching { it.close() } }
        state.disconnected(environment)
    }
}
