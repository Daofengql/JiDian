package com.root.fakeloc.hook.bridge

import android.location.Location
import android.os.Bundle
import android.os.SystemClock

/** A bounded, immutable location snapshot. No route history or unrelated preferences cross IPC. */
data class HookFrame(
    val latitude: Double, val longitude: Double, val altitude: Double?, val accuracy: Float,
    val speed: Float, val bearing: Float, val time: Long, val elapsedNanos: Long,
    val intervalMs: Long
) {
    fun fresh() = SystemClock.elapsedRealtimeNanos() - elapsedNanos in 0..15_000_000_000L
    fun location(provider: String = "gps") = Location(provider).apply {
        latitude = this@HookFrame.latitude; longitude = this@HookFrame.longitude
        this@HookFrame.altitude?.let { altitude = it; verticalAccuracyMeters = 2f }
        accuracy = this@HookFrame.accuracy; speed = this@HookFrame.speed; bearing = this@HookFrame.bearing
        time = this@HookFrame.time; elapsedRealtimeNanos = elapsedNanos
        speedAccuracyMetersPerSecond = .2f; bearingAccuracyDegrees = 1f
    }
    fun bundle() = Bundle().apply {
        putBoolean("active", true); putDouble("lat", latitude); putDouble("lng", longitude)
        putBoolean("hasAltitude", altitude != null); altitude?.let { putDouble("altitude", it) }
        putFloat("accuracy", accuracy); putFloat("speed", speed); putFloat("bearing", bearing)
        putLong("time", time); putLong("elapsedNanos", elapsedNanos); putLong("intervalMs", intervalMs)
    }
    companion object {
        fun from(location: Location, intervalMs: Long) = HookFrame(location.latitude, location.longitude,
            if (location.hasAltitude()) location.altitude else null, location.accuracy, location.speed,
            location.bearing, location.time, location.elapsedRealtimeNanos, intervalMs)
        fun read(bundle: Bundle?): HookFrame? {
            if (bundle?.getBoolean("active") != true) return null
            val frame = HookFrame(bundle.getDouble("lat"), bundle.getDouble("lng"),
                if (bundle.getBoolean("hasAltitude")) bundle.getDouble("altitude") else null,
                bundle.getFloat("accuracy"), bundle.getFloat("speed"), bundle.getFloat("bearing"),
                bundle.getLong("time"), bundle.getLong("elapsedNanos"), bundle.getLong("intervalMs", 1000))
            return frame.takeIf { it.fresh() && it.latitude.isFinite() && it.longitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        }
    }
}
