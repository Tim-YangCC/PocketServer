package com.pocketserver.ui.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketserver.data.db.AccessLogEntity
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.db.TransferStats
import com.pocketserver.data.model.AccessRequest
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.data.model.ServerState
import com.pocketserver.data.model.SpeedTestReport
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.data.repo.DeviceRepository
import com.pocketserver.data.repo.LogRepository
import com.pocketserver.data.repo.TransferControl
import com.pocketserver.data.repo.TransferIntent
import com.pocketserver.data.repo.TransferRepository
import com.pocketserver.net.PairingManager
import com.pocketserver.net.PeerClient
import com.pocketserver.perf.BenchStage
import com.pocketserver.service.ServerController
import com.pocketserver.service.ServerForegroundService
import com.pocketserver.util.PathUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 控制台 ViewModel（FR-1）。
 *
 * 设计原则：**界面不做任何判断，只渲染状态**。
 * "该显示 mDNS 域名还是 IP""该提示切 5GHz 还是提示授权"这类逻辑
 * 全部由 [ServerController] 决定并写进 [ServerState]，界面只负责摆出来。
 * 这样手机端和电视端不会出现两套不一致的提示文字。
 */
@HiltViewModel
class ConsoleViewModel @Inject constructor(
    private val controller: ServerController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val state: StateFlow<ServerState> = controller.state
    val benchStage: StateFlow<BenchStage?> = controller.benchStage
    val benchRunning: StateFlow<Boolean> = controller.benchRunning
    val accessRequests: StateFlow<List<AccessRequest>> = controller.accessRequests

    /** 最近一次测速报告，抽出来单独暴露便于界面直接绑定。 */
    val speedReport: StateFlow<SpeedTestReport?> = controller.state
        .map { it.speedReport }
        .stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value.speedReport)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val isTv: Boolean get() = controller.isTv

    fun toggleService() {
        _busy.value = true
        try {
            if (controller.state.value.running) {
                ServerForegroundService.stop(context)
                _message.value = "正在停止服务…"
            } else {
                ServerForegroundService.start(context)
                _message.value = "正在启动后台服务…"
            }
        } catch (t: Throwable) {
            _message.value = t.message ?: "无法切换服务状态"
        } finally {
            _busy.value = false
        }
    }

    fun startService() {
        if (controller.state.value.running) return
        _busy.value = true
        try {
            ServerForegroundService.start(context)
            _message.value = "正在启动后台服务…"
        } catch (t: Throwable) {
            _message.value = t.message ?: "无法启动后台服务"
        } finally {
            _busy.value = false
        }
    }

    fun rotateCode() {
        controller.rotatePairingCode()
        _message.value = "已更换配对码，旧的立即失效"
    }

    fun runSpeedTest() {
        if (controller.benchRunning.value) return
        viewModelScope.launch {
            val report = controller.runSpeedTest()
            _message.value = report?.advice ?: "测速没有完成，请稍后重试"
        }
    }

    fun decideAccess(id: String, allow: Boolean) {
        controller.accessRequests.value.firstOrNull { it.id == id }?.decide?.invoke(allow)
        _message.value = if (allow) "已允许对方接入" else "已拒绝对方接入"
    }

    fun refreshDiscovery() = controller.refreshDiscovery()

    fun storageHint(): String = controller.storageHint()

    fun hasAllFilesAccess(): Boolean = controller.hasAllFilesAccess()

    /** 返回系统设置跳转 Intent；null 表示当前系统版本无需跳转。 */
    fun allFilesAccessIntent() = controller.allFilesAccessIntent()

    fun currentProfileDescription(): String = controller.currentProfile().describe()

    /**
     * 缓冲池使用情况。属于深层诊断信息，只有"传输莫名变慢"时才需要看，
     * 所以不放在控制台首屏，而是显示在访问日志页的诊断区块里（FR-9.5）。
     */
    fun bufferPoolSummary(): String = controller.bufferStats()

    /** 因配对码连续错误被锁定的次数。这是个安全事件计数，值得让用户看到。 */
    fun lockEventCount(): Long = controller.pairingLockEvents()

    /**
     * 已授权的会话列表（FR-2.10「已连接设备」页）。
     *
     * 刻意返回快照而不是 Flow：会话变化频率极低（一次接入才变一次），
     * 而界面那边用"每秒刷新的时间戳"当 key 重新取一次就够了。
     * 为一个低频数据引入一整套 Flow 只会让服务层多出一堆订阅管理。
     */
    fun connectedSessions(): List<PairingManager.Session> = controller.connectedSessions()

    /** 断开某一台已连接设备（FR-2.10）。 */
    fun disconnect(token: String, deviceName: String) {
        viewModelScope.launch {
            try {
                controller.disconnect(token)
                _message.value = "已断开「${deviceName}」，它需要重新配对才能再进来"
            } catch (t: Throwable) {
                _message.value = "断开设备失败：${t.message ?: "未知原因"}"
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

}

/**
 * 附近设备 ViewModel（FR-11）。
 *
 * 推送/拉取是**跨设备**操作，每一步都可能被对方拒绝或超时，
 * 所以这里把所有失败都翻译成用户能理解的一句话，而不是把异常文本直接丢到界面上。
 */
@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val controller: ServerController,
    private val deviceRepository: DeviceRepository
) : ViewModel() {

    val devices: StateFlow<List<NearbyDevice>> = controller.nearbyDevices

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** 已记住的设备，用于在列表上标记"已配对"。 */
    val remembered = deviceRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** deviceId → 会话令牌，避免每次操作都重新登记（重新登记会再次弹窗）。 */
    private val sessions = mutableMapOf<String, String>()

    fun refresh() = controller.refreshDiscovery()

    fun push(device: NearbyDevice, localRelPath: String) {
        viewModelScope.launch {
            _working.value = true
            try {
                _message.value = "正在连接 ${device.name}…（对方可能需要在大屏上确认）"
                val token = ensureSession(device) ?: return@launch
                val result = controller.pushTo(device, token, localRelPath)
                _message.value = result.getOrElse { it.message ?: "推送失败" }
            } finally {
                _working.value = false
            }
        }
    }

    fun pull(device: NearbyDevice, remotePath: String, remoteSize: Long) {
        viewModelScope.launch {
            _working.value = true
            try {
                _message.value = "正在连接 ${device.name}…"
                val token = ensureSession(device) ?: return@launch
                val result = controller.pullFrom(device, token, remotePath, remoteSize = remoteSize)
                _message.value = result.getOrElse { it.message ?: "拉取失败" }
            } finally {
                _working.value = false
            }
        }
    }

    private suspend fun ensureSession(device: NearbyDevice): String? {
        sessions[device.deviceId]?.let { return it }
        return controller.announceTo(device).fold(
            onSuccess = { session ->
                sessions[device.deviceId] = session.token
                deviceRepository.remember(
                    deviceId = device.deviceId,
                    name = device.name,
                    type = device.type.name,
                    host = device.host,
                    token = session.token,
                    trusted = true
                )
                session.token
            },
            onFailure = { t ->
                _message.value = t.message ?: "无法连接到 ${device.name}"
                null
            }
        )
    }

    fun toggleTrust(device: NearbyDevice) {
        viewModelScope.launch {
            val existing = deviceRepository.find(device.deviceId)
            val next = !(existing?.trusted ?: false)
            if (existing == null) {
                deviceRepository.remember(
                    deviceId = device.deviceId,
                    name = device.name,
                    type = device.type.name,
                    host = device.host,
                    token = sessions[device.deviceId] ?: "",
                    trusted = next
                )
            } else {
                deviceRepository.setTrusted(device.deviceId, next)
            }
            _message.value = if (next) "${device.name} 已加入免确认列表" else "已取消免确认"
        }
    }

    fun forget(device: NearbyDevice) {
        viewModelScope.launch {
            sessions.remove(device.deviceId)
            deviceRepository.forget(device.deviceId)
            _message.value = "已移除 ${device.name}"
        }
    }

    // ==================== 对端目录浏览（FR-11.4 拉取的入口） ====================

    /**
     * 正在浏览的对端目录。
     *
     * 之所以把"浏览对端"放在这个 ViewModel 而不是新开一个：会话令牌存在这里的
     * [sessions] 里。另起一个 ViewModel 就得自己再握手一次，结果是同一台设备
     * 被重复登记、在新设备上多弹一次接入确认窗口 —— 用户会觉得"这软件老问同一件事"。
     */
    data class RemoteBrowse(
        val deviceId: String,
        val deviceName: String,
        val path: String,
        val entries: List<PeerClient.PeerEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null
    ) {
        val atRoot: Boolean get() = path.isBlank()
    }

    private val _remote = MutableStateFlow<RemoteBrowse?>(null)
    val remote: StateFlow<RemoteBrowse?> = _remote.asStateFlow()

    fun browseRemote(device: NearbyDevice, path: String = "") {
        viewModelScope.launch {
            _remote.value = RemoteBrowse(device.deviceId, device.name, path, loading = true)
            val token = ensureSession(device)
            if (token == null) {
                _remote.value = RemoteBrowse(
                    deviceId = device.deviceId,
                    deviceName = device.name,
                    path = path,
                    error = "连接 ${device.name} 失败，请确认对方服务已开启"
                )
                return@launch
            }
            val result = controller.listRemote(device, token, path)
            _remote.value = result.fold(
                onSuccess = {
                    RemoteBrowse(device.deviceId, device.name, path, entries = it)
                },
                onFailure = {
                    RemoteBrowse(
                        deviceId = device.deviceId,
                        deviceName = device.name,
                        path = path,
                        error = it.message ?: "读取对方目录失败"
                    )
                }
            )
        }
    }

    /** 回到对端上一层目录。 */
    fun remoteUp() {
        val current = _remote.value ?: return
        val parent = PathUtil.parent(current.path) ?: return
        val device = devices.value.firstOrNull { it.deviceId == current.deviceId } ?: return
        browseRemote(device, parent)
    }

    /** 刷新当前对端目录。 */
    fun remoteRefresh() {
        val current = _remote.value ?: return
        val device = devices.value.firstOrNull { it.deviceId == current.deviceId } ?: return
        browseRemote(device, current.path)
    }

    fun closeRemote() {
        _remote.value = null
    }

    /** 把对端的一个文件拉到本机。 */
    fun pullEntry(entry: PeerClient.PeerEntry) {
        val current = _remote.value ?: return
        if (entry.isDirectory) {
            val device = devices.value.firstOrNull { it.deviceId == current.deviceId } ?: return
            browseRemote(device, entry.path)
            return
        }
        val device = devices.value.firstOrNull { it.deviceId == current.deviceId } ?: return
        pull(device, entry.path, entry.size)
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * 传输任务 ViewModel（FR-7）。
 *
 * ## 「有没有按钮可以按」不能只看数据库里的状态
 * 数据库里写着"进行中"的任务，可能早就没有搬运工了 —— 浏览器被关掉、
 * 对端设备断了电，记录要等到空闲超时才会被改掉，中间那十分钟它就是一张骗人的卡片。
 * 所以按钮的可见性由**服务端内存里的那份活跃闸门**决定（[live]），
 * 而不是由 `status` 字段决定。没有闸门时只剩一个「取消」可用，
 * 那个按钮的用途正是把这种僵尸记录收掉。
 */
@HiltViewModel
class TransfersViewModel @Inject constructor(
    private val repository: TransferRepository,
    private val controller: ServerController
) : ViewModel() {

    val transfers: StateFlow<List<TransferTaskEntity>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCount: StateFlow<Int> = repository.observeRunningCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 服务端此刻真正在跑的传输：任务 id → 用户意图。
     *
     * 界面用它区分三件事：这条能不能暂停、能不能继续、按钮按下去会不会有反应。
     */
    val live: StateFlow<Map<Long, TransferIntent>> = controller.liveTransfers
        .map { list -> list.filter { it.taskId > 0L }.associate { it.taskId to it.intent } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun pause(task: TransferTaskEntity) = act(task) { controller.pauseTransfer(task.id) }

    fun resume(task: TransferTaskEntity) = act(task) { controller.resumeTransfer(task.id) }

    fun cancel(task: TransferTaskEntity) = act(task) { controller.cancelTransfer(task.id) }

    private fun act(task: TransferTaskEntity, block: suspend () -> TransferControl.Result) {
        viewModelScope.launch {
            _busy.value = true
            try {
                _message.value = block().message
            } catch (t: Throwable) {
                _message.value = "操作没有成功：${t.message ?: "未知原因"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun clearHistory() {
        viewModelScope.launch {
            if (_busy.value) return@launch
            _busy.value = true
            try {
                val deleted = repository.clearPreviousHistory()
                _message.value = if (deleted > 0) "已清除 $deleted 条历史记录，最新批次和进行中的任务已保留。"
                else "没有可清除的旧历史记录。"
            } catch (t: Throwable) {
                _message.value = "清理历史记录失败：${t.message ?: "未知原因"}"
            } finally {
                _busy.value = false
            }
        }
    }

    // ==================== 概览统计（FR-7.7） ====================

    /** 本批次与历史累计分开显示；累计数由数据库全量汇总，不受最近记录列表的 200 条限制。 */
    data class Overview(
        val batch: TransferStats = TransferStats(null, 0, 0, 0, 0, 0, 0),
        val history: TransferStats = TransferStats(null, 0, 0, 0, 0, 0, 0),
        val activeBytes: Long = 0L
    )

    val overview: StateFlow<Overview> = combine(
        transfers,
        repository.observeLatestBatchStats(),
        repository.observeOverallStats()
    ) { list, batch, history ->
            var bytes = 0L
            for (t in list) {
                when (t.status) {
                    TransferStatus.RUNNING.name -> {
                        bytes += (t.totalBytes - t.transferredBytes).coerceAtLeast(0)
                    }
                    TransferStatus.QUEUED.name, TransferStatus.PAUSED.name ->
                        bytes += (t.totalBytes - t.transferredBytes).coerceAtLeast(0)
                }
            }
            Overview(batch, history, bytes)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Overview())

    fun statusLabel(task: TransferTaskEntity): String = when (task.status) {
        TransferStatus.QUEUED.name -> "排队中"
        TransferStatus.RUNNING.name -> "传输中"
        TransferStatus.PAUSED.name -> "已暂停"
        TransferStatus.DONE.name -> "已完成"
        TransferStatus.FAILED.name -> "失败"
        TransferStatus.CANCELED.name -> "已取消"
        else -> task.status
    }

    fun directionLabel(task: TransferTaskEntity): String = when (task.direction) {
        TransferDirection.UPLOAD.name -> "电脑上传"
        TransferDirection.DOWNLOAD.name -> "电脑下载"
        TransferDirection.PUSH.name -> "推送到 ${task.peerName.ifBlank { "设备" }}"
        TransferDirection.PULL.name -> "从 ${task.peerName.ifBlank { "设备" }} 拉取"
        else -> task.direction
    }

    /**
     * 一条记录上该出现哪些按钮。
     *
     * 返回空表示"这条已经结束了，没什么可操作的"。注意**没闸门不等于结束**：
     * 记录还写着"进行中"却没有闸门，说明搬运工已经没了，这时给一个「取消」
     * 让用户能把它从列表里清掉 —— 否则那张卡片会在他眼前转十分钟的圈。
     */
    fun actionsFor(task: TransferTaskEntity): List<TransferAction> {
        val gateIntent = live.value[task.id]
        val liveNow = gateIntent != null
        if (!liveNow) {
            return when (task.status) {
                TransferStatus.RUNNING.name,
                TransferStatus.QUEUED.name,
                TransferStatus.PAUSED.name -> listOf(TransferAction.CANCEL)
                else -> emptyList()
            }
        }
        return when (gateIntent) {
            TransferIntent.PAUSED -> listOf(TransferAction.RESUME, TransferAction.CANCEL)
            TransferIntent.CANCELED -> emptyList()
            else -> listOf(TransferAction.PAUSE, TransferAction.CANCEL)
        }
    }
}

/** 传输卡片上的一个动作。文案放在这里而不是界面里，是为了让两端（手机 / 电视）说同一句话。 */
enum class TransferAction(val label: String) {
    PAUSE("暂停"),
    RESUME("开始"),
    CANCEL("取消")
}

/** 访问日志 ViewModel（FR-7.6 / FR-9）。 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    private val repository: LogRepository
) : ViewModel() {

    val logs: StateFlow<List<AccessLogEntity>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stats = MutableStateFlow(0 to 0)
    val stats: StateFlow<Pair<Int, Int>> = _stats.asStateFlow()

    init {
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            _stats.value = repository.todayRequestCount() to repository.todayPeerCount()
        }
    }

    fun clear() {
        viewModelScope.launch {
            repository.clear()
            refreshStats()
        }
    }

    /** 把原始请求翻译成用户看得懂的动词。日志页最忌讳直接显示 HTTP 方法与路径。 */
    fun actionLabel(log: AccessLogEntity): String = when {
        log.method == "FTP" -> "FTP 传输"
        log.path.startsWith("/dl") -> "下载文件"
        log.path.startsWith("/view") -> "在线预览"
        log.path == "/api/fs/file" && log.method == "PUT" -> "上传文件"
        log.path.startsWith("/api/fs/list") -> "浏览目录"
        log.path.startsWith("/dav") -> "网络驱动器"
        log.path == "/api/pair" -> "配对接入"
        log.path == "/" -> "打开页面"
        log.path.startsWith("/__bench") -> "测速"
        else -> log.method
    }
}

/** 只有明显异常（4xx/5xx 且非探测性请求）才高亮，避免正常 404 让用户误以为被攻击。 */
fun AccessLogEntity.looksAbnormal(): Boolean =
    status >= 400 && path != "/favicon.ico"
