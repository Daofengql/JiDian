package com.root.fakeloc.core.sensor

import android.content.Context
import android.util.Log
import com.root.fakeloc.core.location.MockConfig

/**
 * 模拟传感器核心调度器
 * 统一管理步频、配速、步长与加速度波形状态
 */
class MockSensorEngine(
    private val context: Context,
    private val config: MockConfig
) {
    private val tag = "MockSensorEngine"
    private val stepSimulator = StepSimulator(config)
    private var isEnabled = false

    data class SensorSnapshot(
        val stepTriggered: Boolean,
        val totalSteps: Long,
        val spm: Float,
        val pace: String,
        val strideLength: Float,
        val accelerometer: FloatArray
    )

    fun start() {
        isEnabled = true
        Log.d(tag, "Sensor mock engine started.")
    }

    fun stop() {
        isEnabled = false
        Log.d(tag, "Sensor mock engine stopped.")
    }

    /**
     * 周期性采样并同步步频与传感器数据
     */
    fun tick(speedMps: Float, deltaTimeMs: Long): SensorSnapshot {
        val (triggered, totalSteps) = stepSimulator.tick(speedMps, deltaTimeMs)
        val accel = stepSimulator.generateAccelerometer(deltaTimeMs)

        return SensorSnapshot(
            stepTriggered = triggered,
            totalSteps = totalSteps,
            spm = stepSimulator.currentSpm,
            pace = stepSimulator.currentPaceFormatted,
            strideLength = stepSimulator.currentStrideLengthMeters,
            accelerometer = accel
        )
    }

    fun setInitialSteps(steps: Long) {
        stepSimulator.setCumulativeSteps(steps)
    }
}
