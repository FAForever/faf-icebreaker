package com.faforever.icebreaker.security

import com.faforever.icebreaker.config.FafProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.quarkus.oidc.TenantResolver
import io.vertx.core.http.HttpHeaders.AUTHORIZATION
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped

/**
 * This application supports 2 JWT providers:
 *
 * * "Official" FAF access token from Ory Hydra (hydra.faforever.com)
 * * Self-signed JWTs inside this service that are used for extended session token.
 *
 *  This tenant resolver selects the right provider based on the issuer of the JWT.
 */
@ApplicationScoped
class CustomTenantResolver(
    private val fafProperties: FafProperties,
    private val objectMapper: ObjectMapper,
) : TenantResolver {
    override fun resolve(context: RoutingContext): String? =
        context
            .request()
            .getHeader(AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.let {
                val rawToken = it.substring(7)
                val body =
                    java.util.Base64
                        .getDecoder()
                        .decode(rawToken.split(".")[1])
                val json = objectMapper.readTree(body)

                json["iss"]?.textValue()
            }?.takeIf { it == fafProperties.selfUrl() }
            ?.let { "self-tenant" }
}
