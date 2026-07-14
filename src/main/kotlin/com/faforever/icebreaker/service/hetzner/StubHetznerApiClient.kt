package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Used in tests and the dev environment to disable actual calls to the Hetzner API.
 */
@RestClient
@Alternative
@ApplicationScoped
class StubHetznerApiClient : HetznerApiClient {
    private val callCount = AtomicInteger(0)
    private val rulesByFirewallId: MutableMap<String, List<FirewallRule>> = ConcurrentHashMap()

    /** When set, [setFirewallRules] throws to simulate a failing Hetzner API (e.g. a 403). */
    @Volatile
    var failRequests: Boolean = false

    override fun setFirewallRules(id: String, request: SetFirewallRulesRequest): SetFirewallRulesResponse {
        callCount.incrementAndGet()
        if (failRequests) {
            throw IOException("Simulated Hetzner API failure")
        }
        rulesByFirewallId[id] = request.rules
        return SetFirewallRulesResponse(listOf())
    }

    fun getCallCount(): Int = callCount.get()

    fun getRulesByFirewallId(id: String) = rulesByFirewallId[id]

    fun resetCallCount() {
        callCount.set(0)
    }

    fun reset() {
        callCount.set(0)
        rulesByFirewallId.clear()
        failRequests = false
    }
}
