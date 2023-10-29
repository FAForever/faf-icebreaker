package com.faforever.icebreaker.service.coturn

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.CoturnServerRepository
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import jakarta.inject.Singleton
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Singleton
class CoturnSessionHandler(
    val fafProperties: FafProperties,
    val coturnServerRepository: CoturnServerRepository,
) : SessionHandler {
    // if you don't want to use it, leave the SQL table empty
    override val active = true

    override fun createSession(id: String) {
        // Coturn has no session handling, we use global access
    }

    override fun deleteSession(id: String) {
        // Coturn has no session handling, we use global access
    }

    override fun getIceServers(sessionId: String): List<Session.Server> =
        coturnServerRepository.findAll()
            .list()
            .map {
                val (tokenName, tokenSecret) = buildHmac(sessionId, it.presharedKey)
                Session.Server(
                    username = tokenName,
                    credential = tokenSecret,
                    urls = buildUrls(hostName = it.host, port = it.port),
                )
            }

    private fun buildHmac(sessionName: String, presharedKey: String): Pair<String, String> {
        val timestamp = System.currentTimeMillis() / 1000 + fafProperties.tokenLifetimeSeconds()
        val tokenName = "$timestamp:$sessionName"

        val secretKeySpec = SecretKeySpec(presharedKey.encodeToByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)

        val tokenSecret = mac.doFinal(tokenName.encodeToByteArray()).let {
            Base64.getEncoder().encodeToString(it)
        }

        return tokenName to tokenSecret
    }

    private fun buildUrls(hostName: String, port: Int) = listOf(
        "stun://$hostName:$port",
        "turn://$hostName:$port?transport=udp",
        "turn://$hostName:$port?transport=tcp",
    )
}
