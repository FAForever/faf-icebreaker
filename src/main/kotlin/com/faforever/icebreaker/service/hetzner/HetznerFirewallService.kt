package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.jvm.optionals.getOrNull

private val LOG: Logger = LoggerFactory.getLogger(HetznerFirewallService::class.java)

/** Upper bound for the number of sync runs skipped between retries of a failed sync. */
private const val MAX_RETRY_BACKOFF_RUNS = 29

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

/** Requests a sync of the firewall rules with Hetzner. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncRequest(
    /** A unique identifier for this request. Used to pair the request with its [SyncResponse]. */
    val id: String,
)

/** Reports the outcome of one sync to all requests it resolved. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncResponse(
    /** The IDs of all [SyncRequest]s resolved by the sync. */
    val requestIds: List<String> = emptyList(),
    /** A stable error message if the sync failed; null if it succeeded. */
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
    @param:Channel("hetzner-request-out") private val requestEmitter: Emitter<SyncRequest>,
) {
    /**
     * Maps from the ID of a [SyncRequest] to a future that will be completed when a
     * [SyncResponse] containing that ID is received.
     */
    private val awaitedMessagesById = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /** Whitelists [ipAddress] for session [sessionId]. */
    fun whitelistIpForSession(sessionId: String, userId: Long, ipAddress: String) {
        require(ipAddress.toIpAddress() != null) { "Expected an individual IPv4 or IPv6 address" }
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
        // Sessions without whitelists (or already cleaned up ones) leave the firewall unchanged.
        if (repository.markSessionAsDeleted(sessionId) > 0) {
            syncFirewall()
        }
    }

    /** Removes only the whitelist for user [userId] in session [sessionId]. */
    fun removeWhitelistForSessionUser(userId: Long, sessionId: String) {
        LOG.debug("Removing user {}'s whitelist for session {}", userId, sessionId)
        if (repository.markSessionUserAsDeleted(sessionId, userId) > 0) {
            syncFirewall()
        }
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
        requestEmitter.send(SyncRequest(requestId)).thenCompose { future }.toCompletableFuture()
            .orTimeout(10, TimeUnit.SECONDS).whenComplete { _, _ ->
                awaitedMessagesById.remove(requestId)
            }.join()
    }

    @Incoming("hetzner-response-in")
    fun handle(json: JsonObject) {
        val response = json.mapTo(SyncResponse::class.java)
        // The message resolves a batch of requests, possibly sent by other instances or
        // already timed out; we complete the futures of the requests this instance awaits.
        LOG.trace("Received Hetzner response for {} requests", response.requestIds.size)
        response.requestIds.forEach { requestId ->
            val future = awaitedMessagesById.remove(requestId)
            if (response.error == null) {
                future?.complete(Unit)
            } else {
                future?.completeExceptionally(IOException(response.error))
            }
        }
        // The response is acked when this function returns
    }
}

@ApplicationScoped
internal class HetznerFirewallUpdater(
    private val hetznerProperties: HetznerProperties,
    private val repository: FirewallWhitelistRepository,
    @param:RestClient private val hetznerClient: HetznerApiClient,
    @param:Channel("hetzner-response-out") private val responseEmitter: Emitter<SyncResponse>,
) {
    // Requests waiting to be resolved the next time [syncFirewallWithHetzner] runs.
    private val requestQueue = ConcurrentLinkedQueue<SyncRequest>()

    // Number of consecutive failed syncs. While positive, Hetzner may lag behind the
    // database, so we retry even without pending requests: a removal that was persisted
    // but failed to sync would otherwise only reach Hetzner with some unrelated request.
    // Only the single active consumer receives requests, so only it ever retries.
    @Volatile
    private var consecutiveFailures = 0

    // Sync runs to skip before the next retry, backing off exponentially from 2s to ~60s.
    private var runsUntilRetry = 0

    internal fun numPendingRequests() = requestQueue.size

    internal fun isRetryPending() = consecutiveFailures > 0

    /**
     * Buffers a sync request for the next [syncFirewallWithHetzner] run.
     *
     * The message is acked as soon as this function returns. It must not return a
     * CompletionStage that completes after the sync: SmallRye only delivers the next
     * message once that stage completes, which would serialize the requests to one
     * per sync run and defeat the batching. Losing a buffered request on a crash is
     * harmless, as its requester times out and every sync applies the full state.
     */
    @Incoming("hetzner-request-in")
    fun handle(json: JsonObject) {
        requestQueue.add(json.mapTo(SyncRequest::class.java))
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
            if (consecutiveFailures == 0) {
                LOG.trace("No changes to apply for firewall ID {}", firewall)
                return
            }
            if (runsUntilRetry > 0) {
                runsUntilRetry--
                return
            }
            LOG.info("Retrying Hetzner firewall sync after {} consecutive failures", consecutiveFailures)
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
                consecutiveFailures = 0
                runsUntilRetry = 0
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

    private fun respondWithFailure(batch: List<SyncRequest>) {
        consecutiveFailures++
        runsUntilRetry = minOf((1 shl minOf(consecutiveFailures - 1, 5)) - 1, MAX_RETRY_BACKOFF_RUNS)
        respond(batch, "Hetzner firewall update failed")
    }

    /** Answers the whole [batch] with a single response, however many requests it holds. */
    private fun respond(batch: List<SyncRequest>, error: String? = null) {
        if (batch.isEmpty()) {
            return
        }
        try {
            responseEmitter.send(SyncResponse(batch.map { it.id }, error)).whenComplete { _, responseError ->
                if (responseError != null) {
                    LOG.warn("Failed to send Hetzner sync response for {} requests", batch.size, responseError)
                }
            }
        } catch (e: IllegalStateException) {
            LOG.warn("Failed to send Hetzner sync response for {} requests", batch.size, e)
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
