package com.pocketserver.ui.vm

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.data.repo.CacheJanitor
import com.pocketserver.data.prefs.AppSettings
import com.pocketserver.data.prefs.SettingsStore
import com.pocketserver.data.storage.StorageAccessManager
import com.pocketserver.data.storage.StorageEntry
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.service.ServerController
import com.pocketserver.service.ServerForegroundService
import com.pocketserver.util.Formatters
import com.pocketserver.util.PathUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 本机文件浏览 ViewModel（FR-3 的本地镜像）。
 *
 * ## 它同时服务两个场景
 *  1. 推送文件时**挑源文件**（FR-11.6 分享流程的落点）
 *  2. 拉取文件时**挑落地目录**
 *
 * ## 电视端的裁剪在此处生效
 * FR-10.6 明确：电视端不做重命名与新建文件夹。判断放在 ViewModel 而不是界面，
 * 这样即便将来有人给电视端加了新界面，也绕不过这道闸。
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val storageAccess: StorageAccessManager,
    private val settingsStore: SettingsStore,
    private val controller: ServerController
) : ViewModel() {

    data class UiState(
        val path: String = "",
        val entries: List<StorageEntry> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
        val freeSpace: Long = 0L,
        val totalSpace: Long = 0L,
        val storageMode: String = "",
        val canWrite: Boolean = true,
        /** 电视端为 false：只保留浏览 / 播放 / 删除。 */
        val canTextOperations: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var provider: StorageProvider? = null

    val isTv: Boolean get() = controller.isTv

    init {
        load("")
    }

    fun open(path: String) = load(path)

    fun up() {
        val parent = PathUtil.parent(_state.value.path) ?: return
        load(parent)
    }

    private fun load(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val settings = settingsStore.current()
                val p = provider ?: storageAccess.build(settings).also { provider = it }
                if (!p.ready) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "尚未获得存储访问权限，请在设置中授予「所有文件访问权限」"
                    )
                    return@launch
                }
                val entries = p.list(path)
                _state.value = UiState(
                    path = path,
                    entries = entries,
                    loading = false,
                    error = null,
                    freeSpace = p.freeSpaceBytes(),
                    totalSpace = p.totalSpaceBytes(),
                    storageMode = if (p.mode == com.pocketserver.data.model.StorageMode.DIRECT) "直连（已优化）" else "系统框架（速度下降）",
                    canWrite = settings.permission == SharedPermission.READ_WRITE,
                    canTextOperations = !controller.isTv
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message ?: "读取失败")
            }
        }
    }

    fun createFolder(name: String) {
        if (isTv) {
            _message.value = "电视端不支持新建文件夹"
            return
        }
        val target = PathUtil.join(_state.value.path, name.trim())
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider?.mkdir(target) ?: false
            _message.value = if (ok) "已创建「${name.trim()}」" else "创建失败，可能已存在"
            load(_state.value.path)
        }
    }

    fun delete(entry: StorageEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider?.delete(entry.relPath) ?: false
            _message.value = if (ok) "已删除「${entry.name}」" else "删除失败"
            load(_state.value.path)
        }
    }

    fun rename(entry: StorageEntry, newName: String) {
        if (isTv) {
            _message.value = "电视端不支持重命名"
            return
        }
        val target = PathUtil.join(PathUtil.parent(entry.relPath) ?: "", newName.trim())
        viewModelScope.launch(Dispatchers.IO) {
            val ok = provider?.rename(entry.relPath, target) ?: false
            _message.value = if (ok) "已重命名为「${newName.trim()}」" else "重命名失败"
            load(_state.value.path)
        }
    }

    /** 供推送流程取相对路径。 */
    fun relativePathOf(entry: StorageEntry): String = entry.relPath

    fun dismissMessage() {
        _message.value = null
    }
}

