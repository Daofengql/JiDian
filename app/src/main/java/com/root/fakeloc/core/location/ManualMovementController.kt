package com.root.fakeloc.core.location

import kotlin.math.*

/** A released or stale joystick holds position until the user explicitly resumes the route. */
class ManualMovementController {
    var active = false
        private set
    private var point = GeoPoint(0.0, 0.0)
    private var strength = 0.0
    val moving: Boolean get() = active && strength > 0.0
    private var bearing = 0f
    private var lastInputMs = 0L

    fun update(x: Double, y: Double, current: GeoPoint, nowMs: Long) {
        if (!active) point = current
        active = true
        val safeX = if (x.isFinite()) x.coerceIn(-1.0, 1.0) else 0.0
        val safeY = if (y.isFinite()) y.coerceIn(-1.0, 1.0) else 0.0
        strength = hypot(safeX, safeY).coerceIn(0.0, 1.0)
        if (strength < 0.08) strength = 0.0
        if (strength > 0) bearing = ((Math.toDegrees(atan2(safeX, -safeY)) + 360) % 360).toFloat()
        lastInputMs = nowMs
    }

    fun nextPosition(deltaMs: Long, speedKmh: Float, nowMs: Long): Triple<GeoPoint, Float, Float> {
        if (nowMs - lastInputMs > 700L) strength = 0.0
        val speed = (speedKmh.coerceIn(0f, 60f) / 3.6 * strength).toFloat()
        if (speed > 0) point = offset(point, speed * deltaMs.coerceIn(0L, 1000L) / 1000.0, bearing.toDouble())
        return Triple(point, speed, bearing)
    }

    fun reset() { active = false; strength = 0.0 }

    companion object {
        fun offset(point: GeoPoint, meters: Double, bearingDegrees: Double): GeoPoint {
            val a = meters / 6_371_000.0
            val bearing = Math.toRadians(bearingDegrees)
            val lat = Math.toRadians(point.latitude)
            val lng = Math.toRadians(point.longitude)
            val nextLat = asin((sin(lat) * cos(a) + cos(lat) * sin(a) * cos(bearing)).coerceIn(-1.0, 1.0))
            val nextLng = lng + atan2(sin(bearing) * sin(a) * cos(lat), cos(a) - sin(lat) * sin(nextLat))
            return point.copy(latitude = Math.toDegrees(nextLat), longitude = (Math.toDegrees(nextLng) + 540) % 360 - 180)
        }
    }
}
