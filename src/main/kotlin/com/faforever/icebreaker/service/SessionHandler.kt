package com.faforever.icebreaker.service

import io.smallrye.mutiny.Uni

interface SessionHandler {
    val active: Boolean

    /**
     * Creates a new session for [gameId], connecting from [clientIp].
     *
     * [clientIp] is e.g. "88.217.205.180" or "2001:a61:9c01:11ab:c91e:c468:b262:3442".
     */
    fun createSession(id: String, userId: Long, clientIp: String): Uni<Unit>

    // Remove an entire session
    fun deleteSession(id: String): Uni<Unit>

    // Remove a single user from a session
    fun deletePeerSession(id: String, userId: Long): Uni<Unit>

    fun getIceServers(): List<Server>

    fun getIceServersSession(sessionId: String): List<Session.Server>
}
