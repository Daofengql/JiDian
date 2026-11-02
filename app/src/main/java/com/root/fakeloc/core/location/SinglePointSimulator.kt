package com.root.fakeloc.core.location

import java.util.Random
import kotlin.math.*

/** Samples a bounded circle in meters, including near the poles and date line. */
class SinglePointSimulator(private val config: MockConfig, private val random: Random = Random()) {
    fun nextPoint(): GeoPoint {
        val target = config.targetPoint
        if (!config.enableLocationJitter) return target
        val distance = sqrt(random.nextDouble()) * config.locationJitterMeters.coerceIn(0.5, 10.0)
        val angularDistance = distance / 6_371_000.0
        val bearing = random.nextDouble() * 2 * PI
        val lat = Math.toRadians(target.latitude)
        val lng = Math.toRadians(target.longitude)
        val nextLat = asin((sin(lat) * cos(angularDistance) + cos(lat) * sin(angularDistance) * cos(bearing)).coerceIn(-1.0, 1.0))
        val nextLng = lng + atan2(sin(bearing) * sin(angularDistance) * cos(lat), cos(angularDistance) - sin(lat) * sin(nextLat))
        return target.copy(latitude = Math.toDegrees(nextLat), longitude = ((Math.toDegrees(nextLng) + 540.0) % 360.0) - 180.0)
    }
}
