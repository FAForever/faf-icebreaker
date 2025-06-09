package com.faforever.icebreaker.util

import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientResponseContext
import jakarta.ws.rs.client.ClientResponseFilter
import jakarta.ws.rs.ext.Provider
import org.slf4j.Logger

@Provider
class ErrorLoggingFilter(private val logger: Logger) : ClientResponseFilter {

    override fun filter(requestContext: ClientRequestContext, responseContext: ClientResponseContext) {
        if (responseContext.status >= 400) {
            logger.error("Request to ${requestContext.uri} failed with status ${responseContext.status}")
        }
    }
}
