package com.faforever.icebreaker.service.loki

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "loki")
interface LokiProperties {
    fun appIdentifier(): String
    fun enabled(): Boolean
}
