package com.pocketserver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketserver.R
import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.model.ServerState
import com.pocketserver.data.repo.TransferRepository
import com.pocketserver.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 服务端常驻前台服务（FR-1）。
 *
 * ## 为什么必须是前台服务
 * Android 8 之后，后台进程的网络与 CPU 会被快速限制；息屏几分钟后 socket 会被切断。
 * 用户要的是"出门前打开、回家电脑就能连上"，那就必须挂着一条常驻通知。
 *
 * ## 两条通知，各管一件事（FR-13.2）
 *  - **常驻通知**（[NOTIFY_ID]）：服务在不在、地址是什么、连了几台
 *  - **传输通知**（[NOTIFY_ID_TRANSFER]）：正在传什么、到多少了；**没有传输时主动撤掉**
 *
 * 分成两条而不是合并成一条，是因为它们的生命周期完全不同：常驻通知要一直在，
 * 传输通知必须随之出现、随之中止消失。合并的话，"传输结束"就只能靠改文案——
 * 而一条写着"正在传输"的通知如果没被撤掉，用户会一直等一个早就结束的进度。
 *
 * ## 通知刷新的节流（O-8 反模式清单里明确列了"不要频繁刷通知栏"）
 * 通知栏更新是跨进程调用，每秒更新几次会实打实吃掉吞吐。
 * 所以两条通知都**只订阅"会被显示出来的那几个字段"**再 `distinctUntilChanged`：
 *  - 常驻通知：地址、连接数、传输条数 —— 实时速率根本不进来
 *  - 传输通知：总进度按 **5% 分档**（`bucket`），一个任务全程最多 20 次更新
 *
 * 分档而不是按秒节流，是因为"进度从 37.4% 变成 37.6%"对用户毫无意义，
 * 却会白白触发一次跨进程调用。按档位变化则天然把更新次数压到个位数。
 *
 * ## 电视端差异（FR-10.11）
 *  - 没有通知栏概念，通知只是"让系统不杀进程"的凭证，UI 上不展示
 *  - 前台服务类型包含 connectedDevice，保证系统不把它当临时后台任务回收
 */
@AndroidEntryPoint
class ServerForegroundService : Service() {

    @Inject
    lateinit var controller: ServerController

