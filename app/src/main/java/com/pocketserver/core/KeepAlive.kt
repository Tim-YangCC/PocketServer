package com.pocketserver.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.pocketserver.di.ApplicationScope
import com.pocketserver.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台保活：把"让传输在息屏、切后台、锁屏之后还能继续"这件事集中到一处（FR-13）。
 *
 * ## 为什么需要一个专门的类
 * 保活是**三件事同时成立**才有效的，缺任何一件都会表现为"一息屏就断"：
 *
 *  1. **前台服务**（[com.pocketserver.service.ServerForegroundService]）—— 让进程不被回收
 *  2. **Wi-Fi 高性能锁** —— 让射频不进省电档。少了它速度会从 60MB/s 掉到 5MB/s，
 *     而且亮屏立刻恢复，最容易被误判成"这软件一到后台就限速"
 *  3. **CPU 部分唤醒锁** —— 让 CPU 不在传输期间进 suspend。少了它，**Wi-Fi 锁还在也没用**：
 *     数据到了网卡，但没人从 socket 里读
 *
 * 这三件事原来散在 `ServerController` 与 mDNS 引擎里，各自只管自己那一段，
 * 于是"传输期间要不要额外加锁"这种跨模块的判断就没人负责。集中到这里之后，
 * 判断依据只剩一个：[activeTransfers]。
 *
 * ## 计数为什么必须允许"泄漏"
 * 唤醒锁用 `acquire(timeout)` 而不是裸 `acquire()`。原因是传输的收尾路径很多
 * （正常完成、客户端断开、存储写失败、进程被杀），只要有一条路径漏调
 * [onTransferFinished]，裸 acquire 的锁就**永远不会释放**，用户的手机会一直亮着 CPU 耗电。
 * 带超时的话最坏情况是"提前释放"——传输变慢但不会毁掉电池。
 * 失败必须往安全的方向倒。
 */
