package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import inet.ipaddr.IPAddressString
import io.vertx.core.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture

internal class HetznerFirewallUpdaterTest {
    @Test
    fun `Firewall request stays within the effective rule limit`() {
        val request = buildRequest(
            listOf("10.0.0.0", "10.0.0.3", "10.0.0.4"),
            maxEffectiveRules = 2,
            maxIpsPerRule = 100,
        )

        assertThat(request.rules).hasSize(2)
        assertThat(request.rules[0].sourceIps).containsExactly("10.0.0.0/30", "10.0.0.4/32")
        assertThat(request.rules[1].sourceIps).containsExactly("10.0.0.0/30", "10.0.0.4/32")
    }

    @Test
    fun `Firewall rules do not mix address families`() {
        val addresses = buildList {
            repeat(101) { index -> add("10.${index / 256}.${index % 256}.1") }
            add("2001:db8::1")
        }

        val request = buildRequest(addresses, maxEffectiveRules = 500, maxIpsPerRule = 100)

        assertThat(request.rules).hasSize(6)
        assertThat(request.rules).allSatisfy { rule ->
            assertThat(rule.sourceIps).hasSizeLessThanOrEqualTo(100)
            assertThat(rule.sourceIps.map { source -> IPAddressString(source).toAddress().isIPv4 }.toSet())
                .hasSize(1)
        }
        assertThat(request.rules.map { rule -> rule.sourceIps }.distinct().map { sources -> sources.size })
            .containsExactly(100, 1, 1)
    }

    @Test
    fun `Address families share the effective rule limit`() {
        val addresses = buildList {
            repeat(251) { index -> add("10.${index / 256}.${index % 256}.1") }
            repeat(251) { index -> add("2001:db8:${index.toString(16)}::1") }
        }

        val request = buildRequest(addresses, maxEffectiveRules = 500, maxIpsPerRule = 100)
        val effectivePrefixes = request.rules.flatMap { rule -> rule.sourceIps }.toSet()

        assertThat(effectivePrefixes).hasSize(500)
        assertThat(request.rules).allSatisfy { rule ->
            assertThat(rule.sourceIps).hasSizeLessThanOrEqualTo(100)
            assertThat(rule.sourceIps.map { source -> IPAddressString(source).toAddress().isIPv4 }.toSet())
                .hasSize(1)
        }
    }

    @Test
    fun `Only literal individual addresses are sent to Hetzner`() {
        val request = buildRequest(
            listOf(
                "192.0.2.1",
                "localhost",
                "192.0.2.0/24",
                "192.0.2.*",
                "fe80::1%eth0",
                "0xc0000201",
            ),
            maxEffectiveRules = 500,
            maxIpsPerRule = 100,
        )

        assertThat(request.rules).hasSize(2)
        assertThat(request.rules).allSatisfy { rule ->
            assertThat(rule.sourceIps).containsExactly("192.0.2.1/32")
        }
    }

    @Test
    fun `Batch is answered with a single response`() {
        val properties = mock(HetznerProperties::class.java)
        val emitter = responseEmitter()
        `when`(properties.firewallId()).thenReturn(Optional.empty())
        `when`(emitter.send(any(SyncResponse::class.java))).thenReturn(CompletableFuture.completedFuture(null))

        val updater = updaterWithRequests(properties, emitter, "first", "second", "third")
        updater.syncFirewallWithHetzner()

        verify(emitter).send(SyncResponse(listOf("first", "second", "third")))
        verifyNoMoreInteractions(emitter)
        assertThat(updater.numPendingRequests()).isZero()
    }

    @Test
    fun `Failed sync is reported to the whole batch in a single response`() {
        val properties = mock(HetznerProperties::class.java)
        val emitter = responseEmitter()
        `when`(properties.firewallId()).thenReturn(Optional.of("fwid"))
        `when`(properties.maxEffectiveRules()).thenReturn(500)
        `when`(properties.maxIpsPerRule()).thenReturn(100)
        `when`(emitter.send(any(SyncResponse::class.java))).thenReturn(CompletableFuture.completedFuture(null))

        val repository = mock(FirewallWhitelistRepository::class.java)
        val client = mock(HetznerApiClient::class.java)
        `when`(repository.getAllActive()).thenReturn(emptyList())
        `when`(client.setFirewallRules("fwid", SetFirewallRulesRequest(emptyList()))).thenThrow(RuntimeException("Hetzner unavailable"))

        val updater = updaterWithRequests(properties, emitter, "first", "second", repository = repository, client = client)
        updater.syncFirewallWithHetzner()

        verify(emitter).send(SyncResponse(listOf("first", "second"), "Hetzner firewall update failed"))
        verifyNoMoreInteractions(emitter)
    }

