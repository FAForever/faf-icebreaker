package com.faforever.icebreaker.service

interface SessionHandler {
    val active: Boolean

    fun createSession(id: String)

    fun deleteSession(id: String)

    fun getIceServers(sessionId: String): List<Session.Server>
}
