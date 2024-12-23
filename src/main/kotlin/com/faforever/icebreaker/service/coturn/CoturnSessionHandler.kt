package com.faforever.icebreaker.service.coturn

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.CoturnServerRepository
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
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
) : SessionHandler {
    // if you don't want to use it, leave the SQL table empty
    override val active = true

    @PostConstruct
    fun init() {
        LOG.info("CoturnSessionHandler active: $active")
    }

    override fun createSession(id: String) {
        // Coturn has no session handling, we use global access
    }

    override fun deleteSession(id: String) {
        // Coturn has no session handling, we use global access
    }

    override fun getIceServers() = coturnServerRepository.findActive().map { Server(id = it.host, region = it.region) }

    override fun getIceServersSession(sessionId: String): List<Session.Server> =
        coturnServerRepository
            .findActive()
            .map {
                val (tokenName, tokenSecret) = buildHmac(sessionId, it.presharedKey)
                Session.Server(
                    id = it.host,
                    username = tokenName,
                    credential = tokenSecret,
                    urls = buildUrls(hostName = it.host, port = it.port),
                )
            }

    private fun buildHmac(
        sessionName: String,
        presharedKey: String,
    ): Pair<String, String> {
        val timestamp = System.currentTimeMillis() / 1000 + fafProperties.tokenLifetimeSeconds()
        val tokenName = "$timestamp:$sessionName"

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
        hostName: String,
        port: Int,
    ) = listOf(
        "stun://$hostName:$port",
        "turn://$hostName:$port?transport=udp",
        "turn://$hostName:$port?transport=tcp",
    )
}