/**
 * 设置 ViewModel（FR-8）。
 *
 * 一个关键分工：**能立即生效的设置立即生效，必须重启的由界面明确提示**。
 *  - 权限、可见性、配对策略 → 服务端每次请求都会重新读策略，改动即时生效
 *  - 端口、存储根 → 涉及 socket 与文件句柄，必须重启服务；界面上给一个明确的"重启生效"按钮，
 *    而不是静默重启（静默重启会把正在传输的任务打断，用户完全不知道发生了什么）
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
    private val controller: ServerController
) : ViewModel() {

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val serverRunning: StateFlow<Boolean> = controller.state
        .map { it.running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 需要重启才能生效的设置是否被改动过。 */
    private val _needsRestart = MutableStateFlow(false)
    val needsRestart: StateFlow<Boolean> = _needsRestart.asStateFlow()

    val isTv: Boolean get() = controller.isTv

    fun setDeviceName(value: String) = viewModelScope.launch { store.setDeviceName(value) }

    fun setHttpPort(value: Int) = viewModelScope.launch {
        store.setHttpPort(value)
        _needsRestart.value = true
    }

    fun setFtpPort(value: Int) = viewModelScope.launch {
        store.setFtpPort(value)
        _needsRestart.value = true
    }

    fun setFtpEnabled(value: Boolean) = viewModelScope.launch {
        store.setFtpEnabled(value)
        _needsRestart.value = true
    }

    /** 权限改动即时生效：服务端策略是每次请求现场读取的。 */
    fun setPermission(value: SharedPermission) = viewModelScope.launch {
        store.setPermission(value)
        _message.value = if (value == SharedPermission.READ_ONLY) {
            "已切换为只读，正在进行的写入会被拒绝"
        } else {
            "已切换为读写"
        }
    }

    fun setRequirePairing(value: Boolean) = viewModelScope.launch {
        store.setRequirePairing(value)
        _message.value = if (value) {
            "已开启：每次接入都需要配对码"
        } else {
            "已关闭：局域网内可直接接入。注意，新设备仍会在手机上弹窗确认。"
        }
    }

    fun setTrustedBypass(value: Boolean) = viewModelScope.launch { store.setTrustedBypass(value) }

    fun setShowHidden(value: Boolean) = viewModelScope.launch { store.setShowHidden(value) }

    fun setAllowUpload(value: Boolean) = viewModelScope.launch { store.setAllowUpload(value) }

    fun setAllowDelete(value: Boolean) = viewModelScope.launch { store.setAllowDelete(value) }

    fun setAllowRename(value: Boolean) = viewModelScope.launch { store.setAllowRename(value) }

    fun setAutoStart(value: Boolean) = viewModelScope.launch {
        store.setAutoStart(value)
        _message.value = if (value) "已开启开机自启" else "已关闭开机自启"
    }

    fun setIdleStopMinutes(value: Int) = viewModelScope.launch {
        store.setIdleStopMinutes(value)
        if (value == 0) _message.value = "已关闭自动停止，服务会一直运行（更耗电）"
    }

    /** TLS 只改开关不重启：服务端当前不启用 TLS，这里如实告知用户。 */
    fun setTls(value: Boolean) = viewModelScope.launch {
        store.setTls(value)
        _message.value = if (value) {
            "V1.0 暂未启用加密通道。局域网内明文可获得接近两倍的吞吐。"
        } else {
            "已保持明文传输（局域网推荐）"
        }
    }

    fun setLaneOverride(value: Int) = viewModelScope.launch {
        store.setLaneOverride(value)
        controller.onSettingsChanged()
        _message.value = if (value == 0) "已恢复自动并行路数" else "并行路数固定为 $value 路"
    }

    fun setOnboarded(value: Boolean) = viewModelScope.launch { store.setOnboarded(value) }

    fun storageHint(): String = controller.storageHint()

    fun restartService() {
        try {
            ServerForegroundService.restart(context)
        } catch (t: Throwable) {
            _message.value = t.message ?: "无法重启后台服务"
            return
        }
        _needsRestart.value = false
        _message.value = "正在重启后台服务并应用设置…"
    }

    fun clearPresence() = viewModelScope.launch {
        controller.clearDeviceAuthorizations()
        _message.value = "已清除所有设备授权"
    }

    // ==================== 后台保活（FR-13） ====================

    /*
     * 下面这几个都不是 Flow，是刻意的。
     *
     * 电池优化白名单、Wi-Fi 锁是否持有，都是**系统侧**的状态，用户在我们的界面之外
     * （跳到系统设置页）改变它，应用收不到任何回调。把它做成 Flow 只能靠轮询假装响应式，
     * 反而更容易显示过期信息。老老实实做成"取一次"的函数，由界面在回到前台时重新取。
     */
    fun batteryStatusText(): String = controller.batteryStatusText()

    fun isIgnoringBatteryOptimizations(): Boolean = controller.isIgnoringBatteryOptimizations()

    fun batteryOptimizationIntent(): Intent? = controller.batteryOptimizationIntent()

    fun oemBackgroundHint(): String? = controller.oemBackgroundHint()

    fun keepAliveDiagnostics(): String = controller.keepAliveDiagnostics()

    /**
     * 系统里找不到任何跳转入口时的兜底。
     *
     * 静默什么都不做是最差的选择：用户点了按钮、界面毫无反应，他会认为软件坏了。
     * 给出一条可执行的手工路径，至少他能自己走完。
     */
    fun reportNoBatterySettings() {
        _message.value = "这台设备没有提供直接跳转的入口。请手动进入「设置 → 电池 → 电池优化」，" +
            "找到「口袋服务器」并改成「不优化」。"
    }

    fun dismissMessage() {
        _message.value = null
    }
}

