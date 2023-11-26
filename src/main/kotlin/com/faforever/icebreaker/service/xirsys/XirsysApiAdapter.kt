package com.faforever.icebreaker.service.xirsys

import com.faforever.icebreaker.config.FafProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.inject.Singleton
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.RestClientBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI

private val LOG: Logger = LoggerFactory.getLogger(XirsysApiAdapter::class.java)

/**
 * The Xirsys API does not comply to proper REST standards, thus we need an adapter.
 */
@Singleton
class XirsysApiAdapter(
    private val fafProperties: FafProperties,
    private val xirsysProperties: XirsysProperties,
    private val objectMapper: ObjectMapper,
) {
    private val xirsysApiClient: XirsysApiClient = RestClientBuilder.newBuilder()
        .baseUri(URI.create(xirsysProperties.baseUrl()))
        .register(
            BasicAuthenticationRequestFilter(
                username = xirsysProperties.ident(),
                password = xirsysProperties.secret(),
            ),
        )
        .build(XirsysApiClient::class.java)

    @Retry
    fun listChannel(): List<String> =
        parseAndUnwrap {
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
            if (!e.errorCode.equals(PATH_EXISTS, true)) throw e
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
    fun requestIceServers(channelName: String, turnRequest: TurnRequest = TurnRequest()): TurnResponse =
        parseAndUnwrap {
            xirsysApiClient.requestIceServers(
                namespace = xirsysProperties.channelNamespace(),
                environment = fafProperties.environment(),
                channelName = channelName,
                turnRequest = turnRequest,
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
}
