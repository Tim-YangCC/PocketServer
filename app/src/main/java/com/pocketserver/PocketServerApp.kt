package com.pocketserver

import android.app.Application
import com.pocketserver.core.PocketLog
import com.pocketserver.util.DeviceFormFactor
import dagger.hilt.android.HiltAndroidApp

/**
 * 应用入口。
 *
 * 这里刻意只做两件事：初始化日志、记录一次设备形态判定结果。
 * 不做任何"启动即拉起服务"的动作 —— 是否启动服务必须由用户决定
 * （或者由开机自启设置决定），否则用户装完应用就发现通知栏多了个东西，
 * 第一印象会很差。
 */
@HiltAndroidApp
class PocketServerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val formFactor = DeviceFormFactor.detect(this)
        PocketLog.i(TAG, "启动：形态=$formFactor 型号=${DeviceFormFactor.deviceModel()} " +
            "低内存=${DeviceFormFactor.lowRamDevice(this)}")
    }

    private companion object {
        const val TAG = "App"
    }
}
