package com.faforever.icebreaker.service

import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import java.time.LocalDateTime

@Singleton
class SessionService(
    sessionHandlers: Instance<SessionHandler>,
    private val iceSessionRepository: IceSessionRepository,
) {
    private val activeSessionHandlers = sessionHandlers.filter { it.active }

    @Transactional
    fun getSession(gameId: Long): Session {
        val session = iceSessionRepository.findByGameId(gameId)
            ?: IceSessionEntity(gameId = gameId, createdAt = LocalDateTime.now()).also {
                iceSessionRepository.persist(it)
            }

        val servers = activeSessionHandlers.flatMap {
            it.createSession(session.id)
            it.getIceServers(session.id)
        }

        return Session(
            id = session.id,
            servers = servers,
        )
    }
}
