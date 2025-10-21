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
    private val _callCount = AtomicInteger(0)
    private val _rulesByFirewallId: MutableMap<String, List<FirewallRule>> = ConcurrentHashMap()

    override fun setFirewallRules(id: String, request: SetFirewallRulesRequest): SetFirewallRulesResponse {
        _rulesByFirewallId[id] = request.rules
        _callCount.incrementAndGet()
        return SetFirewallRulesResponse(listOf())
    }

    val callCount get() = _callCount.get()

    val rulesByFirewallId: Map<String, List<FirewallRule>> get() = _rulesByFirewallId

    fun resetCallCount() {
        _callCount.set(0)
    }
}
