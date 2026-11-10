package com.pocketserver.data.repo

import android.content.Context
import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次未完成的上传。
 *
 * ## 为什么用"每路已写结束位点"而不是"已收字节总数"
 * 分块并行上传的每一路都在写**各自的区间**，而其中一路失败后重传时会把同一段重写一遍。
 * 用累加总数的话重传会被算两次，于是进度超过 100%、文件也判定为"已完成"。
 * 取**每路的最大结束位点**则天然幂等：同一段重写多少次，结束位点都停在同一个数。
 *
 * ## 为什么这个模型同时管住了单路和分路
 * 单路就是 `lanes = 1`：该路应有长度就是整个文件，结束位点到达 `size` 即完成。
 * 断点续传（浏览器带着 `offset` 重发剩余部分）也只是把这一路的结束位点往前推。
 * 一个模型覆盖三种情况，比给每种情况各写一套判定要可靠得多。
 */
data class UploadSession(
    /** 共享根下的相对路径，也就是最终落盘的文件名。 */
    val path: String,
    /** 客户端声明的总大小。用来判断"同名文件是不是同一个东西"。 */
    val size: Long,
    /** 客户端声明的并行路数。路数变了就不能沿用旧进度。 */
    val lanes: Int,
    /** 每路已写入的结束位点（字节，开区间）。长度恒为 [lanes]。 */
    val laneEnd: LongArray,
    val updatedAt: Long
) {

    /** 第 [lane] 路应有的结束位点。最后一路兜住整除余数。 */
    fun expectedEnd(lane: Int): Long {
        if (lanes <= 1) return size
        if (lane >= lanes - 1) return size
        // 与前端的 `chunk = Math.floor(size / lanes)` 必须一致。
        // 两边不一致的后果是"某一路永远等不到完成"，记录永远停在"进行中"。
        return (lane + 1) * (size / lanes)
    }

    /** 第 [lane] 路的起始位点。 */
    fun startOf(lane: Int): Long = if (lanes <= 1) 0L else lane * (size / lanes)

    fun laneDone(lane: Int): Boolean =
        lane in 0 until lanes && laneEnd[lane] >= expectedEnd(lane)

    /** 是否每一路都已完成。 */
    fun isComplete(): Boolean {
        if (lanes <= 0) return false
        for (i in 0 until lanes) if (!laneDone(i)) return false
        return true
    }

    fun doneCount(): Int {
        if (lanes <= 0) return 0
        var n = 0
        for (i in 0 until lanes) if (laneDone(i)) n++
        return n
    }

    /** 已经落到磁盘上的有效字节数（各路结束位点之和）。 */
    fun received(): Long {
        var sum = 0L
        for (v in laneEnd) sum += v.coerceAtLeast(0)
        return sum.coerceAtMost(size)
    }

    /** 把某一路的结束位点往前推。只在前进时生效，返回是否真的推进了。 */
    fun advanceTo(lane: Int, endPosition: Long, now: Long): UploadSession? {
        if (lane !in 0 until lanes) return null
        // 上界用 maxOf 兜一下：写成 coerceIn(laneEnd[lane], size) 时，
        // 一旦出现「已写位点比声明大小还大」（客户端改了文件大小又复用同名），
        // coerceIn 会因为 min > max 直接抛异常 —— 一个数据问题变成一次崩溃。
        val hi = maxOf(size, laneEnd[lane])
        val target = endPosition.coerceIn(laneEnd[lane], hi)
        if (target <= laneEnd[lane]) return null
        val copy = laneEnd.copyOf()
        copy[lane] = target
        return copyOf(laneEnd = copy, updatedAt = now)
    }

    /**
     * 切换存储形态时的复制。
     *
     * 不用 `data class` 自动生成的 `copy`：它会把 [laneEnd] 这个数组**按引用**带走，
     * 于是"拷贝"和"原件"共享同一个数组，一次看似无害的修改会同时改掉两个对象。
     */
    fun copyOf(
        path: String = this.path,
        size: Long = this.size,
        lanes: Int = this.lanes,
        laneEnd: LongArray = this.laneEnd.copyOf(),
        updatedAt: Long = this.updatedAt
    ): UploadSession = UploadSession(path, size, lanes, laneEnd, updatedAt)
}

/**
 * 上传会话表（FR-14）。落盘在 `filesDir` 下，是一个几行的 TSV。
 *
 * ## 它解决的是"半截文件认不出来"这个问题
 * 分块上传会先按**最终大小**预分配文件（O-11，为了减少扩容带来的碎片与掉速）。
 * 副作用是：文件传到 10% 就断了，但在磁盘上它的大小已经等于整个文件，
 * 用户和系统都看不出它是残片 —— 于是它既不会被清理，又会占着等量的空间。
 * 唯一能认出它的是这张表，所以表本身就是"清理依据"。
 *
 * ## 为什么用 TSV 而不是 JSON
 * 只存五个字段，且都能用 `split('\t')` 一刀切开。手写 JSON 解析要多写几十行
 * 且容易在转义上出错，而这里路径已经过 `PathUtil.sanitize`（不含制表符与换行），
 * 不存在需要转义的内容。取简单而不会错的那条路。
 *
 * ## 为什么内存 Map 是真源、文件只是镜像
 * 分块上传时多路并发上报完成位点，每次上报都读一遍文件再写回去会引入竞态。
 * 内存 Map + 一把 [Mutex] 串行化改动，写文件只是把当前状态整体盖一遍 —— 状态少，
 * 全量重写的开销可以忽略，却免掉了"增量更新对不上"的一整类 bug。
 */
