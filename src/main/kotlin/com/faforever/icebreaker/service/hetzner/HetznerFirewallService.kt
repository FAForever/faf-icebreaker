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
    /** Whitelists [ipAddress] for session [sessionId]. */
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: InetAddress) {
        LOG.debug("Whitelisting IP {} for session {} in Hetzner cloud firewall", ipAddress, sessionId)
        repository.insert(sessionId, userId, ipAddress)
        // TODO(#132): metric for the number of whitelisted sessions
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String) {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.removeSession(sessionId)
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String) {
        LOG.debug("Removeing user {}'s whitelist for session {}", userId, sessionId)
        repository.removeSessionUser(sessionId, userId)
    }
}
