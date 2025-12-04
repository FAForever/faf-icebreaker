package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol
import io.quarkus.scheduler.Scheduled
import io.smallrye.mutiny.Uni
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Singleton
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Clock
import java.time.Duration
import java.util.Queue
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
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

/** Requests a sync or confirms that the requested sync has been successfully completed. */
data class SyncMessage(
    /** A unique identifier for this request/response pair. Used to pair requests and responses. */
    val id: String,
)

/**
 * Processes requests to the Hetzner API in batches.
 *
 * This class sends messages via RabbitMQ to [HetznerFirewallUpdater], which
 * implements the actual batching and rate-limiting logic. Splitting the logic
 * in this way allows us to use RabbitMQ's "single active consumer" feature
 * to ensure that only one instance of the icebreaker server sends updates to
 * Hetzner.
 */
@Singleton
class HetznerFirewallService(
    private val repository: FirewallWhitelistRepository,
    private val clock: Clock,
    @param:Channel("hetzner-request-out") private val requestEmitter: Emitter<SyncMessage>,
) {
    /**
     * Maps from the ID of a SyncMessage to a future that will be completed when a
     * [SyncMessage] acknowledgement with that ID is received.
     */
    private val awaitedMessagesById = ConcurrentHashMap<String, CompletableFuture<Unit>>()

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
        return syncFirewall()
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String): Uni<Unit> {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.markSessionAsDeleted(sessionId)
        return syncFirewall()
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String): Uni<Unit> {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        repository.markSessionUserAsDeleted(sessionId, userId)
        return syncFirewall()
    }

    /**
     * Asks [HetznerFirewallUpdater] via RabbitMQ to sync rules with Hetzner's API.
     *
     * The returned Uni is completed by [handle] when it receives a message indicating
     * that the requested sync has been successfully completed.
     */
    private fun syncFirewall(): Uni<Unit> {
        val requestId = UUID.randomUUID().toString()
        val future = CompletableFuture<Unit>()
        awaitedMessagesById.set(requestId, future)
        requestEmitter.send(SyncMessage(requestId))
        return Uni.createFrom().completionStage(future).ifNoItem().after(Duration.ofSeconds(10)).fail()
    }

    @Incoming("hetzner-response-in")
    fun handle(json: JsonObject) {
        val response = json.mapTo(SyncMessage::class.java)
        // The message is a response to a previous request; we
        // complete the future that that request is waiting for.
        awaitedMessagesById.remove(response.id)?.complete(Unit)
        // The response is acked when this function returns
    }
}

@ApplicationScoped
private class HetznerFirewallUpdater(
    private val hetznerProperties: HetznerProperties,
    private val repository: FirewallWhitelistRepository,
    @param:RestClient private val hetznerClient: HetznerApiClient,
    @param:Channel("hetzner-response-out") private val responseEmitter: Emitter<SyncMessage>,
) {
    private data class BufferedMessage(val payload: SyncMessage, val ack: CompletableFuture<Unit>)

    // Requests waiting to be resolved the next time [syncFirewallWithHetzner] runs.
    private val requestQueue = ConcurrentLinkedQueue<BufferedMessage>()

    @Incoming("hetzner-request-in")
    fun handle(json: JsonObject): CompletionStage<Unit> {
        val request = json.mapTo(SyncMessage::class.java)
        val ack = CompletableFuture<Unit>()
        requestQueue.add(BufferedMessage(request, ack))
        // The message is acked when `ack` is marked as completed.
        return ack
    }

    // We delay by 3s to make it more likely that RabbitMQ is running by the time this method
    // runs during integration tests. Otherwise, we get spurious errors logged.
    @Scheduled(every = "1s", delayed = "3s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
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
            val response = hetznerClient.setFirewallRules(firewall, request)
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
                batch.forEach {
                    responseEmitter.send(it.payload)
                    it.ack.complete(Unit)
                }
            } else {
                LOG.error("Failed to update Hetzner firewall rules: API request failed")
                batch.forEach { it.ack.completeExceptionally(IOException("Hetzner API request failed")) }
            }
        } catch (e: Exception) {
            LOG.error("Failed to update Hetzner firewall rules", e)
            batch.forEach { it.ack.completeExceptionally(e) }
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
