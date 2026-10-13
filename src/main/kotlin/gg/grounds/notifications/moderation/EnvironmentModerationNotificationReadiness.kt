package gg.grounds.notifications.moderation

import jakarta.enterprise.context.ApplicationScoped
import java.util.concurrent.ConcurrentHashMap
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Readiness

@ApplicationScoped
class EnvironmentModerationConsumerState(
    private val runtime: EnvironmentModerationNotificationRuntime
) {
    private val connections = ConcurrentHashMap<ModerationEnvironment, Boolean>()
    private val lag = ConcurrentHashMap<ModerationEnvironment, Long>()

    fun connected(environment: ModerationEnvironment) {
        connections[environment] = true
    }

    fun disconnected(environment: ModerationEnvironment) {
        connections[environment] = false
    }

    fun updateLag(environment: ModerationEnvironment, value: Long) {
        lag[environment] = value.coerceAtLeast(0)
    }

    fun snapshot(environment: ModerationEnvironment) =
        ModerationConsumerSnapshot(
            runtime.enabled(environment),
            connections[environment] == true,
            lag[environment] ?: 0,
        )

    fun totalLag(): Long = runtime.bindings.sumOf { lag[it.environment] ?: 0 }
}

@Readiness
@ApplicationScoped
class EnvironmentModerationNotificationReadiness(
    private val state: EnvironmentModerationConsumerState
) : HealthCheck {
    override fun call(): HealthCheckResponse {
        // A degraded projector must not take unrelated notifications or administration offline.
        val response = HealthCheckResponse.named("moderation-environment-consumers").up()
        ModerationEnvironment.entries.forEach { environment ->
            val snapshot = state.snapshot(environment)
            response.withData("${environment.wireValue}.enabled", snapshot.enabled)
            response.withData(
                "${environment.wireValue}.state",
                when {
                    !snapshot.enabled -> "disabled"
                    snapshot.connected -> "connected"
                    else -> "degraded"
                },
            )
            response.withData("${environment.wireValue}.consumerLag", snapshot.consumerLag)
        }
        return response.build()
    }
}
