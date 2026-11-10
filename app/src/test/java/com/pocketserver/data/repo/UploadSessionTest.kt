package com.pocketserver.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断点续传的进度模型（FR-14）。
 *
 * ## 为什么这一层值得单独测
 * [UploadSession] 是**唯一**判断"这个文件传完了没有"的地方，而它要同时管住三件事：
 * 单路大文件续传、多路并发上传、某一路失败后重传。三者共用一套"每路已写结束位点"的模型。
 *
 * 这套模型一旦算错，症状全部是**静默**的，而且看起来都不像同一个 bug：
 *  - 位点被算小 → 每次续传都从更早的地方开始，文件永远传不完
 *  - 位点被重复累加 → 进度冲过 100%，文件在没写完的时候被判为"完成"
 *  - 切段算法与服务端不一致 → 某一路永远等不到完成，记录永远停在"进行中"
 *
 * 而且它是个纯数据类，没有任何 Android 依赖 —— 这类"算错了也不会报错"的逻辑，
 * 用单元测试锁住是成本最低、收益最高的一种。
 *
 * ## 与前端算法的契约
 * 切段规则：`chunk = size / lanes`（**整数除法**），第 i 路起点 `i * chunk`，
 * 最后一路负责兜住整除余数。前端 `WebUi.kt` 里的 `Math.floor(size / lanes)`
 * 必须与此逐字一致 —— 两边不一致的后果见上面第三条。
 * 下面的用例把这个契约直接写成了断言。
 */
class UploadSessionTest {

    private fun session(
        size: Long,
        lanes: Int,
        vararg ends: Long
    ): UploadSession = UploadSession(
        path = "测试.bin",
        size = size,
        lanes = lanes,
        laneEnd = LongArray(lanes) { i -> ends.getOrElse(i) { 0L } },
        updatedAt = 1_000L
    )

    // ==================== 切段 ====================

    @Test
    fun `单路时整文件就是一路的长度`() {
        val s = session(1000, 1)
        assertEquals("单路没有分片概念，整文件都归它", 0L, s.startOf(0))
        assertEquals(1000L, s.expectedEnd(0))
        assertFalse(s.laneDone(0))
        assertFalse(s.isComplete())
    }

    @Test
    fun `多路的切段要与前端的整数除法一致`() {
        // size=10、lanes=3 → chunk = 10 / 3 = 3（整数除法，余下的 1 交给最后一路）
        val s = session(10, 3)
        assertEquals(0L, s.startOf(0))
        assertEquals(3L, s.startOf(1))
        assertEquals(6L, s.startOf(2))
        assertEquals(3L, s.expectedEnd(0))
        assertEquals(6L, s.expectedEnd(1))
        assertEquals("最后一路兜住整除余数，必须到 size", 10L, s.expectedEnd(2))
    }

    @Test
    fun `能整除时每路长度相等`() {
        val s = session(12, 4)
        assertEquals(listOf(3L, 6L, 9L, 12L), (0 until 4).map { s.expectedEnd(it) })
        assertEquals(listOf(0L, 3L, 6L, 9L), (0 until 4).map { s.startOf(it) })
    }

    // ==================== 位点推进（幂等是核心） ====================

    @Test
    fun `位点只前进，重复上报同一个值不再变化`() {
        val s = session(1000, 1)
        val once = s.advanceTo(0, 400, now = 2_000)
        assertNotNull("第一次上报必须生成新对象", once)
        assertEquals(400L, once!!.laneEnd[0])

        // 分块上传里同一段可能被重发，位点必须停在同一个数上，而不是累加
        assertNull("同一位点重复上报不算推进", once.advanceTo(0, 400, now = 3_000))
        assertNull("位点回退（迟到的旧回调）必须被丢掉", once.advanceTo(0, 200, now = 3_000))
    }

    @Test
    fun `重传同一段不会把进度算两次`() {
        // 这正是"用累加总数算进度"会出错的地方：同一段重写两次，累加值会变成两倍。
        // 用每路最大结束位点则天然幂等。
        var s = session(100, 2)
        s = s.advanceTo(0, 50, 1)!!   // 第 0 路传完（该路应有 0~50）
        s = s.advanceTo(0, 50, 2) ?: s // 这一段因为链路抖动重发了一次
        assertEquals("重传不该让已收字节翻倍", 50L, s.received())
        assertFalse("第 1 路还没开始，整体不算完成", s.isComplete())
    }

