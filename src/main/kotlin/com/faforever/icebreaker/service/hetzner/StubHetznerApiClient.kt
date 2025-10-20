package com.faforever.icebreaker.service.hetzner

import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative
import org.eclipse.microprofile.rest.client.inject.RestClient
import java.util.concurrent.atomic.AtomicInteger

/**
 * Used in tests and the dev environment to disable actual calls to the Hetzner API.
 */
@RestClient
@Alternative
@ApplicationScoped
class StubHetznerApiClient : HetznerApiClient {
    private val _callCount = AtomicInteger(0)

    override fun setFirewallRules(id: String): String {
        _callCount.incrementAndGet()
        return "success"
    }

    val callCount get() = _callCount.get()

    fun resetCallCount() {
        _callCount.set(0)
    }
}
