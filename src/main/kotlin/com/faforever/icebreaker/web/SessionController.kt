package com.faforever.icebreaker.web

import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionService
import io.quarkus.security.PermissionsAllowed
import jakarta.inject.Singleton
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestPath

@Path("/session")
@Singleton
class SessionController(private val sessionService: SessionService) {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/game/{gameId}")
    @PermissionsAllowed("USER:lobby")
    fun getSession(@RestPath gameId: Long): Session {
        val sessionId: String
        sessionService.lockGameId(gameId).use {
            sessionId = sessionService.getSessionId(gameId)
        }
        return sessionService.getSession(sessionId)
    }

    @GET
    @Produces(MEDIA_TYPE_JSON_API)
    @Path("/game/{gameId}")
    @PermissionsAllowed("USER:lobby")
    fun getSessionJsonApi(@RestPath gameId: Long): JsonApiResponse =
        getSession(gameId).let {
            JsonApiResponse.fromObject(
                JsonApiObject(
                    type = "iceSession",
                    id = it.id,
                    attributes = mapOf("servers" to it.servers),
                ),
            )
        }
}
