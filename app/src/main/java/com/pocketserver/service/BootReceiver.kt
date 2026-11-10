package com.pocketserver.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketserver.core.PocketLog
import com.pocketserver.data.prefs.SettingsStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 开机自启（FR-1.9 / FR-10.10）。
 *
 * ## 电视端的关键场景
 * 电视被定位成 7×24 常驻的服务端。如果每次断电重启都要人去用遥控器点一遍应用，
 * 这个产品在电视上就是不可用的 —— 用户不会为了传一个文件专门去开机顶盒的界面。
 * 所以电视端默认开启自启，手机端默认关闭（省电，且用户开机后就在用手机）。
 *
 * ## 为什么不用 setEnabled 动态注册
 * 静态注册的 BOOT_COMPLETED 在 Android 8+ 可用，但必须注意：
 *  1. 必须等用户至少启动过一次应用（否则应用处于 stopped 状态，收不到广播）
 *  2. 开机后立刻启动前台服务在部分 ROM 上会被拦，所以这里只发 startService，
 *     若被拒绝会在下一次打开应用时由 MainActivity 补启动
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var settingsStore: SettingsStore

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val settings = settingsStore.current()
                if (!settings.autoStartOnBoot) {
                    PocketLog.i(TAG, "$action 收到，但未开启自启，跳过")
                    return@launch
                }
                PocketLog.i(TAG, "$action 收到，正在自启服务")
                ServerForegroundService.start(context.applicationContext)
            } catch (t: Throwable) {
                PocketLog.w(TAG, "自启失败：${t.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
