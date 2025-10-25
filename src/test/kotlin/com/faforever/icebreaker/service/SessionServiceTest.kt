package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.service.hetzner.StubHetznerApiClient
import com.faforever.icebreaker.sync.waitUntil
import com.faforever.icebreaker.util.FakeClock
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.jwt.Claim
import io.quarkus.test.security.jwt.JwtSecurity
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito
import java.time.Duration

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SessionServiceTest {
    @Inject
    lateinit var service: SessionService

    @Inject
    lateinit var firewallWhitelistRepository: FirewallWhitelistRepository

    @Inject
    lateinit var iceSessionRepository: IceSessionRepository

    @Inject
    lateinit var clock: FakeClock

    @Inject
    @RestClient
    lateinit var hetznerApi: StubHetznerApiClient

    @Inject
    lateinit var fafProperties: FafProperties

    @InjectMock
    lateinit var httpServerRequest: HttpServerRequest

    private val testIp = "1.2.3.4"

    @BeforeEach
    fun setup() {
        Mockito.`when`(httpServerRequest.getHeader(fafProperties.realIpHeader()))
            .thenReturn(testIp)

        iceSessionRepository.deleteAll()
        hetznerApi.resetCallCount()
        firewallWhitelistRepository.removeAll()
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = "[lobby]"),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":200}"""),
        ],
    )
    @Test
    fun `getSession whitelists IP for game`() {
        service.getSession(200L)

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/200")
        assertThat(allowedIps).hasSize(1)
        assertThat(allowedIps[0].allowedIp).isEqualTo(testIp)
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = "[lobby]"),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":201}"""),
        ],
    )
    @Test
    fun `Whitelist expires after time passes`() {
        val start = clock.instant()
        service.getSession(201L)

        runBlocking {
            waitUntil {
                iceSessionRepository.existsByGameId(201)
            }
        }
        clock.setNow(start + Duration.ofDays(14))
        service.cleanUpSessions()

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/201")
        assertThat(allowedIps).isEmpty()
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = "[lobby]"),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":201}"""),
        ],
    )
    @Test
    fun `Whitelist expires after client closes WebRTC session`() {
        service.getSession(201L)

        runBlocking {
            waitUntil {
                iceSessionRepository.existsByGameId(201)
            }
        }
        service.onMessageReceived(201, PeerClosingMessage(gameId = 201, senderId = 123))

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/201")
        assertThat(allowedIps).isEmpty()
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = "[lobby]"),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":201}"""),
        ],
    )
    @Test
    fun `Whitelist synced with hetzner firewall`() {
        service.getSession(201L)

        runBlocking {
            waitUntil {
                hetznerApi.getCallCount() == 1
            }
        }

        val whitelistedIps = hetznerApi.getRulesByFirewallId("fwid")!!.flatMap { it.sourceIps }.toSet()
        assertThat(whitelistedIps).contains("$testIp/32")
    }
}
