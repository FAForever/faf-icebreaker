package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetAddress

private val LOG: Logger = LoggerFactory.getLogger(XirsysSessionHandler::class.java)

@ApplicationScoped
class XirsysSessionHandler(
    xirsysProperties: XirsysProperties,
    private val fafProperties: FafProperties,
    private val xirsysApiAdapter: XirsysApiAdapter,
) : SessionHandler {
    companion object {
        const val SERVER_NAME = "xirsys.com"
    }

    override val active = xirsysProperties.enabled()
    private val turnEnabled = xirsysProperties.turnEnabled()

    @PostConstruct
    fun init() {
        LOG.info("XirsysSessionHandler active: $active, turnEnabled: $turnEnabled")
    }

    override fun createSession(id: String, userId: Long, clientIp: InetAddress) {
        LOG.debug("Creating session id $id")
        xirsysApiAdapter.createChannel(id)
    }

    override fun deleteSession(id: String) {
        xirsysApiAdapter.deleteChannel(channelName = id)
    }

    override fun deletePeerSession(id: String, userId: Long) {
        // Xirsys only cares about the entire session being deleted, there's no
        // per-peer state.
    }

    override fun getIceServers() = listOf(Server(id = SERVER_NAME, region = "Global"))

    override fun getIceServersSession(sessionId: String): List<Session.Server> = xirsysApiAdapter.requestIceServers(
        channelName = sessionId,
        turnRequest = TurnRequest(expire = fafProperties.tokenLifetimeSeconds()),
    ).iceServers.let {
        listOf(
            Session.Server(
                id = SERVER_NAME,
                username = it.username,
                credential = it.credential,
                urls = it.urls.map { url ->
                    // A sample response looks like "stun:fr-turn1.xirsys.com"
                    // The java URI class fails to read host and port due to the missing // after the :
                    // Thus we "normalize" the uri, even though it is technically valid
                    url.replaceFirst(":", "://")
                }.filter { url -> turnEnabled || !url.startsWith("turn") },
            ),
        )
    }
}
