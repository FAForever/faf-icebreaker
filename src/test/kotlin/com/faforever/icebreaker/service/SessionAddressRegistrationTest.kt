package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.GameUserStatsRepository
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.security.CurrentUserService
import com.faforever.icebreaker.service.loki.LokiService
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.inject.Instance
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SessionAddressRegistrationTest {
    private val sessions = mock<IceSessionRepository>()
    private val first = mock<SessionHandler>()
    private val second = mock<SessionHandler>()
    private val session = IceSessionEntity(id = "game/200", gameId = 200, createdAt = Instant.EPOCH)

    private fun service(): SessionService {
        val handlers = mock<Instance<SessionHandler>>()
        Mockito.`when`(handlers.iterator()).thenAnswer { listOf(first, second).iterator() }
        Mockito.`when`(first.active).thenReturn(true)
        Mockito.`when`(second.active).thenReturn(true)
        val identity = mock<SecurityIdentity>()
        Mockito.`when`(identity.attributes).thenReturn(emptyMap())
        val user = mock<CurrentUserService>()
        Mockito.`when`(user.requireCurrentUserId()).thenReturn(123L)
        Mockito.`when`(user.getCurrentUserIp()).thenReturn("192.0.2.1")
        return SessionService(
            handlers, mock<FafProperties>(), sessions, mock<GameUserStatsRepository>(),
            identity, user, ObjectMapper(), mock<Emitter<EventMessage>>(),
            mock<LokiService>(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), SimpleMeterRegistry(),
        )
    }

    @Test
    fun `persistence failure prevents all address registrations`() {
        val service = service()
        val failure = IllegalStateException("database unavailable")
        Mockito.doThrow(failure).`when`(sessions).persist(session)
        Mockito.clearInvocations(first, second)

        assertThatThrownBy { service.registerClientAddress(200) }.isSameAs(failure)

        Mockito.verifyNoInteractions(first, second)
    }

    @Test
    fun `registration attempts every handler and reports all failures`() {
        val service = service()
        val firstFailure = IllegalStateException("first handler failed")
        val secondFailure = IllegalStateException("second handler failed")
        Mockito.doThrow(firstFailure).`when`(first).registerClientAddress("game/200", 123, "192.0.2.1")
        Mockito.doThrow(secondFailure).`when`(second).registerClientAddress("game/200", 123, "192.0.2.1")

        val failure = assertThrows<IOException> { service.registerClientAddress(200) }
        assertThat(failure).hasCause(firstFailure)
        assertThat(failure.suppressed).containsExactly(secondFailure)
        Mockito.verify(second).registerClientAddress("game/200", 123, "192.0.2.1")
    }

    @Test
    fun `existing session is reused before handlers register`() {
        val service = service()
        Mockito.`when`(sessions.existsByGameId(200)).thenReturn(true)

        service.registerClientAddress(200)

        val order = Mockito.inOrder(sessions, first, second)
        order.verify(sessions).existsByGameId(200)
        order.verify(first).registerClientAddress("game/200", 123, "192.0.2.1")
        order.verify(second).registerClientAddress("game/200", 123, "192.0.2.1")
        Mockito.verify(sessions, Mockito.never()).persist(session)
    }

    @Test
    fun `a failed handler does not prevent successful registration by the next handler`() {
        val service = service()
        val failure = IllegalStateException("first handler failed")
        Mockito.doThrow(failure).`when`(first).registerClientAddress("game/200", 123, "192.0.2.1")

        assertThatThrownBy { service.registerClientAddress(200) }.hasCause(failure)

        Mockito.verify(second).registerClientAddress("game/200", 123, "192.0.2.1")
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
