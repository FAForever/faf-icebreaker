package com.faforever.icebreaker.service.hetzner

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.rest.client.inject.RestClient
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

    override fun setFirewallRules(id: String, request: SetFirewallRulesRequest): SetFirewallRulesResponse {
        rulesByFirewallId[id] = request.rules
        callCount.incrementAndGet()
        return SetFirewallRulesResponse(listOf())
    }

    fun getCallCount(): Int = callCount.get()

    fun getRulesByFirewallId(id: String) = rulesByFirewallId[id]

    fun resetCallCount() {
        callCount.set(0)
    }
}
