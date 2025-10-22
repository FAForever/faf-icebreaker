package com.faforever.icebreaker.persistence

import io.quarkus.hibernate.orm.panache.kotlin.PanacheEntityBase
import io.quarkus.hibernate.orm.panache.kotlin.PanacheRepository
import jakarta.inject.Singleton
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.transaction.Transactional
import java.time.Clock
import java.time.Instant

@Entity
@Table(name = "firewall_whitelist")
data class FirewallWhitelistEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    val userId: Long,
    val sessionId: String,
    @Column(length = 45)
    val allowedIp: String,
    @Column(updatable = false)
    val createdAt: Instant,
    var deletedAt: Instant?,
) : PanacheEntityBase

interface FirewallWhitelistRepository {
    fun insert(sessionId: String, userId: Long, allowedIp: String)
    fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity>
    fun getAllActive(): List<FirewallWhitelistEntity>
    fun removeSession(sessionId: String)
    fun removeSessionUser(sessionId: String, userId: Long)
    fun removeAll()
}

@Singleton
@Transactional
class FirewallWhitelistPanacheRepository(
    private val clock: Clock,
) : PanacheRepository<FirewallWhitelistEntity>,
    FirewallWhitelistRepository {

    override fun insert(sessionId: String, userId: Long, allowedIp: String) {
        persist(
            FirewallWhitelistEntity(
                userId = userId,
                sessionId = sessionId,
                allowedIp = allowedIp,
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
    }

    override fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity> =
        find("sessionId = ?1 and deletedAt is null", sessionId).list()

    override fun getAllActive(): List<FirewallWhitelistEntity> =
        find("deletedAt is null order by createdAt").list()

    override fun removeSession(sessionId: String) {
        update("deletedAt = ?1 where sessionId = ?2 and deletedAt is null", clock.instant(), sessionId)
    }

    override fun removeSessionUser(sessionId: String, userId: Long) {
        update(
            "deletedAt = ?1 where sessionId = ?2 and userId = ?3 and deletedAt is null",
            clock.instant(),
            sessionId,
            userId,
        )
    }

    override fun removeAll() {
        update("deletedAt = ?1 where deletedAt is null", clock.instant())
    }
}
