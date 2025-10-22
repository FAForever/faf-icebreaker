package com.faforever.icebreaker.persistence

import io.quarkus.test.Mock
import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock

@Mock
@ApplicationScoped
class InMemoryFirewallWhitelistRepository(
    private val clock: Clock,
) : FirewallWhitelistRepository {
    private val allowedIps: MutableList<FirewallWhitelistEntity> = mutableListOf()

    override fun insert(sessionId: String, userId: Long, allowedIp: String) {
        val lastId = allowedIps.map { it.id }.maxOrNull() ?: 0
        allowedIps.add(
            FirewallWhitelistEntity(
                id = lastId + 1,
                userId = userId,
                sessionId = sessionId,
                allowedIp = allowedIp,
                createdAt = clock.instant(),
                deletedAt = null,
            ),
        )
    }

    override fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity> = getAllActive().filter { it.sessionId == sessionId }

    override fun getAllActive(): List<FirewallWhitelistEntity> = allowedIps.filter { it.deletedAt == null }.sortedBy { it.createdAt }

    override fun removeSession(sessionId: String) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }

    override fun removeSessionUser(sessionId: String, userId: Long) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.userId == userId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }

    override fun removeAll() {
        allowedIps.replaceAll {
            it.deletedAt = clock.instant()
            it
        }
    }
}
