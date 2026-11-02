package com.root.fakeloc.ui.web

import com.root.fakeloc.core.location.CadenceMode
import com.root.fakeloc.core.location.GeoPoint
import com.root.fakeloc.core.location.MockConfig
import com.root.fakeloc.core.location.MockMode
import com.root.fakeloc.core.location.RouteWaypoint
import org.json.JSONArray
import org.json.JSONObject

/** Validated JSON contract shared by the local MDUI page and the foreground service. */
object ConfigJson {
    const val MAX_WAYPOINTS = 200

    private fun point(json: JSONObject): GeoPoint {
        val lat = json.getDouble("lat")
        val lng = json.getDouble("lng")
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) { "坐标超出有效范围" }
        return GeoPoint(lat, lng)
    }

    private fun number(json: JSONObject, key: String, fallback: Double, min: Double, max: Double): Double {
        val value = json.optDouble(key, fallback)
        require(value.isFinite()) { "参数 $key 无效" }
        return value.coerceIn(min, max)
    }

    fun decode(json: JSONObject): MockConfig {
        val points = json.optJSONArray("waypoints") ?: JSONArray()
        require(points.length() <= MAX_WAYPOINTS) { "路线点不能超过 $MAX_WAYPOINTS 个" }
        return MockConfig(
            mode = MockMode.valueOf(json.optString("mode", "SINGLE_POINT")),
            targetPoint = json.optJSONObject("targetPoint")?.let(::point) ?: GeoPoint(39.9087, 116.3975),
            waypoints = (0 until points.length()).map { i ->
                RouteWaypoint("point-$i", point(points.getJSONObject(i)), targetSpeedKmh = 0f)
            }.toMutableList(),
            speedKmh = number(json, "speedKmh", 15.0, 0.0, 60.0).toFloat(),
            accuracy = number(json, "accuracy", 3.5, 1.0, 100.0).toFloat(),
            isLoop = json.optBoolean("isLoop", true),
            updateIntervalMs = number(json, "updateIntervalMs", 1000.0, 500.0, 5000.0).toLong(),
            enableLocationJitter = json.optBoolean("enableLocationJitter", false),
            locationJitterMeters = number(json, "locationJitterMeters", 1.0, 0.5, 10.0),
            enableAltitudeSimulation = json.optBoolean("enableAltitudeSimulation", false),
            altitudeMeters = number(json, "altitudeMeters", 30.0, -500.0, 9000.0),
            enableSensorSync = json.optBoolean("enableSensorSync", true),
            cadenceMode = CadenceMode.valueOf(json.optString("cadenceMode", "AUTO_ADAPTIVE")),
            targetCadenceSpm = number(json, "targetCadenceSpm", 175.0, 40.0, 240.0).toInt(),
            enableCadenceJitter = json.optBoolean("enableCadenceJitter", true),
            cadenceJitterRatio = number(json, "cadenceJitterRatio", 0.04, 0.0, 0.2).toFloat(),
            enableCellSync = json.optBoolean("enableCellSync", false)
        )
    }

    private fun encodePoint(point: GeoPoint) = JSONObject().put("lat", point.latitude).put("lng", point.longitude)

    fun encode(config: MockConfig) = JSONObject().apply {
        put("mode", config.mode.name)
        put("targetPoint", encodePoint(config.targetPoint))
        put("waypoints", JSONArray().apply { config.waypoints.forEach { put(encodePoint(it.point)) } })
        put("speedKmh", config.speedKmh.toDouble())
        put("accuracy", config.accuracy.toDouble())
        put("isLoop", config.isLoop)
        put("updateIntervalMs", config.updateIntervalMs)
        put("enableLocationJitter", config.enableLocationJitter)
        put("locationJitterMeters", config.locationJitterMeters)
        put("enableAltitudeSimulation", config.enableAltitudeSimulation)
        put("altitudeMeters", config.altitudeMeters)
        put("enableSensorSync", config.enableSensorSync)
        put("cadenceMode", config.cadenceMode.name)
        put("targetCadenceSpm", config.targetCadenceSpm)
        put("enableCadenceJitter", config.enableCadenceJitter)
        put("cadenceJitterRatio", config.cadenceJitterRatio.toDouble())
        put("enableCellSync", config.enableCellSync)
    }
}
