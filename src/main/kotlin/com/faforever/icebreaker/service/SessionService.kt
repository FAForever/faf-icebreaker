package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val LOG: Logger = LoggerFactory.getLogger(SessionService::class.java)

@Singleton
class SessionService(
    sessionHandlers: Instance<SessionHandler>,
    private val fafProperties: FafProperties,
    private val iceSessionRepository: IceSessionRepository,
) {
    private val activeSessionHandlers = sessionHandlers.filter { it.active }

    fun getServers(): List<Server> = activeSessionHandlers.flatMap { it.getIceServers() }

    fun lockGameId(gameId: Long, timeout: Int = 10): AutoCloseable {
        iceSessionRepository.acquireGameLock(gameId, timeout)
        return AutoCloseable { iceSessionRepository.releaseGameLock(gameId) }
    }

    @Transactional
    fun getSessionId(gameId: Long): String {
        val session: IceSessionEntity = iceSessionRepository.findByGameId(gameId)
            ?: IceSessionEntity(gameId = gameId, createdAt = Instant.now()).also {
                LOG.debug("Creating session for gameId $gameId")
                iceSessionRepository.persist(it)
            }

        return session.id
    }

    fun getSession(sessionId: String): Session {
        val servers = activeSessionHandlers.flatMap {
            it.createSession(sessionId)
            it.getIceServersSession(sessionId)
        }

        return Session(
            id = sessionId,
            servers = servers,
        )
    }

    @Transactional
    @Scheduled(every = "10m")
    fun cleanUpSessions() {
        LOG.info("Cleaning up outdated sessions")
        iceSessionRepository.findByCreatedAtLesserThan(
            instant = Instant.now().plus(fafProperties.maxSessionLifeTimeHours(), ChronoUnit.HOURS),
        ).forEach { iceSession ->
            LOG.debug("Cleaning up session id ${iceSession.id}")
            activeSessionHandlers.forEach { it.deleteSession(iceSession.id) }
            iceSessionRepository.delete(iceSession)
        }
    }
}
