package com.faforever.icebreaker.service.hetzner

import com.faforever.icebreaker.persistence.FirewallWhitelistRepository
import io.vertx.core.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.concurrent.CompletableFuture

internal class HetznerFirewallUpdaterTest {
    @Test
    fun `Synchronous response failure does not leave later acknowledgements pending`() {
        val properties = mock(HetznerProperties::class.java)
        val repository = mock(FirewallWhitelistRepository::class.java)
        val client = mock(HetznerApiClient::class.java)

        @Suppress("UNCHECKED_CAST")
        val emitter = mock(Emitter::class.java) as Emitter<SyncMessage>
        val sendFailure = IllegalStateException("response channel overflow")
        val firstRequest = mock(JsonObject::class.java)
        val secondRequest = mock(JsonObject::class.java)

        `when`(properties.firewallId()).thenReturn(Optional.empty())
        `when`(emitter.send(any(SyncMessage::class.java)))
            .thenThrow(sendFailure)
            .thenReturn(CompletableFuture.completedFuture(null))
        `when`(firstRequest.mapTo(SyncMessage::class.java)).thenReturn(SyncMessage("first"))
        `when`(secondRequest.mapTo(SyncMessage::class.java)).thenReturn(SyncMessage("second"))

        val updater = HetznerFirewallUpdater(properties, repository, client, emitter)
        val firstAck = updater.handle(firstRequest).toCompletableFuture()
        val secondAck = updater.handle(secondRequest).toCompletableFuture()

        updater.syncFirewallWithHetzner()

        assertThat(firstAck).isCompletedExceptionally
        assertThat(secondAck).isCompletedWithValue(Unit)
        verify(emitter, times(2)).send(any(SyncMessage::class.java))
    }
}
