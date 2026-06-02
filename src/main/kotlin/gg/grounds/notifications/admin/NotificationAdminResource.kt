package gg.grounds.notifications.admin

import gg.grounds.notifications.auth.AdminAuthorizationService
import io.quarkus.security.Authenticated
import io.quarkus.security.identity.SecurityIdentity
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/v1/admin/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
class NotificationAdminResource(
    private val repository: NotificationAdminRepository,
    private val authorization: AdminAuthorizationService,
    private val identity: SecurityIdentity,
) {
    @GET
    @Path("/diagnostics")
    @Authenticated
    fun diagnostics(): NotificationAdminDiagnosticsResponse {
        authorization.requireNotificationsAdmin(identity)
        return repository.diagnostics()
    }
}
