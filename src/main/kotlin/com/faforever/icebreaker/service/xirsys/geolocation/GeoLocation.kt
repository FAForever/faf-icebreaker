package com.faforever.icebreaker.service.xirsys.geolocation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal interface GeoLocation {
    val latitude: Double
    val longitude: Double

    // Haversine formula to calculate distance between two points in kilometers
    fun distanceTo(other: GeoLocation): Double {
        val r = 6371 // Radius of the earth in kilometers
        val latDistance = Math.toRadians(other.latitude - this.latitude)
        val lonDistance = Math.toRadians(other.longitude - this.longitude)
        val a =
            sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(this.latitude)) *
                    cos(Math.toRadians(other.latitude)) *
                    sin(lonDistance / 2) *
                    sin(lonDistance / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c // Distance in kilometers
    }
}
