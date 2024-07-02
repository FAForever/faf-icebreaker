package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.xirsys.geolocation.RegionSelectorService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.vertx.core.http.HttpServerRequest
import jakarta.inject.Singleton
import jakarta.ws.rs.core.Context
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetAddress
import java.net.URI

private val LOG: Logger = LoggerFactory.getLogger(XirsysApiAdapter::class.java)

/**
 * The Xirsys API does not comply to proper REST standards, thus we need an adapter.
 */
@Singleton
class XirsysApiAdapter(
    private val fafProperties: FafProperties,
    private val xirsysProperties: XirsysProperties,
    private val regionSelectorService: RegionSelectorService,
    private val objectMapper: ObjectMapper,
    @Context private val httpRequest: HttpServerRequest,
) {
    private val xirsysApiClient: XirsysApiClient =
        RestClientBuilder
            .newBuilder()
            .baseUri(URI.create(xirsysProperties.baseUrl()))
            .register(
                BasicAuthenticationRequestFilter(
                    username = xirsysProperties.ident(),
                    password = xirsysProperties.secret(),
                ),
            ).build(XirsysApiClient::class.java)

    @Retry
    fun listChannel(): List<String> = parseAndUnwrap {
        xirsysApiClient.listChannel(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
        )
    }

    @Retry
    fun createChannel(channelName: String) {
        try {
            parseAndUnwrap<Map<String, String>> {
                xirsysApiClient.createChannel(
                    namespace = xirsysProperties.channelNamespace(),
                    environment = fafProperties.environment(),
                    channelName = channelName,
                )
            }
        } catch (e: XirsysSpecifiedApiException) {
            if (e.errorCode != PATH_EXISTS) throw e
            LOG.debug("Channel $channelName already exists")
        }
    }

    @Retry
    fun deleteChannel(channelName: String) {
        try {
            parseAndUnwrap<Int> {
                xirsysApiClient.deleteChannel(
                    namespace = xirsysProperties.channelNamespace(),
                    environment = fafProperties.environment(),
                    channelName = channelName,
                )
            }
        } catch (e: XirsysSpecifiedApiException) {
            // ignore cases, where session is already deleted
            if (e.errorCode != NOT_FOUND) throw e
            LOG.warn("Channel $channelName does not exist, ignoring delete request")
        }
    }

    @Retry
    fun requestIceServers(
        channelName: String,
        turnRequest: TurnRequest = TurnRequest(),
    ): TurnResponse = parseAndUnwrap<TurnResponse> {
        xirsysApiClient.requestIceServers(
            namespace = xirsysProperties.channelNamespace(),
            environment = fafProperties.environment(),
            channelName = channelName,
            turnRequest = turnRequest,
        )
    }.withUserLocationServers()

    private fun TurnResponse.withUserLocationServers(): TurnResponse =
        regionSelectorService.getClosestRegion(httpRequest.getIp()).let { region ->
            copy(
                iceServers = iceServers.copy(
                    urls = iceServers.urls.map {
                        it.replace(
                            regex = Regex("(.+:)([\\w-]+.xirsys.com)(.*)"),
                            replacement = "$1${region.domain}$3",
                        )
                    },
                ),
            )
        }

    @Throws(IOException::class)
    private inline fun <reified T : Any> parseAndUnwrap(getResponse: () -> String): T {
        val response = getResponse()
        return try {
            when (val result = objectMapper.readValue<XirsysResponse<T>>(response)) {
                is XirsysResponse.Error -> throw XirsysSpecifiedApiException(
                    errorCode = result.code,
                    message = "Listing sessions failed: ${result.code}",
                )

                is XirsysResponse.Success -> result.data
            }
        } catch (e: XirsysSpecifiedApiException) {
            throw e
        } catch (e: IOException) {
            throw XirsysUnspecifiedApiException(errorResponse = response, cause = e)
        }
    }

    private fun HttpServerRequest.getIp() =
        InetAddress.getByName(getHeader(fafProperties.realIpHeader()) ?: remoteAddress().host())
}
