package com.root.fakeloc.hook

import kotlin.math.*

/** SDK coordinates are converted at the boundary; the engine always stores WGS-84. */
object SdkCoordinates {
    data class Point(val lat: Double, val lng: Double)
    fun convert(latitude: Double, longitude: Double, type: String): Point? {
        val gps = Point(latitude, longitude)
        if (type == "wgs84") return gps
        val gcj = gcj(gps)
        if (type == "gcj02") return gcj
        if (type != "bd09ll") return null // Do not mislabel Baidu Mercator as degrees.
        val z = sqrt(gcj.lng * gcj.lng + gcj.lat * gcj.lat) + .00002 * sin(gcj.lat * PI * 3000 / 180)
        val theta = atan2(gcj.lat, gcj.lng) + .000003 * cos(gcj.lng * PI * 3000 / 180)
        return Point(z * sin(theta) + .006, z * cos(theta) + .0065)
    }
    private fun gcj(p: Point): Point {
        if (p.lng !in 72.004..137.8347 || p.lat !in .8293..55.8271) return p
        val x = p.lng - 105; val y = p.lat - 35
        var lat = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * sqrt(abs(x))
        lat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        lat += (20 * sin(y * PI) + 40 * sin(y / 3 * PI)) * 2 / 3
        lat += (160 * sin(y / 12 * PI) + 320 * sin(y * PI / 30)) * 2 / 3
        var lng = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * sqrt(abs(x))
        lng += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        lng += (20 * sin(x * PI) + 40 * sin(x / 3 * PI)) * 2 / 3
        lng += (150 * sin(x / 12 * PI) + 300 * sin(x / 30 * PI)) * 2 / 3
        val rad = p.lat / 180 * PI
        val magic = 1 - .006693421622965943 * sin(rad).pow(2)
        return Point(p.lat + lat * 180 / ((6378245 * (1 - .006693421622965943)) / (magic * sqrt(magic)) * PI),
            p.lng + lng * 180 / (6378245 / sqrt(magic) * cos(rad) * PI))
    }
}
