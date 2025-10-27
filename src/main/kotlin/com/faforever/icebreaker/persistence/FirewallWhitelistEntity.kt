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
    fun persist(entity: FirewallWhitelistEntity)
    fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity>
    fun getAllActive(): List<FirewallWhitelistEntity>
    fun markSessionAsDeleted(sessionId: String)
    fun markSessionUserAsDeleted(sessionId: String, userId: Long)
    fun deleteAll(): Long
}

@Singleton
@Transactional
class FirewallWhitelistPanacheRepository(
    private val clock: Clock,
) : PanacheRepository<FirewallWhitelistEntity>,
    FirewallWhitelistRepository {

    override fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity> =
        find("sessionId = ?1 and deletedAt is null", sessionId).list()

    override fun getAllActive(): List<FirewallWhitelistEntity> =
        find("deletedAt is null order by createdAt").list()

    override fun markSessionAsDeleted(sessionId: String) {
        update("deletedAt = ?1 where sessionId = ?2 and deletedAt is null", clock.instant(), sessionId)
    }

    override fun markSessionUserAsDeleted(sessionId: String, userId: Long) {
        update(
            "deletedAt = ?1 where sessionId = ?2 and userId = ?3 and deletedAt is null",
            clock.instant(),
            sessionId,
            userId,
        )
    }
}
