package com.pocketserver.data.repo

import com.pocketserver.data.db.TransferDao
import com.pocketserver.data.db.TransferStats
import com.pocketserver.data.db.TransferTaskEntity
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输控制层的测试（FR-7.7：暂停 / 继续 / 取消）。
 *
 * ## 为什么这一层必须单独测
 * 这个产品里传输的**执行者有两个，而且不在同一个进程里**：手机自己发起的设备互传由本应用的
 * 协程执行，而主通道（电脑浏览器上传下载）的执行者是**别人的浏览器**，服务端够不着它。
 * 于是"暂停"这件事只能做成一份放在服务端的意图，两端都只是它的客户端。
 * 这一层因此承担了两个责任，而它们各自都会静默出错：
 *
 *  1. **意图是唯一真相**（[TransferControl.intentOf] / [settle]）。写错的表现是
 *     "按了取消，几秒后那条记录自己变回失败" —— 用户会以为按钮是假的。
 *  2. **按路径建索引，任务 id 只是副索引**。任务是异步落库的：句柄返回时主键还不存在，
 *     而 HTTP 循环在任何时刻只知道路径。写错的表现是"上传刚开始的那几毫秒里按暂停没反应"。
 *
 * 这两条都不是"跑起来就看得出来"的问题，所以在这里逐条钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferControlTest {

    /** 只记 `finish` 的假 DAO：控制层关心的就是"最终写成什么状态、盖不盖结束时间"。 */
    private class RecordingDao : TransferDao {

        data class Finish(
            val id: Long,
            val status: String,
            val transferred: Long,
            val note: String?,
            val finishedAt: Long?
        )

        val finishes = mutableListOf<Finish>()
        val progress = mutableListOf<Pair<Long, Long>>()

        override suspend fun insert(task: TransferTaskEntity): Long = 1L

        override suspend fun updateProgress(id: Long, transferred: Long, lanes: Int) {
            progress += id to transferred
        }

        override suspend fun finish(
            id: Long,
            status: String,
            transferred: Long,
            error: String?,
            finishedAt: Long?
        ) {
            finishes += Finish(id, status, transferred, error, finishedAt)
        }

        override suspend fun pending(): List<TransferTaskEntity> = emptyList()

        override fun observeRecent(limit: Int): Flow<List<TransferTaskEntity>> = flowOf(emptyList())

        override fun observeOverallStats(): Flow<TransferStats> = flowOf(TransferStats(null, 0, 0, 0, 0, 0, 0))

        override fun observeLatestBatchStats(): Flow<TransferStats> = flowOf(TransferStats(null, 0, 0, 0, 0, 0, 0))

        override fun observeActive(): Flow<List<TransferTaskEntity>> = flowOf(emptyList())

        override fun observeRunningCount(): Flow<Int> = flowOf(0)

        override suspend fun pruneFinished(before: Long) = Unit

        override suspend fun countFinishedBefore(before: Long): Int = 0

        override suspend fun countAll(): Int = 0

        override suspend fun clearPreviousHistory(): Int = 0
    }

    private fun TestScope.newControl(dao: RecordingDao): TransferControl =
        TransferControl(
            repo = TransferRepository(dao),
            scope = this,
            io = StandardTestDispatcher(testScheduler)
        )

    // ==================== 暂停与继续不是终点 ====================

    /**
     * 暂停要**立刻**可见，但绝不能盖上结束时间。
     *
     * 执行者可能正卡在一个几十 MB 的分片里，等它走到边界再写状态要好几秒 ——
     * 而用户按下按钮之后要的是立刻看到变化。所以暂停这一侧由控制层直接写库。
     *
     * 同时落库的进度必须如实：恢复之后浏览器是从**服务端的续传会话**接着发的，
     * 这里写 0 不会让文件传坏，但会让"已暂停 40%"这种提示显示成"已暂停 0%"，
     * 用户以为进度丢了。
     */
    @Test
    fun `暂停立刻写库，但不盖结束时间`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        val gate = control.register("备份/a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        control.bindTask(gate, 7L)
        gate.transferred = 40

        val result = control.applyToGate(gate, TransferControl.Action.PAUSE)
        assertEquals("暂停本身应当成功", true, result.ok)

        val row = dao.finishes.single()
        assertEquals(7L, row.id)
        assertEquals(TransferStatus.PAUSED.name, row.status)
        assertEquals("进度要如实带过去", 40L, row.transferred)
        assertNull(
            "暂停不是终点：盖上结束时间会让界面上出现「已暂停 · 结束于 3 分钟前」这种自相矛盾",
            row.finishedAt
        )
        assertTrue("暂停的原因要是人话", row.note?.contains("暂停") == true)

        // 网页端每 2 秒读一次的那份快照必须能反映出来，否则按钮不会变
        val live = control.liveTransfers().single()
        assertEquals(TransferIntent.PAUSED, live.intent)
        assertEquals(40, live.percent)
    }

    @Test
    fun `重复暂停不重复写库，继续之后状态回到进行中`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)
        val gate = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        control.bindTask(gate, 1L)

        control.applyToGate(gate, TransferControl.Action.PAUSE)
        control.applyToGate(gate, TransferControl.Action.PAUSE)
        assertEquals("已经在暂停状态就不要再写一次库", 1, dao.finishes.size)

        control.applyToGate(gate, TransferControl.Action.RESUME)
        assertEquals(2, dao.finishes.size)
        assertEquals(
            "继续之后必须回到进行中：停在「已暂停」会骗用户说这条还能接着走，而执行者可能早就没了",
            TransferStatus.RUNNING.name,
            dao.finishes.last().status
        )
        assertNull("进行中的任务同样不该有结束时间", dao.finishes.last().finishedAt)
    }

    @Test
    fun `取消是终态，取消之后不能再暂停`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)
        val gate = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        control.bindTask(gate, 3L)

        control.applyToGate(gate, TransferControl.Action.CANCEL)
        assertEquals(TransferStatus.CANCELED.name, dao.finishes.single().status)
        assertEquals("取消是用户主动做的，要盖上结束时间", true, dao.finishes.single().finishedAt != null)

        val again = control.applyToGate(gate, TransferControl.Action.PAUSE)
        assertEquals("已经取消的传输不该还能暂停", false, again.ok)
        assertEquals("而且不能因此多写一条记录", 1, dao.finishes.size)
    }

    // ==================== 意图优先（settle） ====================

    /**
     * 这一条是整套控制的**规则核心**：
     *
     * > 一旦用户按了暂停或取消，这条传输的最终状态就由用户意图决定，
     * > 不再由执行路径的成败决定。
     *
     * 反过来的写法看着更自然（"谁最后写谁说了算"），但它的症状是
     * "按了取消，浏览器那条连接断开后记录自己跳回失败" —— 用户会以为取消没生效，
     * 然后反复去按。四条分支在这里一次钉死。
     */
    @Test
    fun `收尾状态由用户意图决定，而不是由来路的成败`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        // 1) 用户取消了：即使来路报的是失败，也要落"已取消"
        val canceled = control.register("c.bin", "c.bin", TransferDirection.UPLOAD, 100)
        control.applyToGate(canceled, TransferControl.Action.CANCEL)
        assertEquals(
            TransferStatus.CANCELED,
            control.settle(canceled, 11L, 30, "连接已断开")
        )
        assertEquals(TransferStatus.CANCELED.name, dao.finishes.last().status)

        // 2) 用户暂停了：同样不能被来路失败带跑
        val paused = control.register("p.bin", "p.bin", TransferDirection.UPLOAD, 100)
        control.applyToGate(paused, TransferControl.Action.PAUSE)
        assertEquals(
            TransferStatus.PAUSED,
            control.settle(paused, 12L, 30, "上传不完整，连接可能已中断")
        )
        assertEquals(TransferStatus.PAUSED.name, dao.finishes.last().status)

        // 3) 用户没说话（运行中）+ 没有错误 = 完成
        val running = control.register("d.bin", "d.bin", TransferDirection.UPLOAD, 100)
        assertEquals(TransferStatus.DONE, control.settle(running, 13L, 100, null))
        assertEquals(TransferStatus.DONE.name, dao.finishes.last().status)

        // 4) 用户没说话 + 有错误 = 失败，且原因原样带过去
        val broken = control.register("e.bin", "e.bin", TransferDirection.UPLOAD, 100)
        assertEquals(TransferStatus.FAILED, control.settle(broken, 14L, 10, "磁盘空间不足"))
        assertEquals(TransferStatus.FAILED.name, dao.finishes.last().status)
        assertEquals("磁盘空间不足", dao.finishes.last().note)
    }

    /** 没有闸门时的收尾按"运行中"处理：那是 curl -T 这类一次性请求的正常结局。 */
    @Test
    fun `没有闸门时按运行中收尾`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        assertEquals(TransferStatus.DONE, control.settle(null, 1L, 10, null))
        assertEquals(TransferStatus.FAILED, control.settle(null, 2L, 10, "连接已断开"))
        assertEquals(
            "没有闸门的路径也要如实落库，否则一次失败会静默消失",
            listOf(TransferStatus.DONE.name, TransferStatus.FAILED.name),
            dao.finishes.map { it.status }
        )
    }

    // ==================== 索引与界面的对应关系 ====================

    /**
     * 路径是主键、任务 id 只是副索引。
     *
     * 这条不是为了实现方便，而是因为**任务 id 会晚到**：`TransferRecorder.begin()`
     * 返回句柄时记录还没落库（它由写入协程稍后从数据库拿主键），而 HTTP 处理循环
     * 在任何时刻都只知道路径。若以 id 为唯一入口，"上传刚开始的那几毫秒里按暂停"就会失灵。
     */
    @Test
    fun `按路径与按记录 id 都能找到同一条传输`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)
        val gate = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)

        assertSame("主键还没回填时，路径必须能查到", gate, control.gateFor("a.bin"))
        assertEquals("此时按 id 还查不到，是正常的", null, control.gateForTask(9L))

        control.bindTask(gate, 9L)
        assertSame("回填之后按 id 也要能查到", gate, control.gateForTask(9L))
    }

    @Test
    fun `按记录 id 下达指令：暂停要有搬运工，取消不需要`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)
        val gate = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        control.bindTask(gate, 5L)

        assertEquals(true, control.apply(5L, TransferControl.Action.PAUSE).ok)

        val missing = control.apply(404L, TransferControl.Action.PAUSE)
        assertEquals("找不到闸门时暂停不成立", false, missing.ok)
        assertTrue(
            "拒绝的话要能照做，而不是一个错误码",
            missing.message.contains("不在进行中") || missing.message.contains("不再适用")
        )

        val zombie = control.apply(404L, TransferControl.Action.CANCEL)
        assertEquals(
            "取消不需要搬运工：它正是用来对付「没有执行者却还挂在进行中」的那种僵尸记录的",
            true,
            zombie.ok
        )
        assertEquals(TransferStatus.CANCELED.name, dao.finishes.last().status)
    }

    /**
     * 注销必须用"值相等才删"。
     *
     * 同一个路径上可能已经注册了新的闸门（用户重新挑了同一个文件，启动时会再发一次
     * `reset` 与注册），而旧任务的消费协程此刻才走到收尾。用单参数 `remove(path)`
     * 会把新闸门一起删掉 —— 症状是"新上传刚开始就按不动暂停了"，而且只在这个时序下出现。
     */
    @Test
    fun `注销旧闸门不会误删同名的新闸门`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        val old = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        val fresh = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 200)

        control.unregister(old)
        assertSame(
            "旧闸门收尾时不能把新闸门一起带走",
            fresh,
            control.gateFor("a.bin")
        )

        control.unregister(fresh)
        assertEquals("新闸门自己收尾时才真的从表里消失", null, control.gateFor("a.bin"))
    }

    /**
     * 取消之后闸门要**多站一会儿岗**（墓碑期）。
     *
     * 取消的那一刻浏览器可能还有一两个分片在飞。闸门若立刻注销，它们会因为
     * "查不到闸门"而被当成一次全新的上传：把刚删掉的半截文件重新写一遍，
     * 还多出一条传输记录。所以取消之后闸门保留一段时间，迟到的请求会收到 410 并停手。
     */
    @Test
    fun `取消之后闸门仍然拦着迟到的分片`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)
        val gate = control.register("a.bin", "a.bin", TransferDirection.UPLOAD, 100)

        control.applyToGate(gate, TransferControl.Action.CANCEL)
        assertEquals(TransferIntent.CANCELED, control.intentFor("a.bin"))
        assertTrue("IO 循环要能立刻看到「该停了」", control.haltedFor("a.bin"))

        control.unregister(gate)
        assertEquals(
            "闸门退休之后没有闸门 = 放行；只有闸门还在时才拦得住迟到的请求",
            TransferIntent.RUNNING,
            control.intentFor("a.bin")
        )
        assertFalse(control.haltedFor("a.bin"))
    }

    @Test
    fun `没登记过的路径一律放行`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        assertFalse("没有闸门就没人管它，不能拦着", control.haltedFor("从没传过.bin"))
        assertEquals(TransferIntent.RUNNING, control.intentFor("从没传过.bin"))
        assertTrue(control.liveTransfers().isEmpty())
    }

    // ==================== 取消后的残片清理 ====================

    /**
     * 取消要不要顺手删掉半截文件，判据只能是**这个文件传完了没有**。
     *
     * 用户可能在进度走到 100% 那一刻才按取消，那时文件已经完整落盘 ——
     * 删掉就是毁掉一个完好的文件。反过来，没传完的半截文件留着就是垃圾，
     * 而且下次传同一个文件时它还会被当成"可以续传"的会话，把坏数据续上去。
     *
     * 注意延迟：几秒之后再删，是为了让此刻还在飞的分片先停下来
     * （它们每一个都会在几毫秒内查到闸门状态并停手，几秒后的这一刀必然落在最后）。
     */
    @Test
    fun `取消已传完的文件时保留它`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        var discarded = 0
        control.scheduleDiscard("完整.bin", complete = true) { discarded++ }
        advanceUntilIdle()
        assertEquals("传完的文件不能删", 0, discarded)
    }

    @Test
    fun `取消没传完的文件时延迟删除残片`() = runTest {
        val dao = RecordingDao()
        val control = newControl(dao)

        val removed = mutableListOf<String>()
        control.scheduleDiscard("半截.bin", complete = false) { path -> removed += path }

        assertEquals("要等飞在半空的分片先停下来，不能立刻删", 0, removed.size)
        advanceUntilIdle()
        assertEquals(listOf("半截.bin"), removed)
    }
}
