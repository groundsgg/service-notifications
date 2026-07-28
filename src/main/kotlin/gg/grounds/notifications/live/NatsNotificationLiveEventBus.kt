package gg.grounds.notifications.live

import com.fasterxml.jackson.databind.ObjectMapper
import io.nats.client.Connection
import io.nats.client.Dispatcher
import io.nats.client.Nats
import io.nats.client.Options
import io.quarkus.scheduler.Scheduled
import jakarta.annotation.PreDestroy
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

@ApplicationScoped
class NatsNotificationLiveEventBus(
    private val objectMapper: ObjectMapper,
    private val broadcaster: NotificationLiveBroadcaster,
    @param:ConfigProperty(name = "notifications.nats.url", defaultValue = "")
    private val natsUrl: String,
    @param:ConfigProperty(name = "notifications.nats.token", defaultValue = "")
    private val natsToken: String,
    @param:ConfigProperty(name = "notifications.nats.token-file", defaultValue = "")
    private val natsTokenFile: String,
) : NotificationLiveEventPublisher {
    private val publisher =
        RecoveringNatsPublisher(
            objectMapper,
            JnatsClientFactory(
                objectMapper,
                broadcaster,
                natsUrl,
                NatsTokenResolver(natsToken, natsTokenFile),
            ),
        )

    override fun publish(event: NotificationLiveEvent): Boolean = publisher.publish(event)

    @Scheduled(every = "10s")
    fun reconnect() {
        publisher.connectIfNeeded(force = true)
    }

    @PreDestroy
    fun close() {
        publisher.close()
    }

    interface NatsClient : AutoCloseable {
        val isActive: Boolean

        fun publish(subject: String, payload: ByteArray)

        override fun close()
    }

    interface NatsClientFactory {
        val enabled: Boolean

        fun create(): NatsClient?
    }

    class ForTests(objectMapper: ObjectMapper, clientFactory: NatsClientFactory) :
        NotificationLiveEventPublisher {
        constructor(
            objectMapper: ObjectMapper,
            client: NatsClient,
        ) : this(objectMapper, FixedNatsClientFactory(client))

        private val publisher = RecoveringNatsPublisher(objectMapper, clientFactory)

        override fun publish(event: NotificationLiveEvent): Boolean = publisher.publish(event)

        fun connectIfNeeded() {
            publisher.connectIfNeeded(force = true)
        }

        fun close() {
            publisher.close()
        }
    }

    private class RecoveringNatsPublisher(
        private val objectMapper: ObjectMapper,
        private val clientFactory: NatsClientFactory,
    ) : NotificationLiveEventPublisher {
        private val lock = Any()
        @Volatile private var client: NatsClient? = null
        private var lastConnectionFailureNanos = 0L
        @Volatile private var closed = false

        init {
            connectIfNeeded(force = true)
        }

        override fun publish(event: NotificationLiveEvent): Boolean {
            val natsClient = connectIfNeeded(force = false) ?: return false
            try {
                natsClient.publish(SUBJECT, objectMapper.writeValueAsBytes(event))
                return true
            } catch (exception: Exception) {
                LOG.warnf(exception, "Failed to publish notification live event to NATS")
                disconnectIfCurrent(natsClient)
                return false
            }
        }

        fun close() {
            synchronized(lock) {
                closed = true
                runCatching { client?.close() }
                client = null
            }
        }

        fun connectIfNeeded(force: Boolean): NatsClient? {
            if (!clientFactory.enabled || closed) {
                return null
            }
            client?.let {
                if (it.isActive) {
                    return it
                }
            }

            return synchronized(lock) {
                if (closed) {
                    return@synchronized null
                }
                client?.let {
                    if (it.isActive) {
                        return@synchronized it
                    }
                    client = null
                    runCatching { it.close() }
                }

                val now = System.nanoTime()
                val recentlyFailed =
                    lastConnectionFailureNanos != 0L &&
                        now - lastConnectionFailureNanos < CONNECT_ATTEMPT_INTERVAL_NANOS
                if (!force && recentlyFailed) {
                    return@synchronized null
                }

                try {
                    clientFactory.create()?.also {
                        client = it
                        lastConnectionFailureNanos = 0L
                    }
                } catch (exception: Exception) {
                    lastConnectionFailureNanos = now
                    LOG.errorf(
                        "Failed to initialize notification NATS live event bus (reason=%s)",
                        exception.javaClass.simpleName,
                    )
                    null
                }
            }
        }

        private fun disconnectIfCurrent(natsClient: NatsClient) {
            synchronized(lock) {
                if (client === natsClient) {
                    client = null
                    runCatching { natsClient.close() }
                }
            }
        }
    }

    private class FixedNatsClientFactory(private val client: NatsClient) : NatsClientFactory {
        override val enabled = true

        override fun create(): NatsClient = client
    }

    private class JnatsClientFactory(
        private val objectMapper: ObjectMapper,
        private val broadcaster: NotificationLiveBroadcaster,
        private val natsUrl: String,
        private val tokenResolver: NatsTokenResolver,
    ) : NatsClientFactory {
        override val enabled = natsUrl.isNotBlank()

        init {
            if (!enabled) {
                LOG.warn(
                    "Notification NATS live event bus is disabled because notifications.nats.url is empty"
                )
            }
        }

        override fun create(): NatsClient? {
            if (!enabled) {
                return null
            }

            val optionsBuilder =
                Options.Builder().server(natsUrl).connectionName("service-notifications")
            tokenResolver.resolve()?.let { optionsBuilder.token(it) }

            var connection: Connection? = null
            try {
                connection = Nats.connect(optionsBuilder.build())
                val dispatcher =
                    connection.createDispatcher { message ->
                        try {
                            val event =
                                objectMapper.readValue(
                                    message.data,
                                    NotificationLiveEvent::class.java,
                                )
                            broadcaster.publish(event)
                        } catch (exception: Exception) {
                            LOG.warnf(exception, "Ignoring invalid notification NATS payload")
                        }
                    }
                dispatcher.subscribe(SUBJECT)
                return JnatsClient(connection, dispatcher)
            } catch (exception: Exception) {
                runCatching { connection?.close() }
                throw exception
            }
        }
    }

    private class JnatsClient(
        private val connection: Connection,
        private val dispatcher: Dispatcher,
    ) : NatsClient {
        override val isActive: Boolean
            get() = connection.status == Connection.Status.CONNECTED

        override fun publish(subject: String, payload: ByteArray) {
            connection.publish(subject, payload)
        }

        override fun close() {
            runCatching { dispatcher.unsubscribe(SUBJECT) }
            connection.close()
        }
    }

    companion object {
        const val SUBJECT = "grounds.internal.notifications.changed"
        private const val CONNECT_ATTEMPT_INTERVAL_NANOS = 10_000_000_000L
        private val LOG: Logger = Logger.getLogger(NatsNotificationLiveEventBus::class.java)
    }
}
