package com.faforever.icebreaker.service.xirsys.geolocation

import com.faforever.icebreaker.service.xirsys.XirsysProperties
import com.maxmind.geoip2.DatabaseReader
import jakarta.inject.Singleton
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger(RegionSelectorService::class.java)

@Singleton
class RegionSelectorService(xirsysProperties: XirsysProperties) {
    private val geoIpDatabasePath = Paths.get(xirsysProperties.geoIpPath())

    private val geoLocationReader: DatabaseReader? by lazy {
        if (!Files.exists(geoIpDatabasePath)) {
            LOG.warn(
                "No geo database found at $geoIpDatabasePath! Will use fallback region ${XirsysRegion.fallbackRegion.name}"
            )
            null
        } else {
            DatabaseReader.Builder(geoIpDatabasePath.toFile()).build()
        }
    }

    private data class UserLocation(override val latitude: Double, override val longitude: Double) :
        GeoLocation

    fun getClosestRegion(ipAddress: InetAddress): XirsysRegion =
        geoLocationReader
            ?.runCatching { city(ipAddress) }
            ?.onFailure { LOG.warn("Failed to get geo location for $ipAddress", it) }
            ?.getOrNull()
            ?.location
            ?.let {
                getClosestRegion(UserLocation(latitude = it.latitude, longitude = it.longitude))
            } ?: XirsysRegion.fallbackRegion

    private fun getClosestRegion(userLocation: UserLocation): XirsysRegion =
        XirsysRegion.allRegions.minBy { it.distanceTo(userLocation) }
}
