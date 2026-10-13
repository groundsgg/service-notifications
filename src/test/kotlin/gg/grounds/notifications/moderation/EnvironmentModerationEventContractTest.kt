package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class EnvironmentModerationEventContractTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val validator = ModerationEventValidator(mapper)
    private val fixture =
        javaClass.classLoader
            .getResourceAsStream("moderation/v2/moderation.report.ready-for-review.json")!!
            .use { it.readAllBytes() }

    @Test
    fun `parses exact producer v2 fixture as stage without altering identity`() {
        val event = validator.validate("moderation-v2.stage.report.ready-for-review", fixture)
        assertEquals(2, event.schemaVersion)
        assertEquals(ModerationEnvironment.STAGE, event.data.environment)
        assertEquals(UUID.fromString("1d0221ed-e1f9-4d14-8ccd-3309d2759c20"), event.eventId)
        assertEquals(3, event.data.caseRevision)
    }

    @Test
    fun `same producer IDs in prod remain bound to prod subject`() {
        val root = mapper.readTree(fixture) as ObjectNode
        root.withObject("/data").put("environment", "prod")
        val event =
            validator.validate(
                "moderation-v2.prod.report.ready-for-review",
                mapper.writeValueAsBytes(root),
            )
        assertEquals(ModerationEnvironment.PROD, event.data.environment)
        assertEquals(UUID.fromString("a1637fc1-30ba-4650-b438-8040fadf92f3"), event.data.caseId)
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate(
                "moderation-v2.stage.report.ready-for-review",
                mapper.writeValueAsBytes(root),
            )
        }
    }

    @Test
    fun `legacy and v2 subjects cannot reinterpret one another`() {
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate("moderation.report.ready-for-review", fixture)
        }
        val root = mapper.readTree(fixture) as ObjectNode
        root.withObject("/data").remove("environment")
        root.put("schemaVersion", 1)
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate(
                "moderation-v2.stage.report.ready-for-review",
                mapper.writeValueAsBytes(root),
            )
        }
    }

    @Test
    fun `rejects missing unknown or nonliteral environment and sensitive additions`() {
        for (environment in listOf("dev", "STAGE", "stage ", "prod")) {
            val root = mapper.readTree(fixture) as ObjectNode
            root.withObject("/data").put("environment", environment)
            assertThrows(InvalidModerationEventException::class.java) {
                validator.validate(
                    "moderation-v2.stage.report.ready-for-review",
                    mapper.writeValueAsBytes(root),
                )
            }
        }
        val missing = mapper.readTree(fixture) as ObjectNode
        missing.withObject("/data").remove("environment")
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate(
                "moderation-v2.stage.report.ready-for-review",
                mapper.writeValueAsBytes(missing),
            )
        }
        for (field in listOf("chatText", "playerName", "publicReference", "internalReason")) {
            val root = mapper.readTree(fixture) as ObjectNode
            root.withObject("/data").put(field, "private fixture")
            assertThrows(InvalidModerationEventException::class.java) {
                validator.validate(
                    "moderation-v2.stage.report.ready-for-review",
                    mapper.writeValueAsBytes(root),
                )
            }
        }
    }
}