/**
 * 存储与清理 ViewModel（FR-15）。
 *
 * ## 为什么不并进 [SettingsViewModel]
 * 这一页有自己的异步生命周期：扫描要遍历目录、清理要跑 VACUUM，都是秒级操作，
 * 需要一个"正在忙"的状态来禁用按钮。塞进设置页的 VM 会让它一边管十几个开关
 * 一边管一个长任务，状态机迅速失控。
 *
 * ## 清理完为什么要立刻重扫
 * 只弹一句"已释放 120MB"而不刷新列表，用户低头一看下面的数字一个没变，
 * 第一反应是"这功能是假的"。重扫一遍是最低成本的自我验证。
 */
@HiltViewModel
class StorageCleanupViewModel @Inject constructor(
    private val controller: ServerController
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val busy: Boolean = false,
        val report: CacheJanitor.Report? = null,
        val error: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        rescan()
    }

    fun rescan() {
        // 清理正在跑的时候扫描会读到半清理状态，数字对不上，索性拒绝
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(loading = true, error = null)
            val report = runCatching { controller.scanJunk() }.getOrNull()
            _state.value = _state.value.copy(
                loading = false,
                // 扫描失败时保留上一次的结果：显示一份旧数据比显示空白有用
                report = report ?: _state.value.report,
                error = if (report == null) "扫描失败。若是存储权限还没授予，请先回到设置页开启。" else null
            )
        }
    }

    fun clean(scope: CacheJanitor.Scope) {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(busy = true, loading = true)
            val result = runCatching { controller.cleanJunk(scope) }.getOrNull()
            val refreshed = runCatching { controller.scanJunk() }.getOrNull()
            _state.value = _state.value.copy(
                busy = false,
                loading = false,
                report = refreshed ?: _state.value.report
            )
            _message.value = when {
                result == null -> "清理失败"
                result.freedBytes > 0 -> "已释放 ${Formatters.bytes(result.freedBytes)}"
                result.notes.isEmpty() -> "没有需要清理的内容"
                // 条数掉了但字节数没动是正常的：SQLite 删行不会立刻缩小文件。
                // 与其显示"释放 0 B"让人以为没生效，不如把删了多少条如实说出来。
                else -> result.notes.joinToString("；")
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
