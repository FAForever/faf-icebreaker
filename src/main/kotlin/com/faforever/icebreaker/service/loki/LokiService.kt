package com.faforever.icebreaker.service.loki

import com.faforever.icebreaker.service.LogMessage
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val LOG: Logger = LoggerFactory.getLogger(LokiService::class.java)

@ApplicationScoped
class LokiService(
    private val lokiProperties: LokiProperties,
    @RestClient private val lokiApiClient: LokiApiClient,
) {

    @PostConstruct
    fun init() {
        LOG.info("LokiService enabled: ${lokiProperties.enabled()}")
    }

    fun forwardLogs(gameId: Long, userId: Long, logs: List<LogMessage>) {
        if (!lokiProperties.enabled()) return

        val request =
            LokiApiClient.LokiLogPushRequest(
                streams =
                    listOf(
                        LokiApiClient.LokiLogPushRequest.Log(
                            stream =
                                mapOf(
                                    "app" to lokiProperties.appIdentifier(),
                                    "gameId" to gameId.toString(),
                                    "userId" to userId.toString(),
                                ),
                            values =
                                logs.map {
                                    LokiApiClient.LokiLogPushRequest.Log.Line(
                                        timestamp =
                                            it.timestamp.toInstant().toEpochMilli() * 1_000_000L,
                                        message = it.message,
                                        metaData = it.metaData,
                                    )
                                },
                        )
                    )
            )

        lokiApiClient.push(request)
    }
}
