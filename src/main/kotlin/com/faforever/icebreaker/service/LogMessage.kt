package com.faforever.icebreaker.service

import java.time.ZonedDateTime

data class LogMessage(
    val timestamp: ZonedDateTime,
    val message: String,
    val metaData: Map<String, Any?>,
)
