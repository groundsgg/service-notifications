package gg.grounds.notifications.moderation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaseStructureEventContractTest {
    private val mapper = ObjectMapper().findAndRegisterModules()
    private val validator = CaseStructureEventValidator(mapper)
    private val subject = "moderation-v2.stage.case.restructured"
    private val fixture =
        javaClass.classLoader
            .getResourceAsStream("moderation/v2/moderation.case.restructured.json")!!
            .use { it.readAllBytes() }

    private fun json() = mapper.readTree(fixture) as ObjectNode

    private fun bytes(root: ObjectNode) = mapper.writeValueAsBytes(root)

    @Test
    fun `authoritative bounded fixture is accepted with its explicit environment and readiness reference`() {
        val event = validator.validate(subject, fixture)
        assertEquals(ModerationEnvironment.STAGE, event.data.environment)
        assertEquals(42, event.data.structureSequence)
        assertEquals(CaseStructureKind.MERGE, event.data.kind)
        assertEquals(
            "c30b725f-72cd-4387-93c0-0346f09c8a3f",
            event.data.reports.single().readinessEventId.toString(),
        )
        assertEquals(event.data.destinationCaseId, event.data.reports.single().caseId)
    }

    @Test
    fun `subject version and every nested field remain strict and private text is rejected`() {
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate("moderation-v2.prod.case.restructured", fixture)
        }
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate("moderation.case.restructured", fixture)
        }
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate(subject, bytes(json().put("schemaVersion", 1)))
        }
        for (field in
            listOf("internalReason", "actorId", "notes", "content", "reporterPlayerId", "url")) {
            for (level in 0..2) {
                val root = json()
                val target =
                    when (level) {
                        0 -> root
                        1 -> root.path("data") as ObjectNode
                        else -> root.path("data").path("reports")[0] as ObjectNode
                    }
                target.put(field, "CONFIDENTIAL")
                assertThrows(InvalidModerationEventException::class.java) {
                    validator.validate(subject, bytes(root))
                }
            }
        }
    }

    @Test
    fun `invalid chunk sequence membership and duplicate identity are rejected`() {
        for (change in
            listOf<(ObjectNode) -> Unit>(
                { it.put("structureSequence", 0) },
                { it.put("structureSequence", 9007199254740992L) },
                { it.put("chunkIndex", 1) },
                { it.put("chunkCount", 0) },
                { it.put("sourceCaseId", it.path("destinationCaseId").asText()) },
                {
                    (it.path("reports")[0] as ObjectNode).put(
                        "caseId",
                        it.path("sourceCaseId").asText(),
                    )
                },
                {
                    val a = it.path("reports") as ArrayNode
                    a.add(a[0].deepCopy())
                },
                {
                    val a = it.path("reports") as ArrayNode
                    while (a.size() < 101) a.add(a[0].deepCopy())
                },
            )) {
            val root = json()
            change(root.path("data") as ObjectNode)
            assertThrows(InvalidModerationEventException::class.java) {
                validator.validate(subject, bytes(root))
            }
        }
    }

    @Test
    fun `absent legacy capture is allowed but cannot claim readiness`() {
        val root = json()
        val report = root.path("data").path("reports")[0] as ObjectNode
        report.putNull("evidenceCaptureId")
        report.putNull("captureStatus")
        report.putNull("readinessEventId")
        assertNull(validator.validate(subject, bytes(root)).data.reports.single().captureStatus)
        report.put("readinessEventId", "c30b725f-72cd-4387-93c0-0346f09c8a3f")
        assertThrows(InvalidModerationEventException::class.java) {
            validator.validate(subject, bytes(root))
        }
    }

    @Test
    fun `existing environment processor acknowledges structure events through their typed handler`() {
        val seen = mutableListOf<CaseStructureNotificationEvent>()
        val processor =
            ModerationMessageProcessor(
                ModerationEventValidator(mapper),
                expectedEnvironment = ModerationEnvironment.STAGE,
                structureHandler = ModerationStructureEventHandler { seen.add(it) },
                handler = ModerationReadyEventHandler { fail("Wrong event handler") },
            )
        val incoming = Incoming(subject, fixture)
        processor.process(incoming)
        assertEquals(1, incoming.acks)
        assertEquals(0, incoming.terms)
        assertEquals(1, seen.size)
    }

    @Test
    fun `wrong environment terminates and handler failure retries without acknowledgement`() {
        val wrong = Incoming(subject, fixture)
        ModerationMessageProcessor(
                ModerationEventValidator(mapper),
                expectedEnvironment = ModerationEnvironment.PROD,
                structureHandler =
                    ModerationStructureEventHandler { fail("Wrong deployment environment") },
                handler = ModerationReadyEventHandler {},
            )
            .process(wrong)
        assertEquals(1, wrong.terms)
        val retry = Incoming(subject, fixture)
        ModerationMessageProcessor(
                ModerationEventValidator(mapper),
                expectedEnvironment = ModerationEnvironment.STAGE,
                structureHandler =
                    ModerationStructureEventHandler {
                        throw RetryableModerationNotificationException("fixture")
                    },
                handler = ModerationReadyEventHandler {},
            )
            .process(retry)
        assertEquals(1, retry.naks)
        assertEquals(0, retry.acks)
    }

    private class Incoming(override val subject: String, override val data: ByteArray) :
        ModerationIncomingMessage {
        var acks = 0
        var naks = 0
        var terms = 0

        override fun ack() {
            acks++
        }

        override fun nak(delay: Duration) {
            naks++
        }

        override fun term() {
            terms++
        }
    }
}
