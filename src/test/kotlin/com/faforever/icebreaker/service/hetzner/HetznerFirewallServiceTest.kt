package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.HetznerFirewallService
import com.faforever.icebreaker.service.hetzner.HetznerProperties
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Direction.IN
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol.TCP
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule.Protocol.UDP
import com.faforever.icebreaker.service.hetzner.StubHetznerApiClient
import com.faforever.icebreaker.sync.waitUntil
import io.quarkus.test.Mock
import io.quarkus.test.junit.QuarkusTest
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.Optional

@Dependent
class HetznerFirewallServiceTestConfig {
    @Produces
    @Mock
    @ApplicationScoped
    fun hetznerProperties(): HetznerProperties = object : HetznerProperties {
        override fun firewallId() = Optional.of("fwid")
        override fun maxIpsPerRule() = 3
        override fun apiKey() = "abc-123"
    }
}

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HetznerFirewallServiceTest {
    @Inject
    lateinit var service: HetznerFirewallService

    @Inject
    lateinit var firewallWhitelistRepository: FirewallWhitelistRepository

    @Inject
    @RestClient
    lateinit var hetznerApi: StubHetznerApiClient

    @BeforeEach
    fun setup() {
        hetznerApi.resetCallCount()
        firewallWhitelistRepository.deleteAll()
    }

    @Test
    fun `Add whitelist`() {
        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"))

        assertThat(hetznerApi.getCallCount()).isEqualTo(1)
        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("1.2.3.4/32"))
    }

    @Test
    fun `Add whitelist de-duplicates IPs sent to Hetzner`() {
        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"))

        awaitAll(service.whitelistIpForSession("game/201", userId = 123, ipAddress = "1.2.3.4"))

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("1.2.3.4/32"))
    }

    @Test
    fun `Add whitelist re-uses existing active session`() {
        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"))

        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"))

        assertThat(firewallWhitelistRepository.getAllActive()).hasSize(1)
    }

    @Test
    fun `Add whitelist IPv6`() {
        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "abcd::"))

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("abcd::/128"))
    }

    @Test
    fun `Remove user whitelist`() {
        awaitAll(service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"))

        awaitAll(service.removeWhitelistForSessionUser(123, "game/200"))

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEmpty()
    }

    @Test
    fun `Remove session whitelist`() {
        awaitAll(
            service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4"),
            service.whitelistIpForSession("game/200", userId = 234, ipAddress = "2.3.4.5"),
        )

        awaitAll(
            service.removeWhitelistsForSession("game/200"),
        )

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEmpty()
    }

    @Test
    fun `Whitelist syncing is rate-limited`() {
        // Make multiple requests in quick succession
        val uni1 = service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4")
        val uni2 = service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5")

        runBlocking {
            waitUntil {
                hetznerApi.getCallCount() == 1
            }
        }

        var allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).hasSize(2)
        awaitAll(uni1, uni2)

        val uni3 = service.whitelistIpForSession("game/201", userId = 345, "3.4.5.6")
        val uni4 = service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7")
        val uniRemove = service.removeWhitelistForSessionUser(234, "game/201")

        val currentCount = hetznerApi.getCallCount()
        runBlocking {
            waitUntil {
                hetznerApi.getCallCount() == currentCount + 1
            }
        }

        allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).hasSize(3)
        awaitAll(uni3, uni4, uniRemove)
    }

    @Test
    fun `IPs split across multiple rules`() {
        awaitAll(
            service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4"),
            service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5"),
            service.whitelistIpForSession("game/202", userId = 345, "3.4.5.6"),
            service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7"),
        )

        assertThat(hetznerApi.getRulesByFirewallId("fwid")).isEqualTo(
            listOf(
                FirewallRule(IN, listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"), TCP),
                FirewallRule(IN, listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"), UDP),
                FirewallRule(IN, listOf("4.5.6.7/32"), TCP),
                FirewallRule(IN, listOf("4.5.6.7/32"), UDP),
            ),
        )
    }
}

fun List<FirewallRule>.allSourceIps(): Set<String> = flatMap { it.sourceIps }.toSet()

private fun <T> awaitAll(vararg unis: Uni<T>) {
    for (uni in unis) {
        uni.await().atMost(Duration.ofSeconds(10))
    }
}
