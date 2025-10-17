package com.faforever.icebreaker.service.cloudflare

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@ApplicationScoped
@RegisterRestClient
@Consumes(MediaType.APPLICATION_JSON)
interface CloudflareApiClient {

    data class CredentialRequest(val ttl: Long, val customIdentifier: String?)

    data class CredentialResponse(val iceServers: IceServers) {
        data class IceServers(val urls: List<String>, val username: String, val credential: String)
    }

    @POST
    @Path("/v1/turn/keys/{turnKeyId}/credentials/generate")
    fun requestCredentials(
        @PathParam("turnKeyId") turnKeyId: String,
        credentialRequest: CredentialRequest,
    ): CredentialResponse
}
