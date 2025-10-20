package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import io.quarkus.scheduler.Scheduled
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

private val LOG: Logger = LoggerFactory.getLogger(HetznerFirewallService::class.java)

@Singleton
class HetznerFirewallService(
    private val repository: FirewallWhitelistRepository,
    @RestClient private val client: HetznerApiClient,
    private val clock: Clock,
) {
    private val lastDbModificationTime = AtomicLong(0)
    private var lastUpstreamRequestTime = 0L

    /** Whitelists [ipAddress] for session [sessionId]. */
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: String) {
        LOG.debug("Whitelisting IP {} for session {} in Hetzner cloud firewall", ipAddress, sessionId)
        repository.insert(sessionId, userId, ipAddress)
        lastDbModificationTime.set(clock.instant().epochSecond)
        // TODO(#132): metric for the number of whitelisted sessions
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String) {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.removeSession(sessionId)
        lastDbModificationTime.set(clock.instant().epochSecond)
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String) {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        repository.removeSessionUser(sessionId, userId)
        lastDbModificationTime.set(clock.instant().epochSecond)
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun syncFirewallWithHetzner() {
        val lastDbModTime = lastDbModificationTime.get()

        if (lastDbModTime < lastUpstreamRequestTime) {
            // We check < rather than <= since our timestamps only have second granularity.
            // If a request comes in at 10.1s then we check whether we should
            // sync at 10.5s, we need to make sure we sync the update from that request.
            // This does mean we might make some unnecessary API calls.
            LOG.trace("No database changes since last sync, skipping Hetzner API call")
            return
        }

        val activeEntries = repository.getAllActive()
        LOG.debug("Syncing {} active firewall entries with Hetzner API", activeEntries.size)

        // TODO(#132): call the API with real firewall rules
        client.setFirewallRules("firewall-id")
        lastUpstreamRequestTime = clock.instant().epochSecond
        LOG.debug("Successfully updated Hetzner firewall rules")
    }
}
