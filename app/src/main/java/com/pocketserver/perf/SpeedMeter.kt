package com.pocketserver.perf

import com.pocketserver.data.model.SpeedSample
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局速率统计（FR-1.5 控制台实时速率）。
 *
 * 用「累计计数 + 定时差分」而不是「滑动窗口队列」：
 * 后者要为每个采样点分配对象，在 90MB/s（约每秒 360 次 256KB 写入）的负载下会制造大量
 * 短命对象，正好撞上优化目标里的「控 GC」（O-12）。两个 AtomicLong 的代价几乎为零。
 *
 * 所有传输路径只调用 [onTx] / [onRx]，**不做任何格式化、不碰 UI**——
 * 格式化是展示层的事，混进热路径就是性能问题。
 */
class SpeedMeter(private val minIntervalMs: Long = 250L) {

    private val txCounter = AtomicLong(0)
    private val rxCounter = AtomicLong(0)

    private var lastTx = 0L
    private var lastRx = 0L
    private var lastAtNanos = System.nanoTime()

    @Volatile
    private var cached = SpeedSample(0, 0, 0)

    /** 发送一个字节计数（上传给客户端 / 推送出去）。 */
    fun onTx(bytes: Long) {
        if (bytes > 0) txCounter.addAndGet(bytes)
    }

    /** 接收一个字节计数（客户端上传 / 拉取进来）。 */
    fun onRx(bytes: Long) {
        if (bytes > 0) rxCounter.addAndGet(bytes)
    }

    /**
     * 取当前速率。两次调用间隔小于 [minIntervalMs] 时直接返回上次结果，
     * 防止 UI 每帧调用把这里变成新的热点。
     */
    fun snapshot(): SpeedSample {
        val now = System.nanoTime()
        val elapsedMs = (now - lastAtNanos) / 1_000_000
        if (elapsedMs < minIntervalMs) return cached

        val tx = txCounter.get()
        val rx = rxCounter.get()
        val scale = if (elapsedMs > 0) 1000.0 / elapsedMs else 0.0
        val sample = SpeedSample(
            timestamp = System.currentTimeMillis(),
            txBps = ((tx - lastTx) * scale).toLong().coerceAtLeast(0),
            rxBps = ((rx - lastRx) * scale).toLong().coerceAtLeast(0)
        )
        lastTx = tx
        lastRx = rx
        lastAtNanos = now
        cached = sample
        return sample
    }

    /** 传输任务结束时清零，避免下次启动的第一秒出现"虚高"。 */
    fun reset() {
        txCounter.set(0)
        rxCounter.set(0)
        lastTx = 0
        lastRx = 0
        lastAtNanos = System.nanoTime()
        cached = SpeedSample(0, 0, 0)
    }
}

/**
 * 进度节流器（优化项 O-8）。
 *
 * 反模式里明确写了「不要频繁刷通知栏与进度 UI」：每次 `setProgress` 都跨越进程边界，
 * 每秒上百次会直接吃掉可观吞吐，而且用户根本看不出差别。
 *
 * 强制上报的时机只有三个：开始、完成、失败。中间靠 [intervalMs] 节流。
 */
class ThrottledProgress(
    private val intervalMs: Long = 200L,
    private val onEmit: (Long) -> Unit
) {
    private val lastEmitNanos = AtomicLong(System.nanoTime())

    /** 常规节流上报。 */
    fun update(transferred: Long) {
        val now = System.nanoTime()
        val last = lastEmitNanos.get()
        if (now - last < intervalMs * 1_000_000) return
        if (!lastEmitNanos.compareAndSet(last, now)) return
        onEmit(transferred)
    }

    /** 强制上报（开始 / 结束 / 失败）。 */
    fun force(transferred: Long) {
        lastEmitNanos.set(System.nanoTime())
        onEmit(transferred)
    }
}
