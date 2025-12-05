package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Clock
import java.util.Queue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
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

/**
 * Processes requests to the Hetzner API in batches.
 *
 * In the future, we might run more than one instance of the icebreaker server.
 * If so, we would need to split this class into two: one that writes each request
 * to the DB (containing the methods apart from [syncFirewallWithHetzner]) and
 * one that does the batch update to Hetzner. Between them, we would use RabbitMQ
 * in "single active consumer" mode to ensure than only one of the server's replicas
 * is sending queries to Hetzner.
 */
@Singleton
class HetznerFirewallService(
    private val hetznerProperties: HetznerProperties,
    private val repository: FirewallWhitelistRepository,
    @RestClient private val client: HetznerApiClient,
    private val clock: Clock,
) {
    // Requests waiting to be resolved the next time [syncFirewallWithHetzner] runs.
    private val requestQueue = ConcurrentLinkedQueue<CompletableFuture<Unit>>()

    /** Whitelists [ipAddress] for session [sessionId]. */
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: String): Uni<Unit> {
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
        val future = CompletableFuture<Unit>()
        requestQueue.add(future)
        return Uni.createFrom().completionStage(future)
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String): Uni<Unit> {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.markSessionAsDeleted(sessionId)
        val future = CompletableFuture<Unit>()
        requestQueue.add(future)
        return Uni.createFrom().completionStage(future)
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String): Uni<Unit> {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        repository.markSessionUserAsDeleted(sessionId, userId)
        val future = CompletableFuture<Unit>()
        requestQueue.add(future)
        return Uni.createFrom().completionStage(future)
    }

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun syncFirewallWithHetzner() {
        val firewall = hetznerProperties.firewallId().getOrNull() ?: return

        val batch = takeAll(requestQueue)
        if (batch.isEmpty()) {
            LOG.trace("No changes to apply for firewall ID {}", firewall)
            return
        }

        try {
            val request = buildSetFirewallRequest()
            LOG.info("Syncing {} rules with Hetzner firewall {}", request.rules.size, firewall)
            val response = client.setFirewallRules(firewall, request)
            // It is important that "no actions" is a success: it
            // could happen that a request thread updates the DB, then
            // syncFirewallWithHetzner runs, then the request thread
            // creates its future and pushes it to the queue. In that case,
            // syncFirewallWithHetzner will apply the update to the firewall but
            // won't complete the future until the next time it runs, when it
            // won't make any changes to the firewall. We still want to count
            // the future as successfully updated. (There are also weird cases
            // like the second request failing, causing the future to be incorrectly
            // failed despite the firewall being correctly updated in the first request;
            // we ignore these cases.)
            val success = response.actions.all { it.error == null }
            if (success) {
                LOG.info("Successfully updated Hetzner firewall rules")
                batch.forEach { it.complete(Unit) }
            } else {
                LOG.error("Failed to update Hetzner firewall rules: API request failed")
                batch.forEach { it.completeExceptionally(IOException("Hetzner API request failed")) }
            }
        } catch (e: Exception) {
            LOG.error("Failed to update Hetzner firewall rules", e)
            batch.forEach { it.completeExceptionally(e) }
        }
    }

    private fun buildSetFirewallRequest(): SetFirewallRulesRequest {
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
        val request = SetFirewallRulesRequest(rules)
        LOG.debug("Hetzner request summary: rules={}, totalSourceIps={}", rules.size, sourceIps.size)
        return request
    }
}

private fun <T> takeAll(queue: Queue<T>): List<T> {
    val result = mutableListOf<T>()
    while (true) {
        val x = queue.poll() ?: break
        result.add(x)
    }
    return result
}
