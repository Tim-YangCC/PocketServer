package com.pocketserver.data.repo

import android.content.ContextWrapper
import com.pocketserver.core.KeepAlive
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输记账入口的测试（FR-7）。
 *
 * ## 这里验的不是"能不能写库"，而是三个会静默出错的顺序问题
 *  1. **insert 必须先于 update / finish**：句柄的方法全是非挂起的，
 *     如果实现成"各自 launch 一次协程"，并发下 `finish` 会跑到 `insert` 前面，
 *     拿不到 id 于是整条记录静默丢失。这正是 [TransferRecorder] 用单条队列串行的原因。
 *  2. **进度只能前进**：分片、逐块回调、收尾强推三个来源混在一起，写入顺序不保证单调，
 *     界面上的表现是进度条来回跳 —— 用户会当成卡死。
 *  3. **收尾只生效一次**：分块上传里多个分片可能同时判定"传完了"，
 *     重复收尾会把已完成的任务改回进行中。
 *
 * 另外断言一条容错边界：**记账失败绝不能影响传输本身**。
 * 数据库写不进去（磁盘满、库损坏）时，文件该传还是要传完。
 *
 * 用 `StandardTestDispatcher` 而不是 `UnconfinedTestDispatcher`：后者会在
 * `begin()` 返回前就把协程跑起来，测不出"句柄先返回、落库稍后发生"这个真实时序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferRecorderTest {

    // ==================== 测试替身 ====================

    private class FakeTransferDao : TransferDao {

        /** 按调用先后记录，顺序类断言全靠它。 */
        val calls = mutableListOf<String>()
        val inserted = mutableListOf<TransferTaskEntity>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val finishes = mutableListOf<Triple<Long, String, Long>>()
        val finishErrors = mutableListOf<String?>()

        /**
         * 收尾过的任务：主键 -> 状态，以及主键 -> 带真实主键的实体副本。
         *
         * 真 DAO 的 `countFinishedBefore` 是拿 SQL 现算的
         * （`status IN ('DONE','FAILED','CANCELED') AND createdAt < :before`）。
         * 注意 `@Insert` 返回主键**不会**回填进传进去的对象，所以 `inserted` 里那些实体的
         * id 全是 0 —— 假 DAO 必须自己把两件事记下来，才能对"清掉这条能腾出多少条记录"
         * 给出如实答案，而不是一律回 0 让上层断言变成假绿。
         */
        private val savedById = mutableMapOf<Long, TransferTaskEntity>()
        private val finishedStatus = mutableMapOf<Long, String>()

        /** 让 insert 抛异常，用于验证"记账失败不影响传输"。 */
        var failInsert = false

        private var nextId = 1L

        override suspend fun insert(task: TransferTaskEntity): Long {
            if (failInsert) throw IllegalStateException("数据库不可用")
            calls += "insert:${task.name}"
            inserted += task
            val id = nextId++
            savedById[id] = task.copy(id = id)
            return id
        }

        override suspend fun updateProgress(id: Long, transferred: Long, lanes: Int) {
            calls += "progress:$id:$transferred"
            progress += id to transferred
        }

        override suspend fun finish(id: Long, status: String, transferred: Long, error: String?, finishedAt: Long?) {
            calls += "finish:$id:$status:$transferred"
            finishes += Triple(id, status, transferred)
            finishErrors += error
            finishedStatus[id] = status
        }

        override suspend fun pending(): List<TransferTaskEntity> = emptyList()

        override fun observeRecent(limit: Int): Flow<List<TransferTaskEntity>> = flowOf(emptyList())

        override fun observeOverallStats(): Flow<TransferStats> = flowOf(TransferStats(null, 0, 0, 0, 0, 0, 0))

        override fun observeLatestBatchStats(): Flow<TransferStats> = flowOf(TransferStats(null, 0, 0, 0, 0, 0, 0))

        override fun observeActive(): Flow<List<TransferTaskEntity>> = flowOf(emptyList())

        override fun observeRunningCount(): Flow<Int> = flowOf(0)

        override suspend fun pruneFinished(before: Long) = Unit

        /** 与真 DAO 那条 SQL 同义：收尾过的、且创建时间早于 before 的有多少条。 */
        override suspend fun countFinishedBefore(before: Long): Int =
            savedById.values.count { task ->
                task.createdAt < before && finishedStatus[task.id] in FINISHED_STATUSES
            }

        override suspend fun countAll(): Int = inserted.size

        override suspend fun clearPreviousHistory(): Int = 0

        private companion object {
            val FINISHED_STATUSES = setOf("DONE", "FAILED", "CANCELED")
        }
    }

    /**
     * 建一个"拿不到任何系统服务"的保活管理器。
     *
     * ## 为什么可以用 `ContextWrapper(null)` 而不需要 Robolectric
     * [KeepAlive] 里所有碰系统服务的地方都写在 `runCatching` 里（获取 Wi-Fi 锁、CPU 锁、
     * 查电池优化白名单），拿不到服务就记一条日志然后放弃。所以一个空壳 Context 足够让
     * 它构造出来并正常走完"计数 +1 / -1"这条逻辑 —— 而这一条恰恰是本轮要验的东西。
     *
     * `unitTests.isReturnDefaultValues = true` 让 android.jar 的方法返回默认值而不是抛
     * `Stub!`，所以 `getSystemService` 返回 null、`getApplicationContext` 返回 null，
     * 全都落进 `runCatching` 的失败分支，不会崩。
     *
     * 另有一步刻意为之：这些替身**不会真的去加锁**，所以这些用例跑在 CI 上
     * 不会因为"没有 WAKE_LOCK 权限"而变成环境相关的偶发失败。
     */
    private fun TestScope.newKeepAlive(): KeepAlive =
        KeepAlive(
            context = ContextWrapper(null),
            scope = this,
            io = StandardTestDispatcher(testScheduler)
        )

    /**
     * 建一套"节流与卡死超时都关掉"的记账器。
     *
     * ## 节流必须关掉
     * `ThrottledProgress` 把构造时刻当作"上次上报时刻"，所以紧接着 `begin()`
     * 的第一次 `report` 一定会被丢掉 —— 那是刻意的性能设计，但会让"进度不回退"
     * 这类断言变得没法写。
     *
     * ## 卡死超时也必须关掉（[idleTimeoutMs] 默认 0）
     * `runTest` 里的 `advanceUntilIdle()` 会**推进虚拟时间直到没有任何待执行任务**，
     * 而带超时的接收会在 +10 分钟处排一个任务。于是只要开着超时，任何
     * "故意不收尾"的用例都会被它提前收掉，断言就看不到"进行中"的状态了。
     * 要验超时本身，就用 `idleTimeoutMs` 显式传一个小值（见专门的用例）。
     *
     * ## scope 必须是测试作用域本身，且每个用例都要把句柄收尾
     * 落库协程会一直挂在队列上等下一个动作（这正是它的设计）。而 `runTest` 会
     * 等待测试作用域的子协程结束，**且等待期间不会推进调度器** ——
     * 所以只要有一条句柄没收尾，用例就会卡满 1 分钟然后报
     * `UncompletedCoroutinesError`。因此每个用例最后都必须
     * `succeed`/`fail` + `advanceUntilIdle()`，把队列排空。
     *
     * （试过用 `backgroundScope` 绕开，结果 `advanceUntilIdle()` 不再推进它，
     * 所有断言都变成"库里什么都没有"。不要把这里改回去。）
     */
    private fun TestScope.newRecorder(
        dao: FakeTransferDao,
        keepAlive: KeepAlive = newKeepAlive(),
        idleTimeoutMs: Long = 0L,
        control: TransferControl = newControl(dao)
    ): TransferRecorder =
        TransferRecorder(
            repo = TransferRepository(dao),
            keepAlive = keepAlive,
            control = control,
            scope = this,
            io = StandardTestDispatcher(testScheduler)
        ).apply {
            throttleIntervalMs = 0
            this.idleTimeoutMs = idleTimeoutMs
        }

    /**
     * 控制层（暂停 / 继续 / 取消）。
     *
     * 与记账器共用同一个假 DAO：这样"用户按了暂停"与"这条连接断了"这两件事
     * 会落在**同一份记录**上，断言才能验出它们之间那条最关键的规则 ——
     * 用户意图优先于执行路径的成败。
     */
    private fun TestScope.newControl(dao: FakeTransferDao): TransferControl =
        TransferControl(
            repo = TransferRepository(dao),
            scope = this,
            io = StandardTestDispatcher(testScheduler)
        )

    // ==================== 建记录 ====================

    @Test
    fun `begin 建一条进行中的记录并带上对端信息`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val handle = recorder.begin(
            name = "假期照片.zip",
            relPath = "备份/假期照片.zip",
            direction = TransferDirection.UPLOAD,
            totalBytes = 4096,
            lanes = 4,
            peerName = "Windows 电脑",
            peerIp = "192.168.1.20"
        )
        advanceUntilIdle()

        assertEquals("只应当插入一行", 1, dao.inserted.size)
        val row = dao.inserted.single()
        assertEquals("假期照片.zip", row.name)
        assertEquals("备份/假期照片.zip", row.relPath)
        assertEquals(TransferDirection.UPLOAD.name, row.direction)
        assertEquals("刚建出来必须是进行中，否则界面不会显示进度", TransferStatus.RUNNING.name, row.status)
        assertEquals(4096L, row.totalBytes)
        assertEquals(0L, row.transferredBytes)
        assertEquals(4, row.lanes)
        assertEquals("Windows 电脑", row.peerName)
        assertEquals("192.168.1.20", row.peerIp)
        assertTrue("还没收尾就不该有 finish", dao.finishes.isEmpty())

        // 收尾把队列排空（理由见 newRecorder 的注释）
        handle.succeed(4096)
        advanceUntilIdle()
        assertEquals(TransferStatus.DONE.name, dao.finishes.single().second)
    }

    // ==================== 顺序 ====================

    @Test
    fun `insert 一定发生在进度与收尾之前`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 1000)
        advanceUntilIdle()
        handle.report(400)
        handle.succeed(1000)
        advanceUntilIdle()

        assertEquals("insert 必须排在最前，否则后续没有 id 可用", "insert:a.bin", dao.calls.first())
        assertEquals("收尾必须排在最后", "finish:1:DONE:1000", dao.calls.last())
        assertEquals("一次传输只收尾一次", 1, dao.calls.count { it.startsWith("finish") })
    }

    @Test
    fun `进度不会回退`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 1000)
        advanceUntilIdle()
        handle.report(600)
        handle.report(200) // 迟到的、来自另一路分片的旧值，必须被丢掉
        handle.succeed(200)
        advanceUntilIdle()

        assertEquals("只应落库一次递增后的值", listOf(600L), dao.progress.map { it.second })
        assertEquals(
            "收尾取的是历史最高水位，不能被一个更小的迟到值带回去",
            600L,
            dao.finishes.single().third
        )
    }

    // ==================== 收尾 ====================

    @Test
    fun `重复收尾只写一次`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 1000)
        advanceUntilIdle()
        handle.succeed(1000)
        handle.succeed(1000) // 另一路分片也判定完成
        advanceUntilIdle()

        assertEquals("已完成的任务不能被第二次收尾改回进行中", 1, dao.finishes.size)
        assertEquals(TransferStatus.DONE.name, dao.finishes.single().second)
    }

    @Test
    fun `失败会把原因原样写进去`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 1000)
        advanceUntilIdle()
        handle.fail(0, "无法创建目标文件夹「备份/2024」")
        advanceUntilIdle()

        val finish = dao.finishes.single()
        assertEquals(TransferStatus.FAILED.name, finish.second)
        assertEquals(
            "失败原因会原样显示在传输卡片上，必须是能直接给用户看的人话",
            "无法创建目标文件夹「备份/2024」",
            dao.finishErrors.single()
        )
    }

    @Test
    fun `多条传输互不干扰`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao)

        val upload = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        val download = recorder.begin("b.bin", "b.bin", TransferDirection.DOWNLOAD, 100)
        advanceUntilIdle()
        assertEquals(2, dao.inserted.size)

        upload.succeed(100)
        download.fail(0, "连接已断开")
        advanceUntilIdle()

        val byId = dao.finishes.associateBy { it.first }
        assertEquals(TransferStatus.DONE.name, byId.getValue(1L).second)
        assertEquals(TransferStatus.FAILED.name, byId.getValue(2L).second)
        assertEquals(
            "方向必须跟着各自的那条记录，不能串",
            listOf(TransferDirection.UPLOAD.name, TransferDirection.DOWNLOAD.name),
            dao.inserted.map { it.direction }
        )
    }

    // ==================== 容错 ====================

    @Test
    fun `记账写库失败时不影响调用方`() = runTest {
        val dao = FakeTransferDao().apply { failInsert = true }
        val recorder = newRecorder(dao)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        advanceUntilIdle()
        handle.report(50)
        handle.succeed(100) // 不应抛出
        advanceUntilIdle()

        assertTrue("没有 id 时不该硬写", dao.finishes.isEmpty())
        assertTrue(dao.progress.isEmpty())
        assertNull("没有记录就没有失败原因，也不该崩", dao.finishErrors.firstOrNull())
    }

    // ==================== 保活计数（FR-13.1） ====================

    /**
     * 这一条验的是"手机退到后台还能不能传"的**记账环节**。
     *
     * 保活（Wi-Fi 锁 + CPU 唤醒锁）是由这个计数驱动的：计数 > 0 才有锁。
     * 而计数出错有且只有两个方向，两个方向都很难被发现：
     *  - **少加**：后台传着传着网卡就进省电档，用户看到"一息屏就断"
     *  - **多减 / 不加**：锁永远不释放，一路耗电到天亮，用户只觉得"这软件特别费电"
     *
     * 所以这里逐条核对：两条传输各加一次、收尾一条减一次、全部收尾归零。
     */
    @Test
    fun `保活计数随传输加减，全部收尾后归零`() = runTest {
        val dao = FakeTransferDao()
        val keepAlive = newKeepAlive()
        val recorder = newRecorder(dao, keepAlive)

        val first = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        val second = recorder.begin("b.bin", "b.bin", TransferDirection.UPLOAD, 100)
        advanceUntilIdle()

        assertEquals("两条传输同时在跑，就必须持两次", 2, keepAlive.activeTransfers)

        first.succeed(100)
        advanceUntilIdle()
        assertEquals("还剩一条在跑，不能提前放锁", 1, keepAlive.activeTransfers)

        second.fail(0, "连接已断开")
        advanceUntilIdle()
        assertEquals(
            "全部收尾后必须归零：计数不归零会把「空闲自停」永久关掉，用户完全看不见，只觉得耗电",
            0,
            keepAlive.activeTransfers
        )
    }

    // ==================== 卡死兜底（FR-13.1） ====================

    /**
     * 一条传输如果永远不收到 Finish，[TransferRecorder] 的落库协程就会一直挂着 ——
     * 而它挂着的代价不只是内存：保活计数永远不归零，进而把省电逻辑静默废掉。
     *
     * 所以引擎里加了一个"多久没有任何动作就判为中断"的兜底。这个用例验的就是它
     * **既要把记录收尾成失败，也要把保活计数减回去** —— 只做前一半等于没修。
     */
    @Test
    fun `长时间没有进度会被判定中断，并释放保活计数`() = runTest {
        val dao = FakeTransferDao()
        val keepAlive = newKeepAlive()
        // 显式给一个短超时。默认值是 10 分钟，而 runTest 会推进虚拟时间，
        // 随手一个 advanceUntilIdle 就会把它触发掉 —— 所以其它用例默认把它关掉。
        val recorder = newRecorder(dao, keepAlive, idleTimeoutMs = 5_000)

        recorder.begin("卡住了.bin", "卡住了.bin", TransferDirection.UPLOAD, 100)
        // 此后不再有任何动作：虚拟时间被推过 5 秒，超时应当生效
        advanceUntilIdle()

        assertEquals(
            "超时的传输必须落到失败态，而不是永远显示「进行中」",
            TransferStatus.FAILED.name,
            dao.finishes.single().second
        )
        assertTrue(
            "失败原因会原样显示在传输卡片上，必须是能直接看懂的人话",
            dao.finishErrors.single()?.contains("没有任何进度") == true
        )
        assertEquals(
            "超时这条路径也必须减计数：漏掉它 = 一次卡死就把省电逻辑永久废掉",
            0,
            keepAlive.activeTransfers
        )
    }

    @Test
    fun `超时收尾之后迟到的收尾不会把记录改回已完成`() = runTest {
        val dao = FakeTransferDao()
        val recorder = newRecorder(dao, newKeepAlive(), idleTimeoutMs = 5_000)

        val handle = recorder.begin("卡住了.bin", "卡住了.bin", TransferDirection.UPLOAD, 100)
        advanceUntilIdle() // 超时生效，记录已判为中断

        handle.succeed(100) // 迟到的收尾：通道已关，应当被静默丢弃
        advanceUntilIdle()

        assertEquals("一条传输只能收尾一次", 1, dao.finishes.size)
        assertEquals(
            "已经判为中断的记录不能被一句迟到的成功改回去",
            TransferStatus.FAILED.name,
            dao.finishes.single().second
        )
    }

    // ==================== 暂停 / 继续（FR-7.7） ====================

    /**
     * 这是整个暂停功能里最难、也最容易被改坏的一条规则：
     * **暂停不是终点，所以消费协程不能在暂停时退出。**
     *
     * 网页上传的"暂停"落到服务端是这样一个序列：
     *  1. 用户按下暂停 → 存下意图 → 立刻写库改成"已暂停"（进度保留）
     *  2. 浏览器那条连接被掐断 → 来路报一次失败收尾
     *     （在 [TransferRecorder.finishUpload] 里，任何"没写完整"的请求都会走这一支）
     *  3. 用户点开始 → 浏览器带着同一个句柄接着传 → 最后报一次成功
     *
     * 如果消费协程在 2 之后退出了，第 3 步的成功就会掉进一个已经关闭的队列里被静默丢弃 ——
     * 界面上表现为"点了开始、进度也走完了，但那条记录永远停在已暂停"。
     * 所以这里逐条断言：2 不能写成功败、3 必须能写成功。
     *
     * 全程用 `runCurrent()` 而不是 `advanceUntilIdle()`：暂停期间那个"三十分钟没动静就放手"
     * 的超时是排在虚拟时间上的，`advanceUntilIdle()` 会一口气把它推过去，
     * 于是用例会在断言之前就把这条传输判成中断 —— 那是另一条规则，不能混进来验。
     */
    @Test
    fun `暂停期间收尾不写失败，句柄仍然有效`() = runTest {
        val dao = FakeTransferDao()
        val keepAlive = newKeepAlive()
        val control = newControl(dao)
        val recorder = newRecorder(dao, keepAlive, control = control)

        val handle = recorder.begin("a.bin", "a.bin", TransferDirection.UPLOAD, 100)
        runCurrent()
        assertEquals("先确认记录已经建好（否则后面的断言都无从谈起）", 1, dao.inserted.size)

        assertEquals(
            "暂停本身要成功",
            true,
            control.applyToGate(handle.gate, TransferControl.Action.PAUSE).ok
        )
        runCurrent()
        assertEquals(
            "用户按下暂停后必须立刻可见，不能等执行者走到收尾",
            TransferStatus.PAUSED.name,
            dao.finishes.last().second
        )

        // 第二步：浏览器那条连接断了，来路走"没传完"的分支报一次失败收尾
        handle.fail(50, "上传不完整，连接可能已中断")
        runCurrent()

        assertTrue(
            "用户主动按下的暂停，绝不能被一条来路失败覆盖成「失败」",
            dao.finishes.none { it.second == TransferStatus.FAILED.name }
        )
        assertEquals(
            "记录必须停在「已暂停」",
            TransferStatus.PAUSED.name,
            dao.finishes.last().second
        )
        assertEquals(
            "还在等用户点开始，保活锁不能提前放掉",
            1,
            keepAlive.activeTransfers
        )

        // 第三步：用户点了开始，浏览器从断点接着把剩下的传完
        assertEquals(
            "继续本身要成功",
            true,
            control.applyToGate(handle.gate, TransferControl.Action.RESUME).ok
        )
        runCurrent()
        handle.succeed(100)
        runCurrent()

        assertEquals(
            "暂停时消费协程一旦退出，这一步的收尾会被静默丢弃 —— 记录就永远停在「已暂停」了",
            TransferStatus.DONE.name,
            dao.finishes.last().second
        )
        assertEquals("真正结束之后才放锁", 0, keepAlive.activeTransfers)
    }
}
