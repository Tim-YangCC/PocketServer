package com.pocketserver.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 传输任务（FR-7.1 ~ FR-7.5）。 */
@Entity(tableName = "transfer_tasks", indices = [Index("createdAt")])
data class TransferTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 文件名，列表主标题。 */
    val name: String,
    /** 相对共享根的路径，或对端路径（拉取时）。 */
    val relPath: String,
    /** [com.pocketserver.data.model.TransferDirection] 的 name。 */
    val direction: String,
    /** [com.pocketserver.data.model.TransferStatus] 的 name。 */
    val status: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    /** 实际用了多少路并行。用户看到"4 路并行"比看到"多连接"更有说服力。 */
    val lanes: Int = 1,
    val peerName: String = "",
    val peerIp: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** 网页端一次文件/文件夹选择生成的批次号；旧版本记录为空。 */
    val batchId: String = ""
) {
    val percent: Int
        get() = if (totalBytes <= 0) 0 else ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

/**
 * 访问日志（FR-7.6 / FR-9 安全可见性）。
 *
 * 保留策略：只存最近 [com.pocketserver.core.AppConstants.ACCESS_LOG_MAX_ROWS] 条，
 * 超出后按时间淘汰。日志本身绝不参与传输热路径的同步写 —— 否则每次请求都多一次
 * 磁盘事务，会把小文件传输的整体吞吐拖下去。
 */
@Entity(tableName = "access_logs", indices = [Index("timestamp"), Index("peerIp")])
data class AccessLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val peerIp: String,
    val deviceName: String,
    val method: String,
    val path: String,
    val status: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val detail: String = ""
) {
    val success: Boolean get() = status in 200..299
}

/** 已配对设备（FR-2.10 已连接设备页 / FR-11 附近设备信任状态）。 */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    /** mDNS 的 deviceId；来自电脑网页端的会话用 IP 作为标识。 */
    @PrimaryKey val deviceId: String,
    val name: String,
    val type: String,
    val host: String,
    /** 长效令牌。仅存本机，不参与云备份（见 backup_rules.xml）。 */
    val token: String = "",
    val trusted: Boolean = false,
    val lastSeen: Long = System.currentTimeMillis()
)
