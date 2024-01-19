package com.faforever.icebreaker.security

import io.quarkus.oidc.runtime.OidcJwtCallerPrincipal
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped

@ApplicationScoped
class CurrentUserService(
    private val securityIdentity: SecurityIdentity,
) {
    fun getCurrentUserId(): Long? {
        val principal = (securityIdentity.principal as? OidcJwtCallerPrincipal)
        val subject = principal?.claims?.claimsMap?.get("sub") as? String

        return subject?.toLong()
    }
}
