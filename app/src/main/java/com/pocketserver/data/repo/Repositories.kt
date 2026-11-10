package com.pocketserver.data.repo

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.db.AccessLogDao
import com.pocketserver.data.db.AccessLogEntity
import com.pocketserver.data.db.PairedDeviceDao
import com.pocketserver.data.db.PairedDeviceEntity
import com.pocketserver.data.db.TransferDao
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.db.TransferStats
import com.pocketserver.data.model.SharedPermission
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 传输任务仓库（FR-7）。 */
@Singleton
class TransferRepository @Inject constructor(
    private val dao: TransferDao
) {

    fun observeRecent(limit: Int = 200): Flow<List<TransferTaskEntity>> = dao.observeRecent(limit)

    fun observeOverallStats(): Flow<TransferStats> = dao.observeOverallStats()

    fun observeLatestBatchStats(): Flow<TransferStats> = dao.observeLatestBatchStats()

    fun observeActive(): Flow<List<TransferTaskEntity>> = dao.observeActive()

    fun observeRunningCount(): Flow<Int> = dao.observeRunningCount()

    suspend fun create(
        name: String,
        relPath: String,
        direction: TransferDirection,
        totalBytes: Long,
        lanes: Int,
        peerName: String = "",
        peerIp: String = "",
        batchId: String = ""
    ): Long = dao.insert(
        TransferTaskEntity(
            name = name,
            relPath = relPath,
            direction = direction.name,
            status = TransferStatus.RUNNING.name,
            totalBytes = totalBytes,
            transferredBytes = 0,
            lanes = lanes,
            peerName = peerName,
            peerIp = peerIp,
            batchId = batchId
        )
    )

    suspend fun updateProgress(id: Long, transferred: Long, lanes: Int) =
        dao.updateProgress(id, transferred, lanes)

    suspend fun succeed(id: Long, transferred: Long) =
        dao.finish(id, TransferStatus.DONE.name, transferred, null, System.currentTimeMillis())

    suspend fun fail(id: Long, transferred: Long, error: String) =
        dao.finish(id, TransferStatus.FAILED.name, transferred, error.take(300), System.currentTimeMillis())

    suspend fun cancel(id: Long, transferred: Long) =
        dao.finish(id, TransferStatus.CANCELED.name, transferred, "用户取消", System.currentTimeMillis())

    /**
     * 改状态但**不收尾**（`finishedAt` 传 null）。
     *
     * 暂停与继续走这条路：它们不是终点，任务还在列表里等着用户下一步动作。
     * 若跟 [succeed] 一样盖上结束时间，界面上就会出现"已暂停 · 结束于 3 分钟前"
     * 这种自相矛盾的组合 —— 一条还没结束的传输不该有结束时间。
     */
    suspend fun mark(id: Long, status: TransferStatus, transferred: Long, note: String?, finishedAt: Long?) =
        dao.finish(id, status.name, transferred, note?.take(300), finishedAt)

    /**
     * 服务启动时把残留的"进行中"任务标记为失败。
     *
     * 不做这一步的话，进程被杀之后任务会永远停在 30% 转圈，
     * 用户以为还在传，其实早就没了 —— 这种"假活着"的状态比直接报错更让人恼火。
     *
     * 暂停态也要一起收掉：暂停是靠内存里的闸门撑着的，进程一重启闸门就没了，
     * 那条记录就永远不可能再被"继续"。留着它只会变成一张点不动的卡片。
     */
    suspend fun failStaleTasks() {
        val pending = dao.pending()
        if (pending.isEmpty()) return
        PocketLog.w(TAG, "发现 ${pending.size} 个上次残留的未收尾任务，标记为失败")
        for (task in pending) {
            val wasPaused = task.status == TransferStatus.PAUSED.name
            dao.finish(
                task.id,
                TransferStatus.FAILED.name,
                task.transferredBytes,
                if (wasPaused) "服务已重启，暂停的传输无法恢复；重新选择该文件会从断点继续"
                else "服务已重启，任务中断",
                System.currentTimeMillis()
            )
        }
    }

    /** 清理 30 天前的已完成记录，避免数据库无限增长。 */
    suspend fun pruneOld() {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        dao.pruneFinished(cutoff)
    }

    /** 清除较早批次的已结束记录，保留最新批次和所有仍在活动的任务。 */
    suspend fun clearPreviousHistory(): Int = dao.clearPreviousHistory()

    private companion object {
        const val TAG = "TransferRepository"
    }
}

