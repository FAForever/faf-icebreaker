package com.faforever.icebreaker.service.hetzner
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

/**
 * Request object for /firewalls/{id}/actions/set_rules.
 *
 * Documented at https://docs.hetzner.cloud/reference/cloud#firewall-actions-set-rules
 */
data class SetFirewallRulesRequest(
    val rules: List<FirewallRule>,
)

// We only include the fields that we set - see Hetzner docs for the full list.
data class FirewallRule(
    val direction: Direction,
    val sourceIps: List<String> = emptyList(),
    val protocol: Protocol,
) {
    @JsonProperty("source_ips")
    val sourceIpsJson = sourceIps
}

enum class Direction {
    @JsonProperty("in")
    IN,

    @JsonProperty("out")
    OUT,
}

enum class Protocol {
    @JsonProperty("tcp")
    TCP,

    @JsonProperty("udp")
    UDP,
}

/**
 * Response object for /firewalls/{id}/actions/set_rules.
 *
 * Documented at https://docs.hetzner.cloud/reference/cloud#firewall-actions-set-rules
 */
data class SetFirewallRulesResponse(
    val actions: List<Action>,
)

data class Action(
    val id: Long,
    val command: String,
    val status: ActionStatus,
    val progress: Int,
    val started: String,
    val finished: String?,
    val resources: List<ActionResource>,
    val error: ActionError?,
)

data class ActionResource(
    val id: Long,
    val type: String,
)

data class ActionError(
    val code: String,
    val message: String,
)

enum class ActionStatus {
    @JsonProperty("running")
    RUNNING,

    @JsonProperty("success")
    SUCCESS,

    @JsonProperty("error")
    ERROR,
}

@ApplicationScoped
@RegisterRestClient(configKey = "hetzner")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
interface HetznerApiClient {
    @POST
    @ClientHeaderParam(name = "Authorization", value = arrayOf("Bearer \${hetzner.api-key}"))
    @Path("/firewalls/{id}/actions/set_rules")
    fun setFirewallRules(
        @PathParam("id") id: String,
        request: SetFirewallRulesRequest,
    ): SetFirewallRulesResponse
}
