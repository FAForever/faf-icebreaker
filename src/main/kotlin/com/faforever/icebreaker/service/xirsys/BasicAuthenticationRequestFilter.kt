package com.faforever.icebreaker.service.xirsys

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import java.util.Base64

@Priority(Priorities.AUTHENTICATION)
class BasicAuthenticationRequestFilter(username: String, password: String) : ClientRequestFilter {
    override fun filter(requestContext: ClientRequestContext) {
        requestContext.headers.add(HttpHeaders.AUTHORIZATION, accessToken)
    }

    private val accessToken: String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())
}
