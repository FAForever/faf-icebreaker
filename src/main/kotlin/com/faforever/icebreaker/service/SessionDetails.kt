package com.faforever.icebreaker.service

data class SessionDetails(val userName: String, val secret: String, val iceServerUrls: List<String>)
