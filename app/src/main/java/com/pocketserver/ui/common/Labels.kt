package com.pocketserver.ui.common

import com.pocketserver.data.model.Bottleneck
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.data.model.StorageMode
import com.pocketserver.data.model.WifiBand
import com.pocketserver.ui.components.BannerTone

/**
 * 枚举 → 中文文案的唯一映射点。
 *
 * ## 为什么必须集中在一个文件里
 * 这个应用有两套界面层（手机 / 电视）跑同一套业务数据。如果各处自己写 `when`，
 * 就会出现"手机上写『Wi-Fi 链路是瓶颈』、电视上写『无线受限』"的情况 ——
 * 用户会以为看到的是两个不同的结论，从而怀疑诊断功能本身不可信。
 *
 * 顺带把"该用哪种颜色"也一并定下来：颜色语义也必须跨端一致，
 * 否则同一个"正常"状态在两端一个绿一个蓝，同样会造成误解。
 */
object Labels {

    fun wifiBand(band: WifiBand): String = when (band) {
        WifiBand.BAND_2_4 -> "2.4GHz"
        WifiBand.BAND_5 -> "5GHz"
        WifiBand.ETHERNET -> "有线网络"
        WifiBand.UNKNOWN -> "未识别"
    }

    /** 频段是否值得提示用户切换。有线与 5G 不提示（已经是好状态，提示只是噪音）。 */
    fun bandNeedsUpgrade(band: WifiBand): Boolean = band == WifiBand.BAND_2_4

    fun storageMode(mode: StorageMode): String = when (mode) {
        StorageMode.DIRECT -> "直连通道（已开启零拷贝优化）"
        StorageMode.SAF -> "系统文件框架（速度下降约 15%）"
    }

    fun deviceType(type: DeviceType): String = when (type) {
        DeviceType.PHONE -> "手机"
        DeviceType.TV -> "电视"
        DeviceType.PC -> "电脑"
        DeviceType.WEB -> "浏览器"
        DeviceType.UNKNOWN -> "设备"
    }

    fun permission(p: SharedPermission): String = when (p) {
        SharedPermission.READ_WRITE -> "可读写"
        SharedPermission.READ_ONLY -> "只读"
    }

    fun bottleneck(b: Bottleneck): String = when (b) {
        Bottleneck.NONE -> "没有明显瓶颈"
        Bottleneck.WIRELESS_LINK -> "Wi-Fi 无线链路"
        Bottleneck.DISK_IO -> "存储读写"
        Bottleneck.APP_LAYER -> "应用自身通路"
        Bottleneck.UNKNOWN -> "尚未判定"
    }

    fun bottleneckTone(b: Bottleneck): BannerTone = when (b) {
        Bottleneck.NONE -> BannerTone.Success
        // 无线与磁盘属于"可优化但不是故障"，用提醒色；应用层是自己的问题，用错误色
        Bottleneck.WIRELESS_LINK -> BannerTone.Warn
        Bottleneck.DISK_IO -> BannerTone.Warn
        Bottleneck.APP_LAYER -> BannerTone.Error
        Bottleneck.UNKNOWN -> BannerTone.Info
    }
}
