package com.pocketserver.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketserver.core.AppConstants
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.net.SharingPolicy
import com.pocketserver.util.DeviceFormFactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 全部用户可调项（FR-8 设置项）。集中成一个不可变对象，避免 UI 到处读单独的 key。 */
data class AppSettings(
    val deviceName: String = "",
    val httpPort: Int = AppConstants.HTTP_PORT_DEFAULT,
    val ftpPort: Int = AppConstants.FTP_PORT_DEFAULT,
    val ftpEnabled: Boolean = true,
    val permission: SharedPermission = SharedPermission.READ_WRITE,
    val requirePairing: Boolean = true,
    val trustedBypass: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val allowUpload: Boolean = true,
    val allowDelete: Boolean = true,
    val allowRename: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    /** 0 表示不自动停止。电视端默认 0（FR-10.11）。 */
    val idleStopMinutes: Int = AppConstants.IDLE_STOP_MINUTES_PHONE,
    val tlsEnabled: Boolean = false,
    /** 0 表示自动（clamp(核数/2, 2, 6)）。 */
    val laneOverride: Int = 0,
    val onboarded: Boolean = false,
    /** 稳定设备标识，mDNS 服务实例名与规范主机名都用它。 */
    val deviceId: String = "",
    /** SAF 授权的目录（当无法获得全文件访问时使用）。 */
    val safTreeUri: String = "",
    /** 自定义共享根路径。留空则用默认根。 */
    val shareRootPath: String = ""
) {
    fun toPolicy(isTv: Boolean): SharingPolicy = SharingPolicy(
        permission = permission,
        allowUpload = allowUpload,
        allowDelete = allowDelete,
        allowRename = allowRename,
        allowMkdir = allowRename,
        requirePairing = requirePairing,
        trustedBypass = trustedBypass,
        showHiddenFiles = showHiddenFiles,
        // 电视端不做重命名与新建文件夹（FR-10.6）
        allowTextOperations = !isTv
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketserver_settings")

/**
 * 设置持久化。
 *
 * 用 DataStore 而不是 SharedPreferences：设置页要实时响应开关变化，
 * DataStore 天然给出 Flow，不用自己写监听与反注册（SharedPreferences 的
 * OnSharedPreferenceChangeListener 需要持有强引用，很容易泄漏 Activity）。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val deviceName = stringPreferencesKey("device_name")
        val httpPort = intPreferencesKey("http_port")
        val ftpPort = intPreferencesKey("ftp_port")
        val ftpEnabled = booleanPreferencesKey("ftp_enabled")
        val permission = stringPreferencesKey("permission")
        val requirePairing = booleanPreferencesKey("require_pairing")
        val trustedBypass = booleanPreferencesKey("trusted_bypass")
        val showHidden = booleanPreferencesKey("show_hidden")
        val allowUpload = booleanPreferencesKey("allow_upload")
        val allowDelete = booleanPreferencesKey("allow_delete")
        val allowRename = booleanPreferencesKey("allow_rename")
        val autoStart = booleanPreferencesKey("auto_start_boot")
        val idleStopMinutes = intPreferencesKey("idle_stop_minutes")
        val tls = booleanPreferencesKey("tls_enabled")
        val laneOverride = intPreferencesKey("lane_override")
        val onboarded = booleanPreferencesKey("onboarded")
        val deviceId = stringPreferencesKey("device_id")
        val safTree = stringPreferencesKey("saf_tree_uri")
        val shareRoot = stringPreferencesKey("share_root_path")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val isTv = DeviceFormFactor.isTv(context)
        AppSettings(
            deviceName = prefs[Keys.deviceName]?.takeIf { it.isNotBlank() }
                ?: DeviceFormFactor.defaultDeviceName(context),
            httpPort = prefs[Keys.httpPort] ?: AppConstants.HTTP_PORT_DEFAULT,
            ftpPort = prefs[Keys.ftpPort] ?: AppConstants.FTP_PORT_DEFAULT,
            ftpEnabled = prefs[Keys.ftpEnabled] ?: true,
            permission = if (prefs[Keys.permission] == SharedPermission.READ_ONLY.name) {
                SharedPermission.READ_ONLY
            } else {
                SharedPermission.READ_WRITE
            },
            requirePairing = prefs[Keys.requirePairing] ?: true,
            trustedBypass = prefs[Keys.trustedBypass] ?: true,
            showHiddenFiles = prefs[Keys.showHidden] ?: false,
            allowUpload = prefs[Keys.allowUpload] ?: true,
            allowDelete = prefs[Keys.allowDelete] ?: true,
            allowRename = prefs[Keys.allowRename] ?: true,
            autoStartOnBoot = prefs[Keys.autoStart] ?: false,
            idleStopMinutes = prefs[Keys.idleStopMinutes]
                ?: if (isTv) AppConstants.IDLE_STOP_MINUTES_TV else AppConstants.IDLE_STOP_MINUTES_PHONE,
            tlsEnabled = prefs[Keys.tls] ?: false,
            laneOverride = prefs[Keys.laneOverride] ?: 0,
            onboarded = prefs[Keys.onboarded] ?: false,
            deviceId = prefs[Keys.deviceId]?.takeIf { it.isNotBlank() } ?: "",
            safTreeUri = prefs[Keys.safTree] ?: "",
            shareRootPath = prefs[Keys.shareRoot] ?: ""
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /**
     * 取稳定设备标识；首次调用时生成并落盘。
     *
     * 为什么需要它：mDNS 的规范主机名（`<deviceId>.local`）必须**跨重启保持不变**，
     * 否则对面设备会把同一个手机当成新设备，信任列表与传输记录全部错位。
     */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.first()[Keys.deviceId]?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = "ps-" + UUID.randomUUID().toString().replace("-", "").take(10)
        context.dataStore.edit { it[Keys.deviceId] = generated }
        return generated
    }

    suspend fun setDeviceName(value: String) = edit { it[Keys.deviceName] = value.trim() }

    suspend fun setHttpPort(value: Int) = edit { it[Keys.httpPort] = value.coerceIn(1024, 65535) }

    suspend fun setFtpPort(value: Int) = edit { it[Keys.ftpPort] = value.coerceIn(1024, 65535) }

    suspend fun setFtpEnabled(value: Boolean) = edit { it[Keys.ftpEnabled] = value }

    suspend fun setPermission(value: SharedPermission) = edit { it[Keys.permission] = value.name }

    suspend fun setRequirePairing(value: Boolean) = edit { it[Keys.requirePairing] = value }

    suspend fun setTrustedBypass(value: Boolean) = edit { it[Keys.trustedBypass] = value }

    suspend fun setShowHidden(value: Boolean) = edit { it[Keys.showHidden] = value }

    suspend fun setAllowUpload(value: Boolean) = edit { it[Keys.allowUpload] = value }

    suspend fun setAllowDelete(value: Boolean) = edit { it[Keys.allowDelete] = value }

    suspend fun setAllowRename(value: Boolean) = edit { it[Keys.allowRename] = value }

    suspend fun setAutoStart(value: Boolean) = edit { it[Keys.autoStart] = value }

    suspend fun setIdleStopMinutes(value: Int) = edit { it[Keys.idleStopMinutes] = value.coerceIn(0, 240) }

    suspend fun setTls(value: Boolean) = edit { it[Keys.tls] = value }

    suspend fun setLaneOverride(value: Int) = edit { it[Keys.laneOverride] = value.coerceIn(0, AppConstants.LANE_MAX) }

    suspend fun setOnboarded(value: Boolean) = edit { it[Keys.onboarded] = value }

    suspend fun setSafTreeUri(value: String) = edit { it[Keys.safTree] = value }

    suspend fun setShareRootPath(value: String) = edit { it[Keys.shareRoot] = value.trim() }

    /**
     * 统一写入入口。
     *
     * 参数类型必须是 `suspend (MutablePreferences) -> Unit`：
     * 非 suspend 的函数类型**不是** suspend 函数类型的子类型，写成 `(MutablePreferences) -> Unit`
     * 在调用 `dataStore.edit(block)` 时会编译失败。这一点很容易看错。
     */
    private suspend fun edit(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
