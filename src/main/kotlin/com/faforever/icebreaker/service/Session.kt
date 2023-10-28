package com.faforever.icebreaker.service

data class Session(
    val id: String,
    val servers: List<Server>,
) {
    data class Server(
        val userName: String,
        val secret: String,
        val iceServerUrls: List<String>,
    )
}
