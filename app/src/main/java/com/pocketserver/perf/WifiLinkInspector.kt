package com.pocketserver.perf

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.WifiBand

/** 当前链路快照。 */
data class WifiLink(
    val band: WifiBand = WifiBand.UNKNOWN,
    /** 协商速率（Mbps），Wi-Fi 下由驱动上报。 */
    val linkSpeedMbps: Int = 0,
    /** 中心频率（MHz）。 */
    val frequencyMhz: Int = 0,
    val connected: Boolean = false
) {
    /**
     * 换算为**有效**字节吞吐上限。
     *
     * 为什么不直接用协商速率：Wi-Fi 协商速率是包含物理层前导码、ACK、退避、重传的
     * 空中速率，落到 TCP 应用层通常只剩 45%~60%。实测 144Mbps 的 2.4GHz 链接
     * 稳定在 6~10MB/s，正好落在这个系数区间。所以系数取 0.55 而不是 1.0 ——
     * 取 1.0 会让诊断结论永远显示"网络没问题"，把用户骗到错误的方向。
     */
    fun effectiveCeilingMBps(efficiency: Double = DEFAULT_EFFICIENCY): Double {
        if (linkSpeedMbps <= 0) return 0.0
        return linkSpeedMbps / 8.0 * efficiency
    }

    companion object {
        const val DEFAULT_EFFICIENCY = 0.55
    }
}

/**
 * Wi-Fi 链路探测（优化项 O-1：最大单一杠杆）。
 *
 * 实测收益阶梯里，仅"从 2.4GHz 切到 5GHz"这一项就有 ×7，远大于任何代码层面的优化。
 * 所以产品上必须把这件事**主动告诉用户**，而不是等用户来抱怨慢。
 */
class WifiLinkInspector(private val context: Context) {

    fun inspect(): WifiLink {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return WifiLink()
            val network = cm.activeNetwork ?: return WifiLink()
            val caps = cm.getNetworkCapabilities(network) ?: return WifiLink()

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                // 电视盒子走网线：链路不是瓶颈，给一个保守但远高于 Wi-Fi 的估值
                return WifiLink(WifiBand.ETHERNET, 1000, 0, connected = true)
            }
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return WifiLink()
            }
            readWifiInfo()
        } catch (t: Throwable) {
            PocketLog.w(TAG, "链路探测失败: ${t.message}")
            WifiLink()
        }
    }

    /**
     * 读取 WifiInfo。
     *
     * `getConnectionInfo()` 在 API 31+ 标记为废弃，替代方案是
     * `NetworkCapabilities.getTransportInfo()`，但后者在部分厂商 ROM 上直接抛
     * SecurityException（要求定位权限）。SSID/BSSID 才是受定位权限约束的字段，
     * 我们只需要 frequency 与 linkSpeed —— 这两个在没有定位权限时依然可读。
     * 因此这里保留废弃 API 并明确抑制告警，同时把异常兜住。
     */
    @Suppress("DEPRECATION")
    private fun readWifiInfo(): WifiLink {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiLink(connected = true)
        val info = wm.connectionInfo ?: return WifiLink(connected = true)
        val freq = info.frequency
        val speed = if (info.linkSpeed > 0) info.linkSpeed else 0
        val band = when {
            freq in 2400..2500 -> WifiBand.BAND_2_4
            // 5GHz 与 6GHz（Wi-Fi 6E）都属于高速频段，产品上合并提示
            freq in 4900..5925 -> WifiBand.BAND_5
            freq in 5926..7125 -> WifiBand.BAND_5
            else -> WifiBand.UNKNOWN
        }
        return WifiLink(band = band, linkSpeedMbps = speed, frequencyMhz = freq, connected = true)
    }

    /**
     * 生成给用户看的链路建议。返回 null 表示无需提示。
     *
     * 文案要求：不用"频段""协商速率""链路"这类词，直接说结果和动作。
     */
    fun advice(link: WifiLink, storageMode: com.pocketserver.data.model.StorageMode): String? {
        return when {
            link.band == WifiBand.BAND_2_4 ->
                "手机当前连的是 2.4G 网络，传输会比较慢。换成 5G 那个 Wi-Fi 名称，同样文件大约快 7 倍。"

            link.band == WifiBand.BAND_5 && storageMode == com.pocketserver.data.model.StorageMode.SAF ->
                "当前走的是系统文件框架通道，速度会损失约 15%。在设置里授予「所有文件访问权限」可以跑满。"

            link.band == WifiBand.UNKNOWN && link.connected ->
                "无法读取 Wi-Fi 频段信息。如果速度偏慢，先试着连接名称里带 5G 的那个 Wi-Fi。"

            !link.connected -> "当前未连接 Wi-Fi，请先连上家里的路由器。"

            else -> null
        }
    }

    /** 控制台的一行状态文案。 */
    fun bandLabel(link: WifiLink): String = when (link.band) {
        WifiBand.BAND_2_4 -> "2.4GHz"
        WifiBand.BAND_5 -> "5GHz"
        WifiBand.ETHERNET -> "有线网络"
        WifiBand.UNKNOWN -> "未知网络"
    }

    private companion object {
        const val TAG = "WifiLinkInspector"
    }
}
