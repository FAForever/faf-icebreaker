package com.faforever.icebreaker.security

import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.jwt.JsonWebToken

@ApplicationScoped
class CurrentUserService(
    private val securityIdentity: SecurityIdentity,
) {
    fun getCurrentUserId(): Long? {
        val principal = securityIdentity.principal as? JsonWebToken
        return principal?.subject?.toLongOrNull()
    }
}