@Singleton
class KeepAlive @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    /** 服务级 Wi-Fi 锁（只要服务在跑就持有）。 */
    private var wifiLock: WifiManager.WifiLock? = null

    /** 电视端 7×24 常驻用的常驻唤醒锁。 */
    private var serverWakeLock: PowerManager.WakeLock? = null

    /** 传输期间的 CPU 部分唤醒锁。 */
    private var transferWakeLock: PowerManager.WakeLock? = null

    private var releaseJob: Job? = null

    /** 长时间单文件传输需要在系统超时前续取 CPU 唤醒锁。 */
    private var renewJob: Job? = null

    private val active = AtomicInteger(0)

    /** 当前在跑的传输条数。空闲自停判定与通知栏都用它。 */
    val activeTransfers: Int get() = active.get()

    /** 传输唤醒锁此刻是否真的持有（供诊断展示，不参与逻辑判断）。 */
    val transferLockHeld: Boolean get() = runCatching { transferWakeLock?.isHeld == true }.getOrDefault(false)

    /** Wi-Fi 高性能锁此刻是否真的持有。 */
    val wifiLockHeld: Boolean get() = runCatching { wifiLock?.isHeld == true }.getOrDefault(false)

    // ==================== 服务生命周期 ====================

    /**
     * 服务启动时调用。
     *
     * Wi-Fi 锁**整个服务生命周期都持有**，而不是"只在传输时持有"。
     * 这是本产品的一个刻意取舍：服务端在任何时刻都可能收到连接，
     * 而"收到连接再抢锁"中间有一段窗口，正好是 Wi-Fi 固件已经进省电档的时刻 ——
     * 用户看到的现象是"第一次连接特别慢，第二次就正常"。
     * 常驻持有换来的是稳定的首次响应，代价是一点点待机功耗。
     */
    fun onServerStarted(isTv: Boolean) {
        acquireWifiLock()
        if (isTv) acquireServerWakeLock()
    }

    fun onServerStopped() {
        // 停止服务时把传输计数一起归零：此刻所有连接都已断开，
        // 残留的计数只会在下次启动时把锁莫名其妙地拉起来。
        active.set(0)
        renewJob?.cancel()
        renewJob = null
        releaseJob?.cancel()
        releaseJob = null
        releaseTransferWakeLock()
        releaseServerWakeLock()
        releaseWifiLock()
    }

    // ==================== 传输生命周期 ====================

    /**
     * 一条传输开始（任何通道：网页上传/下载、WebDAV、FTP、设备互传）。
     *
     * 由 [com.pocketserver.data.repo.TransferRecorder] 统一调用 —— 那是五条通道
     * 唯一的共同点，在别处接入必然漏掉一两条。
     */
    @Synchronized
    fun onTransferStarted() {
        val previous = active.getAndIncrement()
        // 取消待执行的释放：上一个传输刚结束就来下一个（传文件夹时的常态），
        // 中间那几十毫秒空档不该真的把锁放掉。
        releaseJob?.cancel()
        releaseJob = null
        acquireTransferWakeLock()
        if (previous == 0) startTransferWakeLockRenewal()
        // 服务重启后 Wi-Fi 锁可能刚被放掉，传输开始时补一次
        acquireWifiLock()
    }

    /**
     * 一条传输结束。计数归零后**延迟**放锁，理由见 [onTransferFinished] 的调用点注释。
     */
    @Synchronized
    fun onTransferFinished() {
        val remaining = active.updateAndGet { if (it > 0) it - 1 else 0 }
        if (remaining > 0) return
        renewJob?.cancel()
        renewJob = null
        releaseJob?.cancel()
        releaseJob = scope.launch(io) {
            delay(AppConstants.TRANSFER_LOCK_LINGER_MS)
            // 再确认一次：linger 期间可能又来了新传输
            if (active.get() == 0) releaseTransferWakeLock()
        }
    }

    // ==================== 锁的获取与释放 ====================

    private fun acquireWifiLock() {
        if (wifiLockHeld) return
        runCatching {
            // 先判空再调用，不要写成 `wm?.createWifiLock(...).apply {}`：
            // `?.` 只作用于 createWifiLock，随后的 .apply 落在可空类型上，
            // 而 acquire() 恰恰是必须执行的那一步，静默跳过等于没加锁。
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching
            @Suppress("DEPRECATION")
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketserver:wifi")
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        }.onFailure { PocketLog.w(TAG, "Wi-Fi 高性能锁获取失败：${it.message}") }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun acquireServerWakeLock() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketserver:tv")
            lock.setReferenceCounted(false)
            lock.acquire()
            serverWakeLock = lock
        }.onFailure { PocketLog.w(TAG, "常驻唤醒锁获取失败：${it.message}") }
    }

    private fun releaseServerWakeLock() {
        runCatching { serverWakeLock?.takeIf { it.isHeld }?.release() }
        serverWakeLock = null
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireTransferWakeLock() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching
            val lock = transferWakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "pocketserver:transfer"
            ).apply {
                setReferenceCounted(false)
                transferWakeLock = this
            }
            // 带超时的 acquire。非引用计数模式下重复 acquire(timeout) 会刷新到期时间，
            // 于是"持续传输"会自动续期，"计数泄漏"最多耗电 30 分钟。见类注释。
            lock.acquire(AppConstants.WAKELOCK_MAX_HOLD_MS)
        }.onFailure { PocketLog.w(TAG, "传输唤醒锁获取失败：${it.message}") }
    }

    /**
     * WakeLock 的带时限 acquire 到期后会由系统释放；只在传输开始时获取一次，
     * 无法保护超过 WAKELOCK_MAX_HOLD_MS 的单个文件。定期重新 acquire，
     * 在最长持有时限的三分之一处续期，并在传输结束或服务停止时取消。
     */
    private fun startTransferWakeLockRenewal() {
        renewJob?.cancel()
        renewJob = scope.launch(io) {
            while (active.get() > 0) {
                delay(WAKELOCK_RENEW_INTERVAL_MS)
                if (active.get() > 0) acquireTransferWakeLock()
            }
        }
    }

    private fun releaseTransferWakeLock() {
        runCatching { transferWakeLock?.takeIf { it.isHeld }?.release() }
    }

    // ==================== 电池优化白名单（FR-13.4） ====================

    /**
     * 是否已被系统排除在电池优化之外。
     *
     * 这一项比前面三个锁更关键：**Doze 模式下系统会直接掐断应用的网络访问**，
     * 而 Doze 的进入条件是"息屏 + 静止不动 + 未充电"，正好是"手机放桌上当服务器"的场景。
     * 前台服务能延缓 Doze，但不能豁免它。
     */
    fun isIgnoringBatteryOptimizations(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return@runCatching false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /**
     * 申请加入白名单的跳转 Intent。
     *
     * 优先用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（直接弹系统确认框，一步到位），
     * 拿不到才退到"打开电池优化设置列表"（用户要自己找本应用）。
     * 必须提前 `resolveActivity` 探一下：部分定制 ROM 阉割了前者，
     * 直接 startActivity 会崩在用户面前。
     */
    fun batteryOptimizationIntent(): Intent? {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (request.resolveActivity(context.packageManager) != null) return request

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (list.resolveActivity(context.packageManager) != null) list else null
    }

    // ==================== 国产 ROM 的后台限制引导（FR-13.5） ====================

    /**
     * 返回本机型可能需要的额外后台设置提示；不需要时返回 null。
     *
     * 为什么必须单独提示：这几家的"省电策略"会**在电池优化白名单之外**再拦一层，
     * 用户按系统的做法加白名单后依然会被杀，然后来抱怨"这软件后台就断"。
     * 与其让用户自己上网搜，不如直接把路径写出来。
     */
    fun oemBackgroundHint(): String? {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                "小米 / 红米：设置 → 应用设置 → 应用管理 → 口袋服务器 → 省电策略改为「无限制」，" +
                    "并打开「自启动」。锁屏后还要在最近任务里给本应用加锁。"

            brand.contains("huawei") || brand.contains("honor") ->
                "华为 / 荣耀：设置 → 应用 → 应用启动管理 → 口袋服务器改为「手动管理」，" +
                    "并勾选「允许自启动」「允许关联启动」「允许后台活动」三项。"

            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ->
                "OPPO / 一加 / realme：设置 → 电池 → 应用耗电管理 → 口袋服务器，" +
                    "打开「允许完全后台行为」与「允许自启动」。"

            brand.contains("vivo") || brand.contains("iqoo") ->
                "vivo / iQOO：设置 → 电池 → 后台耗电管理 → 口袋服务器改为「允许后台高耗电」，" +
                    "并在 i 管家里把本应用加入「自启动」白名单。"

            brand.contains("meizu") ->
                "魅族：设置 → 电量管理 → 耗电详情 → 口袋服务器，允许后台运行并加入「自启动」白名单。"

            brand.contains("samsung") ->
                "三星：设置 → 电池 → 后台使用限制，把口袋服务器加入「不进入休眠的应用」列表。"

            else -> null
        }
    }

    /** 一句话状态描述，直接显示在设置页。 */
    fun statusText(): String =
        if (isIgnoringBatteryOptimizations()) {
            "已加入电池优化白名单：息屏和切后台后可以继续传输。"
        } else {
            "未加入白名单：手机息屏静止一段时间后会进入深度省电，系统会掐断网络，传输会中断。"
        }

    private companion object {
        const val TAG = "KeepAlive"
        const val WAKELOCK_RENEW_INTERVAL_MS = AppConstants.WAKELOCK_MAX_HOLD_MS / 3
    }
}
