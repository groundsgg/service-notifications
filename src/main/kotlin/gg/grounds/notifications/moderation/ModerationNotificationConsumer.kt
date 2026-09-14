package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.MessageConsumer
import io.nats.client.Nats
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.enterprise.inject.Instance
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

fun interface ModerationReadyEventHandler {
    fun handle(event: ReportReadyForReviewEvent)
}

open class RetryableModerationNotificationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

open class TerminalModerationNotificationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface ModerationIncomingMessage {
    val subject: String
    val data: ByteArray
    val pendingCount: Long?
        get() = null

    fun ack()

    fun nak(delay: Duration)

    fun term()
}

class ModerationMessageProcessor(
    private val validator: ModerationEventValidator,
    private val metrics: ModerationNotificationMetrics? = null,
    private val state: ModerationConsumerState? = null,
    private val retryDelay: Duration = Duration.ofSeconds(5),
    private val handler: ModerationReadyEventHandler,
) {
    fun process(message: ModerationIncomingMessage) {
        message.pendingCount?.let { pending ->
            metrics?.updateConsumerLag(pending)
            state?.updateLag(pending)
        }
        try {
            val event = validator.validate(message.subject, message.data)
            handler.handle(event)
            message.ack()
        } catch (_: InvalidModerationEventException) {
            metrics?.recordFailure(ModerationFailureReason.INVALID_EVENT)
            message.term()
        } catch (_: TerminalModerationNotificationException) {
            metrics?.recordFailure(ModerationFailureReason.TERMINAL_PROJECTION)
            message.term()
        } catch (_: RetryableModerationNotificationException) {
            metrics?.recordRetry(ModerationRetryReason.PROJECTION)
            message.nak(retryDelay)
        } catch (_: Exception) {
            metrics?.recordRetry(ModerationRetryReason.PROJECTION)
            message.nak(retryDelay)
        }
    }
}

@ApplicationScoped
class ModerationNotificationConsumer(
    private val objectMapper: ObjectMapper,
    private val handlers: Instance<ModerationReadyEventHandler>,
    private val metrics: ModerationNotificationMetrics,
    private val state: ModerationConsumerState,
    @param:ConfigProperty(
        name = "notifications.moderation-projector.enabled",
        defaultValue = "false",
    )
    private val enabled: Boolean,
    @param:ConfigProperty(name = "notifications.nats.url", defaultValue = "")
    private val natsUrl: String,
    @ConfigProperty(
        name = "notifications.nats.authentication-mode",
        defaultValue = "url-credentials",
    )
    authenticationModeConfig: String,
    @param:ConfigProperty(name = "notifications.nats.token-file", defaultValue = "")
    private val tokenFile: String,
    @param:ConfigProperty(
        name = "notifications.moderation-projector.stream",
        defaultValue = "MODERATION",
    )
    private val streamName: String,
    @param:ConfigProperty(
        name = "notifications.moderation-projector.consumer",
        defaultValue = "service-notifications-moderation-ready-v1",
    )
    private val consumerName: String,
    @param:ConfigProperty(name = "notifications.nats.connect-timeout", defaultValue = "PT2S")
    private val connectTimeout: Duration,
    @param:ConfigProperty(name = "notifications.nats.reconnect-wait", defaultValue = "PT2S")
    private val reconnectWait: Duration,
    @param:ConfigProperty(name = "notifications.nats.max-reconnects", defaultValue = "60")
    private val maxReconnects: Int,
    @param:ConfigProperty(name = "notifications.nats.allow-unauthenticated", defaultValue = "false")
    private val allowUnauthenticated: Boolean,
) {
    private val authenticationMode = NatsAuthenticationMode.parse(authenticationModeConfig)
    private val lock = Any()
    @Volatile private var connection: Connection? = null
    @Volatile private var messageConsumer: MessageConsumer? = null
    @Volatile private var closed = false

    init {
        require(streamName.isNotBlank()) { "Moderation stream name must not be blank" }
        require(consumerName.isNotBlank()) { "Moderation consumer name must not be blank" }
        require(connectTimeout > Duration.ZERO && reconnectWait > Duration.ZERO) {
            "NATS timeouts must be positive"
        }
        require(maxReconnects >= 0) { "NATS max reconnects must not be negative" }
        if (enabled) {
            validateNatsAuthenticationConfiguration(
                natsUrl,
                authenticationMode,
                tokenFile,
                allowUnauthenticated,
            )
        }
    }

    fun start(@Observes event: StartupEvent) {
        if (enabled) connectIfNeeded()
    }

    @Scheduled(every = "10s")
    fun reconnect() {
        if (enabled) connectIfNeeded()
    }

    @PreDestroy
    fun close() {
        synchronized(lock) {
            closed = true
            closeCurrent()
        }
    }

    private fun connectIfNeeded() {
        if (closed || connection?.status == Connection.Status.CONNECTED) return
        synchronized(lock) {
            if (closed || connection?.status == Connection.Status.CONNECTED) return
            closeCurrent()
            try {
                val handler =
                    if (handlers.isResolvable) handlers.get()
                    else throw IllegalStateException("Moderation event handler is unavailable")
                val newConnection =
                    Nats.connect(
                        buildNatsConnectionOptions(
                            url = natsUrl,
                            connectionTimeout = connectTimeout,
                            reconnectWait = reconnectWait,
                            maxReconnects = maxReconnects,
                            authenticationMode = authenticationMode,
                            tokenFile = tokenFile,
                            allowUnauthenticated = allowUnauthenticated,
                        )
                    )
                connection = newConnection
                val configuration = moderationConsumerConfiguration(consumerName)
                newConnection.jetStreamManagement().addOrUpdateConsumer(streamName, configuration)
                val processor =
                    ModerationMessageProcessor(
                        validator = ModerationEventValidator(objectMapper),
                        metrics = metrics,
                        state = state,
                        handler = handler,
                    )
                val newConsumer =
                    newConnection.jetStream().getConsumerContext(streamName, consumerName).consume {
                        processor.process(JnatsIncomingMessage(it))
                    }
                messageConsumer = newConsumer
                state.connected()
            } catch (exception: Exception) {
                closeCurrent()
                LOG.errorf(
                    "Failed to initialize moderation notification consumer (reason=%s)",
                    exception.javaClass.simpleName,
                )
            }
        }
    }

    private fun closeCurrent() {
        runCatching { messageConsumer?.close() }
        messageConsumer = null
        runCatching { connection?.close() }
        connection = null
        state.disconnected()
    }

    private class JnatsIncomingMessage(private val message: Message) : ModerationIncomingMessage {
        override val subject: String
            get() = message.subject

        override val data: ByteArray
            get() = message.data

        override val pendingCount: Long?
            get() = runCatching { message.metaData().pendingCount() }.getOrNull()

        override fun ack() = message.ack()

        override fun nak(delay: Duration) = message.nakWithDelay(delay)

        override fun term() = message.term()
    }

    private companion object {
        val LOG: Logger = Logger.getLogger(ModerationNotificationConsumer::class.java)
    }
}

internal fun moderationConsumerConfiguration(consumerName: String): ConsumerConfiguration =
    ConsumerConfiguration.builder()
        .durable(consumerName)
        .ackPolicy(AckPolicy.Explicit)
        .filterSubject(ModerationEventSubjects.REPORT_READY_FOR_REVIEW)
        .build()
