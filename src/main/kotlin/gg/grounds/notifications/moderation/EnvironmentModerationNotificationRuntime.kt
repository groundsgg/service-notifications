package gg.grounds.notifications.moderation

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty

data class EnvironmentModerationBinding(val environment: ModerationEnvironment) {
    val streamName: String
        get() = "MODERATION_READY_${environment.name}_V2"

    val consumerName: String
        get() = "service-notifications-moderation-ready-${environment.wireValue}-v2"

    val subject: String
        get() = environment.readySubject
}

@ApplicationScoped
class EnvironmentModerationNotificationRuntime(
    @param:ConfigProperty(
        name = "notifications.moderation-projector.stage.enabled",
        defaultValue = "false",
    )
    stageEnabled: Boolean,
    @param:ConfigProperty(
        name = "notifications.moderation-projector.prod.enabled",
        defaultValue = "false",
    )
    prodEnabled: Boolean,
) {
    val bindings: List<EnvironmentModerationBinding> = buildList {
        if (stageEnabled) add(EnvironmentModerationBinding(ModerationEnvironment.STAGE))
        if (prodEnabled) add(EnvironmentModerationBinding(ModerationEnvironment.PROD))
    }

    fun enabled(environment: ModerationEnvironment): Boolean =
        bindings.any { it.environment == environment }
}
