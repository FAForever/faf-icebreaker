package com.faforever.icebreaker.web

import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionService
import io.quarkus.security.PermissionsAllowed
import jakarta.inject.Singleton
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestPath

@Path("/session")
@Singleton
class SessionController(private val sessionService: SessionService) {

    @GET
    @Path("/game/{gameId}")
    @PermissionsAllowed("USER:lobby")
    fun getSession(@RestPath gameId: Long): Session =
        sessionService.getSession(gameId)
}
