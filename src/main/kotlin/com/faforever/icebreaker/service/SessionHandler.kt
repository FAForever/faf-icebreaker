package com.faforever.icebreaker.service

import java.net.InetAddress

interface SessionHandler {
    val active: Boolean

    fun createSession(id: String, userId: Long, clientIp: InetAddress)

    fun deleteSession(id: String)

    fun getIceServers(): List<Server>

    fun getIceServersSession(sessionId: String): List<Session.Server>
}
