package com.faforever.icebreaker.service.xirsys

import io.smallrye.config.ConfigMapping

@ConfigMapping(prefix = "xirsys")
interface XirsysProperties {
    fun enabled(): Boolean

    fun turnEnabled(): Boolean

    fun baseUrl(): String

    fun ident(): String

    fun secret(): String

    fun channelNamespace(): String

    fun geoIpPath(): String
}
