package com.faforever.icebreaker.web

import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.SessionService
import io.quarkus.security.PermissionsAllowed
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType

@Path("/server")
class ServerController(private val sessionService: SessionService) {
    data class ServerList(val servers: List<Server>)

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("")
    @PermissionsAllowed("USER:lobby")
    fun getSession(): ServerList = ServerList(sessionService.getServers())

    @GET
    @Produces(MEDIA_TYPE_JSON_API)
    @Path("")
    @PermissionsAllowed("USER:lobby")
    fun getSessionJsonApi(): JsonApiResponse = JsonApiResponse.fromList(
        sessionService.getServers().map {
            JsonApiObject(
                type = "iceServer",
                id = it.id,
                attributes = mapOf("region" to it.region),
            )
        },
    )
}
