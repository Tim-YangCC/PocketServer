package com.pocketserver.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分块切分器的正确性测试（优化项 O-3 / 验收项 AC-17）。
 *
 * ## 这里验的是"数据完整性"，不是"性能"
 * 分块并行唯一的灾难性失败模式是**区间错位**：某一段被漏掉，或者两段重叠。
 * 后果不是"慢一点"，而是"文件悄悄损坏了" —— 而且往往要等到用户打开那个视频
 * 才发现。所以这里把所有边界都按"拼起来必须严丝合缝等于原文件"来断言。
 */
class ChunkPlannerTest {

    private val mb = 1024L * 1024L

    /** 断言：区间按序首尾相接、无重叠、完整覆盖 [0, totalBytes)。 */
    private fun assertCoversExactlyOnce(ranges: List<ChunkRange>, totalBytes: Long) {
        assertTrue("至少要有一块", ranges.isNotEmpty())
        assertEquals("第一块必须从 0 开始", 0L, ranges.first().start)
        assertEquals("最后一块必须结束在文件末尾", totalBytes, ranges.last().endExclusive)

        var cursor = 0L
        ranges.forEachIndexed { i, range ->
            assertEquals("第 $i 块的起点应当紧接上一块的终点", cursor, range.start)
            assertTrue("第 $i 块长度不能为负", range.length >= 0)
            assertEquals("块的 index 应当等于它的序号", i, range.index)
            cursor = range.endExclusive
        }
        assertEquals("总长度必须等于文件大小，一块不多一块不少", totalBytes, cursor)
    }

    @Test
    fun `小文件不切块`() {
        val ranges = ChunkPlanner.plan(totalBytes = 1 * mb, lanes = 6)
        assertEquals(1, ranges.size)
        assertCoversExactlyOnce(ranges, 1 * mb)
    }

    @Test
    fun `刚好低于阈值时不切块`() {
        val size = 64 * mb - 1
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 4)
        assertEquals("未达阈值就切块只会白白增加连接开销", 1, ranges.size)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `达到阈值时按路数切块`() {
        val size = 64 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 4)
        assertEquals(4, ranges.size)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `大文件按指定路数切块且完整覆盖`() {
        val size = 100 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 6)
        assertEquals(6, ranges.size)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `每块不小于下限 否则自动减少路数`() {
        // 20MB、要求 6 路：按 8MB 下限最多只能切 2 块
        val size = 20 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 6, thresholdBytes = 0)
        assertEquals("块太小的话并行收益会被连接开销吃掉", 2, ranges.size)
        assertCoversExactlyOnce(ranges, size)
        ranges.forEach {
            assertTrue("每块都应不小于下限", it.length >= ChunkPlanner.MIN_CHUNK_BYTES)
        }
    }

    @Test
    fun `除不尽时余数归入最后一块`() {
        // 故意取一个除不尽的尺寸：100MB / 6
        val size = 100 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 6)
        val others = ranges.dropLast(1)
        assertTrue("前几块长度应当一致", others.all { it.length == others.first().length })
        assertTrue("最后一块长度不小于前面的", ranges.last().length >= others.first().length)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `单路时退化为一块`() {
        val size = 500 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 1)
        assertEquals(1, ranges.size)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `零字节文件返回一个空块而不是空列表`() {
        val ranges = ChunkPlanner.plan(totalBytes = 0, lanes = 4)
        assertEquals("空列表会让调用方的并发编排拿到空任务集，容易写出 NPE", 1, ranges.size)
        assertEquals(0, ranges.first().index)
        assertEquals(0L, ranges.first().length)
    }

    @Test
    fun `负数长度不会崩 只返回一个空块`() {
        val ranges = ChunkPlanner.plan(totalBytes = -1, lanes = 4)
        assertEquals(1, ranges.size)
        assertEquals(0L, ranges.first().length)
    }

    @Test
    fun `超大文件不产生整数溢出`() {
        // 模拟 20GB 文件（真实存在：蓝光原盘、手机录像合集）
        val size = 20L * 1024 * 1024 * 1024
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 6)
        assertEquals(6, ranges.size)
        assertCoversExactlyOnce(ranges, size)
    }

    @Test
    fun `恰好在块下限边缘时不会切出超小块`() {
        // 16MB / 8MB 下限 = 最多 2 块；请求 4 路应当被压到 2 路
        val size = 16 * mb
        val ranges = ChunkPlanner.plan(totalBytes = size, lanes = 4, thresholdBytes = 0)
        assertEquals(2, ranges.size)
        assertCoversExactlyOnce(ranges, size)
        ranges.forEach { assertEquals(8 * mb, it.length) }
    }
}
