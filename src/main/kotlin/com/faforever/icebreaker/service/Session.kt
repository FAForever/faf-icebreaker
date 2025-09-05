package com.faforever.icebreaker.service

data class Session(
    val id: String,
    val forceRelay: Boolean,
    val servers: List<Server>,
) {
    data class Server(
        val id: String,
        val username: String,
        val credential: String,
        val urls: List<String>,
    )
}
