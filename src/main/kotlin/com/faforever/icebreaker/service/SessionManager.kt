package com.faforever.icebreaker.service

interface SessionManager {
    fun createSession(name: String)

    fun deleteSession(name: String)

    fun listSessions(): List<String>

    fun getIceServers(sessionName: String): SessionDetails
}
