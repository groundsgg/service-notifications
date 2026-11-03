package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.audience.*
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaseStructureProjectorTest {
    private fun event(): CaseStructureNotificationEvent {
        val bytes =
            javaClass.classLoader
                .getResourceAsStream("moderation/v2/moderation.case.restructured.json")!!
                .use { it.readAllBytes() }
        return CaseStructureEventValidator(ObjectMapper().findAndRegisterModules())
            .validate("moderation-v2.stage.case.restructured", bytes)
    }

    private val audience =
        ForgeAudienceSnapshot(
            listOf("moderator"),
            Instant.parse("2026-11-03T12:00:00Z"),
            "a".repeat(64),
        )

    private fun resolver(resolve: (ModerationEnvironment?) -> ForgeAudienceSnapshot) =
        object : ForgeAudienceResolver {
            override fun resolveModerationAudience(): ForgeAudienceSnapshot =
                error("Legacy resolver is forbidden")

            override fun resolveModerationAudience(environment: ModerationEnvironment?) =
                resolve(environment)
        }

    @Test
    fun `projection resolves only the event environment and uses the existing canonical store`() {
        var stored = false
        val projector =
            CaseStructureNotificationProjector(
                resolver { environment ->
                    assertEquals(ModerationEnvironment.STAGE, environment)
                    audience
                },
                ModerationStructureProjectionStore { input, snapshot ->
                    assertEquals(42, input.data.structureSequence)
                    assertEquals(audience, snapshot)
                    stored = true
                    CaseStructureProjectionResult(input.data.operationId, true, false)
                },
            )
        projector.handle(event())
        assertTrue(stored)
    }

    @Test
    fun `processed chunks bypass fresh audience and never project twice`() {
        val event = event()
        val store =
            object : ModerationStructureProjectionStore {
                override fun findProcessedCaseStructure(event: CaseStructureNotificationEvent) =
                    CaseStructureProjectionResult(event.data.operationId, true, true)

                override fun projectCaseStructure(
                    event: CaseStructureNotificationEvent,
                    audience: ForgeAudienceSnapshot,
                ): CaseStructureProjectionResult = error("Must not project")
            }
        CaseStructureNotificationProjector(resolver { error("Must not resolve") }, store)
            .handle(event)
    }

    @Test
    fun `retryable audience failure never persists a chunk and retains retry classification`() {
        var stored = false
        val projector =
            CaseStructureNotificationProjector(
                resolver { throw RetryableForgeAudienceException("fixture") },
                ModerationStructureProjectionStore { _, _ ->
                    stored = true
                    error("Must not store")
                },
            )
        assertThrows(RetryableModerationNotificationException::class.java) {
            projector.handle(event())
        }
        assertFalse(stored)
    }

    @Test
    fun `terminal audience failure is not acknowledged as a successful projection`() {
        val projector =
            CaseStructureNotificationProjector(
                resolver { throw TerminalForgeAudienceException("fixture") },
                ModerationStructureProjectionStore { _, _ -> error("Must not store") },
            )
        assertThrows(TerminalModerationNotificationException::class.java) {
            projector.handle(event())
        }
    }
}
