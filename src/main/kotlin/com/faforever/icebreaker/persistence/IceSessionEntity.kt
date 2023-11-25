package com.faforever.icebreaker.persistence

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.inject.Singleton
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeoutException

@Entity
@Table(name = "ice_sessions")
data class IceSessionEntity(
    @Id
    val id: String = UUID.randomUUID().toString(),

    val gameId: Long,

    val createdAt: Instant,

) : PanacheEntityBase

@Singleton
class IceSessionRepository : PanacheRepository<IceSessionEntity> {

    companion object {
        val LOG = LoggerFactory.getLogger(IceSessionRepository::class.java)
    }

    fun findByGameId(gameId: Long) =
        find("gameId = ?1", gameId).firstResult()

    fun findByCreatedAtLesserThan(instant: Instant) =
        find("createdAt <= ?1", instant).list()

    fun acquireGameLock(gameId: Long, timeout: Int = 10) {
        val lockAcquired = getEntityManager().createNativeQuery("SELECT GET_LOCK(:lockName,:timeout)", Boolean::class.java).apply {
            setParameter("lockName", "game_id_$gameId")
            setParameter("timeout", timeout)
        }.singleResult as Boolean?

        if (lockAcquired != true) {
            throw TimeoutException("Unable to acquire game lock for $gameId")
        }
    }

    fun releaseGameLock(gameId: Long) {
        val lockReleased = getEntityManager().createNativeQuery("SELECT RELEASE_LOCK(:lockName)", Boolean::class.java).apply {
            setParameter("lockName", "game_id_$gameId")
        }.singleResult as Boolean?

        when (lockReleased) {
            null -> LOG.warn("No lock exists for $gameId")
            false -> LOG.warn("Not owner of lock for $gameId")
            true -> LOG.debug("lock released for $gameId")
        }
    }

}

