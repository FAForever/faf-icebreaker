package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol
import inet.ipaddr.AddressStringParameters.RangeParameters
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import inet.ipaddr.IPAddressStringParameters
import io.quarkus.scheduler.Scheduled
import io.vertx.core.json.JsonObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Singleton
import jakarta.ws.rs.WebApplicationException
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Clock
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull

private val LOG: Logger = LoggerFactory.getLogger(HetznerFirewallService::class.java)

private val IP_ADDRESS_VALIDATION_OPTIONS = IPAddressStringParameters.Builder()
    .allowEmpty(false)
    .allowAll(false)
    .allowSingleSegment(false)
    .allowPrefix(false)
    .allowMask(false)
    .allowPrefixOnly(false)
    .allowWildcardedSeparator(false)
    .setRangeOptions(RangeParameters.NO_RANGE)
    .allow_inet_aton(false)
    .also { builder ->
        builder.getIPv4AddressParametersBuilder().allowBinary(false)
        builder.getIPv6AddressParametersBuilder()
            .allowBase85(false)
            .allowBinary(false)
            .allowZone(false)
            .allow_mixed_inet_aton(false)
    }
    .toParams()

/**
 * Parses an individual IP address without resolving host names.
 *
 * Returns null if the value is not an individual IPv4 or IPv6 address.
 */
private fun String.toIpAddress(): IPAddress? = try {
    IPAddressString(this, IP_ADDRESS_VALIDATION_OPTIONS).toAddress()
        ?.takeUnless { address -> address.isMultiple || address.prefixLength != null }
} catch (_: Exception) {
    null
}

