package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.SetFirewallRulesRequest.FirewallRule
import com.faforever.icebreaker.sync.waitUntil
import io.quarkus.scheduler.Scheduler
import io.quarkus.test.Mock
import io.quarkus.test.junit.QuarkusTest
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
import java.util.*
import java.util.concurrent.CompletableFuture

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
internal class HetznerFirewallServiceTest {
    @Inject
    lateinit var service: HetznerFirewallService

    @Inject
    lateinit var updater: HetznerFirewallUpdater

    @Inject lateinit var scheduler: Scheduler

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
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")

        assertThat(hetznerApi.getCallCount()).isEqualTo(1)
        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("1.2.3.4/32"))
    }

    @Test
    fun `Add whitelist de-duplicates IPs sent to Hetzner`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        service.whitelistIpForSession("game/201", userId = 123, ipAddress = "1.2.3.4")

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("1.2.3.4/32"))
    }

    @Test
    fun `Add whitelist re-uses existing active session`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")

        assertThat(firewallWhitelistRepository.getAllActive()).hasSize(1)
    }

    @Test
    fun `Add whitelist IPv6`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "abcd::")

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("abcd::/128"))
    }

    @Test
    fun `Remove user whitelist`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")

        val allowedIps1 = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps1).isNotEmpty()

        service.removeWhitelistForSessionUser(123, "game/200")

        val allowedIps2 = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps2).isEmpty()
    }

    @Test
    fun `Remove session whitelist`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        service.whitelistIpForSession("game/200", userId = 234, ipAddress = "2.3.4.5")

        val allowedIps1 = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps1).isNotEmpty()

        service.removeWhitelistsForSession("game/200")

        val allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).isEmpty()
    }

    @Test
    fun `Whitelist syncing is rate-limited`() {
        scheduler.pause()

        val requestFutures1 = CompletableFuture.allOf(
            CompletableFuture.runAsync { service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4") },
            CompletableFuture.runAsync { service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5") },
        )

        runBlocking {
            waitUntil {
                updater.numPendingRequests() == 2
            }
        }

        scheduler.resume()

        requestFutures1.join()

        assertThat(hetznerApi.getCallCount()).isEqualTo(1)

        var allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).hasSize(2)

        scheduler.pause()
        val currentCount = hetznerApi.getCallCount()

        val requestFutures2 = CompletableFuture.allOf(
            CompletableFuture.runAsync { service.whitelistIpForSession("game/201", userId = 345, "3.4.5.6") },
            CompletableFuture.runAsync { service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7") },
            CompletableFuture.runAsync { service.removeWhitelistForSessionUser(234, "game/201") },
        )

        runBlocking {
            waitUntil {
                updater.numPendingRequests() == 3
            }
        }

        scheduler.resume()

        requestFutures2.join()

        assertThat(hetznerApi.getCallCount()).isEqualTo(currentCount + 1)

        allowedIps = hetznerApi.getRulesByFirewallId("fwid")!!.allSourceIps()
        assertThat(allowedIps).hasSize(3)

        scheduler.resume()
    }

    @Test
    fun `IPs split across multiple rules`() {
        service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4")
        service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5")
        service.whitelistIpForSession("game/202", userId = 345, "3.4.5.6")
        service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7")

        assertThat(hetznerApi.getRulesByFirewallId("fwid")).isEqualTo(
            listOf(
                FirewallRule(
                    FirewallRule.Direction.IN,
                    listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"),
                    FirewallRule.Protocol.TCP,
                ),
                FirewallRule(
                    FirewallRule.Direction.IN,
                    listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"),
                    FirewallRule.Protocol.UDP,
                ),
                FirewallRule(FirewallRule.Direction.IN, listOf("4.5.6.7/32"), FirewallRule.Protocol.TCP),
                FirewallRule(FirewallRule.Direction.IN, listOf("4.5.6.7/32"), FirewallRule.Protocol.UDP),
            ),
        )
    }
}

fun List<FirewallRule>.allSourceIps(): Set<String> = flatMap { it.sourceIps }.toSet()
