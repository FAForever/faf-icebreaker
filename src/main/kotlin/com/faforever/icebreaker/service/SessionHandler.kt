package com.faforever.icebreaker.service

interface SessionHandler {
    val active: Boolean

    /**
     * Creates a new session for [id], connecting from [clientIp], and returns the servers
     * that were successfully provisioned for it.
     *
     * [clientIp] is e.g. "88.217.205.180" or "2001:a61:9c01:11ab:c91e:c468:b262:3442".
     */
    fun createSession(id: String, userId: Long, clientIp: String): List<Session.Server>

    // Remove an entire session
    fun deleteSession(id: String)

    // Remove a single user from a session
    fun deletePeerSession(id: String, userId: Long)

    fun getIceServers(): List<Server>
}
