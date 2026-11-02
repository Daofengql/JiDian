package com.root.fakeloc.core.location

import android.content.Context
import android.annotation.SuppressLint
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.Random

/**
 * 模拟位置核心调度引擎
 * 负责通过 Android TestProvider 体系注入底层 GPS 与 Network 定位包，
 * 包含速度、航向角、拟真经纬度微抖动、高程与定位精度计算。
 */
class MockLocationEngine(
    private val context: Context,
    private val config: MockConfig,
    private val injectIntoSystem: Boolean = true
) {
    private val tag = "MockLocationEngine"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val routeCalculator = RouteCalculator(config)
    private val singlePointSimulator = SinglePointSimulator(config)
    private val manualMovement = ManualMovementController()
    private var lastPoint = if (config.mode == MockMode.ROUTE_TRACK) config.waypoints.firstOrNull()?.point ?: config.targetPoint else config.targetPoint
    val isManualControl: Boolean get() = manualMovement.active && config.mode == MockMode.ROUTE_TRACK
    val isManuallyMoving: Boolean get() = isManualControl && manualMovement.moving
    private val random = Random()

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private var isRunning = false
    private val registeredProviders = mutableListOf<String>()

    fun resetRoute() { routeCalculator.reset(); manualMovement.reset() }
    fun joystick(x: Double, y: Double) {
        if (config.mode == MockMode.ROUTE_TRACK) manualMovement.update(x, y, lastPoint, SystemClock.elapsedRealtime())
    }
    fun resumeRoute() {
        if (isManualControl) routeCalculator.resumeFrom(lastPoint)
        manualMovement.reset()
    }

    /**
     * 初始化注册系统底层 TestProvider
     */
    @SuppressLint("WrongConstant") // Pre-31 addTestProvider accepts the legacy Criteria constants.
    fun start() {
        if (isRunning) return
        routeCalculator.reset()
        if (!injectIntoSystem) { isRunning = true; return }

        providers.forEach { provider ->
            try {
                // 如果已存在先移除，防止重复冲突
                try {
                    locationManager.removeTestProvider(provider)
                } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    locationManager.addTestProvider(provider, ProviderProperties.Builder()
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build())
                } else locationManager.addTestProvider(
                    provider,
                    false, // requiresNetwork
                    false, // requiresSatellite
                    false, // requiresCell
                    false, // hasMonetaryCost
                    true,  // supportsAltitude
                    true,  // supportsSpeed
                    true,  // supportsBearing
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE
                )
                locationManager.setTestProviderEnabled(provider, true)
                registeredProviders.add(provider)
                Log.d(tag, "TestProvider $provider registered successfully.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to register test provider $provider: ${e.message}")
            }
        }
        check(registeredProviders.isNotEmpty()) { "Root 定位配置未生效，请返回应用重新检测 Root" }
        isRunning = true
    }

    /**
     * 停止模拟并清理已注入的测试提供者
     */
    fun stop() {
        if (!isRunning) return
        registeredProviders.forEach { provider ->
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (e: Exception) {
                Log.e(tag, "Failed to remove test provider $provider: ${e.message}")
            }
        }
        isRunning = false
        registeredProviders.clear()
    }

    /**
     * 定时心跳注入，计算当前应该推送的拟真位置
     */
    fun pushNextTick(deltaTimeMs: Long): Location {
        val (currentPoint, speedMps, bearing) = when (config.mode) {
            MockMode.SINGLE_POINT -> {
                Triple(singlePointSimulator.nextPoint(), 0f, 0f)
            }
            MockMode.ROUTE_TRACK -> {
                if (manualMovement.active) manualMovement.nextPosition(deltaTimeMs, config.speedKmh, SystemClock.elapsedRealtime())
                else routeCalculator.nextPosition(deltaTimeMs)
            }
        }
        lastPoint = currentPoint

        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = currentPoint.latitude
            longitude = currentPoint.longitude
            if (config.mode == MockMode.ROUTE_TRACK || config.enableAltitudeSimulation) {
                altitude = if (config.mode == MockMode.SINGLE_POINT) config.altitudeMeters else currentPoint.altitude
                verticalAccuracyMeters = 2.0f
            }
            speed = speedMps
            this.bearing = bearing
            accuracy = config.accuracy + (random.nextFloat() - 0.5f) * 0.4f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()

            // Android 8.0+ 垂直精度与速度精度
            speedAccuracyMetersPerSecond = 0.2f
            bearingAccuracyDegrees = 1.0f
        }

        if (!injectIntoSystem) return location

        // 推送给所有注册的 Provider
        var pushed = false
        registeredProviders.forEach { provider ->
            try {
                val loc = Location(location).apply { this.provider = provider }
                locationManager.setTestProviderLocation(provider, loc)
                pushed = true
            } catch (e: Exception) {
                Log.e(tag, "Push location to $provider failed: ${e.message}")
            }
        }

        check(pushed) { "Root 定位环境已失效，请返回应用重新检测 Root" }

        return location
    }
}