/** Requests a sync or reports the outcome of a requested sync. */
data class SyncMessage(
    /** A unique identifier for this request/response pair. Used to pair requests and responses. */
    val id: String,
    /** A stable error message for failed responses; null for requests and successful responses. */
    val error: String? = null,
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
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: String) {
        LOG.debug("Whitelisting IP {} for session {} in Hetzner cloud firewall", ipAddress, sessionId)
        repository.persistOrGet(
            FirewallWhitelistEntity(
                userId = userId,
                sessionId = sessionId,
                allowedIp = ipAddress,
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
        syncFirewall()
    }

    /** Removes all whitelists for session [sessionId]. */
    fun removeWhitelistsForSession(sessionId: String) {
        LOG.debug("Removing whitelist for session {}", sessionId)
        repository.markSessionAsDeleted(sessionId)
        syncFirewall()
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String) {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        repository.markSessionUserAsDeleted(sessionId, userId)
        syncFirewall()
    }

    /**
     * Asks [HetznerFirewallUpdater] via RabbitMQ to sync rules with Hetzner's API.
     *
     * The request completes when [handle] receives the sync outcome.
     */
    private fun syncFirewall() {
        LOG.info("Requesting Hetzner cloud firewall rules to be updated")
        val requestId = UUID.randomUUID().toString()
        val future = CompletableFuture<Unit>()
        awaitedMessagesById[requestId] = future
        requestEmitter.send(SyncMessage(requestId)).thenCompose { future }.toCompletableFuture()
            .orTimeout(10, TimeUnit.SECONDS).whenComplete { _, _ ->
                awaitedMessagesById.remove(requestId)
            }.join()
    }

    @Incoming("hetzner-response-in")
    fun handle(json: JsonObject) {
        val response = json.mapTo(SyncMessage::class.java)
        // The message is a response to a previous request; we
        // complete the future that that request is waiting for.
        LOG.trace("Received Hetzner response for request {}", response.id)
        val future = awaitedMessagesById.remove(response.id)
        if (response.error == null) {
            future?.complete(Unit)
        } else {
            future?.completeExceptionally(IOException(response.error))
        }
        // The response is acked when this function returns
    }
}

@ApplicationScoped
internal class HetznerFirewallUpdater(
    private val hetznerProperties: HetznerProperties,
    private val repository: FirewallWhitelistRepository,
    @param:RestClient private val hetznerClient: HetznerApiClient,
    @param:Channel("hetzner-response-out") private val responseEmitter: Emitter<SyncMessage>,
) {
    private data class BufferedMessage(val payload: SyncMessage, val ack: CompletableFuture<Unit>)

    // Requests waiting to be resolved the next time [syncFirewallWithHetzner] runs.
    private val requestQueue = ConcurrentLinkedQueue<BufferedMessage>()

    internal fun numPendingRequests() = requestQueue.size

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
    @Scheduled(every = "2s", delayed = "3s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun syncFirewallWithHetzner() {
        val firewall = hetznerProperties.firewallId().getOrNull()
        val batch = takeAll(requestQueue)

        if (firewall == null) {
            respond(batch)
            return
        }

        if (batch.isEmpty()) {
            LOG.trace("No changes to apply for firewall ID {}", firewall)
            return
        }

        try {
            val request = buildSetFirewallRequest()
            LOG.info("Syncing {} rules with Hetzner firewall {}", request.rules.size, firewall)
            // TEMPORARY (debugging Hetzner 403s): log the request *structure* to share with
            // Hetzner support. We deliberately log only per-rule IP counts, never the IPs
            // themselves - they are personal data (GDPR) and must not land in our logs.
            // Remove once resolved.
            val ruleSizes = request.rules.map { it.sourceIps.size }
            val effectiveSourcePrefixes = request.rules.flatMap { it.sourceIps }.toSet().size
            LOG.info(
                "Hetzner set_rules structure for firewall {}: ruleCount={}, effectiveSourcePrefixes={}, " +
                    "totalSourceIpEntries={}, sizeDistribution={}, perRule={}",
                firewall,
                request.rules.size,
                effectiveSourcePrefixes,
                ruleSizes.sum(),
                ruleSizes.groupingBy { it }.eachCount(),
                request.rules.map { "${it.direction}/${it.protocol}=${it.sourceIps.size}" },
            )
            val response = hetznerClient.setFirewallRules(firewall, request)
            // TEMPORARY (debugging Hetzner 403s): log the full parsed response. It contains
            // only action metadata/errors, no IPs, so it is safe to log. Remove once resolved.
            LOG.info("Hetzner set_rules response for firewall {}: {}", firewall, response)
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
                respond(batch)
            } else {
                LOG.error("Failed to update Hetzner firewall rules: API request failed")
                respondWithFailure(batch)
            }
        } catch (e: WebApplicationException) {
            // TEMPORARY (debugging Hetzner 403s): a non-2xx response throws before the body is
            // parsed, so read the raw error envelope (e.g. {"error":{"code":"forbidden",...}}).
            // No IPs are echoed back, so it is safe to log. Remove once resolved.
            val body = runCatching { e.response.readEntity(String::class.java) }.getOrNull()
            LOG.error(
                "Failed to update Hetzner firewall rules: status={}, responseBody={}",
                e.response.status,
                body,
                e,
            )
            respondWithFailure(batch)
        } catch (e: Exception) {
            LOG.error("Failed to update Hetzner firewall rules", e)
            respondWithFailure(batch)
        }
    }

    private fun respondWithFailure(batch: List<BufferedMessage>) {
        respond(batch, "Hetzner firewall update failed")
    }

    private fun respond(batch: List<BufferedMessage>, error: String? = null) {
        batch.forEach { message ->
            val response = try {
                responseEmitter.send(message.payload.copy(error = error))
            } catch (e: IllegalStateException) {
                message.ack.completeExceptionally(e)
                return@forEach
            }
            response.whenComplete { _, responseError ->
                if (responseError == null) {
                    message.ack.complete(Unit)
                } else {
                    message.ack.completeExceptionally(responseError)
                }
            }
        }
    }

    internal fun buildSetFirewallRequest(): SetFirewallRulesRequest {
        val activeWhitelists = repository.getAllActive()
        val sourceAddresses = activeWhitelists.mapNotNull { entry -> entry.allowedIp.trim().toIpAddress() }
        val malformedAddressCount = activeWhitelists.size - sourceAddresses.size
        if (malformedAddressCount > 0) {
            LOG.warn("Ignoring {} malformed addresses in the active firewall whitelist", malformedAddressCount)
        }
        val aggregation = IpRangeAggregator.aggregate(sourceAddresses, hetznerProperties.maxEffectiveRules())
        val sourceBlocks = aggregation.prefixesByFamily.values.flatMap { prefixes ->
            prefixes.chunked(hetznerProperties.maxIpsPerRule())
        }
        val rules = sourceBlocks.flatMap { sources ->
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
        LOG.info(
            "Aggregated {} unique client IPs into {} Hetzner firewall prefixes, admitting {} additional addresses " +
                "in total and at most {} in one prefix",
            aggregation.addressCount,
            aggregation.prefixes.size,
            aggregation.additionalAddressCount,
            aggregation.largestPrefixAdditionalAddressCount,
        )
        LOG.debug("Hetzner request summary: rules={}, effectiveSourcePrefixes={}", rules.size, aggregation.prefixes.size)
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
