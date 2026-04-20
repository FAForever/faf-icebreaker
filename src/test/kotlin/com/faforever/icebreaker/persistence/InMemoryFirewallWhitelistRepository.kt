package com.faforever.icebreaker.persistence

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList

@Mock
@ApplicationScoped
class InMemoryFirewallWhitelistRepository(
    private val clock: Clock,
) : FirewallWhitelistRepository {
    private val allowedIps: MutableList<FirewallWhitelistEntity> = CopyOnWriteArrayList()

    fun persist(entity: FirewallWhitelistEntity): FirewallWhitelistEntity {
        val lastId = allowedIps.maxOfOrNull { it.id } ?: 0
        val newEntity = FirewallWhitelistEntity(
            id = lastId + 1,
            userId = entity.userId,
            sessionId = entity.sessionId,
            allowedIp = entity.allowedIp,
            createdAt = clock.instant(),
            deletedAt = null,
        )
        allowedIps.add(newEntity)
        return newEntity
    }

    @Synchronized
    override fun persistOrGet(entity: FirewallWhitelistEntity): FirewallWhitelistEntity {
        val existing = allowedIps.find {
            it.sessionId == entity.sessionId && it.userId == entity.userId && it.deletedAt == null
        }
        if (existing != null) {
            return existing
        }
        return persist(entity)
    }

    override fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity> = getAllActive().filter { it.sessionId == sessionId }

    override fun getAllActive(): List<FirewallWhitelistEntity> = allowedIps.filter { it.deletedAt == null }.sortedBy { it.createdAt }

    override fun markSessionAsDeleted(sessionId: String) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }

    override fun markSessionUserAsDeleted(sessionId: String, userId: Long) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.userId == userId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }

    override fun deleteAll(): Long {
        val count = allowedIps.count()
        allowedIps.replaceAll {
            it.deletedAt = clock.instant()
            it
        }

        return count.toLong()
    }
}
