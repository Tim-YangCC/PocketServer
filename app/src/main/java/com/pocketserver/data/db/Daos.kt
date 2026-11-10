package com.pocketserver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 传输概览的数据库汇总；历史统计直接聚合整张表，不受列表条数上限影响。 */
data class TransferStats(
    val batchId: String?,
    val total: Int,
    val running: Int,
    val paused: Int,
    val done: Int,
    val failed: Int,
    val canceled: Int
)

@Dao
interface TransferDao {

    @Insert
    suspend fun insert(task: TransferTaskEntity): Long

    @Query("UPDATE transfer_tasks SET transferredBytes = :transferred, lanes = :lanes WHERE id = :id")
    suspend fun updateProgress(id: Long, transferred: Long, lanes: Int)

    @Query(
        "UPDATE transfer_tasks SET status = :status, transferredBytes = :transferred, " +
            "error = :error, finishedAt = :finishedAt WHERE id = :id"
    )
    suspend fun finish(id: Long, status: String, transferred: Long, error: String?, finishedAt: Long?)

    /**
     * 未收尾的任务：服务重启后需要把它们标为失败，否则会永远停在"进行中"。
     *
     * **PAUSED 也算在内。** 暂停态是内存里的闸门加上库里的状态一起构成的，
     * 进程一重启闸门就没了，那条记录就再也不可能被"继续"——留在库里
     * 只会让用户看到一个永远点不动的"已暂停"。所以它同样是残留。
     */
    @Query("SELECT * FROM transfer_tasks WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun pending(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<TransferTaskEntity>>

    @Query(
        "SELECT NULL AS batchId, COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN status IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END),0) AS running, " +
            "COALESCE(SUM(CASE WHEN status = 'PAUSED' THEN 1 ELSE 0 END),0) AS paused, " +
            "COALESCE(SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END),0) AS done, " +
            "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),0) AS failed, " +
            "COALESCE(SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END),0) AS canceled " +
            "FROM transfer_tasks"
    )
    fun observeOverallStats(): Flow<TransferStats>

    @Query(
        "SELECT batchId, COUNT(*) AS total, " +
            "COALESCE(SUM(CASE WHEN status IN ('QUEUED','RUNNING') THEN 1 ELSE 0 END),0) AS running, " +
            "COALESCE(SUM(CASE WHEN status = 'PAUSED' THEN 1 ELSE 0 END),0) AS paused, " +
            "COALESCE(SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END),0) AS done, " +
            "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),0) AS failed, " +
            "COALESCE(SUM(CASE WHEN status = 'CANCELED' THEN 1 ELSE 0 END),0) AS canceled " +
            "FROM transfer_tasks WHERE batchId = " +
            "(SELECT batchId FROM transfer_tasks WHERE batchId != '' ORDER BY createdAt DESC, id DESC LIMIT 1)"
    )
    fun observeLatestBatchStats(): Flow<TransferStats>

    @Query("SELECT * FROM transfer_tasks WHERE status IN ('QUEUED','RUNNING','PAUSED') ORDER BY createdAt DESC")
    fun observeActive(): Flow<List<TransferTaskEntity>>

    @Query("SELECT COUNT(*) FROM transfer_tasks WHERE status = 'RUNNING'")
    fun observeRunningCount(): Flow<Int>

    @Query("DELETE FROM transfer_tasks WHERE status IN ('DONE','FAILED','CANCELED') AND createdAt < :before")
    suspend fun pruneFinished(before: Long)

    /** 清洁页要如实告诉用户"清掉这条能腾出多少条记录"，所以需要先数一遍。 */
    @Query("SELECT COUNT(*) FROM transfer_tasks WHERE status IN ('DONE','FAILED','CANCELED') AND createdAt < :before")
    suspend fun countFinishedBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM transfer_tasks")
    suspend fun countAll(): Int

    /** 清除旧批次的已结束记录，保留最新批次以维持本批次统计；活动任务不会被删除。 */
    @Query(
        "DELETE FROM transfer_tasks " +
            "WHERE status IN ('DONE','FAILED','CANCELED') " +
            "AND (batchId = '' OR batchId != (" +
            "SELECT batchId FROM transfer_tasks WHERE batchId != '' " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1))"
    )
    suspend fun clearPreviousHistory(): Int
}

@Dao
interface AccessLogDao {

    @Insert
    suspend fun insert(log: AccessLogEntity)

    @Insert
    suspend fun insertAll(logs: List<AccessLogEntity>)

    @Query("SELECT * FROM access_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 300): Flow<List<AccessLogEntity>>

    @Query("SELECT COUNT(*) FROM access_logs WHERE timestamp >= :since")
    suspend fun countSince(since: Long): Int

    @Query("SELECT COUNT(DISTINCT peerIp) FROM access_logs WHERE timestamp >= :since")
    suspend fun distinctPeersSince(since: Long): Int

    @Query("DELETE FROM access_logs")
    suspend fun clear()

    /** 只保留最新 N 条。每次插入一批后调用，避免表无限增长。 */
    @Query(
        "DELETE FROM access_logs WHERE id NOT IN " +
            "(SELECT id FROM access_logs ORDER BY timestamp DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM access_logs")
    suspend fun countAll(): Int

    @Query("DELETE FROM access_logs WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface PairedDeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("SELECT * FROM paired_devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices ORDER BY lastSeen DESC")
    suspend fun all(): List<PairedDeviceEntity>

    @Query("SELECT * FROM paired_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun find(deviceId: String): PairedDeviceEntity?

    @Query("SELECT * FROM paired_devices WHERE token = :token AND trusted = 1 AND type = 'WEB' LIMIT 1")
    suspend fun findTrustedWebSession(token: String): PairedDeviceEntity?

    @Query("UPDATE paired_devices SET trusted = :trusted WHERE deviceId = :deviceId")
    suspend fun setTrusted(deviceId: String, trusted: Boolean)

    @Query("UPDATE paired_devices SET lastSeen = :at, host = :host, token = :token WHERE deviceId = :deviceId")
    suspend fun touch(deviceId: String, at: Long, host: String, token: String)

    @Query("DELETE FROM paired_devices WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("DELETE FROM paired_devices WHERE token = :token AND trusted = 1 AND type = 'WEB'")
    suspend fun deleteTrustedWebSession(token: String)

    @Query("DELETE FROM paired_devices WHERE token = :token")
    suspend fun deleteByToken(token: String)

    @Query("SELECT * FROM paired_devices WHERE type = 'WEB' AND trusted = 1 AND token != ''")
    suspend fun trustedWebSessions(): List<PairedDeviceEntity>

    @Query("DELETE FROM paired_devices")
    suspend fun clear()
}
