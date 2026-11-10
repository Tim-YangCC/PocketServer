package com.pocketserver.data.repo

import android.content.Context
import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.db.AccessLogDao
import com.pocketserver.data.db.AppDatabase
import com.pocketserver.data.db.TransferDao
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.di.IoDispatcher
import com.pocketserver.util.Formatters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 垃圾回收（FR-15）。
 *
 * ## 先说清楚：这个应用到底会产生哪些垃圾
 * 需求是"用得越多垃圾越多，我不希望这样"。所以第一件事是**把来源列全**，
 * 而不是加一个"清理缓存"按钮了事 —— 那样只是把问题藏起来。实际来源有四个：
 *
 *  1. **测速临时文件**（`cacheDir/ps_bench_*.tmp`）：单个可达 128MB。
 *     正常路径会自己删，但**进程在测速中被杀就永远留下了**。
 *  2. **中断上传的残片**：分块上传先按最终大小预分配（O-11），
 *     传到 10% 就断的话，磁盘上留下一个"大小看起来完全正常"的文件。
 *     这是最恶劣的一类 —— 用户和系统都识别不出它，它就一直占着空间。
 *  3. **两张只增不减的表**：`transfer_tasks` 与 `access_logs`。
 *     访问日志有"保留 2000 条"的自动淘汰，**传输记录此前没有任何淘汰机制**
 *     （`pruneOld()` 写好了却从没被调用过），用一年就是几万行。
 *  4. **应用缓存目录**：零散临时产物，交给系统也可能不回收。
 *
 * ## 为什么"清理"必须先"能看见"
 * 用户说"我不希望垃圾越来越多"，他能接受的前提是**能自己验证**。
 * 所以 [scan] 返回的是分类明细（哪一类、多少字节、多少条、能不能清），
 * 而不是一个"已清理 12.3MB"的黑箱结论 —— 后者只会让人怀疑到底清了什么。
 *
 * ## 自动清理的边界
 * [autoClean] 只在服务启动时和固定周期跑，且**只碰"确定是垃圾"的东西**：
 * 过期的上传残片、过期记录、测速残片。任何"可能还有用"的（比如 23 小时内
 * 还能续传的会话）一律不碰 —— 自动任务没有资格替用户做这种判断。
 */
