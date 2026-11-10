package com.pocketserver.util

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/**
 * 设备形态检测（FR-10.1）。
 *
 * 单 APK 同时上手机与电视，运行时决定用哪套界面层：
 *  - [FormFactor.TV]  → 十尺 UI、D-pad 焦点导航、功能裁剪为浏览/播放/删除
 *  - [FormFactor.PHONE] → 触摸 UI、全功能
 *
 * 判定顺序刻意如此：先看 UiMode（最权威，用户也可在系统里改），
 * 再看 leanback 特性，最后用「无触摸屏 + 大屏」兜底认下国行杂牌盒子。
 */
object DeviceFormFactor {

    enum class FormFactor { PHONE, TV }

    fun detect(context: Context): FormFactor {
        val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return FormFactor.TV
        }
        val pm = context.packageManager
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
            return FormFactor.TV
        }
        val hasTouch = pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        if (!hasTouch) {
            return FormFactor.TV
        }
        return FormFactor.PHONE
    }

    fun isTv(context: Context): Boolean = detect(context) == FormFactor.TV

    /**
     * 电视端是否允许「重命名 / 新建文件夹」。
     *
     * 需规 FR-10.6 明确裁剪：遥控器输入文本的体验会直接毁掉产品口碑，
     * 因此电视端只保留浏览 / 播放 / 删除三项写操作。
     */
    fun supportsTextInput(context: Context): Boolean = detect(context) == FormFactor.PHONE

    /**
     * 桌面端能否扫码。电视没有摄像头（FR-10.4：反向配对）。
     */
    fun supportsScanning(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** Android 版本是否为低内存候选，用于把主缓冲从 256KB 降到 128KB（O-2）。 */
    fun lowRamDevice(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        return am.isLowRamDevice
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** 默认设备名：手机用厂商型号，电视加 TV 后缀便于在附近设备列表里区分。 */
    fun defaultDeviceName(context: Context): String {
        val base = deviceModel().ifBlank { "Android 设备" }
        return if (isTv(context)) "$base TV" else base
    }
}
