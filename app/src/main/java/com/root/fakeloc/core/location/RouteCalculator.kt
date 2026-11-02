package com.root.fakeloc.core.location

import kotlin.math.*

class RouteCalculator(private val config: MockConfig) {
    private var fromIndex = 0
    private var direction = 1
    private var progress = 0.0
    private var finished = false
    private var rejoinStart: GeoPoint? = null
    private var rejoinTarget: GeoPoint? = null
    private var rejoinProgress = 0.0

    fun reset() { fromIndex = 0; direction = 1; progress = 0.0; finished = false; rejoinStart = null; rejoinTarget = null }

    fun resumeFrom(point: GeoPoint) {
        rejoinTarget = nextRoutePosition(0L).first
        rejoinStart = point
        rejoinProgress = 0.0
    }

    fun nextPosition(deltaTimeMs: Long): Triple<GeoPoint, Float, Float> {
        val start = rejoinStart
        val target = rejoinTarget
        if (start != null && target != null) {
            val distance = calculateDistance(start, target)
            val speed = config.speedKmh.coerceAtLeast(0f) / 3.6f
            val seconds = deltaTimeMs.coerceAtLeast(0L) / 1000.0
            val bearing = calculateBearing(start, target)
            if (speed == 0f) return Triple(interpolate(start, target, rejoinProgress), 0f, bearing)
            val timeLeft = distance * (1 - rejoinProgress) / speed
            if (distance > 0.001 && seconds < timeLeft) {
                rejoinProgress += seconds * speed / distance
                return Triple(interpolate(start, target, rejoinProgress), speed, bearing)
            }
            rejoinStart = null
            rejoinTarget = null
            return nextRoutePosition(((seconds - timeLeft).coerceAtLeast(0.0) * 1000).toLong())
        }
        return nextRoutePosition(deltaTimeMs)
    }

    private fun nextRoutePosition(deltaTimeMs: Long): Triple<GeoPoint, Float, Float> {
        val points = config.waypoints
        if (points.isEmpty()) return Triple(config.targetPoint, 0f, 0f)
        if (points.size == 1) return Triple(points[0].point, 0f, 0f)
        if (fromIndex !in points.indices) reset()
        if (finished) return Triple(points.last().point, 0f, 0f)
        var remainingSeconds = deltaTimeMs.coerceAtLeast(0L) / 1000.0
        // Bound traversal for degenerate routes containing identical points.
        for (ignored in 0 until points.size * 4) {
            val toIndex = fromIndex + direction
            if (toIndex !in points.indices) { reset(); continue }
            val start = points[fromIndex]
            val end = points[toIndex]
            val distance = calculateDistance(start.point, end.point)
            val speed = (if (start.targetSpeedKmh > 0f) start.targetSpeedKmh else config.speedKmh).coerceAtLeast(0f) / 3.6f
            val bearing = calculateBearing(start.point, end.point)
            if (speed == 0f) return Triple(interpolate(start.point, end.point, progress), 0f, bearing)
            val distanceLeft = distance * (1.0 - progress)
            val timeToEnd = distanceLeft / speed
            if (distance > 0.001 && remainingSeconds < timeToEnd) {
                progress += speed * remainingSeconds / distance
                return Triple(interpolate(start.point, end.point, progress), speed, bearing)
            }
            remainingSeconds = (remainingSeconds - timeToEnd).coerceAtLeast(0.0)
            fromIndex = toIndex
            progress = 0.0
            if (fromIndex == points.lastIndex) {
                if (!config.isLoop) { finished = true; return Triple(end.point, 0f, bearing) }
                direction = -1
            } else if (fromIndex == 0) direction = 1
            if (remainingSeconds <= 0.0) return Triple(end.point, speed, bearing)
        }
        return Triple(points[fromIndex].point, 0f, 0f)
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6371000.0
        fun calculateDistance(p1: GeoPoint, p2: GeoPoint): Double {
            val dLat = Math.toRadians(p2.latitude - p1.latitude)
            val dLon = Math.toRadians(p2.longitude - p1.longitude)
            val a = (sin(dLat / 2).pow(2) + sin(dLon / 2).pow(2) * cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude))).coerceIn(0.0, 1.0)
            return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
        fun calculateBearing(p1: GeoPoint, p2: GeoPoint): Float {
            val lat1 = Math.toRadians(p1.latitude)
            val lat2 = Math.toRadians(p2.latitude)
            val dLon = Math.toRadians(p2.longitude - p1.longitude)
            return ((Math.toDegrees(atan2(sin(dLon) * cos(lat2), cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon))) + 360) % 360).toFloat()
        }
        fun interpolate(p1: GeoPoint, p2: GeoPoint, ratio: Double): GeoPoint {
            val r = ratio.coerceIn(0.0, 1.0)
            // Use the shorter arc when a route crosses the antimeridian.
            val lngDelta = ((p2.longitude - p1.longitude + 540) % 360) - 180
            val lng = ((p1.longitude + lngDelta * r + 540) % 360) - 180
            return GeoPoint(p1.latitude + (p2.latitude - p1.latitude) * r, lng, p1.altitude + (p2.altitude - p1.altitude) * r)
        }
    }
}