    /**
     * 直接注入传输仓库，而不是绕 [ServerController] 要一份进度快照。
     *
     * 进度是**每 200ms 就可能变一次**的高频数据（PROGRESS_THROTTLE_MS）。把它塞进
     * `ServerState` 会把那个"每秒更新一次的全局状态"变成高频源，牵连所有订阅者
     * （控制台、二维码页、通知栏全都跟着被唤醒）。通知栏要的只是"哪个文件、到多少了"，
     * 让需要它的人自己订阅那张表更干净，也不会污染别的界面。
     */
    @Inject
    lateinit var transferRepo: TransferRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    controller.stop()
                    stopForegroundCompat()
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startAsForeground(controller.state.value)
                scope.launch {
                    val outcome = controller.restart()
                    if (!outcome.ok) {
                        PocketLog.w(TAG, "服务重启失败：${outcome.error}")
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        observeState()
                    }
                }
                return START_STICKY
            }

            ACTION_ROTATE_CODE -> {
                controller.rotatePairingCode()
                return START_STICKY
            }

            else -> {
                startAsForeground(controller.state.value)
                scope.launch {
                    val outcome = controller.start()
                    if (!outcome.ok) {
                        // 启动失败就把服务退掉，避免留一条"服务运行中"的假通知骗用户
                        PocketLog.w(TAG, "服务启动失败：${outcome.error}")
                        stopForegroundCompat()
                        stopSelf()
                    } else {
                        observeState()
                    }
                }
                return START_STICKY
            }
        }
    }

    /**
     * 订阅两条通知源，各自独立。
     *
     * `distinctUntilChanged` 放在映射之后：先把状态压成"通知里真正会显示的字符串"，
     * 再比较。这样速率变化（每秒都变）根本不会触发通知更新 —— 这才是节流的正确位置，
     * 而不是在更新前加个 sleep。
     */
    private fun observeState() {
        observeJob?.cancel()
        observeJob = scope.launch {
            // ① 常驻通知：服务在不在、地址是什么、连了几台、有几个传输在跑
            launch {
                controller.state
                    .map { it.notificationSignature() }
                    .distinctUntilChanged()
                    .collect { signature ->
                        runCatching {
                            notificationManager().notify(NOTIFY_ID, buildNotification(signature))
                        }
                    }
            }
            // ② 传输进度通知（FR-13.2）
            launch {
                transferRepo.observeActive()
                    .map { it.transferNotice() }
                    .distinctUntilChanged()
                    .collect { notice ->
                        runCatching {
                            if (notice == null) {
                                // 没有传输就把这条通知撤掉。留着一条写着"正在传输"的通知
                                // 比没有通知更糟 —— 用户会一直等一个早就结束的进度。
                                notificationManager().cancel(NOTIFY_ID_TRANSFER)
                            } else {
                                notificationManager().notify(
                                    NOTIFY_ID_TRANSFER,
                                    buildTransferNotification(notice)
                                )
                            }
                        }
                    }
            }
        }
    }

    /** 传输通知要显示的两个字段。做成数据类是为了让 `distinctUntilChanged` 有个可比的结构。 */
    private data class TransferNotice(val count: Int, val body: String)

    /**
     * 生成传输通知的文案；没有传输时返回 null（表示"该把这条通知撤掉"）。
     *
     * ## 为什么总进度要按 5% 分档
     * 进度每 200ms 就会变一次（`PROGRESS_THROTTLE_MS`），照原样发出去就是每秒五次跨进程调用 ——
     * O-8 明确把"频繁刷通知栏"列为会实打实拖慢吞吐的反模式。而"37.4% 变成 37.6%"
     * 对用户毫无意义，却要付一次跨进程调用的代价，所以按 5% 归档：
     * 一个任务全程最多 20 次更新，且更新次数与实际传了多久**无关**。
     *
     * 这比按时间节流（`AppConstants.NOTIFICATION_THROTTLE_MS`）更省：
     * 时间节流是"每 500ms 看一眼有没有变"，分档是"只在真的跨档时才发"。
     *
     * 同理，**速度不进通知文案**：它每秒都在变，是天生的高频源。
     * 速度留给应用内控制台 —— 用户不会盯着通知栏看 MB/s。
     *
     * ## 为什么按"所有任务的总进度"算，而不是挑最大的那个任务单独显示
     * 挑单个任务会让文案在多任务之间来回切换，签名反复变化，反而触发更多次更新。
     * 求和是单调递增的，配上 `distinctUntilChanged` 就只在跨档时更新一次。
     */
    private fun List<TransferTaskEntity>.transferNotice(): TransferNotice? {
        if (isEmpty()) return null
        val done = sumOf { it.transferredBytes }
        val total = sumOf { it.totalBytes }
        val head = maxByOrNull { it.totalBytes } ?: return null

        val body = buildString {
            append("「").append(head.name).append("」")
            if (total > 0) {
                // 先乘后除：先除会把小文件的精度直接丢成 0
                val percent = (done * 100 / total).coerceIn(0L, 100L).toInt()
                append("已传输 ").append(percent / 5 * 5).append("%")
            } else {
                // 总大小未知（少数第三方客户端不带 Content-Length）。此时不编一个百分比，
                // 如实说"传输中"—— 显示一个永远不动的 0% 更像卡死。
                append("传输中")
            }
            append("　·　息屏后继续")
        }
        return TransferNotice(size, body)
    }

    /**
     * 传输进度通知。
     *
     * 请求码用 2（常驻通知用 0、"停止"动作用 1）：PendingIntent 的身份由
     * (requestCode, intent, flags) 三者共同决定，共用请求码会让两条通知的点击目标互相覆盖。
     */
    private fun buildTransferNotification(notice: TransferNotice): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent().setClassName(packageName, "com.pocketserver.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags()
        )
        return NotificationCompat.Builder(this, AppConstants.CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.notify_transfer_title, "${notice.count} 个任务"))
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun startAsForeground(state: ServerState) {
        val notification = buildNotification(state.notificationSignature())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFY_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // ==================== 通知 ====================

    private fun buildNotification(signature: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "com.pocketserver.ui.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            pendingIntentFlags()
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerForegroundService::class.java).setAction(ACTION_STOP),
            pendingIntentFlags()
        )

        return NotificationCompat.Builder(this, AppConstants.CHANNEL_SERVER)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(getString(R.string.notify_server_title))
            .setContentText(signature)
            .setStyle(NotificationCompat.BigTextStyle().bigText(signature))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notify_action_stop), stopIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun pendingIntentFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun ServerState.notificationSignature(): String = buildString {
        if (mdnsHost.isNotBlank()) {
            append("http://")
            append(mdnsHost)
            append(':')
            append(httpPort)
        } else {
            append(addresses.firstOrNull()?.let { "http://$it:$httpPort" } ?: "正在获取地址…")
        }
        append("　·　连接 ")
        append(clientCount)
        append(" 条")
        if (activeTransfers > 0) {
            append("　·　传输中 ")
            append(activeTransfers)
        }
    }

    private fun createChannels() {
        // Notification channels were introduced in Android 8.0 (API 26).
        // This service also supports API 24/25, where channel APIs do not exist.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = notificationManager()
        val server = NotificationChannel(
            AppConstants.CHANNEL_SERVER,
            getString(R.string.notify_channel_server),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notify_channel_server_desc)
            setShowBadge(false)
        }
        val transfer = NotificationChannel(
            AppConstants.CHANNEL_TRANSFER,
            getString(R.string.notify_channel_transfer),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notify_channel_transfer_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(server)
        manager.createNotificationChannel(transfer)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ServerService"

        /** 服务常驻通知。 */
        private const val NOTIFY_ID = 1024

        /**
         * 传输进度通知。
         *
         * 必须与 [NOTIFY_ID] 不同：两个通知用同一个 ID 会互相顶掉，
         * 表现是"服务通知在传输开始时突然消失"（或反过来），偶发且极难自查。
         */
        private const val NOTIFY_ID_TRANSFER = 1025

        const val ACTION_STOP = "com.pocketserver.action.STOP"
        const val ACTION_RESTART = "com.pocketserver.action.RESTART"
        const val ACTION_ROTATE_CODE = "com.pocketserver.action.ROTATE_CODE"

        fun start(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun restart(context: Context) {
            val intent = Intent(context, ServerForegroundService::class.java).setAction(ACTION_RESTART)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 供 UI 展示"服务已运行多久"。 */
        fun uptimeText(state: ServerState): String =
            if (state.startedAt <= 0) "—" else Formatters.duration(System.currentTimeMillis() - state.startedAt)
    }
}
