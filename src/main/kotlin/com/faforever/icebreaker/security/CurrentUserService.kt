package com.faforever.icebreaker.security

import com.faforever.icebreaker.config.FafProperties
import io.quarkus.security.identity.SecurityIdentity
import io.vertx.core.http.HttpServerRequest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Context
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class CurrentUserService(
    private val securityIdentity: SecurityIdentity,
    private val fafProperties: FafProperties,
    @Context private val httpRequest: HttpServerRequest,
) {
    fun getCurrentUserId(): Long? {
        val principal = securityIdentity.principal as? JsonWebToken
        return principal?.subject?.toLongOrNull()
    }

    fun getCurrentUserIp(): String =
        httpRequest.getHeader(fafProperties.realIpHeader())
            ?: httpRequest.remoteAddress().host()
}
