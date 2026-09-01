package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.FirewallWhitelistEntity
import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import com.faforever.icebreaker.persistence.GameUserStatsEntity
import com.faforever.icebreaker.persistence.GameUserStatsRepository
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.service.hetzner.StubHetznerApiClient
import com.faforever.icebreaker.service.loki.LokiService
import com.faforever.icebreaker.sync.waitUntil
import com.faforever.icebreaker.util.FakeClock
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import io.quarkus.test.InjectMock
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import io.quarkus.test.security.jwt.Claim
import io.quarkus.test.security.jwt.ClaimType
import io.quarkus.test.security.jwt.JwtSecurity
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.Mockito
import java.time.Duration
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds

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
    lateinit var gameUserStatsRepository: GameUserStatsRepository

    @Inject
    lateinit var clock: FakeClock

    @Inject
    @RestClient
    lateinit var hetznerApi: StubHetznerApiClient

    @Inject
    lateinit var fafProperties: FafProperties

    @InjectMock
    lateinit var httpServerRequest: HttpServerRequest

    @InjectMock
    lateinit var lokiService: LokiService

    @ConfigProperty(name = "rabbitmq-host")
    lateinit var rabbitmqHost: String

    @ConfigProperty(name = "rabbitmq-port")
    var rabbitmqPort: Int = 0

    @ConfigProperty(name = "rabbitmq-username")
    lateinit var rabbitmqUsername: String

    @ConfigProperty(name = "rabbitmq-password")
    lateinit var rabbitmqPassword: String

    @ConfigProperty(name = "mp.messaging.incoming.game-result-in.virtual-host")
    lateinit var rabbitmqVirtualHost: String

    private val testIp = "1.2.3.4"

    @BeforeEach
    fun setup() {
        Mockito.`when`(httpServerRequest.getHeader(fafProperties.realIpHeader()))
            .thenReturn(testIp)

        gameUserStatsRepository.deleteAll()
        iceSessionRepository.deleteAll()
        hetznerApi.reset()
        firewallWhitelistRepository.deleteAll()
        Mockito.clearInvocations(lokiService)
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
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
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
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
        assertThat(iceSessionRepository.existsByGameId(201)).isFalse()
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":201}"""),
        ],
    )
    @Test
    fun `Client close removes every address family from the WebRTC session`() {
        service.getSession(201L)

        runBlocking {
            waitUntil {
                iceSessionRepository.existsByGameId(201)
            }
        }

        Mockito.`when`(httpServerRequest.getHeader(fafProperties.realIpHeader()))
            .thenReturn("2001:db8::1")
        service.registerClientAddress(201L)

        assertThat(firewallWhitelistRepository.getForSessionId("game/201").map { it.allowedIp })
            .containsExactlyInAnyOrder(testIp, "2001:db8::1")

        service.onMessageReceived(201, PeerClosingMessage(gameId = 201, senderId = 123))

        val allowedIps = firewallWhitelistRepository.getForSessionId("game/201")
        assertThat(allowedIps).isEmpty()
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":203}"""),
        ],
    )
    @Test
    fun `registerClientAddress persists session lifecycle state`() {
        service.registerClientAddress(203L)

        assertThat(iceSessionRepository.existsByGameId(203L)).isTrue()
        assertThat(firewallWhitelistRepository.getForSessionId("game/203").map { it.allowedIp })
            .containsExactly(testIp)
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
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

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "123"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":202}"""),
        ],
    )
    @Test
    fun `getSession succeeds when Hetzner firewall sync fails`() {
        hetznerApi.failRequests = true

        // The Hetzner sync fails, but the request must not blow up with a 500.
        assertThatCode { service.getSession(202L) }.doesNotThrowAnyException()
    }

    @Test
    fun `game result closes only the matching session resources`() {
        persistSession(gameId = 301, userId = 101, ip = "1.2.3.4")
        persistSession(gameId = 302, userId = 102, ip = "5.6.7.8")

        service.onGameResult(gameResultMessage(301))

        assertThat(iceSessionRepository.existsByGameId(301)).isTrue()
        assertThat(firewallWhitelistRepository.getForSessionId("game/301")).isEmpty()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(301, 101)).isNotNull()

        assertThat(iceSessionRepository.existsByGameId(302)).isTrue()
        assertThat(firewallWhitelistRepository.getForSessionId("game/302")).hasSize(1)
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(302, 102)).isNotNull()

        val whitelistedIps = hetznerApi.getRulesByFirewallId("fwid")!!.flatMap { it.sourceIps }.toSet()
        assertThat(whitelistedIps).containsExactly("5.6.7.8/32")
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "108"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":308}"""),
        ],
    )
    @Test
    fun `game result preserves post-game log uploads`() {
        persistSession(gameId = 308, userId = 108, ip = "1.2.3.4")
        val logs = listOf(LogMessage(ZonedDateTime.parse("2026-08-26T12:00:00Z"), "Game has ended", emptyMap()))

        service.onGameResult(gameResultMessage(308))

        assertThatCode { service.onLogsPushed(308, logs) }.doesNotThrowAnyException()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(308, 108)!!.logBytesPushed).isPositive()
        Mockito.verify(lokiService).forwardLogs(308, 108, logs)
    }

    @TestSecurity(user = "testUser", roles = ["viewer"])
    @JwtSecurity(
        claims = [
            Claim(key = "sub", value = "105"),
            Claim(key = "scp", value = """["lobby"]""", type = ClaimType.JSON_ARRAY),
            Claim(key = "ext", value = """{"roles":["USER"],"gameId":305}"""),
        ],
    )
    @Test
    fun `malformed broker message does not prevent the next game result`() {
        service.getSession(305)
        runBlocking {
            waitUntil {
                iceSessionRepository.existsByGameId(305)
            }
        }
        gameUserStatsRepository.persist(GameUserStatsEntity(gameId = 305, userId = 105))

        publishGameResult("not json".encodeToByteArray())
        publishGameResult(gameResultMessage(305))

        runBlocking {
            waitUntil(timeout = 8.seconds) {
                firewallWhitelistRepository.getForSessionId("game/305").isEmpty() &&
                    hetznerApi.getRulesByFirewallId("fwid")?.isEmpty() == true
            }
        }
        assertThat(iceSessionRepository.existsByGameId(305)).isTrue()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(305, 105)).isNotNull()
    }

    @Test
    fun `failed game result cleanup retains state for scheduled retry`() {
        persistSession(gameId = 306, userId = 106, ip = "1.2.3.4")
        hetznerApi.failRequests = true

        assertThatCode { service.onGameResult(gameResultMessage(306)) }.doesNotThrowAnyException()

        assertThat(iceSessionRepository.existsByGameId(306)).isTrue()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(306, 106)).isNotNull()

        hetznerApi.failRequests = false
        clock.setNow(clock.instant() + Duration.ofDays(14))
        service.cleanUpSessions()

        assertThat(iceSessionRepository.existsByGameId(306)).isFalse()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(306, 106)).isNull()
    }

    @Test
    fun `scheduled cleanup retains state until session handlers succeed`() {
        persistSession(gameId = 307, userId = 107, ip = "1.2.3.4")
        clock.setNow(clock.instant() + Duration.ofDays(14))
        hetznerApi.failRequests = true

        assertThatCode { service.cleanUpSessions() }.doesNotThrowAnyException()

        assertThat(iceSessionRepository.existsByGameId(307)).isTrue()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(307, 107)).isNotNull()

        hetznerApi.failRequests = false
        service.cleanUpSessions()

        assertThat(iceSessionRepository.existsByGameId(307)).isFalse()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(307, 107)).isNull()
    }

    @Test
    fun `duplicate game results are idempotent and unknown results are ignored`() {
        persistSession(gameId = 303, userId = 103, ip = "1.2.3.4")

        service.onGameResult(gameResultMessage(303))

        assertThatCode {
            service.onGameResult(gameResultMessage(303))
        }.doesNotThrowAnyException()
        assertThat(iceSessionRepository.existsByGameId(303)).isTrue()
        assertThat(firewallWhitelistRepository.getForSessionId("game/303")).isEmpty()
        assertThat(gameUserStatsRepository.findByGameIdAndUserId(303, 103)).isNotNull()

        val callsAfterDuplicate = hetznerApi.getCallCount()
        assertThatCode { service.onGameResult(gameResultMessage(999)) }.doesNotThrowAnyException()
        assertThat(hetznerApi.getCallCount()).isEqualTo(callsAfterDuplicate)
    }

    @Test
    fun `malformed game results are ignored`() {
        persistSession(gameId = 304, userId = 104, ip = "1.2.3.4")

        assertThatCode {
            service.onGameResult("not json".encodeToByteArray())
            service.onGameResult("""{"rating_type":"global"}""".encodeToByteArray())
            service.onGameResult("""{"game_id":304.5}""".encodeToByteArray())
            service.onGameResult("""{"game_id":"304"}""".encodeToByteArray())
        }.doesNotThrowAnyException()

        assertThat(iceSessionRepository.existsByGameId(304)).isTrue()
        assertThat(firewallWhitelistRepository.getForSessionId("game/304")).hasSize(1)
    }

    private fun persistSession(
        gameId: Long,
        userId: Long,
        ip: String,
    ) {
        iceSessionRepository.persist(
            IceSessionEntity(
                id = "game/$gameId",
                gameId = gameId,
                createdAt = clock.instant(),
            ),
        )
        gameUserStatsRepository.persist(GameUserStatsEntity(gameId = gameId, userId = userId))
        firewallWhitelistRepository.persistOrGet(
            FirewallWhitelistEntity(
                userId = userId,
                sessionId = "game/$gameId",
                allowedIp = ip,
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
    }

    private fun gameResultMessage(gameId: Long) =
        """
        {
          "game_id": $gameId,
          "rating_type": "global",
          "map_id": 123,
          "featured_mod": "faf",
          "sim_mod_ids": [],
          "commander_kills": {},
          "validity": "VALID",
          "teams": []
        }
        """.trimIndent().encodeToByteArray()

    private fun publishGameResult(message: ByteArray) {
        val connectionFactory =
            ConnectionFactory().apply {
                host = rabbitmqHost
                port = rabbitmqPort
                username = rabbitmqUsername
                password = rabbitmqPassword
                virtualHost = rabbitmqVirtualHost
            }
        // Match the lobby server: persistent JSON bytes with no AMQP content type.
        val properties = AMQP.BasicProperties.Builder().deliveryMode(2).build()

        connectionFactory.newConnection().use { connection ->
            connection.createChannel().use { channel ->
                channel.basicPublish("faf-lobby", "success.gameResults.create", properties, message)
            }
        }
    }
}
