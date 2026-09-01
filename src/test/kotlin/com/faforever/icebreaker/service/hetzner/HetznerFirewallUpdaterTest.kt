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
    fun `Synchronous response failure does not leave later acknowledgements pending`() {
        val properties = mock(HetznerProperties::class.java)
        val repository = mock(FirewallWhitelistRepository::class.java)
        val client = mock(HetznerApiClient::class.java)

        @Suppress("UNCHECKED_CAST")
        val emitter = mock(Emitter::class.java) as Emitter<SyncMessage>
        val sendFailure = IllegalStateException("response channel overflow")
        val firstRequest = mock(JsonObject::class.java)
        val secondRequest = mock(JsonObject::class.java)

        `when`(properties.firewallId()).thenReturn(Optional.empty())
        `when`(emitter.send(any(SyncMessage::class.java)))
            .thenThrow(sendFailure)
            .thenReturn(CompletableFuture.completedFuture(null))
        `when`(firstRequest.mapTo(SyncMessage::class.java)).thenReturn(SyncMessage("first"))
        `when`(secondRequest.mapTo(SyncMessage::class.java)).thenReturn(SyncMessage("second"))

        val updater = HetznerFirewallUpdater(properties, repository, client, emitter)
        val firstAck = updater.handle(firstRequest).toCompletableFuture()
        val secondAck = updater.handle(secondRequest).toCompletableFuture()

        updater.syncFirewallWithHetzner()

        assertThat(firstAck).isCompletedExceptionally
        assertThat(secondAck).isCompletedWithValue(Unit)
        verify(emitter, times(2)).send(any(SyncMessage::class.java))
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
        val emitter = mock(Emitter::class.java) as Emitter<SyncMessage>

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
