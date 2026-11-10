package com.pocketserver.data.repo

import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.di.ApplicationScope
import com.pocketserver.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 用户对一条传输下达的意图。它是"用户想让它怎样"，而不是"它现在怎样"。 */
enum class TransferIntent { RUNNING, PAUSED, CANCELED }

/**
 * 传输被用户取消。**不是错误** —— 它是正常流程，所以各执行路径必须单独接住它，
 * 不能让它走"失败"那条路（否则用户主动取消的传输会在记录里显示成红色失败）。
 */
class TransferCanceledException(message: String) : Exception(message)

/**
 * 传输控制中心（FR-7 的暂停 / 继续 / 取消）。
 *
 * ## 为什么必须做成"服务端唯一的权威状态"
 * 这个产品里传输的执行者有两个，而且**不在同一个进程里**：
 *  - 手机自己发起的设备互传（PUSH / PULL）—— 执行者是本应用的协程；
 *  - 电脑浏览器驱动的上传下载 —— 执行者是**别人的浏览器**，我们够不着它。
 *
 * 如果"暂停"只是各自界面里的一份局部状态，就会出现最荒谬的那种情形：
 * 在手机上暂停了浏览器正在上传的文件，手机显示"已暂停"，而电脑那边依然在传，
 * 传输页的进度条继续往上走。用户会立刻得出"这按钮是假的"的结论。
 *
 * 所以控制状态的存放点只能有一个：**服务端**。两端界面都只是它的客户端 ——
 * 手机上按的暂停，会真的把电脑那条连接掐掉；电脑轮询到服务端的意图恢复成
 * 运行，就自己从断点接着传。
 *
 * ## 为什么按路径而不是按任务 id 建索引
 * 任务是异步落库的：`TransferRecorder.begin()` 返回句柄时，主键还不存在
 * （它由写入协程稍后从数据库拿）。而 HTTP 处理循环在任何时刻**只知道路径**。
 * 若以任务 id 为主键，就会出现"上传刚开始的几毫秒里控制查询查不到东西"的窗口，
 * 暂停按钮偶尔失灵。所以路径是主键，任务 id 只是回填出来的副索引。
 *
 * ## 同步与异步两套查询，各有各的用处
 *  - [Gate.halted] 是**同步**的：IO 循环在每一个 8KB 缓冲区之间都要问一次
 *    "还要不要继续"，这里不能有挂起、也不该分配对象。
 *  - [Gate.checkpoint] 是**挂起**的：它用在分片边界上，暂停时真的把协程停住
 *    （连接保持、TCP 窗口自然收缩），继续时从原地接着走。
 */