/**
 * 访问日志仓库（FR-7.6 / FR-9）。
 *
 * 写入走**缓冲批量提交**，不逐条插库。原因：一次网页目录浏览会产生十几个请求，
 * 逐条写就是十几个 SQLite 事务，在低端电视盒子上能明显拖慢首屏。
 * 攒 300ms 或满 32 条提交一次，日志的实时性损失完全不可感知。
 */
@Singleton
class LogRepository @Inject constructor(
    private val dao: AccessLogDao,
    @ApplicationScope private val scope: CoroutineScope
) {

    private val queue = Channel<AccessLogEntity>(capacity = Channel.UNLIMITED)

    init {
        scope.launch {
            val batch = ArrayList<AccessLogEntity>(BATCH_MAX)
            while (isActive) {
                val first = queue.receiveCatching().getOrNull() ?: break
                batch.add(first)
                // 攒一小会儿，把同一批请求合并成一次事务
                withTimeoutOrNull(FLUSH_WINDOW_MS) {
                    while (batch.size < BATCH_MAX) {
                        batch.add(queue.receive())
                    }
                }
                val snapshot = batch.toList()
                batch.clear()
                runCatching {
                    dao.insertAll(snapshot)
                    dao.trimTo(AppConstants.ACCESS_LOG_MAX_ROWS)
                }.onFailure { PocketLog.w(TAG, "日志写入失败：${it.message}") }
            }
        }
    }

    /** 非挂起入队，绝不阻塞请求处理线程。 */
    fun enqueue(log: AccessLogEntity) {
        queue.trySend(log)
    }

    fun observeRecent(limit: Int = 300): Flow<List<AccessLogEntity>> = dao.observeRecent(limit)

    suspend fun todayRequestCount(): Int =
        dao.countSince(System.currentTimeMillis() - 24L * 60 * 60 * 1000)

    suspend fun todayPeerCount(): Int =
        dao.distinctPeersSince(System.currentTimeMillis() - 24L * 60 * 60 * 1000)

    suspend fun clear() = dao.clear()

    private companion object {
        const val TAG = "LogRepository"
        const val FLUSH_WINDOW_MS = 300L
        const val BATCH_MAX = 32
    }
}

/** 已配对设备仓库（FR-2.10 / FR-11.5）。 */
@Singleton
class DeviceRepository @Inject constructor(
    private val dao: PairedDeviceDao
) {

    fun observeAll(): Flow<List<PairedDeviceEntity>> = dao.observeAll()

    suspend fun all(): List<PairedDeviceEntity> = dao.all()

    suspend fun find(deviceId: String): PairedDeviceEntity? = dao.find(deviceId)

    suspend fun findTrustedWebSession(token: String): PairedDeviceEntity? = dao.findTrustedWebSession(token)

    suspend fun trustedWebSessions(): List<PairedDeviceEntity> = dao.trustedWebSessions()

    suspend fun remember(
        deviceId: String,
        name: String,
        type: String,
        host: String,
        token: String,
        trusted: Boolean
    ) = dao.upsert(
        PairedDeviceEntity(
            deviceId = deviceId,
            name = name,
            type = type,
            host = host,
            token = token,
            trusted = trusted
        )
    )

    suspend fun touch(deviceId: String, host: String, token: String) =
        dao.touch(deviceId, System.currentTimeMillis(), host, token)

    suspend fun setTrusted(deviceId: String, trusted: Boolean) = dao.setTrusted(deviceId, trusted)

    suspend fun forget(deviceId: String) = dao.delete(deviceId)

    suspend fun forgetTrustedWebSession(token: String) = dao.deleteTrustedWebSession(token)

    suspend fun forgetToken(token: String) = dao.deleteByToken(token)

    suspend fun forgetAll() = dao.clear()

    /** 供 SharingPolicy 使用：判断某 IP 是否属于已信任设备。 */
    suspend fun isTrustedHost(host: String): Boolean =
        dao.all().any { it.host == host && it.trusted && it.type != "WEB" }

    /** 从已记住的设备里找一台，用于"上次连过的设备"快捷入口。 */
    suspend fun mostRecent(): PairedDeviceEntity? =
        dao.all().filterNot { it.type == "WEB" }.maxByOrNull { it.lastSeen }

    fun defaultPermission() = SharedPermission.READ_WRITE
}
