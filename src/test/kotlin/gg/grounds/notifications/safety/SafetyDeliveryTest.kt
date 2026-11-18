package gg.grounds.notifications.safety

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.moderation.ModerationEnvironment
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import io.smallrye.jwt.build.Jwt
import jakarta.inject.Inject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SafetyFixtureProfile : QuarkusTestProfile {
    override fun getConfigOverrides() =
        mapOf(
            "quarkus.flyway.schemas" to "confidential_safety_test",
            "quarkus.datasource.jdbc.additional-jdbc-properties.currentSchema" to
                "confidential_safety_test",
            "notifications.safety.stage.enabled" to "true",
            "notifications.safety.stage.producer-token" to STAGE_KEY,
            "notifications.safety.prod.enabled" to "true",
            "notifications.safety.prod.producer-token" to PROD_KEY,
            "quarkus.datasource.jdbc.max-size" to "2",
            "notifications.forge.read-timeout" to "PT3S",
            "mp.jwt.verify.issuer" to ISSUER,
            "mp.jwt.verify.publickey.location" to "jwt/publicKey.pem",
            "smallrye.jwt.sign.key.location" to "jwt/privateKey.pem",
        )
}

@QuarkusTest
@TestProfile(SafetyFixtureProfile::class)
@QuarkusTestResource(value = FakeSafetyForge::class, restrictToAnnotatedClass = true)
class SafetyDeliveryTest {
    @Inject
    lateinit var playerResolver: gg.grounds.notifications.channel.InMemoryMinecraftPlayerResolver
    @Inject
    lateinit var live: gg.grounds.notifications.outbox.RecordingNotificationLiveEventPublisher
    @Inject lateinit var source: DataSource
    @Inject lateinit var authority: SafetyAudienceAuthority
    @Inject lateinit var worker: SafetyResolutionWorker
    @Inject lateinit var store: SafetyStore
    @Inject lateinit var mapper: ObjectMapper
    @Inject lateinit var config: Config
    private val path = "/v1/internal/moderation-safety-signals"
    private val inbox = "/v1/notifications/safety/stage"

    private fun sql(q: String) =
        source.connection.use { c -> c.createStatement().use { it.execute(q) } }

