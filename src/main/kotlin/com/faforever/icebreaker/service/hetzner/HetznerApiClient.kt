package com.faforever.icebreaker.service.hetzner

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@ApplicationScoped
@RegisterRestClient(configKey = "hetzner")
@Consumes(MediaType.APPLICATION_JSON)
interface HetznerApiClient {
    @POST
    @Path("/firewalls/{id}/actions/set_rules")
    fun setFirewallRules(
        @PathParam("id") id: String,
    ): String
}
