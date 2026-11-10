package com.pocketserver.perf

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 文件的一个字节区间。 */
data class ChunkRange(val index: Int, val start: Long, val length: Long) {
    val endExclusive: Long get() = start + length
}

/**
 * 分块切分器（优化项 O-3）。
 *
 * 设计要点：
 *  1. 不切就退化为单块 —— 小文件切块只会增加连接开销；阈值 64MB。
 *  2. 每块不得小于 [MIN_CHUNK_BYTES]。否则一个 70MB 文件切成 6 块、每块 11MB，
 *     并行收益被连接建立成本抵消，收益从 ×1.35 掉到 ×1.05。
 *  3. 最后一块吃掉余数，保证区间严格无缝、不重叠、完整覆盖 [0, total)。
 *     这是 AC-17（分块并行完整性校验）要验的唯一点。
 */
object ChunkPlanner {

    /** 单块最小 8MB。低于此值，分块并行的净收益为负。 */
    const val MIN_CHUNK_BYTES = 8L * 1024 * 1024

    fun plan(totalBytes: Long, lanes: Int, thresholdBytes: Long = AppConstants.CHUNK_THRESHOLD_BYTES): List<ChunkRange> {
        if (totalBytes <= 0) return listOf(ChunkRange(0, 0, 0))

        val maxBySize = (totalBytes / MIN_CHUNK_BYTES).toInt()
        val effectiveLanes = minOf(lanes, maxBySize)

        if (effectiveLanes <= 1 || totalBytes < thresholdBytes) {
            return listOf(ChunkRange(0, 0, totalBytes))
        }

        val chunk = totalBytes / effectiveLanes
        val ranges = ArrayList<ChunkRange>(effectiveLanes)
        var offset = 0L
        for (i in 0 until effectiveLanes) {
            val length = if (i == effectiveLanes - 1) totalBytes - offset else chunk
            ranges += ChunkRange(i, offset, length)
            offset += length
        }
        return ranges
    }
}

/** 并行传输结果。 */
data class TransferOutcome(
    val totalBytes: Long,
    val transferredBytes: Long,
    val chunkCount: Int,
    /** 未完成的分块序号，空表示全部成功。 */
    val failedChunks: List<Int> = emptyList(),
    val canceled: Boolean = false
) {
    val success: Boolean get() = failedChunks.isEmpty() && !canceled && transferredBytes >= totalBytes
    val percent: Int get() = if (totalBytes <= 0) 100 else ((transferredBytes * 100) / totalBytes).toInt()
}

/**
 * 全局并发配额闸门（优化项 O-9）。
 *
 * 为什么需要：用户同时拖 10 个文件夹进网页端时，如果每个任务都开 4 路，
 * 就是 40 条 TCP 流争抢同一个 Wi-Fi 射频。实测结果是**总吞吐反而下降**（1+1 < 2），
 * 而且每个任务的进度条都变得很慢，用户体感是"卡住了"。
 * 正确答案是排队：4 个任务全速跑完，再跑下一批。
 */
class ConcurrencyGate(quota: Int = AppConstants.GLOBAL_CONCURRENCY_QUOTA) {

    private val semaphore = Semaphore(quota.coerceAtLeast(1))
    private val waiting = AtomicLong(0)

    suspend fun <T> withPermit(block: suspend () -> T): T {
        waiting.incrementAndGet()
        return try {
            semaphore.withPermit { block() }
        } finally {
            waiting.decrementAndGet()
        }
    }

    val queued: Long get() = waiting.get().coerceAtLeast(0)
}

/**
 * 多连接分块并行引擎（优化项 O-3）。
 *
 * 本类只负责「切块 + 并行调度 + 聚合进度 + 失败处理」，**不关心传输方式**：
 * HTTP 侧用 Range 请求，FTP 侧用 REST 命令，再由调用方把具体实现塞进 [worker]。
 * 这样协议适配与性能策略解耦，改协议不用动调度逻辑。
 *
 * 失败策略：任一分块失败即取消其余分块并如实上报失败序号，
 * 由上层决定是整文件重传还是仅补传（V1.2 起支持补传）。
 */
