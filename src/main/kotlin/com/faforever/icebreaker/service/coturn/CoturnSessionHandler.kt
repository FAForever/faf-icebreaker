package com.faforever.icebreaker.service.coturn

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.CoturnServerEntity
import com.faforever.icebreaker.persistence.CoturnServerRepository
import com.faforever.icebreaker.security.getUserId
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import com.faforever.icebreaker.service.hetzner.HetznerFirewallService
import io.quarkus.security.identity.SecurityIdentity
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val LOG: Logger = LoggerFactory.getLogger(CoturnSessionHandler::class.java)

@ApplicationScoped
class CoturnSessionHandler(
    val fafProperties: FafProperties,
    val coturnServerRepository: CoturnServerRepository,
    val securityIdentity: SecurityIdentity,
    val hetznerFirewallService: HetznerFirewallService,
) : SessionHandler {
    // if you don't want to use it, leave the SQL table empty
    override val active = true

    @PostConstruct
    fun init() {
        LOG.info("CoturnSessionHandler active: $active")
    }

    override fun createSession(id: String, userId: Long, clientIp: String) {
        hetznerFirewallService.whitelistIpForSession(id, userId, clientIp)
    }

    override fun deleteSession(id: String) {
        hetznerFirewallService.removeWhitelistsForSession(id)
    }

    override fun getIceServers() = coturnServerRepository.findActive().map { Server(id = it.host, region = it.region) }

    override fun getIceServersSession(sessionId: String): List<Session.Server> =
        coturnServerRepository
            .findActive()
            .map {
                val (tokenName, tokenSecret) = buildHmac(sessionId, securityIdentity.getUserId(), it.presharedKey)
                Session.Server(
                    id = it.host,
                    username = tokenName,
                    credential = tokenSecret,
                    urls = buildUrls(coturnServer = it),
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
        coturnServer: CoturnServerEntity,
    ): List<String> {
        val result = mutableListOf<String>()

        if (coturnServer.stunPort != null) {
            result.add("stun://${coturnServer.host}:${coturnServer.stunPort}")
        }

        if (coturnServer.turnUdpPort != null) {
            result.add("turn://${coturnServer.host}:${coturnServer.turnUdpPort}?transport=udp")
        }

        if (coturnServer.turnTcpPort != null) {
            result.add("turn://${coturnServer.host}:${coturnServer.turnTcpPort}?transport=tcp")
        }

        if (coturnServer.turnsTcpPort != null) {
            result.add("turns://${coturnServer.host}:${coturnServer.turnsTcpPort}?transport=tcp")
        }

        return result
    }
}
