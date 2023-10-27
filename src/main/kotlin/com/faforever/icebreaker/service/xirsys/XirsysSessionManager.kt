package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.service.SessionDetails
import com.faforever.icebreaker.service.SessionManager
import jakarta.inject.Singleton
import org.eclipse.microprofile.rest.client.RestClientBuilder
import java.io.IOException
import java.net.URI

@Singleton
class XirsysSessionManager(
    xirsysProperties: XirsysProperties,
) : SessionManager {
    private val xirsysClient: XirsysClient = RestClientBuilder.newBuilder()
        .baseUri(URI.create(xirsysProperties.baseUrl()))
        .register(
            BasicAuthenticationRequestFilter(
                username = xirsysProperties.ident(),
                password = xirsysProperties.secret(),
            ),
        )
        .build(XirsysClient::class.java)

    override fun createSession(name: String) {
        val result = xirsysClient.createChannel(name)

        if (result is XirsysResponse.Error) {
            throw IOException(result.code)
        }
    }

    override fun deleteSession(name: String) {
        val result = xirsysClient.deleteChannel(name)

        if (result is XirsysResponse.Error) {
            throw IOException(result.code)
        }
    }

    override fun listSessions(): List<String> =
        when (val result = xirsysClient.listChannel()) {
            is XirsysResponse.Error -> throw IOException(result.code)
            is XirsysResponse.Success -> result.data
        }

    override fun getIceServers(sessionName: String): SessionDetails =
        when (val result = xirsysClient.requestIceServers(sessionName, TurnRequest())) {
            is XirsysResponse.Error -> throw IOException(result.code)
            is XirsysResponse.Success -> result.data.iceServers.let {
                SessionDetails(
                    userName = it.username,
                    secret = it.credential,
                    iceServerUrls = it.urls,
                )
            }
        }
}