    @Test
    fun `Failed sync is retried without new requests and backs off`() {
        val properties = mock(HetznerProperties::class.java)
        val emitter = responseEmitter()
        `when`(properties.firewallId()).thenReturn(Optional.of("fwid"))
        `when`(properties.maxEffectiveRules()).thenReturn(500)
        `when`(properties.maxIpsPerRule()).thenReturn(100)
        `when`(emitter.send(any(SyncResponse::class.java))).thenReturn(CompletableFuture.completedFuture(null))

        val repository = mock(FirewallWhitelistRepository::class.java)
        val client = mock(HetznerApiClient::class.java)
        val request = SetFirewallRulesRequest(emptyList())
        `when`(repository.getAllActive()).thenReturn(emptyList())
        `when`(client.setFirewallRules("fwid", request))
            .thenThrow(RuntimeException("Hetzner unavailable"))
            .thenThrow(RuntimeException("Hetzner unavailable"))
            .thenReturn(SetFirewallRulesResponse(emptyList()))

        val updater = updaterWithRequests(properties, emitter, "first", repository = repository, client = client)
        // First failure, then an immediate retry that fails again
        updater.syncFirewallWithHetzner()
        updater.syncFirewallWithHetzner()
        verify(client, times(2)).setFirewallRules("fwid", request)

        // The second failure skips one run before retrying
        updater.syncFirewallWithHetzner()
        verify(client, times(2)).setFirewallRules("fwid", request)
        updater.syncFirewallWithHetzner()
        verify(client, times(3)).setFirewallRules("fwid", request)

        // The successful retry ends the retries
        updater.syncFirewallWithHetzner()
        updater.syncFirewallWithHetzner()
        verify(client, times(3)).setFirewallRules("fwid", request)
    }

    @Test
    fun `Synchronous response failure does not break the sync`() {
        val properties = mock(HetznerProperties::class.java)
        val emitter = responseEmitter()
        `when`(properties.firewallId()).thenReturn(Optional.empty())
        `when`(emitter.send(any(SyncResponse::class.java)))
            .thenThrow(IllegalStateException("response channel overflow"))

        val updater = updaterWithRequests(properties, emitter, "first")
        updater.syncFirewallWithHetzner()

        assertThat(updater.numPendingRequests()).isZero()
    }

    @Suppress("UNCHECKED_CAST")
    private fun responseEmitter() = mock(Emitter::class.java) as Emitter<SyncResponse>

    private fun updaterWithRequests(
        properties: HetznerProperties,
        emitter: Emitter<SyncResponse>,
        vararg requestIds: String,
        repository: FirewallWhitelistRepository = mock(FirewallWhitelistRepository::class.java),
        client: HetznerApiClient = mock(HetznerApiClient::class.java),
    ): HetznerFirewallUpdater {
        val updater = HetznerFirewallUpdater(properties, repository, client, emitter)
        requestIds.forEach { id ->
            val request = mock(JsonObject::class.java)
            `when`(request.mapTo(SyncRequest::class.java)).thenReturn(SyncRequest(id))
            updater.handle(request)
        }
        return updater
    }

    private fun buildRequest(
        addresses: List<String>,
        maxEffectiveRules: Int,
        maxIpsPerRule: Int,
    ): SetFirewallRulesRequest {
        val properties = mock(HetznerProperties::class.java)
        val repository = mock(FirewallWhitelistRepository::class.java)
        val client = mock(HetznerApiClient::class.java)

        @Suppress("UNCHECKED_CAST")
        val emitter = mock(Emitter::class.java) as Emitter<SyncResponse>

        `when`(properties.maxEffectiveRules()).thenReturn(maxEffectiveRules)
        `when`(properties.maxIpsPerRule()).thenReturn(maxIpsPerRule)
        `when`(repository.getAllActive()).thenReturn(
            addresses.mapIndexed { index, address ->
                FirewallWhitelistEntity(
                    id = index.toLong(),
                    userId = index.toLong(),
                    sessionId = "game/$index",
                    allowedIp = address,
                    createdAt = Instant.EPOCH,
                    deletedAt = null,
                )
            },
        )

        return HetznerFirewallUpdater(properties, repository, client, emitter).buildSetFirewallRequest()
    }
}
