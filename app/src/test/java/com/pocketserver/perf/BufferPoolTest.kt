package com.pocketserver.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓冲池测试（优化项 O-2 / O-12）。
 *
 * ## 最需要盯住的行为是"池永不阻塞"
 * 池容量是 8 块，而并发传输可能同时需要更多块。如果 `acquire()` 在池空时傻等，
 * 整个传输线程就会被卡住 —— 现象是"传着传着彻底不动了"，而且不会报错。
 * 所以它的设计是：池空时不阻塞，直接临时分配一块，用完再归还。
 * 代价是短时内存占用上升，收益是**永不会因为池容量而死锁**。
 */
class BufferPoolTest {

    private fun pool(size: Int = 1024, max: Int = 2) = BufferPool(bufferSize = size, maxPooled = max)

    @Test
    fun `借出的缓冲容量符合配置`() {
        val p = pool(size = 4096)
        val buf = p.acquire()
        assertEquals(4096, buf.capacity())
        assertEquals(4096, p.bufferSizeBytes)
    }

    @Test
    fun `归还后同一块缓冲被复用 不重复分配`() {
        val p = pool(max = 1)
        val first = p.acquire()
        p.release(first)
        val second = p.acquire()
        // 池里只有 1 块可复用，两次借出必须是同一块
        assertTrue("同一块缓冲应当被复用，而不是每次新分配", first === second)
    }

    @Test
    fun `池空时 acquire 立刻返回 而不是阻塞`() {
        val p = pool(max = 2)
        val a = p.acquire()
        val b = p.acquire()
        val c = p.acquire() // 池已空，但必须立刻返回一块临时缓冲

        assertNotNull(a)
        assertNotNull(b)
        assertNotNull(c)
        assertFalse(
            "临时分配的缓冲不能和已借出的混为同一块",
            a === b || b === c || a === c
        )
    }

    @Test
    fun `超出容量的归还被丢弃而不是无限堆积`() {
        val p = pool(max = 1)
        val a = p.acquire()
        val b = p.acquire()
        p.release(a)
        p.release(b) // 池只能放 1 块，这一块应被丢弃而不是抛异常

        val c = p.acquire()
        assertNotNull(c)
        assertEquals(1024, c.capacity())
        assertTrue("借出计数不能变成负数（那会让诊断信息完全失真）", p.inUse() >= 0)
    }

    @Test
    fun `acquire 返回的缓冲位置为 0 可直接写入`() {
        val p = pool(size = 16, max = 1)
        val buf = p.acquire()
        assertEquals("借出的缓冲必须处于可写状态", 0, buf.position())
        buf.put(1)
        p.release(buf)

        val again = p.acquire()
        assertEquals(
            "归还时必须重置位置，否则下一次写入会从错误偏移开始，直接写坏数据",
            0,
            again.position()
        )
    }

    @Test
    fun `clear 之后仍可正常借出`() {
        val p = pool(max = 2)
        p.release(p.acquire())
        p.clear()
        assertNotNull(p.acquire())
    }

    @Test
    fun `借出计数随借还与释放同步`() {
        val p = pool(max = 2)
        assertEquals(0, p.inUse())
        val a = p.acquire()
        assertEquals(1, p.inUse())
        p.release(a)
        assertEquals(0, p.inUse())
    }

    @Test
    fun `stats 输出非空 便于诊断`() {
        val p = pool(max = 2)
        p.acquire()
        assertTrue(p.stats().isNotBlank())
    }

    @Test
    fun `多个缓冲并发借出不会互相覆盖内容`() {
        // 这是分块并行最容易出的事故：两块缓冲其实是同一块，两个线程交替写入，
        // 结果文件里出现交错错乱的字节。用内容隔离来验证每块都是独立的。
        val p = pool(size = 8, max = 2)
        val buffers = (0 until 6).map { p.acquire() }
        buffers.forEachIndexed { i, buf ->
            buf.clear()
            repeat(8) { buf.put((i + 1).toByte()) }
        }
        buffers.forEachIndexed { i, buf ->
            buf.flip()
            val values = ByteArray(buf.remaining()).also { buf.get(it) }
            assertTrue(
                "第 $i 块缓冲的内容被别的借出污染了",
                values.all { it == (i + 1).toByte() }
            )
        }
    }
}
