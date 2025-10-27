package com.faforever.icebreaker.service

import com.faforever.icebreaker.config.FafProperties
import com.faforever.icebreaker.persistence.GameUserStatsEntity
import com.faforever.icebreaker.persistence.GameUserStatsRepository
import com.faforever.icebreaker.persistence.IceSessionEntity
import com.faforever.icebreaker.persistence.IceSessionRepository
import com.faforever.icebreaker.security.CurrentUserService
import com.faforever.icebreaker.security.getUserId
import com.faforever.icebreaker.service.loki.LokiService
import com.faforever.icebreaker.util.AsyncRunner
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import io.quarkus.scheduler.Scheduled
import io.quarkus.security.ForbiddenException
import io.quarkus.security.identity.SecurityIdentity
import io.smallrye.jwt.build.Jwt
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import io.smallrye.mutiny.helpers.MultiEmitterProcessor
import io.smallrye.mutiny.infrastructure.Infrastructure
import io.vertx.core.json.JsonObject
import jakarta.enterprise.inject.Instance
import jakarta.inject.Singleton
import jakarta.transaction.Transactional
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.temporal.ChronoUnit

private val LOG: Logger = LoggerFactory.getLogger(SessionService::class.java)

@Singleton
class SessionService(
    sessionHandlers: Instance<SessionHandler>,
    private val fafProperties: FafProperties,
    private val iceSessionRepository: IceSessionRepository,
    private val gameUserStatsRepository: GameUserStatsRepository,
    private val securityIdentity: SecurityIdentity,
    private val currentUserService: CurrentUserService,
    private val objectMapper: ObjectMapper,
    @Channel("events-out")
    private val rabbitmqEventEmitter: Emitter<EventMessage>,
    private val lokiService: LokiService,
    private val clock: Clock,
) {
    private val activeSessionHandlers = sessionHandlers.filter { it.active }
    private val localEventEmitter = MultiEmitterProcessor.create<EventMessage>()
    private val localEventBroadcast: Multi<EventMessage> = localEventEmitter.toMulti().broadcast().toAllSubscribers()

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
            .expiresAt(clock.instant().plus(fafProperties.maxSessionLifeTimeHours(), ChronoUnit.HOURS))
            .sign()
    }

    @Deprecated(
        message = "Only remaining for java-ice-adapter compatibility",
        replaceWith = ReplaceWith("getSession"),
    )
    fun getServers(): List<Server> = activeSessionHandlers.flatMap { it.getIceServers() }

    /**
     * Creates a new session for [gameId]
     */
    fun getSession(gameId: Long): Session {
        // For compatibility reasons right now we only check on mismatch because general FAF JWT are still allowed
        // but have no implicit gameId attached
        securityIdentity.attributes["gameId"]?.takeIf { it != gameId }?.run {
            throw ForbiddenException("Not authorized to join game $gameId")
        }

        val sessionId = buildSessionId(gameId)

        val currentUserId = currentUserService.requireCurrentUserId()
        val currentUserIp = currentUserService.getCurrentUserIp()
        val servers =
            activeSessionHandlers.flatMap {
                it.createSession(sessionId, currentUserId, currentUserIp)
                it.getIceServersSession(sessionId)
            }

        AsyncRunner.runLater {
            persistSessionDetailsIfNecessary(gameId, sessionId)
        }

        return Session(
            id = gameId.toString(),
            forceRelay = fafProperties.forceRelay(),
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
                        createdAt = clock.instant(),
                    ),
                )
            } catch (e: Exception) {
                LOG.warn("Unable to persist session details for game id $gameId and session id $sessionId")
            }
        }
    }

    // The `delayed` parameter is an inelegant way to avoid a startup race where
    // the events-out channel isn't ready by the time Quarkus tries to run this method,
    // causing error messages when running the tests.
    @Scheduled(delayed = "10s", every = "10m")
    fun cleanUpSessions() {
        val cleanupTime = clock.instant().minus(fafProperties.maxSessionLifeTimeHours(), ChronoUnit.HOURS)
        LOG.info("Cleaning up sessions older than $cleanupTime")
        iceSessionRepository
            .findByCreatedAtLesserThan(
                instant = cleanupTime,
            ).forEach { iceSession ->
                LOG.debug("Cleaning up session id ${iceSession.id}")
                activeSessionHandlers.forEach { it.deleteSession(iceSession.id) }

                gameUserStatsRepository.deleteByGameId(iceSession.gameId)
                iceSessionRepository.delete(iceSession)
            }
    }

    fun listenForEventMessages(gameId: Long): Multi<EventMessage> {
        val userId = currentUserService.requireCurrentUserId()

        // Use Uni to wrap the blocking repository calls
        val userStatsUni = Uni.createFrom().item {
            gameUserStatsRepository.findByGameIdAndUserId(gameId = gameId, userId = userId)
        }.runSubscriptionOn(Infrastructure.getDefaultWorkerPool())

        // Use flatMap to reactively persist or increment stats
        return userStatsUni.onItem().transformToUni { gameUserStats ->
            if (gameUserStats != null) {
                // If gameUserStats exists update the stats
                Uni.createFrom().item {
                    gameUserStatsRepository.incrementConnectionAttempts(gameId = gameId, userId = userId)
                }
            } else {
                // If not, persist the new stats reactively
                Uni.createFrom().item {
                    gameUserStatsRepository.persist(
                        GameUserStatsEntity(
                            gameId = gameId,
                            userId = userId,
                        ),
                    )
                }
            }
        }.flatMap {
            // Send message after processing stats persistence or increment
            Uni.createFrom()
                .completionStage(rabbitmqEventEmitter.send(ConnectedMessage(gameId = gameId, senderId = userId)))
        }.onItem().transformToMulti {
            LOG.debug("Subscription to gameId $gameId events established")

            localEventBroadcast
                .filter { event -> event.gameId == gameId && (event.recipientId == userId || (event.recipientId == null && event.senderId != userId)) }
        }
    }

    fun onMessageReceived(gameId: Long, eventMessage: EventMessage) {
        check(eventMessage !is ConnectedMessage) {
            "ConnectedMessage is implicitly created and must not be sent by peers"
        }

        // Check messages for manipulation. We need to prevent cross-channel vulnerabilities.
        check(eventMessage.gameId == gameId) {
            "gameId $gameId from endpoint does not match gameId ${eventMessage.gameId} in candidateMessage"
        }

        val currentUserId = currentUserService.requireCurrentUserId()
        if (eventMessage.senderId != currentUserId) {
            throw ForbiddenException("Current user id $currentUserId from endpoint does not match sourceId ${eventMessage.senderId} in candidateMessage")
        }

        val sessionId = buildSessionId(gameId)
        if (eventMessage is PeerClosingMessage) {
            activeSessionHandlers.forEach { it.deletePeerSession(sessionId, currentUserId) }
        }

        rabbitmqEventEmitter.send(eventMessage)
    }

    @Incoming("events-in")
    fun onEventMessage(eventMessage: JsonObject) {
        LOG.debug("Received event message: {}", eventMessage)
        val parsedMessage = objectMapper.convertValue<EventMessage>(eventMessage.map)
        localEventEmitter.emit(parsedMessage)
    }

    fun onLogsPushed(gameId: Long, logs: List<LogMessage>) {
        val currentUserId = currentUserService.requireCurrentUserId()

        LOG.debug("Received logs for gameId {} from userId {}", gameId, currentUserId)

        if (gameUserStatsRepository.findByGameIdAndUserId(gameId = gameId, userId = currentUserId) == null) {
            LOG.error("User id {} is not connected to game id {}", currentUserId, gameId)
            throw ForbiddenException()
        }

        val estimatedLogsSize = objectMapper.writeValueAsString(logs).toByteArray().size
        gameUserStatsRepository.incrementLogBytesPushed(gameId, currentUserId, estimatedLogsSize)

        lokiService.forwardLogs(gameId = gameId, userId = currentUserId, logs = logs)
    }

    private fun buildSessionId(gameId: Long) = "game/$gameId"
}