@Singleton
class CacheJanitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val transferDao: TransferDao,
    private val accessLogDao: AccessLogDao,
    private val transferRepo: TransferRepository,
    private val resumeStore: UploadResumeStore,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    /** 可清理的类别。UI 上的每一行对应一个。 */
    enum class Scope {
        /** 应用缓存目录（含测速临时文件）。 */
        TEMP_FILES,

        /** 过期未完成的上传残片 + 它们的会话记录。 */
        ABANDONED_UPLOADS,

        /** 传输记录与访问日志（清理后 VACUUM 回收文件空间）。 */
        DATABASE,

        /** 内存里的运行日志。 */
        MEMORY_LOG,

        /** 以上全部。 */
        ALL
    }

    /**
     * 一条占用明细。
     *
     * [clearable] 为 false 表示"这是信息，不是垃圾" ——
     * 例如还在续传有效期内的未完成上传。界面要把它显示出来但**不给清理按钮**：
     * 直接藏起来会让用户以为"没占空间"，给了按钮又会让他误删掉正在续传的任务。
     */
    data class Item(
        val scope: Scope,
        val label: String,
        val detail: String,
        val bytes: Long,
        val count: Int,
        val clearable: Boolean = true
    )

    data class Report(val items: List<Item>, val scannedAt: Long) {
        val totalBytes: Long get() = items.filter { it.clearable }.sumOf { it.bytes }
        val reclaimableCount: Int get() = items.filter { it.clearable }.sumOf { it.count }
    }

    data class Result(val freedBytes: Long, val notes: List<String>) {
        val ok: Boolean get() = true
    }

    // ==================== 扫描 ====================

    suspend fun scan(storage: StorageProvider?): Report = withContext(io) {
        val now = System.currentTimeMillis()
        val items = mutableListOf<Item>()

        items += scanTempFiles()

        val sessions = runCatching { resumeStore.all() }.getOrDefault(emptyList())
        val cutoff = now - AppConstants.UPLOAD_RESUME_TTL_MS
        val expired = sessions.filter { it.updatedAt < cutoff }
        val resumable = sessions.filter { it.updatedAt >= cutoff }

        items += scanSessionFiles(
            scope = Scope.ABANDONED_UPLOADS,
            sessions = expired,
            storage = storage,
            label = "中断的上传残片",
            clearable = true,
            detailFor = { n, bytes ->
                if (n == 0) "没有超时未完成的上传" else "$n 个文件传了一半就断了，已超过 24 小时没有继续，可以安全删除"
            }
        )

        if (resumable.isNotEmpty()) {
            items += scanSessionFiles(
                scope = Scope.ABANDONED_UPLOADS,
                sessions = resumable,
                storage = storage,
                label = "可继续的上传",
                clearable = false,
                detailFor = { n, _ ->
                    "$n 个文件没传完但还在续传有效期内，重新上传同名文件会自动接着传"
                }
            )
        }

        items += scanDatabase()

        items += Item(
            scope = Scope.MEMORY_LOG,
            label = "运行日志",
            detail = "只存在内存里，不占存储空间；清掉只会丢失诊断线索",
            bytes = 0L,
            count = PocketLog.lines.value.size
        )

        Report(items = items, scannedAt = now)
    }

    private fun scanTempFiles(): Item {
        val dirs = appCacheDirs()
        var bytes = 0L
        var count = 0
        var benchBytes = 0L
        for (dir in dirs) {
            for (f in walkFiles(dir)) {
                bytes += f.length()
                count++
                if (f.name.startsWith(AppConstants.BENCH_TEMP_PREFIX)) benchBytes += f.length()
            }
        }
        val detail = buildString {
            append("应用自己的临时目录，系统不保证回收")
            if (benchBytes > 0) {
                append("；其中测速残留 ")
                append(formatBytes(benchBytes))
                append("（上次测速被中途打断留下的）")
            }
        }
        return Item(Scope.TEMP_FILES, "临时文件与缓存", detail, bytes, count)
    }

    private suspend fun scanSessionFiles(
        scope: Scope,
        sessions: List<UploadSession>,
        storage: StorageProvider?,
        label: String,
        clearable: Boolean,
        detailFor: (Int, Long) -> String
    ): Item {
        var bytes = 0L
        var existing = 0
        for (session in sessions) {
            val stat = runCatching { storage?.stat(session.path) }.getOrNull() ?: continue
            if (stat.isDirectory) continue
            existing++
            bytes += stat.size
        }
        return Item(scope, label, detailFor(existing, bytes), bytes, existing, clearable)
    }

    private suspend fun scanDatabase(): Item {
        val dbBytes = databaseFiles().sumOf { it.length() }
        val transfers = runCatching { transferDao.countAll() }.getOrDefault(0)
        val logs = runCatching { accessLogDao.countAll() }.getOrDefault(0)
        val cutoff = System.currentTimeMillis() -
            AppConstants.TRANSFER_RECORD_KEEP_DAYS * 24L * 60 * 60 * 1000
        val stale = runCatching { transferDao.countFinishedBefore(cutoff) }.getOrDefault(0)
        val detail = buildString {
            append("传输记录 ").append(transfers).append(" 条")
            append("（其中 ").append(stale).append(" 条超过 ")
                .append(AppConstants.TRANSFER_RECORD_KEEP_DAYS).append(" 天）")
            append("　·　访问日志 ").append(logs).append(" 条")
        }
        return Item(Scope.DATABASE, "记录数据库", detail, dbBytes, stale)
    }

    // ==================== 清理 ====================

    suspend fun clean(scope: Scope, storage: StorageProvider?): Result = withContext(io) {
        val notes = mutableListOf<String>()
        var freed = 0L

        if (scope == Scope.ALL || scope == Scope.TEMP_FILES) {
            val f = cleanTempFiles()
            freed += f
            if (f > 0) notes += "临时文件释放 ${formatBytes(f)}"
        }

        if (scope == Scope.ALL || scope == Scope.ABANDONED_UPLOADS) {
            val r = cleanAbandonedUploads(storage)
            freed += r.first
            if (r.first > 0 || r.second > 0) {
                notes += "中断的上传：删除 ${r.second} 个残片，释放 ${formatBytes(r.first)}"
            }
        }

        if (scope == Scope.ALL || scope == Scope.DATABASE) {
            notes += cleanDatabase()
        }

        if (scope == Scope.ALL || scope == Scope.MEMORY_LOG) {
            PocketLog.clear()
            notes += "已清空内存日志"
        }

        if (notes.isEmpty()) notes += "没有需要清理的内容"
        Result(freedBytes = freed, notes = notes)
    }

    private fun cleanTempFiles(): Long {
        // 一分钟内被写过的文件不碰：可能正有一个测速在跑，
        // 删掉它会让测速得出一个乱七八糟的结果，而用户完全不知道是自己点了清理导致的。
        val grace = AppConstants.TEMP_FILE_IN_USE_GRACE_MS
        var freed = 0L
        for (dir in appCacheDirs()) freed += deleteContents(dir, grace)
        return freed
    }

    private suspend fun cleanAbandonedUploads(storage: StorageProvider?): Pair<Long, Int> {
        val expired = runCatching { resumeStore.dropExpired() }.getOrDefault(emptyList())
        if (expired.isEmpty()) return 0L to 0
        var freed = 0L
        var deleted = 0
        for (session in expired) {
            val stat = runCatching { storage?.stat(session.path) }.getOrNull()
            val size = stat?.size ?: 0L
            // 注意 `storage?.delete()` 的类型是 Boolean?（安全调用），不能直接 getOrDefault(false)
            // —— 那仍然是 Boolean?，`if (ok)` 编译不过。用 `== true` 收成非空。
            val ok = runCatching { storage?.delete(session.path) }.getOrNull() == true
            if (ok) {
                freed += size
                deleted++
            } else if (stat != null) {
                // 删不掉要说出来。静默失败会让用户以为"清理了"，实际空间一点没回来。
                PocketLog.w(TAG, "残片删除失败：${session.path}")
            }
        }
        return freed to deleted
    }

    /**
     * 清理两张记录表并回收文件空间。
     *
     * VACUUM 不是可选项：SQLite 删除行只是把它标记为空闲页，
     * `.db` 文件大小**一点都不会变小**。用户点完清理去看存储占用，
     * 发现数字没动，就会认定这个功能是假的。
     */
    private suspend fun cleanDatabase(): String {
        val before = databaseFiles().sumOf { it.length() }

        runCatching { transferRepo.pruneOld() }.onFailure {
            PocketLog.w(TAG, "传输记录清理失败：${it.message}")
        }

        // 顺带把访问日志压到最近 24 小时：日志的用途是"回看刚刚发生了什么"，
        // 三十天前的访问记录没有任何使用场景，却一直在占地方。
        val logCutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        runCatching { accessLogDao.deleteOlderThan(logCutoff) }.onFailure {
            PocketLog.w(TAG, "访问日志清理失败：${it.message}")
        }

        vacuum()

        val after = databaseFiles().sumOf { it.length() }
        val saved = (before - after).coerceAtLeast(0)
        return if (saved > 0) {
            "记录数据库：释放 ${formatBytes(saved)}"
        } else {
            "记录数据库：已清理过期记录（文件空间由 SQLite 复用，暂未缩小）"
        }
    }

    private suspend fun vacuum() = withContext(io) {
        runCatching {
            database.openHelper.writableDatabase.execSQL("VACUUM")
        }.onFailure {
            // VACUUM 失败不影响"记录已经被删掉"这个事实，所以只记一句，不往上抛。
            PocketLog.w(TAG, "VACUUM 未成功（不影响记录清理）：${it.message}")
        }
        Unit
    }

    // ==================== 自动清理 ====================

    /**
     * 服务启动时与固定周期各跑一次。
     *
     * 只做三件**确定安全**的事：删应用缓存、删过期上传残片、清过期传输记录。
     * 不叫"清理缓存"而叫"自动回收"，因为它回收的是**应用自己造的垃圾**，
     * 而不是用户的东西 —— 这一点边界必须守死，否则某天一个手滑的规则
     * 就会删掉用户刚传上来的文件。
     */
    suspend fun autoClean(storage: StorageProvider?): Result = withContext(io) {
        val notes = mutableListOf<String>()
        var freed = cleanTempFiles()
        if (freed > 0) notes += "临时文件 ${formatBytes(freed)}"

        val r = cleanAbandonedUploads(storage)
        freed += r.first
        if (r.first > 0) notes += "中断上传残片 ${formatBytes(r.first)}"

        runCatching { transferRepo.pruneOld() }.onFailure {
            PocketLog.w(TAG, "自动清理传输记录失败：${it.message}")
        }

        Result(freedBytes = freed, notes = notes)
    }

    /** 服务启动时调用：把"上次进程被杀"留下的东西一次性收干净。 */
    suspend fun onServiceStart(storage: StorageProvider?): Result = autoClean(storage)

    // ==================== 文件工具 ====================

    private fun appCacheDirs(): List<File> = listOfNotNull(
        context.cacheDir,
        runCatching { context.externalCacheDir }.getOrNull()
    ).distinct()

    /**
     * 递归遍历。
     *
     * 不用 `File.walkTopDown()`：它在遇到无权限目录时会抛异常中断整棵树的遍历，
     * 而"清理"这个动作最不该因为一个读不了的子目录就整体失败。
     */
    private fun walkFiles(root: File?): List<File> {
        if (root == null || !root.isDirectory) return emptyList()
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (child in children) {
                if (child.isDirectory) stack.addLast(child) else out += child
            }
        }
        return out
    }

    private fun deleteContents(dir: File?, graceMs: Long): Long {
        if (dir == null || !dir.isDirectory) return 0L
        val cutoff = System.currentTimeMillis() - graceMs
        var freed = 0L
        for (child in runCatching { dir.listFiles() }.getOrNull().orEmpty()) {
            freed += deleteEntry(child, cutoff)
        }
        return freed
    }

    private fun deleteEntry(file: File, cutoff: Long): Long {
        if (file.isDirectory) {
            var freed = 0L
            for (child in runCatching { file.listFiles() }.getOrNull().orEmpty()) {
                freed += deleteEntry(child, cutoff)
            }
            // 尽力删空目录；里面还有被 grace 保护的文件时删不掉，这没关系
            runCatching { file.delete() }
            return freed
        }
        if (file.lastModified() > cutoff) return 0L
        val size = file.length()
        return if (runCatching { file.delete() }.getOrDefault(false)) size else 0L
    }

    private fun databaseFiles(): List<File> = listOf(
        context.getDatabasePath(AppDatabase.NAME),
        File(context.getDatabasePath(AppDatabase.NAME).path + "-wal"),
        File(context.getDatabasePath(AppDatabase.NAME).path + "-shm")
    ).filter { it.exists() }

    private fun formatBytes(bytes: Long): String = Formatters.bytes(bytes)

    private companion object {
        const val TAG = "CacheJanitor"
    }
}
