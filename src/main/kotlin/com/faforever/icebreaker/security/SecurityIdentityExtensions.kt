package com.faforever.icebreaker.security

import io.quarkus.security.UnauthorizedException
import io.quarkus.security.identity.SecurityIdentity
import org.eclipse.microprofile.jwt.JsonWebToken

fun SecurityIdentity.getUserId(): Int =
    when (val principal = principal) {
        null -> throw UnauthorizedException("No principal available")
        is JsonWebToken -> principal.subject.toInt()
        else -> throw IllegalStateException("Unexpected principal type: ${principal.javaClass} ($principal)")
    }
