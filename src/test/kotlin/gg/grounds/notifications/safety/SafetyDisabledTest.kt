package gg.grounds.notifications.safety

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import java.lang.reflect.Proxy
import java.util.Optional
import javax.sql.DataSource
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SafetyOffProfile : io.quarkus.test.junit.QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "quarkus.flyway.schemas" to "confidential_safety_test",
            "quarkus.datasource.jdbc.additional-jdbc-properties.currentSchema" to
                "confidential_safety_test",
        )
}

@QuarkusTest
@io.quarkus.test.junit.TestProfile(SafetyOffProfile::class)
class SafetyDisabledTest {
    @Inject lateinit var repo: gg.grounds.notifications.db.NotificationRepository

    @Test
    fun reservedTypeRemainsClosedToGeneralWritersWhenBothGatesAreOff() {
        val request =
            gg.grounds.notifications.core.NotificationEventRequest(
                "id",
                "MODERATION_SAFETY_SIGNAL",
                "system",
                "HIGH",
                gg.grounds.notifications.core.NotificationScope("network"),
                gg.grounds.notifications.core.NotificationActor("system"),
                title = "fixture",
                body = "fixture",
                recipients =
                    listOf(gg.grounds.notifications.core.NotificationRecipientRequest(STAFF)),
            )
        assertThrows(jakarta.ws.rs.BadRequestException::class.java) { repo.createEvent(request) }
    }

    @Inject lateinit var source: DataSource
    @Inject lateinit var worker: SafetyResolutionWorker

    @Test
    fun disabledOnlyConsultsTheTwoGateFlags() {
        val reads = mutableListOf<String>()
        val config =
            Proxy.newProxyInstance(Config::class.java.classLoader, arrayOf(Config::class.java)) {
                _,
                m,
                args ->
                check(m.name == "getOptionalValue")
                val key = args[0] as String
                reads.add(key)
                check(key.endsWith(".enabled"))
                Optional.empty<String>()
            } as Config
        assertNull(SafetyGate(config).authenticate(STAGE_KEY))
        assertEquals(
            setOf("notifications.safety.stage.enabled", "notifications.safety.prod.enabled"),
            reads.toSet(),
        )
    }

    @Test
    fun disabledEndpointsAreAbsentAndAnExpiredRecordIsPurged() {
        source.connection.use { c ->
            c.createStatement().use { s ->
                s.execute("TRUNCATE notification_safety_signal CASCADE")
                s.execute(
                    """INSERT INTO notification_safety_signal(environment,event_id,signal_ref,occurred_at_utc,expires_at)
                VALUES('stage',gen_random_uuid(),gen_random_uuid(),'2026-01-01T00:00:00Z',now()-interval '1 hour'),
                ('stage',gen_random_uuid(),gen_random_uuid(),'2026-01-01T00:00:00Z',now()+interval '1 hour')"""
                )
            }
        }
        given()
            .header("X-Grounds-Safety-Producer-Token", STAGE_KEY)
            .contentType("application/json")
            .body("{}")
            .post("/v1/internal/moderation-safety-signals")
            .then()
            .statusCode(404)
        given().get("/v1/notifications/safety/stage").then().statusCode(404)
        worker.tick()
        source.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT state,attempts FROM notification_safety_signal").use { r ->
                    assertTrue(r.next())
                    assertEquals("PENDING", r.getString(1))
                    assertEquals(0, r.getInt(2))
                    assertFalse(r.next())
                }
            }
        }
    }
}
