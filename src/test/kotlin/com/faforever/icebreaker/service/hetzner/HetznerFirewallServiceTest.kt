package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.service.hetzner.Direction.IN
import com.faforever.icebreaker.service.hetzner.FirewallRule
import com.faforever.icebreaker.service.hetzner.HetznerFirewallService
import com.faforever.icebreaker.service.hetzner.HetznerProperties
import com.faforever.icebreaker.service.hetzner.Protocol.TCP
import com.faforever.icebreaker.service.hetzner.Protocol.UDP
import com.faforever.icebreaker.service.hetzner.StubHetznerApiClient
import io.quarkus.test.Mock
import io.quarkus.test.junit.QuarkusTest
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.Dependent
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Optional
import kotlin.time.Duration.Companion.milliseconds

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
        firewallWhitelistRepository.removeAll()
    }

    @Test
    fun `Add whitelist`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        runBlocking { waitUntil { hetznerApi.callCount == 1 } }

        val allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("1.2.3.4/32"))
    }

    @Test
    fun `Add whitelist IPv6`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "abcd::")
        runBlocking { waitUntil { hetznerApi.callCount == 1 } }

        val allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).isEqualTo(setOf("abcd::/128"))
    }

    @Test
    fun `Remove user whitelist`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        runBlocking { waitUntil { hetznerApi.callCount == 1 } }

        service.removeWhitelistForSessionUser(123, "game/200")
        runBlocking { waitUntil { hetznerApi.callCount == 2 } }

        val allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).isEmpty()
    }

    @Test
    fun `Remove session whitelist`() {
        service.whitelistIpForSession("game/200", userId = 123, ipAddress = "1.2.3.4")
        service.whitelistIpForSession("game/200", userId = 234, ipAddress = "2.3.4.5")
        runBlocking { waitUntil { hetznerApi.callCount == 1 } }

        service.removeWhitelistsForSession("game/200")
        runBlocking { waitUntil { hetznerApi.callCount == 2 } }

        val allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).isEmpty()
    }

    @Test
    fun `Whitelist syncing is rate-limited`() {
        // Make multiple requests in quick succession
        service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4")
        service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5")

        runBlocking {
            waitUntil {
                hetznerApi.callCount == 1
            }
        }

        var allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).hasSize(2)
        assertThat(hetznerApi.callCount).isEqualTo(1)

        service.whitelistIpForSession("game/201", userId = 345, "3.4.5.6")
        service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7")
        service.removeWhitelistForSessionUser(234, "game/201")

        runBlocking {
            waitUntil {
                hetznerApi.callCount == 2
            }
        }

        allowedIps = hetznerApi.rulesByFirewallId["fwid"]!!.allSourceIps()
        assertThat(allowedIps).hasSize(3)
        assertThat(hetznerApi.callCount).isEqualTo(2)
    }

    @Test
    fun `IPs split across multiple rules`() {
        service.whitelistIpForSession("game/201", userId = 123, "1.2.3.4")
        service.whitelistIpForSession("game/201", userId = 234, "2.3.4.5")
        service.whitelistIpForSession("game/202", userId = 345, "3.4.5.6")
        service.whitelistIpForSession("game/202", userId = 456, "4.5.6.7")

        runBlocking {
            waitUntil {
                hetznerApi.callCount == 1
            }
        }

        assertThat(hetznerApi.rulesByFirewallId["fwid"]).isEqualTo(
            listOf(
                FirewallRule(IN, listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"), TCP),
                FirewallRule(IN, listOf("1.2.3.4/32", "2.3.4.5/32", "3.4.5.6/32"), UDP),
                FirewallRule(IN, listOf("4.5.6.7/32"), TCP),
                FirewallRule(IN, listOf("4.5.6.7/32"), UDP),
            ),
        )
    }

    // Polls the predicate until it returns true or a timeout expires.
    private suspend fun waitUntil(pred: () -> Boolean) {
        val timeout = 5_000.milliseconds
        val checkInterval = 100.milliseconds
        withTimeoutOrNull(timeout) {
            while (!pred()) {
                delay(checkInterval)
            }
        }
    }
}

fun List<FirewallRule>.allSourceIps(): Set<String> = flatMap { it.sourceIps }.toSet()