    @Test
    fun `越界的路号被拒绝而不是抛异常`() {
        val s = session(100, 2)
        assertNull("负数路号", s.advanceTo(-1, 10, 1))
        assertNull("超出路数的路号", s.advanceTo(2, 10, 1))
    }

    @Test
    fun `已写位点大于声明大小时不会崩，而是夹回声明大小`() {
        /*
         * 这是一个真实可能出现的组合：客户端改了文件大小却复用了同名，
         * 于是会话里存着的旧位点比新声明的大小还大。
         *
         * 曾经的写法是 `endPosition.coerceIn(laneEnd[lane], size)`，
         * 此时 min=20 > max=10，`coerceIn` 会直接抛 IllegalArgumentException ——
         * 一个数据问题被放大成一次崩溃。改用 maxOf 兜上界之后只是原地不动。
         */
        val s = session(10, 1, 20L)
        val result = runCatching { s.advanceTo(0, 15, now = 2_000) }
        assertTrue("不能因为数据不一致就崩掉", result.isSuccess)
        assertNull("上界被 maxOf 兜住，所以这里判为没有推进", result.getOrNull())
    }

    @Test
    fun `推进到超过声明大小时会被夹到 size`() {
        val s = session(100, 1)
        val advanced = s.advanceTo(0, 9_999, now = 2_000)
        assertEquals("位点不能超过文件本身的大小", 100L, advanced!!.laneEnd[0])
        assertTrue(advanced.isComplete())
    }

    // ==================== 完成判定 ====================

    @Test
    fun `每一路都到位才算完成`() {
        var s = session(10, 3)
        assertEquals(0, s.doneCount())

        s = s.advanceTo(0, 3, 1)!!
        s = s.advanceTo(2, 10, 2)!!   // 最后一路长度不同，必须各自用 expectedEnd 判断
        assertEquals(2, s.doneCount())
        assertFalse("第 1 路还没到 6，整体不算完成", s.isComplete())

        s = s.advanceTo(1, 6, 3)!!
        assertEquals(3, s.doneCount())
        assertTrue(s.isComplete())
    }

    @Test
    fun `单路续传到位即完成`() {
        var s = session(1_000, 1)
        s = s.advanceTo(0, 999, 1)!!
        assertFalse("差一个字节也不算传完", s.isComplete())
        s = s.advanceTo(0, 1_000, 2)!!
        assertTrue(s.isComplete())
    }

    @Test
    fun `已收字节数是各路之和且不超过文件大小`() {
        val s = session(10, 3)
        assertEquals(0L, s.received())
        val half = s.advanceTo(0, 3, 1)!!.advanceTo(1, 4, 2)!!
        assertEquals("第 0 路 3 字节 + 第 1 路 4 字节", 7L, half.received())
        // 声明大小被改小之后，各路之和可能超过它 —— 不能把"已收 12/10"这种数字给用户看
        val over = session(5, 2, 5L, 5L)
        assertEquals(5L, over.received())
    }

    // ==================== 复制 ====================

    @Test
    fun `copyOf 不共享底层数组`() {
        /*
         * `data class` 自动生成的 `copy()` 会把这个 LongArray **按引用**带走，
         * 于是"副本"和"原件"共用同一个数组，改一个等于改两个 ——
         * 而会话表把内存 Map 当真源、文件当镜像，这种共享会直接把表写脏。
         * 所以 [UploadSession] 自己写了一个 copyOf，这个用例是它的守卫。
         */
        val original = session(100, 2, 50L, 0L)
        val copy = original.copyOf()
        copy.laneEnd[0] = 99L
        assertEquals("改副本不能影响原件", 50L, original.laneEnd[0])
    }

    @Test
    fun `复制时默认深拷数组，也可以显式换掉整份位点`() {
        // lanes=2 时 chunk = 100/2 = 50：第 0 路负责 [0,50)，第 1 路负责 [50,100)。
        // 所以"传完"必须是 [50, 100] —— 写成 [10, 100] 第 0 路还差 40 字节，
        // isComplete() 当然为 false（这个数最初就是在这里写错的）。
        val original = session(100, 2, 10L, 0L)
        val replaced = original.copyOf(laneEnd = longArrayOf(50L, 100L), updatedAt = 5L)
        assertEquals(5L, replaced.updatedAt)
        assertEquals(100L, replaced.laneEnd[1])
        assertTrue(replaced.isComplete())
        assertEquals("原件不受影响", 0L, original.laneEnd[1])
    }
}
