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

    @Transactional
    fun getSession(gameId: Long): Session {
        val session = iceSessionRepository.findByGameId(gameId)
            ?: IceSessionEntity(gameId = gameId, createdAt = Instant.now()).also {
                LOG.debug("Creating session for gameId $gameId")
                iceSessionRepository.persist(it)
            }

        val servers = activeSessionHandlers.flatMap {
            it.createSession(session.id)
            it.getIceServersSession(session.id)
        }

        return Session(
            id = session.id,
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
