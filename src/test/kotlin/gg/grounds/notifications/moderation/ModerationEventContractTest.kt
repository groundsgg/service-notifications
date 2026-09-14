package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ModerationEventContractTest {
    private val objectMapper = ObjectMapper().findAndRegisterModules().registerKotlinModule()
    private val validator = ModerationEventValidator(objectMapper)
    private val fixture = resource("moderation/moderation.report.ready-for-review.json")

    @Test
    fun `parses approved producer fixture`() {
        val event = validator.validate(ModerationEventSubjects.REPORT_READY_FOR_REVIEW, fixture)

        assertEquals(UUID.fromString("1d0221ed-e1f9-4d14-8ccd-3309d2759c20"), event.eventId)
        assertEquals(UUID.fromString("a1637fc1-30ba-4650-b438-8040fadf92f3"), event.data.caseId)
        assertEquals(CaptureStatus.COMPLETE, event.data.captureStatus)
        assertEquals(1, event.data.caseRevision)
    }

    @Test
    fun `rejects unexpected sensitive fields`() {
        listOf("playerName", "chatText", "reporterId", "reason", "publicReference").forEach { field
            ->
            val root = objectMapper.readTree(fixture)
            (root.path("data") as com.fasterxml.jackson.databind.node.ObjectNode).put(
                field,
                "secret",
            )

            assertThrows(InvalidModerationEventException::class.java) {
                validator.validate(
                    ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
                    objectMapper.writeValueAsBytes(root),
                )
            }
        }
    }

    @Test
    fun `rejects invalid status uuid revision type version and subject`() {
        val mutations =
            listOf<(com.fasterxml.jackson.databind.node.ObjectNode) -> Unit>(
                { root -> root.withObject("/data").put("captureStatus", "OPEN") },
                { root -> root.withObject("/data").put("caseId", "not-a-uuid") },
                { root -> root.withObject("/data").put("caseRevision", 0) },
                { root -> root.put("eventType", "moderation.report.accepted") },
                { root -> root.put("schemaVersion", 2) },
            )

        mutations.forEach { mutate ->
            val root =
                objectMapper.readTree(fixture) as com.fasterxml.jackson.databind.node.ObjectNode
            mutate(root)
            assertThrows(InvalidModerationEventException::class.java) {
                validator.validate(
                    ModerationEventSubjects.REPORT_READY_FOR_REVIEW,
                    objectMapper.writeValueAsBytes(root),
                )
            }
        }
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate("moderation.report.accepted", fixture)
        }
    }

    private fun resource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing $path" }
            .use { it.readAllBytes() }
}