@Singleton
class UploadResumeStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    private val mutex = Mutex()
    private val sessions = LinkedHashMap<String, UploadSession>()

    @Volatile
    private var loaded = false

    private val file: File get() = File(context.filesDir, AppConstants.UPLOAD_RESUME_FILE)

    // ==================== 读 ====================

    suspend fun find(path: String): UploadSession? = mutex.withLock {
        ensureLoaded()
        sessions[path]
    }

    suspend fun all(): List<UploadSession> = mutex.withLock {
        ensureLoaded()
        sessions.values.toList()
    }

    // ==================== 写 ====================

    suspend fun upsert(session: UploadSession) = mutex.withLock {
        ensureLoaded()
        sessions[session.path] = session
        flush()
    }

    /**
     * 把某一路的结束位点往前推。返回推进后的会话；没有推进（重复上报、位点回退）返回 null。
     */
    suspend fun advance(path: String, lane: Int, endPosition: Long): UploadSession? = mutex.withLock {
        ensureLoaded()
        val existing = sessions[path] ?: return@withLock null
        val updated = existing.advanceTo(lane, endPosition, System.currentTimeMillis())
            ?: return@withLock null
        sessions[path] = updated
        flush()
        updated
    }

    suspend fun remove(path: String) = mutex.withLock {
        ensureLoaded()
        if (sessions.remove(path) != null) flush()
    }

    /**
     * 丢掉所有过期会话，并把它们原样返回。
     *
     * 返回值不是可有可无的：调用方要拿它去删**对应的半截文件**。
     * 只清表不删文件的话，磁盘上会留下一个大小看起来完全正常、
     * 内容却是残片 + 空洞的文件 —— 比直接报错更糟。
     */
    suspend fun dropExpired(now: Long = System.currentTimeMillis()): List<UploadSession> = mutex.withLock {
        ensureLoaded()
        val cutoff = now - AppConstants.UPLOAD_RESUME_TTL_MS
        val gone = sessions.values.filter { it.updatedAt < cutoff }
        if (gone.isEmpty()) return@withLock emptyList()
        for (s in gone) sessions.remove(s.path)
        flush()
        gone
    }

    suspend fun clear() = mutex.withLock {
        sessions.clear()
        loaded = true
        runCatching { if (file.exists()) file.delete() }
        Unit
    }

    // ==================== 落盘 ====================

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val text = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")
        if (text.isBlank()) return
        var bad = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parsed = parseLine(line)
            if (parsed == null) {
                bad++
                continue
            }
            sessions[parsed.path] = parsed
        }
        if (bad > 0) {
            // 单行坏掉不该让整张表作废（那会让所有断点续传失效），但要留痕。
            PocketLog.w(TAG, "上传会话表有 $bad 行无法解析，已跳过")
        }
        if (sessions.isNotEmpty()) {
            PocketLog.i(TAG, "已恢复 ${sessions.size} 条未完成的上传会话，可继续续传")
        }
    }

    private fun parseLine(line: String): UploadSession? {
        val parts = line.split('\t')
        if (parts.size < 5) return null
        val path = parts[0]
        // 路径里出现制表符或换行说明这行已经错位了，宁可丢掉也不要解析出一个假的路径
        if (path.isBlank()) return null
        val size = parts[1].toLongOrNull() ?: return null
        val lanes = parts[2].toIntOrNull() ?: return null
        if (lanes <= 0 || lanes > 64 || size < 0) return null
        val endText = parts[3]
        val ends = LongArray(lanes)
        if (lanes > 1 || endText.isNotEmpty()) {
            val tokens = endText.split(',')
            if (tokens.size != lanes) return null
            for (i in 0 until lanes) {
                ends[i] = tokens[i].toLongOrNull() ?: return null
            }
        }
        val updatedAt = parts[4].toLongOrNull() ?: return null
        return UploadSession(path, size, lanes, ends, updatedAt)
    }

    private fun encode(session: UploadSession): String = buildString {
        append(session.path)
        append('\t').append(session.size)
        append('\t').append(session.lanes)
        append('\t').append(session.laneEnd.joinToString(","))
        append('\t').append(session.updatedAt)
    }

    /**
     * 先写临时文件再改名。
     *
     * 直接覆写的话，写一半被系统杀掉就会得到一张截断的表 ——
     * 由于坏行会被跳过，表现是"部分会话静默消失"，用户看到的是"续传时进度归零"。
     * 改名在同分区上是原子的，能保证读到的要么是旧的全量、要么是新的全量。
     */
    private suspend fun flush() = withContext(io) {
        runCatching {
            val tmp = File(file.parentFile, "${AppConstants.UPLOAD_RESUME_FILE}.tmp")
            val body = sessions.values.joinToString("\n") { encode(it) }
            tmp.writeText(body)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // 改名失败（极少数文件系统）就退化为直接写，别把数据丢了
                file.writeText(body)
                tmp.delete()
            }
        }.onFailure { PocketLog.w(TAG, "上传会话表写入失败：${it.message}") }
        Unit
    }

    private companion object {
        const val TAG = "UploadResumeStore"
    }
}
