package com.faforever.icebreaker.service.cloudflare

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.service.Server
import com.faforever.icebreaker.service.Session
import com.faforever.icebreaker.service.SessionHandler
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val LOG: Logger = LoggerFactory.getLogger(CloudflareSessionHandler::class.java)

@ApplicationScoped
class CloudflareSessionHandler(
    cloudflareProperties: CloudflareProperties,
    val fafProperties: FafProperties,
    private val cloudflareApiAdapter: CloudflareApiAdapter,
) : SessionHandler {
    companion object {
        const val SERVER_NAME = "cloudflare.com"
    }

    override val active = cloudflareProperties.enabled()
    private val turnEnabled = cloudflareProperties.turnEnabled()

    @PostConstruct
    fun init() {
        LOG.info("CloudflareSessionHandler active: $active, turnEnabled: $turnEnabled")
    }

    override fun createSession(id: String, userId: Long, clientIp: String) {
        // Cloudflare has no session handling, we use global access
    }

    override fun deleteSession(id: String) {
        // Cloudflare has no session handling, we use global access
    }

    override fun getIceServers() = listOf(Server(id = SERVER_NAME, region = "Global"))

    override fun getIceServersSession(sessionId: String): List<Session.Server> =
        cloudflareApiAdapter.requestIceServers(
            credentialRequest = CloudflareApiClient.CredentialRequest(
                ttl = fafProperties.tokenLifetimeSeconds(),
                customIdentifier = sessionId,
            ),
        ).let {
            listOf(
                Session.Server(
                    id = SERVER_NAME,
                    username = it.iceServers.username,
                    credential = it.iceServers.credential,
                    urls = it.iceServers.urls.map { url ->
                        // A sample response looks like "stun:fr-turn1.xirsys.com"
                        // The java URI class fails to read host and port due to the missing // after the :
                        // Thus we "normalize" the uri, even though it is technically valid
                        url.replaceFirst(":", "://")
                    }.filter { url -> turnEnabled || !url.startsWith("turn") },
                ),
            )
        }
}