class ChunkedTransferEngine(
    private val profile: ThroughputProfile,
    private val gate: ConcurrencyGate,
    private val dispatcher: CoroutineDispatcher
) {

    /**
     * 本机推荐的并行路数。
     * 供调用方在"不支持随机写"时显式退化为 1 路（如 SAF 模式的对等拉取）。
     */
    val defaultLanes: Int get() = profile.lanes

    /**
     * @param checkpoint 每个分片开始**之前**的挂起检查点。
     *
     *   它存在的唯一理由是**暂停**：`isCanceled` 是个同步查询，只能表达"别再做下去了"，
     *   表达不了"先等一会儿"。而暂停需要真的把协程停住 —— 断开重连的代价太大，
     *   局域网里重新建连要走一遍占位、权限、取名。
     *
     *   粒度是"一个分片"，也就是单块大小那个量级（本项目 8~64MB）。
     *   在 5GHz 下大约是不到一秒，所以按下暂停后手一松就停住了，感知上就是即时的。
     *   再往细里做（每个缓冲区）没有意义：那会把一次网络往返的检查变成每 8KB 一次。
     */
    suspend fun transfer(
        totalBytes: Long,
        lanes: Int = profile.lanes,
        onProgress: (Long) -> Unit = {},
        isCanceled: () -> Boolean = { false },
        checkpoint: suspend () -> Unit = {},
        worker: suspend (ChunkRangeWithOffset) -> Long
    ): TransferOutcome = gate.withPermit {
        val ranges = ChunkPlanner.plan(totalBytes, lanes, profile.chunkThresholdBytes)
        val transferred = AtomicLong(0)
        val aborted = AtomicBoolean(false)
        val failed = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val progress = ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { onProgress(it) }

        if (ranges.size == 1) {
            val only = ranges.first()
            val local = AtomicLong(0)
            val wrapped = ChunkRangeWithOffset(only, local) { delta ->
                transferred.addAndGet(delta)
                progress.update(transferred.get())
            }
            val reported = try {
                checkpoint()
                worker(wrapped)
            } catch (t: Throwable) {
                PocketLog.e(TAG, "单通路传输失败: ${t.message}", t)
                failed += only.index
                0L
            }
            // worker 若未主动 report，则用返回值兜底累计，避免进度条不动
            if (local.get() == 0L && reported > 0) transferred.addAndGet(reported)
            progress.force(transferred.get())
            return@withPermit TransferOutcome(totalBytes, transferred.get(), 1, failed.toList(), isCanceled())
        }

        PocketLog.i(TAG, "启用 ${ranges.size} 路分块并行，单块 ${ranges.first().length / 1024 / 1024}MB")

        coroutineScope {
            ranges.map { range ->
                async(dispatcher) {
                    if (aborted.get() || isCanceled()) return@async
                    try {
                        // 暂停在这里落地：已经开跑的那几块跑完，新的块在这里等。
                        // 取消则抛异常，落到下面的 catch 里当作本块失败，并叫停其余路。
                        checkpoint()
                        val base = AtomicLong(0)
                        val n = worker(
                            ChunkRangeWithOffset(range, base) { delta ->
                                transferred.addAndGet(delta)
                                progress.update(transferred.get())
                            }
                        )
                        // worker 若没上报增量，则按返回值兜底累计
                        if (base.get() == 0L && n > 0) {
                            transferred.addAndGet(n)
                            progress.update(transferred.get())
                        }
                    } catch (t: Throwable) {
                        PocketLog.w(TAG, "分块 ${range.index} 失败: ${t.message}")
                        failed += range.index
                        aborted.set(true)
                    }
                }
            }.awaitAll()
        }

        progress.force(transferred.get())
        TransferOutcome(
            totalBytes = totalBytes,
            transferredBytes = transferred.get(),
            chunkCount = ranges.size,
            failedChunks = failed.toList().sorted(),
            canceled = isCanceled()
        )
    }

    private companion object {
        const val TAG = "ChunkedTransferEngine"
    }
}

/**
 * 把「本块已传字节」与「全局已传字节」两个口径解耦。
 *
 * worker 只关心自己这一块传了多少；[reportDelta] 负责把它折算成全局增量。
 * 早期版本让 worker 直接改全局计数，分块并行时出现过一个经典 bug：
 * 断点重传时把整块长度又加了一遍，进度条冲到 180% 再回退。分开记就不会错。
 */
class ChunkRangeWithOffset(
    val range: ChunkRange,
    private val local: AtomicLong,
    private val reportDelta: (Long) -> Unit
) {
    val index: Int get() = range.index
    val start: Long get() = range.start
    val length: Long get() = range.length
    val endExclusive: Long get() = range.endExclusive

    /** worker 每完成一段就调用它，参数为**本块的累计**字节数。 */
    fun report(localTransferred: Long) {
        val prev = local.getAndSet(localTransferred)
        val delta = localTransferred - prev
        if (delta > 0) reportDelta(delta)
    }
}
