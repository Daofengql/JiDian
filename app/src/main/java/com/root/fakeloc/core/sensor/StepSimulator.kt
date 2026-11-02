package com.root.fakeloc.core.sensor

import com.root.fakeloc.core.location.CadenceMode
import com.root.fakeloc.core.location.MockConfig
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * 步频 (Cadence) 与运动学传感器高精度模拟引擎
 *
 * 核心功能：
 * 1. 步频 (SPM: Steps Per Minute) 智能计算与手动锁定；
 * 2. 拟真人体步频微浮动 (Jitter)，防止特征分析检测出机械固定值；
 * 3. 配速 (Pace) 与步长 (Stride Length) 拟真生物力学换算；
 * 4. 踏步脉冲 (StepDetector) 与累加步数 (StepCounter) 驱动；
 * 5. 频谱匹配的三轴加速度计正弦波形生成。
 */
class StepSimulator(private val config: MockConfig) {

    private val random = Random()

    private var cumulativeSteps: Long = 0L
    private var stepTimeAccumulatorMs: Double = 0.0
    private var phaseAngle: Double = 0.0

    // 实时状态输出
    var currentSpm: Float = 0f
        private set
    var currentPaceFormatted: String = "--'--\""
        private set
    var currentStrideLengthMeters: Float = 0.72f
        private set

    companion object {
        private const val GRAVITY = 9.80665f
    }

    /**
     * 更新运动状态并计算步频、步长与步数增量
     * @param speedMps 当前瞬时速度 (米/秒)
     * @param deltaTimeMs 经历的时间跨度 (毫秒)
     * @return Pair<是否产生踏步脉冲, 当前累计总步数>
     */
    fun tick(speedMps: Float, deltaTimeMs: Long): Pair<Boolean, Long> {
        val speedKmh = speedMps * 3.6f

        // 1. 静止或极低速判断 (< 0.8 km/h)
        if (speedKmh < 0.8f) {
            currentSpm = 0f
            currentPaceFormatted = "--'--\""
            currentStrideLengthMeters = 0f
            stepTimeAccumulatorMs = 0.0
            return Pair(false, cumulativeSteps)
        }

        // 2. 计算实时配速 (Pace)
        val paceTotalSec = (3600.0 / speedKmh).toInt()
        val paceMin = paceTotalSec / 60
        val paceSec = paceTotalSec % 60
        currentPaceFormatted = "${paceMin}'${paceSec.toString().padStart(2, '0')}\""

        // 3. 计算基准步频 (SPM)
        var baseSpm = when (config.cadenceMode) {
            CadenceMode.AUTO_ADAPTIVE -> calculateAdaptiveSpm(speedKmh)
            CadenceMode.CUSTOM_FIXED -> config.targetCadenceSpm.toFloat()
        }

        // 4. 拟真人体微抖动 (避免被大数据方差算法判定为规律机械脚本)
        if (config.enableCadenceJitter) {
            // 在 ±cadenceJitterRatio 范围内产生平滑的高斯波动
            val jitter = 1.0f + (random.nextFloat() - 0.5f) * 2.0f * config.cadenceJitterRatio
            baseSpm *= jitter
        }
        currentSpm = baseSpm.coerceIn(40f, 240f)

        // 5. 动态计算步长 = 速度(m/s) / 每秒步数
        val stepsPerSecond = currentSpm / 60.0f
        currentStrideLengthMeters = if (stepsPerSecond > 0) speedMps / stepsPerSecond else 0.72f

        // 6. 踏步计时器推进
        val msPerStep = 60000.0 / currentSpm
        stepTimeAccumulatorMs += deltaTimeMs.coerceAtLeast(0L)
        var stepTriggered = false

        if (stepTimeAccumulatorMs >= msPerStep) {
            val stepsElapsed = (stepTimeAccumulatorMs / msPerStep).toLong()
            stepTimeAccumulatorMs %= msPerStep
            cumulativeSteps += stepsElapsed
            stepTriggered = true
        }

        return Pair(stepTriggered, cumulativeSteps)
    }

    /**
     * 智能自适应步频计算模型：
     * 根据真实跑步运动生理学大数据构建速度与步频的非线性映射
     */
    private fun calculateAdaptiveSpm(speedKmh: Float): Float {
        return when {
            // 散步 (1.0 ~ 4.0 km/h): 步频 85 ~ 110
            speedKmh <= 4.0f -> 85f + (speedKmh - 1.0f) * 8.33f
            // 快走 (4.0 ~ 6.5 km/h): 步频 110 ~ 135
            speedKmh <= 6.5f -> 110f + (speedKmh - 4.0f) * 10.0f
            // 慢跑/热身 (6.5 ~ 9.0 km/h): 步频 135 ~ 162
            speedKmh <= 9.0f -> 135f + (speedKmh - 6.5f) * 10.8f
            // 匀速有氧跑 (9.0 ~ 12.0 km/h): 步频 162 ~ 178 (接近专业跑者黄金步频)
            speedKmh <= 12.0f -> 162f + (speedKmh - 9.0f) * 5.33f
            // 快速跑 (12.0 ~ 16.0 km/h): 步频 178 ~ 195
            speedKmh <= 16.0f -> 178f + (speedKmh - 12.0f) * 4.25f
            // 冲刺阶段 (> 16.0 km/h): 步频 195 ~ 215
            else -> (195f + (speedKmh - 16.0f) * 2.5f).coerceAtMost(225f)
        }
    }

    /**
     * 生成与步频严格同相位的加速度计波形 (含足弓着地二次谐波冲击)
     */
    fun generateAccelerometer(deltaTimeMs: Long): FloatArray {
        if (currentSpm <= 0f) {
            // 静止状态微噪声
            val nx = (random.nextFloat() - 0.5f) * 0.04f
            val ny = (random.nextFloat() - 0.5f) * 0.04f
            val nz = GRAVITY + (random.nextFloat() - 0.5f) * 0.06f
            return floatArrayOf(nx, ny, nz)
        }

        // 步频角频率
        val freq = currentSpm / 60.0
        phaseAngle += 2.0 * Math.PI * freq * (deltaTimeMs / 1000.0)
        if (phaseAngle > 2.0 * Math.PI) {
            phaseAngle %= 2.0 * Math.PI
        }

        // 颠簸振幅随步频成正比 (振动幅度 1.2 ~ 4.2 m/s^2)
        val amplitude = (currentSpm / 50.0f).coerceIn(1.0f, 4.5f)

        // 左右晃动 (X轴，踏步的一半频率)
        val x = (sin(phaseAngle / 2.0) * 0.4f).toFloat()
        // 前后晃动 (Y轴，倾斜角)
        val y = (cos(phaseAngle) * amplitude * 0.35f).toFloat()
        // 垂直冲击波 (Z轴，基波 + 脚掌着地二次谐波)
        val z = (GRAVITY + sin(phaseAngle) * amplitude + sin(2 * phaseAngle) * (amplitude * 0.25f)).toFloat()

        return floatArrayOf(x, y, z)
    }

    fun getCumulativeSteps(): Long = cumulativeSteps

    fun setCumulativeSteps(steps: Long) {
        this.cumulativeSteps = steps
    }
}
