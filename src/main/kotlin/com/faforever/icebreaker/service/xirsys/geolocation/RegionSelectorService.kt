package com.faforever.icebreaker.service.xirsys.geolocation

import com.faforever.icebreaker.service.xirsys.XirsysProperties
import com.maxmind.geoip2.DatabaseReader
import com.maxmind.geoip2.model.CityResponse
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Paths

private val LOG = LoggerFactory.getLogger(RegionSelectorService::class.java)

@Singleton
class RegionSelectorService(
    xirsysProperties: XirsysProperties,
) {
    private val geoIpDatabasePath = Paths.get(xirsysProperties.geoIpPath())

    private val geoLocationReader: DatabaseReader? by lazy {
        if (!Files.exists(geoIpDatabasePath)) {
            LOG.warn("No geo database found at $geoIpDatabasePath! Will use fallback region ${XirsysRegion.fallbackRegion.name}")
            null
        } else {
            DatabaseReader.Builder(geoIpDatabasePath.toFile()).build()
        }
    }

    private data class UserLocation(
        override val latitude: Double,
        override val longitude: Double,
    ) : GeoLocation

    fun getClosestRegion(ipAddress: InetAddress): XirsysRegion =
        geoLocationReader
            ?.tryCity(ipAddress)
            ?.map(CityResponse::getLocation)
            ?.map { getClosestRegion(UserLocation(latitude = it.latitude, longitude = it.longitude)) }
            ?.orElse(null)
            ?: XirsysRegion.fallbackRegion

    private fun getClosestRegion(userLocation: UserLocation): XirsysRegion = XirsysRegion.allRegions.minBy { it.distanceTo(userLocation) }
}
