package com.pocketserver.perf

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 直接内存缓冲池（优化项 O-2 + O-12）。
 *
 * 为什么必须池化：
 *  1. 每个传输连接若各自 `ByteBuffer.allocateDirect(256KB)`，4 路并行 + 并发任务会瞬间
 *     申请数 MB 直接内存，GC 之外还要承受 DirectByteBuffer 的 Cleaner 回收抖动；
 *  2. 直接内存的优势（JVM 堆外、可与 FileChannel 直接交互、避免二次拷贝）只有复用才划算；
 *  3. 池被取空时**不阻塞**，而是新分配一块临时缓冲，用完直接丢弃交给 GC ——
 *     宁可多一次分配，也不让传输线程在锁上排队（排队会让吞吐出现台阶式下跌）。
 */
class BufferPool(
    private val bufferSize: Int = AppConstants.BUFFER_SIZE,
    private val maxPooled: Int = AppConstants.BUFFER_POOL_SIZE
) {

    private val pool = ArrayBlockingQueue<ByteBuffer>(maxPooled)
    private val created = AtomicInteger(0)
    private val borrowed = AtomicInteger(0)

    /** 单块缓冲容量。接收方向需要堆缓冲时按此值分配，保证两条通路粒度一致。 */
    val bufferSizeBytes: Int get() = bufferSize

    init {
        repeat(maxPooled) {
            pool.offer(allocate())
            created.incrementAndGet()
        }
    }

    private fun allocate(): ByteBuffer =
        ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.BIG_ENDIAN)

    /** 取一块缓冲。始终返回可用对象，绝不返回 null。 */
    fun acquire(): ByteBuffer {
        val buf = pool.poll() ?: allocate().also { created.incrementAndGet() }
        buf.clear()
        borrowed.incrementAndGet()
        return buf
    }

    /**
     * 归还缓冲。容量不匹配的（例如临时扩容过的）直接丢弃，避免污染池。
     */
    fun release(buffer: ByteBuffer?) {
        if (buffer == null) return
        borrowed.decrementAndGet()
        if (buffer.capacity() != bufferSize) return
        buffer.clear()
        if (!pool.offer(buffer)) {
            // 池满：说明并发已回落，多余的缓冲交给 GC 回收更划算
            PocketLog.d(TAG, "缓冲池已满，丢弃一块 ${bufferSize / 1024}KB 缓冲")
        }
    }

    /** 借出中的缓冲数量，用于自检报告判断是否存在泄漏。 */
    fun inUse(): Int = borrowed.get().coerceAtLeast(0)

    fun pooled(): Int = pool.size

    fun stats(): String = "池内 ${pool.size}/$maxPooled，借出 ${inUse()}，累计分配 $created 块"

    /** 释放池内所有缓冲（服务停止时调用）。 */
    fun clear() {
        pool.clear()
        PocketLog.i(TAG, "缓冲池已清空（累计分配 $created 块）")
    }

    private companion object {
        const val TAG = "BufferPool"
    }
}
