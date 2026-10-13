package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.core.NotificationActionKind
import gg.grounds.notifications.core.NotificationActionRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnvironmentNotificationActionTest {
    private val mapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `case navigation carries explicit environment while legacy shape stays unchanged`() {
        val legacy =
            NotificationActionRequest(
                "open_case",
                "Open case",
                "primary",
                kind = NotificationActionKind.OPEN_PORTAL_CASE,
                entityType = "CASE",
                entityId = UUID.randomUUID().toString(),
            )
        val action = legacy.copy(environment = ModerationEnvironment.STAGE).validated()
        assertEquals(
            "stage",
            mapper
                .valueToTree<com.fasterxml.jackson.databind.JsonNode>(action)
                .path("environment")
                .asText(),
        )
        assertFalse(
            mapper
                .valueToTree<com.fasterxml.jackson.databind.JsonNode>(legacy.validated())
                .has("environment")
        )
    }

    @Test
    fun `command action cannot masquerade as environment-bound case navigation`() {
        assertThrows(IllegalArgumentException::class.java) {
            NotificationActionRequest(
                    "execute",
                    "Run",
                    "primary",
                    command = "cluster.resume",
                    environment = ModerationEnvironment.STAGE,
                )
                .validated()
        }
    }
}
