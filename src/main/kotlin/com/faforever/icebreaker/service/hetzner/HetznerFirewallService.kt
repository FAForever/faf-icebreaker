package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetAddress

private val LOG: Logger = LoggerFactory.getLogger(HetznerFirewallService::class.java)

@Singleton
class HetznerFirewallService(
    private val repository: FirewallWhitelistRepository,
) {
    fun whitelistIpForSession(sessionId: String, ipAddress: InetAddress) {
        LOG.debug("Whitelisting IP {} for session {} in Hetzner cloud firewall", ipAddress, sessionId)
        repository.insert(sessionId, ipAddress)
        // TODO(#132): metric for the number of whitelisted sessions
    }
}
