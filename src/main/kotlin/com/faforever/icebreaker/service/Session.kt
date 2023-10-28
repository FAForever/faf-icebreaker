package com.faforever.icebreaker.service

data class Session(
    val id: String,
    val servers: List<Server>,
) {
    data class Server(
        val username: String,
        val credential: String,
        val urls: List<String>,
    )
}
