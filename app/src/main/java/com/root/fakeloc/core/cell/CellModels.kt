package com.root.fakeloc.core.cell

import java.io.Serializable

/**
 * 虚拟基站参数模型 (以国内 4G LTE / 5G NR 最通用规范定义)
 */
data class MockCellStation(
    val mcc: Int = 460,        // 国家码 (中国: 460)
    val mnc: Int = 0,          // 运营商代码 (0:中国移动, 1:中国联通, 11:中国电信)
    val tac: Int = 28654,      // 跟踪区编码 (Tracking Area Code)
    val ci: Int = 12548976,    // 小区基站标识 (Cell Identity)
    val pci: Int = 138,        // 物理小区标识 (Physical Cell ID: 0-503)
    val earfcn: Int = 1825,    // 绝对射频频道号 (LTE 频段)
    val rsrp: Int = -85,       // 参考信号接收功率 (dBm, -140 ~ -44)
    val rsrq: Int = -9,        // 参考信号接收质量 (dB)
    val rssi: Int = -60        // 接收信号强度指示 (dBm)
) : Serializable {

    val operatorName: String
        get() = when (mnc) {
            0, 2, 7 -> "中国移动 (China Mobile)"
            1, 6 -> "中国联通 (China Unicom)"
            11 -> "中国电信 (China Telecom)"
            else -> "虚拟运营商"
        }
}
