package com.faforever.icebreaker.service.cloudflare

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "cloudflare")
interface CloudflareProperties {
    fun enabled(): Boolean

    fun turnEnabled(): Boolean

    fun turnKeyId(): String

    fun turnKeyApiToken(): String
}
