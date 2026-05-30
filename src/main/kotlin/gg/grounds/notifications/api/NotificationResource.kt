package gg.grounds.notifications.api

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/notifications")
class NotificationResource {
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    fun status(): NotificationStatus = NotificationStatus(status = "ok")
}

data class NotificationStatus(val status: String)
