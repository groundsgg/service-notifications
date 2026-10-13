package gg.grounds.notifications.moderation

import io.nats.client.Connection
import io.nats.client.Message
import io.nats.client.MessageConsumer
import io.nats.client.Nats
import io.nats.client.api.AckPolicy
import io.nats.client.api.ConsumerConfiguration
import jakarta.enterprise.context.ApplicationScoped
import java.time.Duration
import org.eclipse.microprofile.config.inject.ConfigProperty

interface EnvironmentModerationSubscription : AutoCloseable {
    val connected: Boolean
}

fun interface EnvironmentModerationSubscriber {
    fun subscribe(
        binding: EnvironmentModerationBinding,
        processor: ModerationMessageProcessor,
    ): EnvironmentModerationSubscription
}

@ApplicationScoped
class JnatsEnvironmentModerationSubscriber(
    runtime: EnvironmentModerationNotificationRuntime,
    @param:ConfigProperty(name = "notifications.nats.url", defaultValue = "")
    private val natsUrl: String,
    @ConfigProperty(
        name = "notifications.nats.authentication-mode",
        defaultValue = "url-credentials",
    )
    authenticationModeConfig: String,
    @param:ConfigProperty(name = "notifications.nats.token-file", defaultValue = "")
    private val tokenFile: String,
    @param:ConfigProperty(name = "notifications.nats.connect-timeout", defaultValue = "PT2S")
    private val connectTimeout: Duration,
    @param:ConfigProperty(name = "notifications.nats.reconnect-wait", defaultValue = "PT2S")
    private val reconnectWait: Duration,
    @param:ConfigProperty(name = "notifications.nats.max-reconnects", defaultValue = "60")
    private val maxReconnects: Int,
    @param:ConfigProperty(name = "notifications.nats.allow-unauthenticated", defaultValue = "false")
    private val allowUnauthenticated: Boolean,
) : EnvironmentModerationSubscriber {
    private val authenticationMode = NatsAuthenticationMode.parse(authenticationModeConfig)

    init {
        require(connectTimeout > Duration.ZERO && reconnectWait > Duration.ZERO) {
            "NATS timeouts must be positive"
        }
        require(maxReconnects >= 0) { "NATS max reconnects must not be negative" }
        if (runtime.bindings.isNotEmpty())
            validateNatsAuthenticationConfiguration(
                natsUrl,
                authenticationMode,
                tokenFile,
                allowUnauthenticated,
            )
    }

    override fun subscribe(
        binding: EnvironmentModerationBinding,
        processor: ModerationMessageProcessor,
    ): EnvironmentModerationSubscription {
        val connection =
            Nats.connect(
                buildNatsConnectionOptions(
                    natsUrl,
                    connectTimeout,
                    reconnectWait,
                    maxReconnects,
                    authenticationMode,
                    tokenFile,
                    allowUnauthenticated,
                )
            )
        try {
            connection
                .jetStreamManagement()
                .addOrUpdateConsumer(
                    binding.streamName,
                    environmentModerationConsumerConfiguration(binding),
                )
            val consumer =
                connection
                    .jetStream()
                    .getConsumerContext(binding.streamName, binding.consumerName)
                    .consume { processor.process(Incoming(it)) }
            return Subscription(connection, consumer)
        } catch (exception: Exception) {
            runCatching { connection.close() }
            throw exception
        }
    }

    private class Subscription(
        private val connection: Connection,
        private val consumer: MessageConsumer,
    ) : EnvironmentModerationSubscription {
        override val connected: Boolean
            get() = connection.status == Connection.Status.CONNECTED

        override fun close() {
            try {
                consumer.close()
            } finally {
                connection.close()
            }
        }
    }

    private class Incoming(private val message: Message) : ModerationIncomingMessage {
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
}

internal fun environmentModerationConsumerConfiguration(
    binding: EnvironmentModerationBinding
): ConsumerConfiguration =
    ConsumerConfiguration.builder()
        .durable(binding.consumerName)
        .ackPolicy(AckPolicy.Explicit)
        .filterSubject(binding.subject)
        .build()