@Singleton
class TransferControl @Inject constructor(
    private val repo: TransferRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    /** 用户能下达的三个动作。 */
    enum class Action { PAUSE, RESUME, CANCEL }

    /** 动作结果。[message] 直接展示给用户，所以必须是能照做的事，不是错误码。 */
    data class Result(val ok: Boolean, val message: String)

    /**
     * 一条传输的控制闸门，同时兼作它的**任务视图**。
     *
     * 进度这类会频繁变化的值用 `@Volatile` 裸变量而不是 StateFlow：
     * 它们只在被查询时读一次（网页 2 秒轮询一次），为它们建 Flow 只是白建订阅。
     * 只有 [intent] 是 Flow —— 界面的按钮要跟着它变。
     */
    class Gate internal constructor(
        val path: String,
        val name: String,
        val direction: TransferDirection,
        val totalBytes: Long
    ) {

        private val _intent = MutableStateFlow(TransferIntent.RUNNING)
        val intent: StateFlow<TransferIntent> = _intent.asStateFlow()

        /** 任务主键。异步回填，回填前为 0。 */
        @Volatile
        var taskId: Long = 0L

        /** 已传字节数，由记账层顺路喂进来。 */
        @Volatile
        var transferred: Long = 0L

        @Volatile
        var note: String? = null

        @Volatile
        var at: Long = System.currentTimeMillis()

        val isPaused: Boolean get() = _intent.value == TransferIntent.PAUSED
        val isCanceled: Boolean get() = _intent.value == TransferIntent.CANCELED

        /**
         * 是否应当停止一切数据搬运。**同步**、无分配，可以放在每块缓冲的循环条件里。
         */
        val halted: Boolean get() = _intent.value != TransferIntent.RUNNING

        internal fun intentTo(next: TransferIntent) {
            _intent.value = next
        }

        /**
         * 分片边界的检查点。
         *
         * 暂停时在这里挂起 —— 挂起而不是"断开重连"，是因为局域网传输重新建连
         * 要在对端再走一遍占位、权限、取名，代价远高于让一个协程多等一会儿。
         * 取消时抛 [TransferCanceledException]：各执行路径必须把它与真正的失败分开处理。
         */
        suspend fun checkpoint() {
            when (_intent.value) {
                TransferIntent.RUNNING -> return
                TransferIntent.CANCELED -> throw TransferCanceledException("传输已取消")
                TransferIntent.PAUSED -> {
                    _intent.first { it != TransferIntent.PAUSED }
                    // 醒来后要再判一次：暂停与取消可能被连着按下，只判一次会漏掉取消
                    if (_intent.value == TransferIntent.CANCELED) {
                        throw TransferCanceledException("传输已取消")
                    }
                }
            }
        }

        internal fun snapshot(): LiveTransfer = LiveTransfer(
            taskId = taskId,
            path = path,
            name = name,
            direction = direction,
            intent = _intent.value,
            transferred = transferred,
            totalBytes = totalBytes
        )
    }

    /** 给界面看的快照。用 data class 而不是直接暴露 [Gate]：Compose 只认不可变值。 */
    data class LiveTransfer(
        val taskId: Long,
        val path: String,
        val name: String,
        val direction: TransferDirection,
        val intent: TransferIntent,
        val transferred: Long,
        val totalBytes: Long
    ) {
        val percent: Int
            get() = if (totalBytes > 0) {
                ((transferred * 100) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }

    private val byPath = ConcurrentHashMap<String, Gate>()
    private val byTask = ConcurrentHashMap<Long, Gate>()

    private val _live = MutableStateFlow<List<LiveTransfer>>(emptyList())

    /** 当前**可被控制**的传输（进行中或已暂停）。历史记录不在这里，它们在数据库里。 */
    val live: StateFlow<List<LiveTransfer>> = _live.asStateFlow()

    // ==================== 注册 ====================

    /**
     * 开一条传输的控制闸门。
     *
     * 同一个路径重复注册时**替换**旧闸门：那意味着用户重新挑了同名文件
     * （`reserve` 会再发一次），旧的那次尝试已经被 [`reset`] 收尾，
     * 不该再留着它去拦新上传 —— 否则会出现"刚拖进去的文件显示已暂停"这种幽灵状态。
     */
    fun register(
        path: String,
        name: String,
        direction: TransferDirection,
        totalBytes: Long
    ): Gate {
        val gate = Gate(path, name, direction, totalBytes)
        byPath[path] = gate
        refresh()
        return gate
    }

    /** 记账层拿到主键后回填，之后手机端就能按记录 id 控制这条传输了。 */
    fun bindTask(gate: Gate, taskId: Long) {
        if (taskId <= 0L) return
        gate.taskId = taskId
        byTask[taskId] = gate
        refresh()
    }

    /**
     * 注销闸门。
     *
     * 用**两参数版** remove（值相等才删）：同一条路径上可能已经注册了新的闸门，
     * 而旧闸门的消费协程此刻才走到收尾。单参数 remove 会把新闸门一起删掉，
     * 症状是"新上传刚开始就按不动暂停了"。
     */
    fun unregister(gate: Gate) {
        byPath.remove(gate.path, gate)
        if (gate.taskId > 0L) byTask.remove(gate.taskId, gate)
        refresh()
    }

    /**
     * 把闸门复位成运行态，**不写数据库**。
     *
     * 只有一个调用点：[com.pocketserver.net.http.HttpFileServer] 的 reserve。
     * 用户重新拖进同一个文件，等于用行动说了"我还在弄它"，此前按下的暂停
     * （那已经是上一轮的事了）不该继续拦着。
     */
    fun reset(path: String) {
        val gate = byPath[path] ?: return
        if (gate.intent.value != TransferIntent.RUNNING) {
            PocketLog.i(TAG, "重新占位复位了此前的暂停/取消：${gate.name}")
            gate.intentTo(TransferIntent.RUNNING)
            refresh()
        }
    }

    // ==================== 查询 ====================

    fun gateFor(path: String): Gate? = byPath[path]

    fun gateForTask(taskId: Long): Gate? = byTask[taskId]

    /** 供 IO 循环的同步查询。没有闸门 = 没人管它 = 放行。 */
    fun haltedFor(path: String): Boolean = byPath[path]?.halted == true

    fun intentFor(path: String): TransferIntent =
        byPath[path]?.intent?.value ?: TransferIntent.RUNNING

    fun liveTransfers(): List<LiveTransfer> = _live.value

    // ==================== 动作 ====================

    /**
     * 按传输记录的主键下达动作。
     *
     * 找不到闸门时的回答因动作而异，这个区别很重要：
     *  - 暂停 / 继续需要一个还在跑的搬运工，没有就是"已经开始不了"；
     *  - 取消不需要 —— 它就是用来对付"没有搬运工却还挂在进行中"的那种僵尸记录，
     *    所以这种情况下仍然把数据库里的记录收尾掉。
     */
    suspend fun apply(taskId: Long, action: Action): Result {
        val gate = byTask[taskId]
        if (gate == null) return applyWithoutGate(taskId, action)
        return applyToGate(gate, action)
    }

    /**
     * 对已知闸门下达动作。
     *
     * 网页端用它：那边只有路径（任务 id 要等落库回填，可能还没到），
     * 而路径能直接在注册表里查到闸门，比等 id 更快也更可靠。
     */
    suspend fun applyToGate(gate: Gate, action: Action): Result {
        val taskId = gate.taskId
        return when (action) {
            Action.PAUSE -> {
                if (gate.isPaused) {
                    Result(true, "这条传输已经是暂停状态")
                } else if (gate.isCanceled) {
                    Result(false, "这条传输正在取消，不能暂停")
                } else {
                    gate.intentTo(TransferIntent.PAUSED)
                    gate.note = "已暂停"
                    refresh()
                    // 立刻写库，不等执行者走到收尾：执行者可能正卡在一个 64MB 的分片里，
                    // 等它到边界要好几秒，而用户按下按钮后要的是立刻看到状态变化。
                    if (taskId > 0L) {
                        repo.mark(taskId, TransferStatus.PAUSED, gate.transferred, "用户暂停", null)
                    }
                    Result(true, "已暂停「${gate.name}」，进度不会丢")
                }
            }

            Action.RESUME -> {
                if (!gate.isPaused) {
                    Result(true, "这条传输本来就在进行")
                } else {
                    gate.intentTo(TransferIntent.RUNNING)
                    gate.note = null
                    refresh()
                    // 状态先写回进行中。若此刻执行者其实已经不在（例如网页被关掉），
                    // 记录会挂着"进行中"直到空闲超时把它判为中断 —— 这是刻意的：
                    // 宁可过一段时间如实说"没进度了"，也不要停在"已暂停"骗用户说还能继续。
                    if (taskId > 0L) {
                        repo.mark(taskId, TransferStatus.RUNNING, gate.transferred, null, null)
                    }
                    Result(true, "已继续「${gate.name}」")
                }
            }

            Action.CANCEL -> {
                gate.intentTo(TransferIntent.CANCELED)
                gate.note = "已取消"
                refresh()
                if (taskId > 0L) repo.cancel(taskId, gate.transferred)
                Result(true, "已取消「${gate.name}」")
            }
        }
    }

    /**
     * 没有闸门时按记录 id 直接处理。
     *
     * 这里刻意不做"查不到就不管"：用户看到的是一张写着"传输中"的卡片，
     * 他要的是把它从列表里清掉，这个诉求与"有没有搬运工"无关。
     */
    private suspend fun applyWithoutGate(taskId: Long, action: Action): Result = when (action) {
        Action.PAUSE, Action.RESUME -> Result(
            false,
            "这条传输已经不在进行中了，暂停和继续都不再适用。"
        )

        Action.CANCEL -> {
            repo.cancel(taskId, 0L)
            Result(true, "已取消这条记录")
        }
    }

    /**
     * 收尾时问一次"用户当初想让它怎样"。
     *
     * [`com.pocketserver.data.repo.TransferRecorder`] 在写最终状态前会调用它。
     * 规则只有一条，但它决定了整个功能对不对：
     *
     * > **一旦用户按了暂停或取消，这条传输的状态就由用户意图决定，
     * >  不再由执行路径的成败决定。**
     *
     * 不这么做的话，用户按下取消之后，浏览器那条连接断开会让执行者写一个
     * FAILED 进去，界面上就从"已取消"跳回"失败"——用户会以为取消没生效。
     */
    fun intentOf(gate: Gate?): TransferIntent = gate?.intent?.value ?: TransferIntent.RUNNING

    /**
     * 按"用户意图优先"的规则写最终状态。
     *
     * 这条规则只有这一处实现，因为有两个调用方：[TransferRecorder]（网页上传下载、
     * FTP）与 [com.pocketserver.service.ServerController]（设备互传）。
     * 两边各写一遍的话，迟早有一边忘了判取消 —— 症状就是"按了取消，几秒后变失败"。
     *
     * 返回值是最终落库的状态，调用方用它决定给用户什么文案。
     */
    suspend fun settle(gate: Gate?, id: Long, transferred: Long, error: String?): TransferStatus {
        val intent = intentOf(gate)
        return when (intent) {
            TransferIntent.CANCELED -> {
                repo.cancel(id, transferred)
                TransferStatus.CANCELED
            }

            TransferIntent.PAUSED -> {
                repo.mark(id, TransferStatus.PAUSED, transferred, "用户暂停", null)
                TransferStatus.PAUSED
            }

            TransferIntent.RUNNING ->
                if (error == null) {
                    repo.succeed(id, transferred)
                    TransferStatus.DONE
                } else {
                    repo.fail(id, transferred, error)
                    TransferStatus.FAILED
                }
        }
    }

    /**
     * 取消之后顺手丢掉残片。
     *
     * 只删**没传完**的：用户可能在 100% 那一刻才按取消，那时文件已经完整落盘，
     * 删掉就等于毁掉一个完好的文件。判据用会话的完成度而不是文件大小 ——
     * 分块上传会预分配，磁盘大小在第一个分片到达时就已经等于整个文件了。
     *
     * 延迟几秒再删是必要的：此刻可能还有分片在飞，它会把刚删掉的文件重新创建出来。
     * 各通道的 IO 循环每个缓冲区都会查一次闸门，几毫秒内就会停，
     * 所以几秒后这一刀必然落在最后。
     */
    fun scheduleDiscard(path: String, complete: Boolean, discard: suspend (String) -> Unit) {
        if (complete) {
            PocketLog.i(TAG, "取消的是已传完的文件，保留它：${path.substringAfterLast('/')}")
            return
        }
        scope.launch(io) {
            kotlinx.coroutines.delay(DISCARD_DELAY_MS)
            runCatching { discard(path) }
                .onFailure { PocketLog.w(TAG, "清理残片失败（稍后会由清洁器收走）：${it.message}") }
        }
    }

    private fun refresh() {
        _live.value = byPath.values
            .sortedByDescending { it.at }
            .map { it.snapshot() }
    }

    private companion object {
        const val TAG = "TransferControl"

        /** 等所有在飞的分片停下来再删残片。 */
        const val DISCARD_DELAY_MS = 3_000L
    }
}