    private fun scalar(q: String) =
        source.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(q).use {
                    it.next()
                    it.getString(1)
                }
            }
        }

    private fun mode(value: String) {
        val port = config.getValue("notifications.safety.fixture-port", String::class.java)
        assertEquals(
            "http://127.0.0.1:$port",
            config.getValue("notifications.forge.base-url", String::class.java),
            "Safety profile uses its own Forge process",
        )
        HttpClient.newHttpClient().use {
            it.send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:$port/fixture"))
                    .POST(HttpRequest.BodyPublishers.ofString(value))
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    @BeforeEach
    fun reset() {
        sql(
            "TRUNCATE notification_safety_signal,notification_outbox,notifications,notification_channel_clients CASCADE"
        )
        mode("ok")
        live.reset()
        playerResolver.clear()
    }

    private fun data(
        env: String = "stage",
        event: UUID = UUID.randomUUID(),
        ref: UUID = UUID.randomUUID(),
        at: Instant = Instant.now(),
    ) =
        mapOf(
            "schemaVersion" to 1,
            "environment" to env,
            "eventId" to event.toString(),
            "signalRef" to ref.toString(),
            "occurredAt" to at.toString(),
            "mode" to "ACTIVE",
        )

    private fun submit(data: Any, token: String = STAGE_KEY) =
        given()
            .header("X-Grounds-Safety-Producer-Token", token)
            .contentType("application/json")
            .body(data)
            .post(path)

    private fun user(id: String = STAFF, admin: Boolean = false) =
        given()
            .auth()
            .oauth2(
                Jwt.issuer(ISSUER)
                    .subject(id)
                    .groups(if (admin) setOf("NOTIFICATIONS_MANAGE") else emptySet())
                    .sign()
            )

    @Test
    fun durableAcceptance() {
        val e = data()
        repeat(2) {
            val r = submit(e)
            assertEquals(202, r.statusCode)
            assertEquals(
                mapOf("eventId" to e["eventId"], "accepted" to true),
                r.jsonPath().getMap<String, Any>(""),
            )
        }
        assertEquals(409, submit(e + ("signalRef" to UUID.randomUUID().toString())).statusCode)
        assertEquals(409, submit(e + ("eventId" to UUID.randomUUID().toString())).statusCode)
        assertEquals("1", scalar("SELECT count(*) FROM notification_safety_signal"))
        assertEquals("0", scalar("SELECT count(*) FROM notification_safety_inbox"))
    }

    @Test
    fun rejectsShadowForgeryAndExtensionsBeforeStorage() {
        val e = data()
        for (bad in
            listOf(
                e + ("mode" to "SHADOW"),
                e + ("reason" to "private"),
                e - "occurredAt",
                e + ("schemaVersion" to 1.0),
                data(at = Instant.now().minusSeconds(86401)),
                data(at = Instant.now().plusSeconds(60)),
            )) {
            assertEquals(400, submit(bad).statusCode)
        }
        val json = mapper.writeValueAsString(e)
        for (bad in
            listOf(
                json + " {}",
                json.replace("\"ACTIVE\"", "\"ACTIVE\",\"mode\":\"ACTIVE\""),
            )) assertEquals(400, submit(bad).statusCode)
        assertEquals(413, submit(" ".repeat(2050)).statusCode)
        assertEquals(404, submit(e, PROD_KEY).statusCode)
        assertEquals(404, submit(e, "x".repeat(40)).statusCode)
        assertEquals(
            404,
            user(STAFF, true).contentType("application/json").body(e).post(path).statusCode,
        )
        assertEquals("0", scalar("SELECT count(*) FROM notification_safety_signal"))
        assertEquals(202, submit(data("prod"), PROD_KEY).statusCode)
        assertEquals(
            "0",
            scalar("SELECT count(*) FROM notification_safety_signal WHERE environment='stage'"),
        )
    }

    @Test
    fun audienceFailuresRemainRetryableAndLeaseFenceIsIrreversible() {
        assertEquals(202, submit(data()).statusCode)
        for (bad in listOf("down", "stale", "nonce", "env", "extra", "oversized")) {
            mode(bad)
            sql("UPDATE notification_safety_signal SET available_at=now()")
            worker.tick()
            assertEquals("PENDING", scalar("SELECT state FROM notification_safety_signal"))
            assertEquals("0", scalar("SELECT count(*) FROM notification_safety_inbox"))
        }
        sql("UPDATE notification_safety_signal SET available_at=now()")
        val old = store.claim(listOf(ModerationEnvironment.STAGE))!!
        assertNull(store.claim(listOf(ModerationEnvironment.STAGE)))
        sql("UPDATE notification_safety_signal SET lease_until=now()-interval '1 second'")
        val current = store.claim(listOf(ModerationEnvironment.STAGE))!!
        val a = SafetyAudience(setOf(UUID.fromString(STAFF)), Instant.now())
        assertFalse(store.complete(old, a))
        assertEquals(0, store.retry(old))
        assertTrue(store.complete(current, a))
        assertFalse(store.complete(current, a))
        assertEquals("1", scalar("SELECT count(*) FROM notification_safety_inbox"))
        assertEquals("1", scalar("SELECT count(*) FROM notification_safety_recipient"))
    }

    @Test
    fun everyReadAndReplayChecksCurrentAuthorityAndStoredMembership() {
        val ref = UUID.randomUUID()
        submit(data(ref = ref)).then().statusCode(202)
        mode("duplicate")
        worker.tick()
        mode("ok")
        assertEquals(
            "READY",
            scalar("SELECT state FROM notification_safety_signal"),
            "worker received and durably materialized a fresh all-users response",
        )
        assertEquals(
            setOf(UUID.fromString(STAFF)),
            authority.resolve(ModerationEnvironment.STAGE, UUID.fromString(STAFF))?.recipients,
            "Forge answered for the authenticated user",
        )
        assertEquals(
            1,
            store.page(ModerationEnvironment.STAGE, UUID.fromString(STAFF), 25, null)?.items?.size,
            "PG exposed the ready recipient to its private inbox",
        )
        user().get("/v1/notifications").then().statusCode(200)
        val first = user().get(inbox)
        assertEquals(200, first.statusCode)
        assertEquals("no-store", first.header("Cache-Control"))
        val item = mapper.readTree(first.asString())["items"].single()
        assertEquals(
            setOf("id", "type", "priority", "title", "body", "createdAt", "readAt", "action"),
            item.fieldNames().asSequence().toSet(),
        )
        assertEquals("MODERATION_SAFETY_SIGNAL", item["type"].asText())
        assertEquals("HIGH", item["priority"].asText())
        assertEquals(
            mapper.readTree(
                """{"kind":"OPEN_SAFETY_SIGNAL","environment":"stage","signalRef":"$ref"}"""
            ),
            item["action"],
        )
        val id = item["id"].asText()
        assertEquals(1, user().get("$inbox/unread-count").jsonPath().getInt("unreadCount"))
        repeat(2) { user().post("$inbox/$id/read").then().statusCode(204) }
        val readAt = user().get("$inbox/$id").jsonPath().getString("readAt")
        assertNotNull(readAt)
        for (bad in listOf("revoked", "down", "stale", "nonce", "extra", "foreign")) {
            mode(bad)
            for (target in listOf(inbox, "$inbox/unread-count", "$inbox/$id")) assertEquals(
                404,
                user().get(target).statusCode,
            )
            assertEquals(404, user().post("$inbox/$id/read").statusCode)
            assertEquals(404, user().post("$inbox/$id/unread").statusCode)
        }
        mode("ok")
        assertEquals(readAt, user().get("$inbox/$id").jsonPath().getString("readAt"))
        assertEquals(0, user().get("$inbox/unread-count").jsonPath().getInt("unreadCount"))
        user().post("$inbox/$id/unread").then().statusCode(204)
        assertEquals(1, user().get("$inbox/unread-count").jsonPath().getInt("unreadCount"))
        mode("foreign")
        assertEquals(404, user(OTHER).get("$inbox/$id").statusCode)
        assertEquals(404, user(OTHER).queryParam("cursor", id).get(inbox).statusCode)
        assertEquals(404, user(OTHER).post("$inbox/$id/read").statusCode)
        mode("ok")
        assertEquals(404, user().get("/v1/notifications/safety/prod/$id").statusCode)
        assertEquals(400, user().queryParam("userId", OTHER).get(inbox).statusCode)
        assertEquals(404, given().get(inbox).statusCode)
    }

    @Test
    fun privatePaginationRetentionAndGenericExclusion() {
        repeat(3) {
            submit(data()).then().statusCode(202)
            worker.tick()
        }
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(3) {
            val request = user().queryParam("limit", 1)
            if (cursor != null) request.queryParam("cursor", cursor)
            val page = request.get(inbox).then().statusCode(200).extract().jsonPath()
            assertTrue(seen.add(page.getString("items[0].id")))
            cursor = page.getString("nextCursor")
        }
        assertNull(cursor)
        assertTrue(
            user()
                .get("/v1/notifications")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList<Any>("items")
                .isEmpty()
        )
        for (table in
            listOf(
                "notifications",
                "notification_recipients",
                "notification_outbox",
                "notification_deliveries",
                "notification_actions",
            )) assertEquals("0", scalar("SELECT count(*) FROM $table"), table)
        for (route in listOf("diagnostics", "types", "deliveries", "recipients")) {
            val r = user(STAFF, true).get("/v1/admin/notifications/$route")
            assertEquals(200, r.statusCode)
            assertFalse(r.asString().contains("MODERATION_SAFETY_SIGNAL"))
            assertTrue(seen.none { r.asString().contains(it) })
        }
        sql("UPDATE notification_safety_signal SET expires_at=now()-interval '1 second'")
        assertEquals(0, user().get("$inbox/unread-count").jsonPath().getInt("unreadCount"))
        assertEquals(404, user().get("$inbox/${seen.first()}").statusCode)
        assertEquals(3, store.purge())
        assertEquals("0", scalar("SELECT count(*) FROM notification_safety_recipient"))
    }

    private fun channel(
        channel: String,
        scopes: List<String>,
        extra: Map<String, String> = emptyMap(),
    ) =
        user(STAFF, true)
            .contentType("application/json")
            .body(mapOf("channel" to channel, "scopes" to scopes) + extra)
            .post("/v1/admin/notifications/channel-clients")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()

    @Test
    fun mintedAndRotatedGenericScopesCannotSubmitOrForgePrivateSignals() {
        val client =
            channel(
                "api",
                listOf(
                    "notifications:write",
                    "notifications:safety:write",
                    "moderation.safety.receive",
                ),
            )
        val id = client.getString("client.id")
        val generic =
            mapOf(
                "idempotencyKey" to "private-forgery",
                "type" to "MODERATION_SAFETY_SIGNAL",
                "category" to "moderation",
                "priority" to "HIGH",
                "scope" to mapOf("type" to "network"),
                "actor" to mapOf("type" to "system"),
                "title" to "generic",
                "body" to "generic",
                "recipients" to listOf(mapOf("userId" to STAFF)),
            )
        for (i in 1..2) {
            val token =
                if (i == 1) client.getString("token")
                else
                    user(STAFF, true)
                        .post("/v1/admin/notifications/channel-clients/$id/rotate")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getString("token")
            assertEquals(404, submit(data(), token).statusCode)
            assertEquals(
                404,
                given()
                    .header("Authorization", "Bearer $token")
                    .contentType("application/json")
                    .body(data())
                    .post(path)
                    .statusCode,
            )
            val api =
                given().header("Authorization", "Bearer $token").contentType("application/json")
            assertEquals(400, api.body(generic).post("/v1/notification-events").statusCode)
            val navigation =
                mapOf(
                    "kind" to "OPEN_SAFETY_SIGNAL",
                    "actionKey" to "OpenSafetySignal",
                    "label" to "Open",
                    "style" to "DEFAULT",
                    "command" to "OpenSafetySignal",
                )
            for (action in listOf(navigation, navigation + ("kind" to "COMMAND"))) {
                val body = generic + ("type" to "GENERIC") + ("actions" to listOf(action))
                assertEquals(400, api.body(body).post("/v1/notification-events").statusCode)
            }
        }
        assertEquals("0", scalar("SELECT count(*) FROM notification_safety_signal"))
        assertEquals("0", scalar("SELECT count(*) FROM notifications"))
    }

    @Test
    fun readySafetySignalNeverTriggersGenericSseOrMinecraft() {
        live.reset()
        submit(data()).then().statusCode(202)
        worker.tick()
        val notice = user().get(inbox).jsonPath().getString("items[0].id")
        user().post("$inbox/$notice/read").then().statusCode(204)
        val minecraft =
            channel(
                    "minecraft",
                    listOf("minecraft.notifications.read"),
                    mapOf("serverId" to "server-1", "moderationEnvironment" to "stage"),
                )
                .getString("token")
        val player = UUID.randomUUID().toString()
        playerResolver.mapPlayer(player, STAFF)
        val batch =
            given()
                .header("Authorization", "Bearer $minecraft")
                .contentType("application/json")
                .body(mapOf("serverId" to "server-1", "playerUuids" to listOf(player)))
                .post("/v1/channel/minecraft/notifications/batch")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
        assertEquals(0, batch.getList<Any>("players").size)
        assertTrue(live.events.isEmpty())
        assertEquals("0", scalar("SELECT count(*) FROM notification_outbox"))
    }

    @Test
    fun hardCapacityAndBoundedPhysicalCleanupArePerEnvironment() {
        val e = data()
        submit(e).then().statusCode(202)
        sql(
            """INSERT INTO notification_safety_signal(environment,event_id,signal_ref,occurred_at_utc,expires_at)
          SELECT 'stage',gen_random_uuid(),gen_random_uuid(),'2026-01-01T00:00:00Z',now()+interval '1 hour' FROM generate_series(1,999)"""
        )
        assertEquals(503, submit(data()).statusCode)
        assertEquals(202, submit(e).statusCode)
        assertEquals(202, submit(data("prod"), PROD_KEY).statusCode)
        sql(
            "UPDATE notification_safety_signal SET expires_at=now()-interval '1 minute' WHERE environment='stage'"
        )
        assertEquals(32, store.purge())
        assertEquals(
            "968",
            scalar("SELECT count(*) FROM notification_safety_signal WHERE environment='stage'"),
        )
        assertEquals(
            "1",
            scalar("SELECT count(*) FROM notification_safety_signal WHERE environment='prod'"),
        )
    }

    @Test
    fun stalledForgeDoesNotOccupyTheOnlyDatabaseConnection() {
        submit(data()).then().statusCode(202)
        mode("hold")
        val port = config.getValue("notifications.safety.fixture-port", String::class.java)
        // Flyway needs two connections during startup; hold one to exercise a one-slot processing
        // pool.
        source.connection.use { _ ->
            HttpClient.newHttpClient().use { http ->
                fun probe(path: String) =
                    http
                        .send(
                            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/$path"))
                                .GET()
                                .build(),
                            HttpResponse.BodyHandlers.discarding(),
                        )
                        .statusCode()
                java.util.concurrent.Executors.newSingleThreadExecutor().use { executor ->
                    val job = executor.submit { worker.tick() }
                    try {
                        val until =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                        while (probe("held") != 204 && System.nanoTime() < until) Thread.sleep(10)
                        assertEquals(204, probe("held"))
                        // The HTTP handler is still blocked. A retained pool connection or
                        // transaction
                        // would stall this read.
                        assertEquals(
                            "LEASED",
                            scalar("SELECT state FROM notification_safety_signal"),
                        )
                        assertFalse(job.isDone)
                    } finally {
                        probe("release")
                    }
                    job.get(4, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        assertEquals("READY", scalar("SELECT state FROM notification_safety_signal"))
    }

    @Test
    fun authoritativeEmptySetCompletesWithoutDelivering() {
        mode("revoked")
        submit(data()).then().statusCode(202)
        worker.tick()
        assertEquals("READY", scalar("SELECT state FROM notification_safety_signal"))
        assertEquals("1", scalar("SELECT count(*) FROM notification_safety_inbox"))
        assertEquals("0", scalar("SELECT count(*) FROM notification_safety_recipient"))
    }
}
