package com.faforever.icebreaker.web

import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.ExceptionMapper
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.ConfigProvider
import org.slf4j.LoggerFactory

private val LOG: org.slf4j.Logger = LoggerFactory.getLogger(GlobalExceptionMapper::class.java)

@Provider
class GlobalExceptionMapper : ExceptionMapper<Throwable> {

    private val isDevMode: Boolean by lazy {
        val profile = ConfigProvider.getConfig().getOptionalValue("quarkus.profile", String::class.java)
        profile.orElse("prod").equals("dev", ignoreCase = true)
    }

    override fun toResponse(exception: Throwable): Response {
        LOG.error("Unhandled exception caught by GlobalExceptionMapper", exception)

        val errorBody = mutableMapOf<String, Any>(
            "details" to (exception.message ?: "Unknown error"),
        )

        if (isDevMode) {
            errorBody["stack"] = exception.stackTraceToString()
        }

        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(errorBody)
            .type(MediaType.APPLICATION_JSON)
            .build()
    }
}
