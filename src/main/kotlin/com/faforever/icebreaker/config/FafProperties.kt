package com.faforever.icebreaker.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "faf")
interface FafProperties {
    fun selfUrl(): String

    /**
     * Must start with a letter, otherwise potential conflicts in Xirsys!
     */
    fun environment(): String

    fun tokenLifetimeSeconds(): Long

    fun maxSessionLifeTimeHours(): Long
}
