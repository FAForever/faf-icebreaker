package com.faforever.icebreaker.persistence

import jakarta.enterprise.context.ApplicationScoped
import java.net.InetAddress
import java.time.Clock
import java.time.Instant

// TODO(#132) - store FirewallWhitelistEntity in the DB as an actual entity
data class FirewallWhitelistEntity(
    val id: Long,
    val userId: Long,
    val sessionId: String,
    val allowedIp: InetAddress,
    var deletedAt: Instant?,
)

@ApplicationScoped
class FirewallWhitelistRepository(
    private val clock: Clock,
) {
    private val allowedIps: MutableList<FirewallWhitelistEntity> = mutableListOf()

    /** Whitelists [allowedIp] for the session [sessionId]. */
    fun insert(sessionId: String, userId: Long, allowedIp: InetAddress) {
        val lastId = allowedIps.map { it.id }.maxOrNull() ?: 0
        allowedIps.add(
            FirewallWhitelistEntity(
                id = lastId + 1,
                userId,
                sessionId,
                allowedIp,
                deletedAt = null,
            ),
        )
    }

    /** Returns a list of all whitelists for [sessionId]. */
    fun getForSessionId(sessionId: String): List<FirewallWhitelistEntity> = allowedIps.filter { it.sessionId == sessionId && it.deletedAt == null }

    /** Removes all whitelists for [sessionId]. */
    fun removeSession(sessionId: String) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }

    /** Removes the whitelist for user [userId] in session [sessionId]. */
    fun removeSessionUser(sessionId: String, userId: Long) {
        allowedIps.replaceAll {
            if (it.sessionId == sessionId && it.userId == userId && it.deletedAt == null) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }
}
