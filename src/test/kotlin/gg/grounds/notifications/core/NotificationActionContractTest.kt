package gg.grounds.notifications.core

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotificationActionContractTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `portal case action is typed and non executable`() {
        val caseId = UUID.randomUUID().toString()
        val action =
            NotificationActionRequest(
                actionKey = "open-case",
                label = "Fall öffnen",
                style = "primary",
                kind = NotificationActionKind.OPEN_PORTAL_CASE,
                entityType = "CASE",
                entityId = caseId,
            )

        assertSame(action, action.validated())
        assertNull(action.command)
        assertNull(action.payload)
    }

    @Test
    fun `portal case action rejects command url and malformed entity`() {
        val base =
            NotificationActionRequest(
                actionKey = "open-case",
                label = "Fall öffnen",
                style = "primary",
                kind = NotificationActionKind.OPEN_PORTAL_CASE,
                entityType = "CASE",
                entityId = UUID.randomUUID().toString(),
            )

        assertThrows(IllegalArgumentException::class.java) {
            base.copy(command = "case open").validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            base
                .copy(payload = objectMapper.readTree("{\"url\":\"https://evil.invalid\"}"))
                .validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            base.copy(entityId = "not-a-uuid").validated()
        }
    }

    @Test
    fun `command action still requires a command`() {
        val action =
            NotificationActionRequest(
                actionKey = "accept",
                label = "Accept",
                style = "primary",
                kind = NotificationActionKind.COMMAND,
            )

        assertThrows(IllegalArgumentException::class.java, action::validated)
    }
}
