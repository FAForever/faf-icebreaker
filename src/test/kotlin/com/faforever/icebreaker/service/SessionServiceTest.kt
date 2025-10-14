package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.util.FakeClock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.jwt.Claim
import io.quarkus.test.security.jwt.JwtSecurity
import jakarta.inject.Inject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

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
        service.getSession(200L, "1.2.3.4")

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/200")
        assertThat(allowedIps).hasSize(1)
        assertThat(allowedIps[0].allowedIp).isEqualTo("1.2.3.4")
    }
}
