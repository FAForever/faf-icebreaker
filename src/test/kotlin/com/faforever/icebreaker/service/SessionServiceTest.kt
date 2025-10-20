package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.service.hetzner.StubHetznerApiClient
import com.faforever.icebreaker.util.FakeClock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.jwt.Claim
import io.quarkus.test.security.jwt.JwtSecurity
import jakarta.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    @BeforeEach
    fun cleanDb() {
        iceSessionRepository.deleteAll()
        hetznerApi.resetCallCount()
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
        service.getSession(200L, "1.2.3.4")

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/200")
        assertThat(allowedIps).hasSize(1)
        assertThat(allowedIps[0].allowedIp).isEqualTo("1.2.3.4")
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
        service.getSession(201L, "1.2.3.4")

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
        service.getSession(201L, "1.2.3.4")

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
        // Make multiple requests in quick succession
        service.getSession(201L, "1.2.3.4")
        service.getSession(201L, "2.3.4.5")

        runBlocking {
            waitUntil {
                hetznerApi.callCount >= 1
            }
        }

        // Expect exactly one request to be sent upstream due to rate-limiting
        assertThat(hetznerApi.callCount).isEqualTo(1)
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = "[lobby]"),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":202}"""),
        ],
    )
    @Test
    fun `Subsequent DB changes trigger another API call`() {
        // First batch of requests
        service.getSession(202L, "1.2.3.4")
        service.getSession(202L, "2.3.4.5")

        runBlocking {
            waitUntil {
                hetznerApi.callCount >= 1
            }
        }

        assertThat(hetznerApi.callCount).isEqualTo(1)

        // Second batch of requests after some time
        service.getSession(202L, "3.4.5.6")

        runBlocking {
            waitUntil {
                hetznerApi.callCount >= 2
            }
        }

        // Should have made exactly 2 API calls total
        assertThat(hetznerApi.callCount).isEqualTo(2)
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
