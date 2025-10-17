package com.faforever.icebreaker.persistence

import jakarta.enterprise.context.ApplicationScoped
import java.time.Clock
import java.time.Instant

// TODO(#132) - store FirewallWhitelistEntity in the DB as an actual entity
data class FirewallWhitelistEntity(
    val id: Long,
    val userId: Long,
    val sessionId: String,
    // e.g. "88.217.205.180" or "2001:a61:9c01:11ab:c91e:c468:b262:3442"
    val allowedIp: String,
    var deletedAt: Instant?,
)

@ApplicationScoped
class FirewallWhitelistRepository(
    private val clock: Clock,
) {
    private val allowedIps: MutableList<FirewallWhitelistEntity> = mutableListOf()

    /**
     * Whitelists [allowedIp] for the session [sessionId].
     *
     * [allowedIp] is e.g. "88.217.205.180".
     */
    fun insert(sessionId: String, userId: Long, allowedIp: String) {
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
            if (it.sessionId == sessionId) {
                it.deletedAt = clock.instant()
            }
            it
        }
    }
}
