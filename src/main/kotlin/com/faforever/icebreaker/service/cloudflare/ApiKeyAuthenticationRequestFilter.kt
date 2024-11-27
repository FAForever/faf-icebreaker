package com.faforever.icebreaker.service.cloudflare

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders

@Priority(Priorities.AUTHENTICATION)
class ApiKeyAuthenticationRequestFilter(turnApiKey: String) : ClientRequestFilter {
    override fun filter(requestContext: ClientRequestContext) {
        requestContext.headers.add(HttpHeaders.AUTHORIZATION, accessToken)
    }

    private val accessToken: String = "Bearer $turnApiKey"
}
