package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.RestClientBuilder
import java.io.IOException
import java.net.URI

@Singleton
class XirsysSessionHandler(
    private val fafProperties: FafProperties,
    private val xirsysProperties: XirsysProperties,
) : SessionHandler {
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
        if (listSessions().contains(id)) return

        val result = xirsysClient.createChannel(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
            channelName = id,
        )

        if (result is XirsysResponse.Error) {
            throw IOException(result.code)
        }
    }

    override fun deleteSession(id: String) {
        val result = xirsysClient.deleteChannel(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
            channelName = id,
        )

        if (result is XirsysResponse.Error) {
            throw IOException(result.code)
        }
    }

    fun listSessions(): List<String> =
        when (
            val result = xirsysClient.listChannel(
                namespace = xirsysProperties.channelNamespace(),
                environment = fafProperties.environment(),
            )
        ) {
            is XirsysResponse.Error -> throw IOException(result.code)
            is XirsysResponse.Success -> result.data
        }

    override fun getIceServers(sessionId: String): List<Session.Server> =
        when (
            val result = xirsysClient.requestIceServers(
                namespace = xirsysProperties.channelNamespace(),
                environment = fafProperties.environment(),
                channelName = sessionId,
                turnRequest = TurnRequest(),
            )
        ) {
            is XirsysResponse.Error -> throw IOException(result.code)
            is XirsysResponse.Success -> result.data.iceServers.let {
                Session.Server(
                    userName = it.username,
                    secret = it.credential,
                    iceServerUrls = it.urls,
                )
            }
        }.let { listOf(it) }
}
