package com.faforever.icebreaker.service.turn

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.TurnServerEntity
import com.faforever.icebreaker.persistence.TurnServerRepository
import com.faforever.icebreaker.security.getUserId
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val LOG: Logger = LoggerFactory.getLogger(TurnSessionHandler::class.java)

@ApplicationScoped
class TurnSessionHandler(
    val fafProperties: FafProperties,
    val turnServerRepository: TurnServerRepository,
    val securityIdentity: SecurityIdentity,
) : SessionHandler {
    // if you don't want to use it, leave the SQL table empty
    override val active = true

    @PostConstruct
    fun init() {
        LOG.info("TurnSessionHandler active: $active")
    }

    override fun createSession(id: String) {
        // TURN has no session handling, we use global access
    }

    override fun deleteSession(id: String) {
        // TURN has no session handling, we use global access
    }

    override fun getIceServers() = turnServerRepository.findActive().map { Server(id = it.host, region = it.region) }

    override fun getIceServersSession(sessionId: String): List<Session.Server> =
        turnServerRepository
            .findActive()
            .map {
                val (tokenName, tokenSecret) = buildHmac(sessionId, securityIdentity.getUserId(), it.presharedKey)
                Session.Server(
                    id = it.host,
                    username = tokenName,
                    credential = tokenSecret,
                    urls = buildUrls(turnServer = it),
                )
            }

    private fun buildHmac(
        sessionName: String,
        userId: Int,
        presharedKey: String,
    ): Pair<String, String> {
        val timestamp = System.currentTimeMillis() / 1000 + fafProperties.tokenLifetimeSeconds()
        val tokenName = "$timestamp:$userId-$sessionName"

        val secretKeySpec = SecretKeySpec(presharedKey.encodeToByteArray(), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(secretKeySpec)

        val tokenSecret =
            mac.doFinal(tokenName.encodeToByteArray()).let {
                Base64.getEncoder().encodeToString(it)
            }

        return tokenName to tokenSecret
    }

    private fun buildUrls(
        turnServer: TurnServerEntity,
    ): List<String> {
        val result = mutableListOf<String>()

        if (turnServer.stunPort != null) {
            result.add("stun://${turnServer.host}:${turnServer.stunPort}")
        }

        if (turnServer.turnUdpPort != null) {
            result.add("turn://${turnServer.host}:${turnServer.turnUdpPort}?transport=udp")
        }

        if (turnServer.turnTcpPort != null) {
            result.add("turn://${turnServer.host}:${turnServer.turnTcpPort}?transport=tcp")
        }

        if (turnServer.turnsTcpPort != null) {
            result.add("turns://${turnServer.host}:${turnServer.turnsTcpPort}?transport=tcp")
        }

        return result
    }
}
