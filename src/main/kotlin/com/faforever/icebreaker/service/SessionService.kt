package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.util.AsyncRunner
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

    fun getSession(gameId: Long): Session {
        val sessionId = "game/$gameId"

        val servers = activeSessionHandlers.flatMap {
            it.createSession(sessionId)
            it.getIceServersSession(sessionId)
        }

        AsyncRunner.runLater {
            persistSessionDetailsIfNecessary(gameId, sessionId)
        }

        return Session(
            id = gameId.toString(),
            servers = servers,
        )
    }

    @Transactional
    fun persistSessionDetailsIfNecessary(gameId: Long, sessionId: String) {
        if (!iceSessionRepository.existsByGameId(gameId)) {
            try {
                LOG.debug("Creating session for gameId $gameId")
                iceSessionRepository.persist(
                    IceSessionEntity(
                        id = sessionId,
                        gameId = gameId,
                        createdAt = Instant.now(),
                    ),
                )
            } catch (e: Exception) {
                LOG.warn("Unable to persist session details for game id $gameId and session id $sessionId")
            }
        }
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
