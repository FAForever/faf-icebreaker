package com.faforever.icebreaker.service

import java.net.InetAddress

interface SessionHandler {
    val active: Boolean

    fun createSession(id: String, userId: Long, clientIp: InetAddress)

    // Remove an entire session
    fun deleteSession(id: String)

    // Remove a single user from a session
    fun deletePeerSession(id: String, userId: Long)

    fun getIceServers(): List<Server>

    fun getIceServersSession(sessionId: String): List<Session.Server>
}
