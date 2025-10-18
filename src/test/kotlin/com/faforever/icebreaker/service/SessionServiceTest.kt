package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.persistence.IceSessionRepository
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetAddress
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

    @BeforeEach
    fun cleanDb() {
        iceSessionRepository.deleteAll()
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
        service.getSession(200L, InetAddress.getByName("1.2.3.4"))

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/200")
        assertThat(allowedIps).hasSize(1)
        assertThat(allowedIps[0].allowedIp).isEqualTo(InetAddress.getByName("1.2.3.4"))
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
        service.getSession(201L, InetAddress.getByName("1.2.3.4"))

        runBlocking { waitUntilSessionCreated(gameId = 201) }
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
        service.getSession(201L, InetAddress.getByName("1.2.3.4"))

        runBlocking { waitUntilSessionCreated(gameId = 201) }
        service.onMessageReceived(201, PeerClosingMessage(gameId = 201, senderId = 123))

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/201")
        assertThat(allowedIps).isEmpty()
    }

    // We create the session entity asynchronously, so this helper waits until
    // the session with the specified ID exists.
    private suspend fun waitUntilSessionCreated(gameId: Long) {
        val timeout = 5_000.milliseconds
        val checkInterval = 100.milliseconds
        withTimeoutOrNull(timeout) {
            while (!iceSessionRepository.existsByGameId(gameId)) {
                delay(checkInterval)
            }
        }
    }
}
