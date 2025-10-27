package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol
import io.quarkus.scheduler.Scheduled
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlin.jvm.optionals.getOrNull

private val LOG: Logger = LoggerFactory.getLogger(HetznerFirewallService::class.java)

/**
 * Converts an IP address string to CIDR notation.
 *
 * Returns null if the IP address cannot be parsed or is of unknown type.
 */
private fun String.toCidr(): String? = try {
    val inetAddress = InetAddress.getByName(this)
    when (inetAddress) {
        is Inet4Address -> "$this/32"
        is Inet6Address -> "$this/128"
        else -> {
            LOG.warn("Unknown IP address type for {}", this)
            null
        }
    }
} catch (e: Exception) {
    LOG.warn("Failed to parse IP address {}: {}", this, e.message)
    null
}

@Singleton
class HetznerFirewallService(
    private val hetznerProperties: HetznerProperties,
    private val repository: FirewallWhitelistRepository,
    @RestClient private val client: HetznerApiClient,
    private val clock: Clock,
) {
    private val lastDbModificationTime = AtomicLong(0)
    private var lastUpstreamRequestTime = 0L

    /** Whitelists [ipAddress] for session [sessionId]. */
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: String) {
        LOG.debug("Whitelisting IP {} for session {} in Hetzner cloud firewall", ipAddress, sessionId)
        repository.persist(
            FirewallWhitelistEntity(
                userId = userId,
                sessionId = sessionId,
                allowedIp = ipAddress,
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
        lastDbModificationTime.set(clock.instant().epochSecond)
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String) {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.markSessionAsDeleted(sessionId)
        lastDbModificationTime.set(clock.instant().epochSecond)
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String) {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        repository.markSessionUserAsDeleted(sessionId, userId)
        lastDbModificationTime.set(clock.instant().epochSecond)
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun syncFirewallWithHetzner() {
        val firewall = hetznerProperties.firewallId().getOrNull() ?: return

        val lastDbModTime = lastDbModificationTime.get()

        if (lastDbModTime < lastUpstreamRequestTime) {
            // We check < rather than <= since our timestamps only have second granularity.
            // If a request comes in at 10.1s then we check whether we should
            // sync at 10.5s, we need to make sure we sync the update from that request.
            // This does mean we might make some unnecessary API calls.
            LOG.trace("No database changes since last sync, skipping Hetzner API call")
            return
        }

        val sourceIps = repository.getAllActive().mapNotNull { entry ->
            entry.allowedIp.trim().toCidr()
        }.distinct()
        val sourceBlocks: List<List<String>> = sourceIps.chunked(hetznerProperties.maxIpsPerRule())
        val rules =
            sourceBlocks.flatMap { sources ->
                listOf(
                    // We don't specify the ports for either rule, because the port might
                    // be different on each TURN server.
                    FirewallRule(
                        direction = Direction.IN,
                        sourceIps = sources,
                        protocol = Protocol.TCP,
                    ),
                    FirewallRule(
                        direction = Direction.IN,
                        sourceIps = sources,
                        protocol = Protocol.UDP,
                    ),
                )
            }
        LOG.info("Syncing {} active whitelist IPs to Hetzner firewall {}", sourceIps.size, firewall)
        val request = SetFirewallRulesRequest(rules)
        LOG.debug("Hetzner request summary: rules={}, totalSourceIps={}", rules.size, sourceIps.size)
        // It's important that an empty list of actions counts as success, since we might
        // re-apply the current whitelist without changes due to our "last applied" timestamp
        // only having second granularity.
        val response = try {
            client.setFirewallRules(firewall, request)
        } catch (e: Exception) {
            LOG.error("Failed to update Hetzner firewall rules", e)
            return
        }

        val success = response.actions.all { it.error == null }

        if (success) {
            lastUpstreamRequestTime = clock.instant().epochSecond
            LOG.info("Successfully updated Hetzner firewall rules")
        } else {
            LOG.error("Failed to update Hetzner firewall rules")
        }
    }
}
