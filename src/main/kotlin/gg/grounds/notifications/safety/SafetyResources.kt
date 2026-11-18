package gg.grounds.notifications.safety

import com.fasterxml.jackson.databind.ObjectMapper
import gg.grounds.notifications.moderation.ModerationEnvironment
import io.quarkus.scheduler.Scheduled
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.*
import jakarta.ws.rs.container.*
import jakarta.ws.rs.core.*
import jakarta.ws.rs.ext.Provider
import java.io.InputStream
import java.util.UUID
import org.eclipse.microprofile.jwt.JsonWebToken

@NameBinding
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SafetyPrivateResponse

@Provider
@SafetyPrivateResponse
class SafetyCacheControl : ContainerResponseFilter {
    override fun filter(request: ContainerRequestContext, response: ContainerResponseContext) {
        response.headers.putSingle("Cache-Control", "no-store")
    }
}

private fun absent(): Nothing =
    throw NotFoundException(Response.status(404).header("Cache-Control", "no-store").build())

private fun invalid(): Nothing =
    throw BadRequestException(Response.status(400).header("Cache-Control", "no-store").build())

@Path("/v1/internal/moderation-safety-signals")
@SafetyPrivateResponse
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class SafetyProducerResource(
    private val gate: SafetyGate,
    private val store: SafetyStore,
    private val mapper: ObjectMapper,
) {
    @POST
    fun receive(
        input: InputStream,
        @Context headers: HttpHeaders,
        @Context uri: UriInfo,
    ): Response {
        val env =
            gate.authenticate(
                headers.getRequestHeader("X-Grounds-Safety-Producer-Token")?.singleOrNull()
            ) ?: absent()
        if (uri.queryParameters.isNotEmpty()) invalid()
        val bytes = input.readNBytes(2049)
        if (bytes.size > 2048) return Response.status(413).build()
        val e =
            try {
                SafetyEnvelope.parse(mapper, bytes)
            } catch (_: Exception) {
                invalid()
            }
        if (e.environment != env) absent()
        return when (store.admit(e)) {
            Admission.ACCEPTED -> Response.accepted(SafetyAccepted(e.eventId)).build()
            Admission.CONFLICT -> Response.status(409).build()
            Admission.EXPIRED -> Response.status(400).build()
            Admission.FULL -> Response.status(503).build()
        }
    }
}

@ApplicationScoped
class SafetyResolutionWorker(
    private val gate: SafetyGate,
    private val store: SafetyStore,
    private val authority: SafetyAudienceAuthority,
) {
    @Scheduled(every = "2s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun tick() {
        // Physical retention continues after an environment is switched off, using the service
        // database.
        store.purge()
        val lease = store.claim(envs.filter(gate::enabled)) ?: return
        try {
            val audience = authority.resolve(lease.env, null)
            if (audience != null && gate.enabled(lease.env) && store.complete(lease, audience))
                return
        } catch (_: Exception) {
            /* The lease is recoverable even if the retry write also fails. */
        }
        store.retry(lease)
    }
}

@Path("/v1/notifications/safety/{environment}")
@Produces(MediaType.APPLICATION_JSON)
@SafetyPrivateResponse
class SafetyInboxResource(
    private val gate: SafetyGate,
    private val store: SafetyStore,
    private val authority: SafetyAudienceAuthority,
    private val jwt: JsonWebToken,
    private val identity: SecurityIdentity,
) {
    private data class Access(
        val env: ModerationEnvironment,
        val user: UUID,
        val audience: SafetyAudience,
    )

    private fun access(environment: String): Access {
        val env = ModerationEnvironment.parse(environment)?.takeIf(gate::enabled) ?: absent()
        val user =
            runCatching { if (identity.isAnonymous) null else uuid(jwt.subject) }.getOrNull()
                ?: absent()
        val audience = authority.resolve(env, user)?.takeIf { user in it.recipients } ?: absent()
        return Access(env, user, audience)
    }

    private fun params(uri: UriInfo, vararg allowed: String): Map<String, String> =
        uri.queryParameters.mapValues { (key, values) ->
            if (key !in allowed) invalid()
            values.singleOrNull() ?: invalid()
        }

    private fun <T> fresh(a: Access, lookup: () -> T?): T {
        val value =
            try {
                lookup()
            } catch (_: java.sql.SQLException) {
                absent()
            } ?: absent()
        if (!a.audience.fresh() || !gate.enabled(a.env)) absent()
        return value
    }

    @GET
    fun list(@PathParam("environment") env: String, @Context uri: UriInfo): SafetyInboxPage {
        val a = access(env)
        val q = params(uri, "limit", "cursor")
        val limit =
            q["limit"]?.let { value ->
                value.takeIf { it.matches(Regex("[1-9][0-9]?")) }?.toInt()?.takeIf { it <= 50 }
                    ?: invalid()
            } ?: 25
        val cursor = q["cursor"]?.let { uuid(it) ?: absent() }
        return fresh(a) { store.page(a.env, a.user, limit, cursor) }
    }

    @GET
    @Path("unread-count")
    fun count(@PathParam("environment") env: String, @Context uri: UriInfo): SafetyUnreadCount {
        val a = access(env)
        params(uri)
        return fresh(a) { store.unread(a.env, a.user) }
    }

    @GET
    @Path("{id}")
    fun detail(
        @PathParam("environment") env: String,
        @PathParam("id") id: String,
        @Context uri: UriInfo,
    ): SafetyInboxItem {
        val a = access(env)
        params(uri)
        return fresh(a) { store.detail(a.env, a.user, uuid(id) ?: absent()) }
    }

    @POST
    @Path("{id}/{state:read|unread}")
    fun mark(
        @PathParam("environment") env: String,
        @PathParam("id") id: String,
        @PathParam("state") state: String,
        @Context uri: UriInfo,
    ): Response {
        val a = access(env)
        params(uri)
        if (
            !fresh(a) {
                store.mark(a.env, a.user, uuid(id) ?: absent(), state == "read", a.audience)
            }
        )
            absent()
        return Response.noContent().build()
    }
}
