package com.faforever.icebreaker.web

import com.faforever.icebreaker.service.SessionDetails
import com.faforever.icebreaker.service.xirsys.XirsysSessionManager
import io.quarkus.security.PermissionsAllowed
import jakarta.inject.Singleton
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import org.jboss.resteasy.reactive.RestPath

@Path("/session")
@Singleton
class SessionController(private val sessionManager: XirsysSessionManager) {

    @GET
    @Path("/{sessionId}")
    @PermissionsAllowed("USER:lobby")
    fun getSession(@RestPath sessionId: String): SessionDetails {
        if (!sessionManager.listSessions().contains(sessionId)) {
            sessionManager.createSession(sessionId)
        }

        return sessionManager.getIceServers(sessionId)
    }
}
