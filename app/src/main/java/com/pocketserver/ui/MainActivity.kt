package com.pocketserver.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.pocketserver.R
import com.pocketserver.core.PocketLog
import com.pocketserver.data.prefs.SettingsStore
import com.pocketserver.data.storage.StorageAccessManager
import com.pocketserver.service.ServerController
import com.pocketserver.ui.phone.PhoneApp
import com.pocketserver.ui.theme.PocketServerTheme
import com.pocketserver.ui.tv.TvApp
import com.pocketserver.util.DeviceFormFactor
import com.pocketserver.util.ShareImporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 唯一 Activity。
 *
 * ## 它承担三个入口的汇聚
 *  1. **启动器入口**：桌面图标（手机）与 Leanback 启动器（电视）
 *  2. **系统「分享到」入口**：相册/文件管理器里选中文件 → 分享 → 口袋服务器（FR-11.6）
 *  3. **深链入口**：扫描二维码后由浏览器/相机跳转（FR-2.7）
 *
 * ## 单 APK 双形态
 * 手机与电视共用这一个 Activity、共用同一套业务代码，只在 `setContent` 处
 * 按 [DeviceFormFactor] 分派到 PhoneApp / TvApp。这样做的代价是两个界面树都要维护，
 * 但换来的是**发布一个 APK 就能覆盖两类设备**，对用户和运维都更省事（FR-10.1）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var storageAccess: StorageAccessManager

    @Inject
    lateinit var controller: ServerController

    /** 分享进来、等待用户选目标设备的文件（相对共享根的路径）。 */
    private val pendingShare = mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = DeviceFormFactor.isTv(this)
        // Activity 主题必须在 setContent 之前切换，否则电视上会闪一下浅色背景
        setTheme(if (isTv) R.style.Theme_PocketServer_Tv else R.style.Theme_PocketServer)

        consumeIntent(intent)

        setContent {
            PocketServerTheme(isTv = isTv) {
                if (isTv) {
                    TvApp()
                } else {
                    PhoneApp(
                        pendingShare = pendingShare.value,
                        onShareHandled = { pendingShare.value = emptyList() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /**
     * 从系统设置返回时检查共享根。
     *
     * 用户被引导去授予「所有文件访问权限」，授权动作发生在**本应用之外**，
     * 应用内收不到任何回调。以前的做法是"必须手动把服务关掉再开一次"，
     * 但没有人会想到要这么做 —— 用户只会得出"授权了还是看不到文件"的结论。
     * 这里在每次回到前台时比对一次存储根指纹，变了就重建协议栈。
     *
     * 用 lifecycleScope 而不是阻塞 onResume：重建要走一次网络与存储初始化。
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { controller.refreshStorageIfChanged() }
                .onFailure { PocketLog.w(TAG, "检查存储根变化失败：${it.message}") }
        }
    }

    /**
     * 处理分享与深链。
     *
     * 分享内容必须**先复制进共享根**再推送 —— 对端拿不到我们的 `content://` URI。
     * 复制是 IO 操作，因此放到 lifecycleScope + IO 线程，避免阻塞首帧。
     */
    private fun consumeIntent(intent: Intent?) {
        val action = intent?.action ?: return
        when (action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                importShared(uri?.let { listOf(it) } ?: emptyList())
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                importShared(uris ?: emptyList())
            }

            Intent.ACTION_VIEW -> {
                // 二维码扫描结果：pocketserver://pair?... 由扫码页处理，这里只记日志
                PocketLog.i(TAG, "深链进入：${intent.data}")
            }
        }
    }

    private fun importShared(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                val settings = settingsStore.current()
                val storage = storageAccess.build(settings)
                ShareImporter.import(contentResolver, storage, uris)
            }
            pendingShare.value = imported
            PocketLog.i(TAG, "分享导入 ${imported.size} 项，等待用户选择目标设备")
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
