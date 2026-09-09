package gg.grounds.notifications.moderation

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

data class ModerationConsumerSnapshot(
    val enabled: Boolean,
    val connected: Boolean,
    val consumerLag: Long,
)

@ApplicationScoped
class ModerationConsumerState(
    @param:ConfigProperty(
        name = "notifications.moderation-projector.enabled",
        defaultValue = "false",
    )
    private val enabled: Boolean
) {
    @Volatile private var connected = false
    @Volatile private var consumerLag = 0L

    fun connected(consumerLag: Long = this.consumerLag) {
        this.consumerLag = consumerLag.coerceAtLeast(0)
        connected = true
    }

    fun disconnected(consumerLag: Long = this.consumerLag) {
        this.consumerLag = consumerLag.coerceAtLeast(0)
        connected = false
    }

    fun updateLag(consumerLag: Long) {
        this.consumerLag = consumerLag.coerceAtLeast(0)
    }

    fun snapshot(): ModerationConsumerSnapshot =
        ModerationConsumerSnapshot(enabled, connected, consumerLag)
}

@Readiness
@ApplicationScoped
class ModerationNotificationReadiness(private val state: ModerationConsumerState) : HealthCheck {
    override fun call(): HealthCheckResponse {
        val snapshot = state.snapshot()
        val stateName =
            when {
                !snapshot.enabled -> "disabled"
                snapshot.connected -> "connected"
                else -> "degraded"
            }
        return HealthCheckResponse.named("moderation-notification-consumer")
            .up()
            .withData("enabled", snapshot.enabled)
            .withData("state", stateName)
            .withData("consumerLag", snapshot.consumerLag)
            .build()
    }
}
