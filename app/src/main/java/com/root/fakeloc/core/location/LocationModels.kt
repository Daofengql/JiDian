package com.root.fakeloc.core.location

import java.io.Serializable

/**
 * 经纬度点模型
 */
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 30.0
) : Serializable

/**
 * 路径标点节点
 */
data class RouteWaypoint(
    val id: String,
    val point: GeoPoint,
    val targetSpeedKmh: Float = 15.0f, // 该段的目标移动速度 (km/h)
    val stayDurationMs: Long = 0L      // 在此点的停留时间 (毫秒)
) : Serializable

/**
 * 步频模拟模式
 */
enum class CadenceMode {
    AUTO_ADAPTIVE,  // 智能自适应模式: 根据当前移动速度/配速自动计算最自然的步频与步长
    CUSTOM_FIXED    // 自定义固定模式: 锁定目标步频 (如跑者黄金步频 180 步/分)
}

/**
 * 模拟运行模式配置
 */
data class MockConfig(
    var mode: MockMode = MockMode.SINGLE_POINT,
    var targetPoint: GeoPoint = GeoPoint(39.9087, 116.3975), // 默认天安门
    var waypoints: MutableList<RouteWaypoint> = mutableListOf(),
    var speedKmh: Float = 15.0f,            // 模拟速度 (km/h)
    var accuracy: Float = 3.5f,             // 定位精度 (米)
    var isLoop: Boolean = true,             // 路径是否往复循环
    var updateIntervalMs: Long = 1000L,     // GPS 上报频率 (毫秒)

    // 定点模式可选能力；路线模式使用独立的运动参数。
    var enableLocationJitter: Boolean = false,
    var locationJitterMeters: Double = 1.0,
    var enableAltitudeSimulation: Boolean = false,
    var altitudeMeters: Double = 30.0,

    // 传感器与步频仿真配置
    var enableSensorSync: Boolean = true,   // 是否同步模拟步数/传感器
    var cadenceMode: CadenceMode = CadenceMode.AUTO_ADAPTIVE, // 步频模式
    var targetCadenceSpm: Int = 175,        // 自定义目标步频 (SPM: Steps Per Minute, 步/分钟)
    var enableCadenceJitter: Boolean = true,// 是否开启拟真自然抖动 (防止恒定步频被特征检测)
    var cadenceJitterRatio: Float = 0.04f,  // 抖动范围 (±4%)

    // 基站仿真配置
    var enableCellSync: Boolean = false     // 定点模式是否计算模拟基站信息
) : Serializable

enum class MockMode {
    SINGLE_POINT, // 单点固定位置模拟
    ROUTE_TRACK   // 路径轨迹多点模拟
}

/** Called only on the service worker so engine objects keep their progress across updates. */
fun MockConfig.applyFrom(next: MockConfig) {
    mode = next.mode
    targetPoint = next.targetPoint
    waypoints = next.waypoints.toMutableList()
    speedKmh = next.speedKmh
    accuracy = next.accuracy
    isLoop = next.isLoop
    updateIntervalMs = next.updateIntervalMs
    enableLocationJitter = next.enableLocationJitter
    locationJitterMeters = next.locationJitterMeters
    enableAltitudeSimulation = next.enableAltitudeSimulation
    altitudeMeters = next.altitudeMeters
    enableSensorSync = next.enableSensorSync
    cadenceMode = next.cadenceMode
    targetCadenceSpm = next.targetCadenceSpm
    enableCadenceJitter = next.enableCadenceJitter
    cadenceJitterRatio = next.cadenceJitterRatio
    enableCellSync = next.enableCellSync
}
