package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.security.getUserId
import com.faforever.icebreaker.util.AsyncRunner
import io.quarkus.scheduler.Scheduled
import io.quarkus.security.ForbiddenException
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.jwt.build.Jwt
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
    private val securityIdentity: SecurityIdentity,
) {
    private val activeSessionHandlers = sessionHandlers.filter { it.active }

    fun buildToken(gameId: Long): String {
        val userId = securityIdentity.getUserId()

        return Jwt
            .subject(userId.toString())
            .claim(
                "ext",
                mapOf(
                    "roles" to listOf("USER"),
                    "gameId" to gameId,
                ),
            ).claim("scp", listOf("lobby"))
            .issuer(fafProperties.selfUrl())
            .audience(fafProperties.selfUrl())
            .expiresAt(Instant.now().plus(fafProperties.maxSessionLifeTimeHours(), ChronoUnit.HOURS))
            .sign()
    }

    @Deprecated(
        message = "Only remaining for java-ice-adapter compatibility",
        replaceWith = ReplaceWith("getSession"),
    )
    fun getServers(): List<Server> = activeSessionHandlers.flatMap { it.getIceServers() }

    fun getSession(gameId: Long): Session {
        // For compatibility reasons right now we only check on mismatch because general FAF JWT are still allowed
        // but have no implicit gameId attached
        securityIdentity.attributes["gameId"]?.takeIf { it != gameId }?.run {
            throw ForbiddenException("Not authorized to join game $gameId")
        }

        val sessionId = "game/$gameId"

        val servers =
            activeSessionHandlers.flatMap {
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
    fun persistSessionDetailsIfNecessary(
        gameId: Long,
        sessionId: String,
    ) {
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

    @Scheduled(every = "10m")
    fun cleanUpSessions() {
        LOG.info("Cleaning up outdated sessions")
        iceSessionRepository
            .findByCreatedAtLesserThan(
                instant = Instant.now().plus(fafProperties.maxSessionLifeTimeHours(), ChronoUnit.HOURS),
            ).forEach { iceSession ->
                LOG.debug("Cleaning up session id ${iceSession.id}")
                activeSessionHandlers.forEach { it.deleteSession(iceSession.id) }
                iceSessionRepository.delete(iceSession)
            }
    }
}
