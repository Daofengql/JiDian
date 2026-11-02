package com.root.fakeloc.core.cell

import android.content.Context
import android.telephony.CellInfo
import android.util.Log
import com.root.fakeloc.core.location.GeoPoint
import java.util.Random

/**
 * 模拟基站核心引擎
 * 根据当前虚拟定位 GPS 坐标，动态计算附近虚拟蜂窝基站拓扑分布，
 * 提供主服务小区与邻区(Neighboring Cells)完整参数。
 */
class MockCellEngine(private val context: Context) {
    private val tag = "MockCellEngine"
    private val random = Random()

    private var activeCell = MockCellStation()
    private val neighborCells = mutableListOf<MockCellStation>()
    private var isEnabled = false

    fun start() {
        isEnabled = true
        Log.d(tag, "Cell mock engine started.")
    }

    fun stop() {
        isEnabled = false
        Log.d(tag, "Cell mock engine stopped.")
    }

    /**
     * 依据当前经纬度坐标，动态计算并更新主服务小区与邻区信号
     */
    fun updateWithLocation(location: GeoPoint) {
        if (!isEnabled) return

        // 简化的经纬度哈希生成相对稳定的固定基站ID
        val latHash = (location.latitude * 1000).toInt()
        val lonHash = (location.longitude * 1000).toInt()
        val baseCi = (latHash shl 12) xor lonHash and 0x0FFFFFFF

        // 主服务小区 (Serving Cell)
        activeCell = MockCellStation(
            mcc = 460,
            mnc = 0, // 移动
            tac = (latHash and 0xFFFF),
            ci = baseCi,
            pci = (lonHash % 504).coerceAtLeast(0),
            rsrp = -78 + (random.nextInt(10) - 5), // 信号小幅波动
            rssi = -55 + (random.nextInt(6) - 3)
        )

        // 生成 2~3 个相邻小区 (Neighbor Cells) 保证完整度
        neighborCells.clear()
        for (i in 1..3) {
            neighborCells.add(
                MockCellStation(
                    mcc = 460,
                    mnc = 0,
                    tac = activeCell.tac,
                    ci = (activeCell.ci + i * 256) and 0x0FFFFFFF,
                    pci = (activeCell.pci + i * 37) % 504,
                    rsrp = activeCell.rsrp - 10 - i * 5, // 邻区信号逐步递减
                    rssi = activeCell.rssi - 15 - i * 5
                )
            )
        }
    }

    fun getActiveCell(): MockCellStation = activeCell
    fun getNeighborCells(): List<MockCellStation> = neighborCells
}
