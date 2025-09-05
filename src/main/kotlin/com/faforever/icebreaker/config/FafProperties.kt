package com.faforever.icebreaker.config

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "faf")
interface FafProperties {
    fun selfUrl(): String

    /**
     * Must start with a letter, otherwise potential conflicts in Xirsys!
     */
    fun environment(): String

    /**
     * Tell api clients to force their ICE adapters to use a TURN server
     */
    fun forceRelay(): Boolean

    /**
     * Define the header, where to pick the real ip address from. For regular reverse proxies such as nginx or Traefik,
     * this is X-Real-Ip. However, in certain scenarios such as Cloudflare proxy different headers might be required.
     */
    fun realIpHeader(): String

    fun tokenLifetimeSeconds(): Long

    fun maxSessionLifeTimeHours(): Long
}
