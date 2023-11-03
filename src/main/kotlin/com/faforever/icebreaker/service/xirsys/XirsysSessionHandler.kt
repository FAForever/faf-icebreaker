package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI

private val LOG: Logger = LoggerFactory.getLogger(XirsysSessionHandler::class.java)

@Singleton
class XirsysSessionHandler(
    private val fafProperties: FafProperties,
    private val xirsysProperties: XirsysProperties,
) : SessionHandler {
    companion object {
        const val SERVER_NAME = "xirsys.com"
    }

    private val xirsysClient: XirsysClient = RestClientBuilder.newBuilder()
        .baseUri(URI.create(xirsysProperties.baseUrl()))
        .register(
            BasicAuthenticationRequestFilter(
                username = xirsysProperties.ident(),
                password = xirsysProperties.secret(),
            ),
        )
        .build(XirsysClient::class.java)

    override val active = xirsysProperties.enabled()

    override fun createSession(id: String) {
        if (listSessions().contains(id)) {
            LOG.debug("Session id $id already exists")
            return
        }

        LOG.debug("Creating session id $id")

        val result = xirsysClient.createChannel(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
            channelName = id,
        )

        if (result is XirsysResponse.Error) {
            LOG.error("Creating session failed: ${result.code}")
        }
    }

    override fun deleteSession(id: String) {
        val result = xirsysClient.deleteChannel(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
            channelName = id,
        )

        if (result is XirsysResponse.Error) {
            LOG.error("Deleting session failed: ${result.code}")
        }
    }

    private fun listSessions(): List<String> =
        when (
            val result = xirsysClient.listChannel(
                namespace = xirsysProperties.channelNamespace(),
                environment = fafProperties.environment(),
            )
        ) {
            is XirsysResponse.Error -> emptyList<String>().also {
                LOG.error("Listing sessions failed: ${result.code}")
            }

            is XirsysResponse.Success -> result.data
        }

    override fun getIceServers() = listOf(Server(id = SERVER_NAME, region = "Global"))

    override fun getIceServersSession(sessionId: String): List<Session.Server> =
        when (
            val result = xirsysClient.requestIceServers(
                namespace = xirsysProperties.channelNamespace(),
                environment = fafProperties.environment(),
                channelName = sessionId,
                turnRequest = TurnRequest(),
            )
        ) {
            is XirsysResponse.Error -> emptyList<Session.Server>().also {
                LOG.error("Requesting ICE servers failed: ${result.code}")
            }

            is XirsysResponse.Success -> result.data.iceServers.let {
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
                        },
                    ),
                )
            }
        }
}
