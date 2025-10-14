package com.faforever.icebreaker.web

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.EventMessage
import com.faforever.icebreaker.service.LogMessage
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionService
import io.quarkus.runtime.annotations.RegisterForReflection
import io.quarkus.security.PermissionsAllowed
import io.smallrye.mutiny.Multi
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Singleton
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.MediaType
import org.jboss.resteasy.reactive.RestPath
import org.jboss.resteasy.reactive.RestStreamElementType
import java.net.InetAddress

@Path("/session")
@Singleton
class SessionController(
    private val sessionService: SessionService,
    private val fafProperties: FafProperties,
    @Context private val httpRequest: HttpServerRequest,
) {
    @RegisterForReflection
    data class TokenRequest(
        val gameId: Long,
    )

    @RegisterForReflection
    data class TokenResponse(
        val jwt: String,
    )

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/token")
    fun buildToken(tokenRequest: TokenRequest): TokenResponse =
        sessionService
            .buildToken(tokenRequest.gameId)
            .let { TokenResponse(it) }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/game/{gameId}")
    @PermissionsAllowed("USER:lobby")
    fun getSession(
        @RestPath gameId: Long,
    ): Session = sessionService.getSession(gameId, httpRequest.getIp().getHostAddress())

    @GET
    @Produces(MEDIA_TYPE_JSON_API)
    @Path("/game/{gameId}")
    @PermissionsAllowed("USER:lobby")
    fun getSessionJsonApi(
        @RestPath gameId: Long,
    ): JsonApiResponse =
        getSession(gameId).let {
            JsonApiResponse.fromObject(
                JsonApiObject(
                    type = "iceSession",
                    id = it.id,
                    attributes = mapOf("servers" to it.servers),
                ),
            )
        }

    @POST
    @Path("/game/{gameId}/events")
    @PermissionsAllowed("USER:lobby")
    @Consumes(MediaType.APPLICATION_JSON)
    fun postEvent(@RestPath gameId: Long, eventMessage: EventMessage) {
        sessionService.onMessageReceived(gameId, eventMessage)
    }

    @GET
    @Path("/game/{gameId}/events")
    @PermissionsAllowed("USER:lobby")
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    fun getSessionEvents(@RestPath gameId: Long): Multi<EventMessage> = sessionService.listenForEventMessages(gameId)

    @POST
    @Path("/game/{gameId}/logs")
    @PermissionsAllowed("USER:lobby")
    @Consumes(MediaType.APPLICATION_JSON)
    fun postLogs(@RestPath gameId: Long, logRequest: List<LogMessage>) {
        sessionService.onLogsPushed(gameId, logRequest)
    }

    private fun HttpServerRequest.getIp() =
        InetAddress.getByName(getHeader(fafProperties.realIpHeader()) ?: remoteAddress().host())
}
