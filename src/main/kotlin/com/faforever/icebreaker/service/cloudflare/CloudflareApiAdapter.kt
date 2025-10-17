package com.faforever.icebreaker.service.cloudflare

import jakarta.inject.Singleton
import java.net.URI
import org.eclipse.microprofile.faulttolerance.Retry
import org.eclipse.microprofile.rest.client.RestClientBuilder

@Singleton
class CloudflareApiAdapter(private val cloudflareProperties: CloudflareProperties) {
    private val cloudflareApiClient =
        RestClientBuilder.newBuilder()
            .baseUri(URI.create("https://rtc.live.cloudflare.com"))
            .register(
                ApiKeyAuthenticationRequestFilter(
                    turnApiKey = cloudflareProperties.turnKeyApiToken()
                )
            )
            .build(CloudflareApiClient::class.java)

    @Retry
    fun requestIceServers(credentialRequest: CloudflareApiClient.CredentialRequest) =
        cloudflareApiClient.requestCredentials(
            turnKeyId = cloudflareProperties.turnKeyId(),
            credentialRequest = credentialRequest,
        )
}
