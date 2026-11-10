package com.pocketserver.data.repo

import com.pocketserver.core.AppConstants
import com.pocketserver.core.KeepAlive
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.di.ApplicationScope
import com.pocketserver.di.IoDispatcher
import com.pocketserver.perf.ThrottledProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传输记账的统一入口（FR-7）。
 *
 * ## 为什么需要它：主通道此前根本不记账
 * 传输记录原先只由「附近」页的设备互传写入（`ServerController.pushTo` / `pullFrom`）。
 * 而本产品的 P0 主通道是**电脑浏览器**[com.pocketserver.net.http.HttpFileServer]，
 * 用户最常做的动作就是把文件从电脑拖进网页 —— 这条路径此前一个字节的记录都不留。
 * 结果就是「传输」页对绝大多数真实使用场景永远显示"还没有传输记录"，
 * 而这一页存在的全部意义是"心里有底"。
 *
 * `TransferDirection` 里 `UPLOAD` / `DOWNLOAD` 两个方向、以及
 * `TransfersViewModel.directionLabel` 里"电脑上传 / 电脑下载"两条文案
 * 早就写好了，只是一直没人往里写数据 —— 缺的是接线，不是设计。
 *
 * ## 为什么句柄是非挂起的
 * HTTP 与 FTP 的处理循环都是**同步阻塞**写法（直接操作 `SocketChannel`），
 * 在那里调用挂起函数要一路改签名；而且这些循环也不该为了写一行数据库而挂起。
 * 所以 [begin] 返回一个非挂起句柄，句柄把动作丢进**每条传输一个**的队列，
 * 由一条协程串行落库 —— 顺序因此天然成立：先 insert 拿到 id，再 update，最后 finish。
 *
 * 单条队列同时解决了另一个坑：分块并行上传会让多个线程同时上报进度，
 * 直接并发写 Room 会出现"完成态先落库、进度后落库"的倒灌，
 * 界面上表现为已完成的文件进度条来回跳。串行队列从结构上排除了它。
 */
@Singleton
class TransferRecorder @Inject constructor(
    private val repo: TransferRepository,
    private val keepAlive: KeepAlive,
    private val control: TransferControl,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher
) {

    /**
     * 进度上报的最小间隔。
     *
     * 默认取 [AppConstants.PROGRESS_THROTTLE_MS]；测试会把它调成 0，
     * 否则"第一次上报必然被节流丢掉"（[ThrottledProgress] 把构造时刻当作上次上报时刻）
     * 会让断言变得莫名其妙。
     */
    internal var throttleIntervalMs: Long = AppConstants.PROGRESS_THROTTLE_MS

    /**
     * 卡死判定。`<= 0` 表示关闭。
     *
     * 测试要把某个用例调成 0（关闭）：`runTest` 里的 `advanceUntilIdle()` 会
     * **推进虚拟时间直到没有任何待执行任务**，所以只要开着超时，任何"故意不收尾"
     * 的用例都会被这个超时提前收掉，断言就看不到"进行中"的状态了。
     */
    internal var idleTimeoutMs: Long = AppConstants.TRANSFER_IDLE_TIMEOUT_MS

    /**
     * 暂停状态能挂多久。
     *
     * 暂停期间**不判**空闲超时（用户就是明确说了"先别传"，拿"没有进度"去判它中断
     * 是文不对题）。但也不能无限期挂着 —— 用户按下暂停然后关掉网页再也回来，
     * 那条记录和一个闸门就会永远留在内存里，"进行中"也永远收不了尾。
     * 半小时没有任何动静之后按结束处理，界面上给的理由要如实写"暂停超时"。
     */
    internal var pausedHoldMs: Long = PAUSED_HOLD_MS

    /**
     * 开一条传输记录，立刻返回句柄。
     *
     * 落库是异步的（[Handle] 的方法都不阻塞），所以调用方可以放心地在
     * 每个字节块里调 [Handle.report] —— 内部的节流会把它压到
     * [throttleIntervalMs] 一次。
     *
     * 这里同时**拉起后台保活的 CPU 唤醒锁**（FR-13.1）。选在这里接线的理由：
     * 五条通道（网页上传/下载、WebDAV、FTP、设备互传）里只有这一步是共同经过的，
     * 在别处接必然漏掉一两条 —— 那正是上一轮"传输记录只覆盖设备互传"的同款错误。
     *
     * 还顺手开了一道**控制闸门**（[TransferControl]）：暂停/继续/取消三个动作要生效，
     * 必须先有一个所有通道都能拿到的句柄，而这里是它们唯一的汇聚点 ——
     * 理由与保活完全相同，就不再重复接两遍。
     */
    fun begin(
        name: String,
        relPath: String,
        direction: TransferDirection,
        totalBytes: Long,
        lanes: Int = 1,
        peerName: String = "",
        peerIp: String = "",
        batchId: String = ""
    ): Handle {
        val ops = Channel<Op>(Channel.UNLIMITED)
        val gate = control.register(relPath, name, direction, totalBytes)
        keepAlive.onTransferStarted()
        scope.launch(io) { consume(ops, lanes, gate) }
        ops.trySend(Op.Begin(name, relPath, direction, totalBytes, peerName, peerIp, batchId))
        return Handle(ops, gate, throttleIntervalMs)
    }

    /**
     * 单条传输的落库协程。
     *
     * 拿到 [Op.Finish] 就退出：一条传输的生命周期到此为止，
     * 之后再来 Report（例如迟到的分片回调）会写进已关闭的通道并被静默丢弃 ——
     * 这是刻意的，否则一条已收尾的记录会被后续写入重新改回"进行中"。
     *
     * ## 为什么用"带超时的接收"而不是 `for (op in ops)`
     * 通道只有在收尾时才会被关闭，所以 `for` 循环意味着"句柄不收尾就永远挂着"。
     * 挂着的后果不只是内存：它会让保活计数永远不归零，进而把「空闲自停」永久关掉。
     * 改成带超时的接收之后，卡死的传输会在 [idleTimeoutMs] 后被判为中断收尾 ——
     * 记录状态是对的，计数也归零了。
     */
    private suspend fun consume(ops: Channel<Op>, lanes: Int, gate: TransferControl.Gate) {
        var id: Long? = null
        // 进度只允许前进。多个上报方（分片、逐块回调、收尾强推）混在一起时，
        // 写入顺序不保证单调，而界面上"进度条往回退"只会被当成卡死或 bug。
        var highWater = 0L
        try {
            while (true) {
                val op = nextOp(ops, gate)
                when (op) {
                    // 等超时了：写一条"中断"收尾再退休。
                    // 少了这一支，一次卡死的传输会永远显示"进行中"。
                    Op.Idle -> {
                        closeStaleTransfer(ops, gate, id, highWater)
                        break
                    }

                    // 通道已经关了，说明我们自己前面已经收尾过。这里什么都不要再写 ——
                    // 再写一次会把一条正常的完成记录改判成"长时间没有任何进度"。
                    Op.Closed -> break

                    is Op.Begin -> {
                        val newId = runCatching {
                            repo.create(
                                name = op.name,
                                relPath = op.relPath,
                                direction = op.direction,
                                totalBytes = op.totalBytes,
                                lanes = lanes,
                                peerName = op.peerName,
                                peerIp = op.peerIp,
                                batchId = op.batchId
                            )
                        }.getOrElse {
                            PocketLog.w(TAG, "传输记录创建失败（${op.name}）：${it.message}")
                            null
                        }
                        id = newId
                        // 主键到手，回填给闸门：手机端与网页端都是按记录 id 下指令的
                        if (newId != null) control.bindTask(gate, newId)
                    }

                    is Op.Report -> {
                        // 不用 `id?.let { ... }` 包起来：`let` 是内联 lambda，
                        // 里面既不能 `continue` 也不能 `break`（Kotlin 把"内联 lambda 里的
                        // 跳转"列为实验特性，需要显式开启）。老老实实判空更清楚，也省得踩这个坑。
                        val current = id
                        if (current != null && op.transferred > highWater) {
                            highWater = op.transferred
                            gate.transferred = highWater
                            runCatching { repo.updateProgress(current, highWater, lanes) }
                                .onFailure { PocketLog.d(TAG, "传输进度写入失败：${it.message}") }
                        }
                    }

                    is Op.Finish -> {
                        val current = id
                        if (current != null) {
                            val finalBytes = maxOf(highWater, op.transferred)
                            gate.transferred = finalBytes
                            // 状态交给 [TransferControl.settle] 写：它实现了"用户意图优先"那条规则，
                            // 也就是"按了暂停或取消之后，最终状态由用户说了算，不由这条路径的成败说了算"。
                            // 两边各写一遍的话，迟早有一边忘了判取消，症状是"按了取消，几秒后变失败"。
                            runCatching { control.settle(gate, current, finalBytes, op.error) }
                                .onFailure { PocketLog.w(TAG, "传输收尾写入失败：${it.message}") }
                        }
                        // 暂停时**不退出**。这一点是整个功能的关键：
                        // 一退出就要注销闸门，而闸门一没，网页那边就再也查不到
                        // "这条传输恢复运行了没有"，暂停就变成了一条死路。
                        // 继续留在这里等后续动作：用户点开始，浏览器会带着同一个句柄
                        // 继续上报，状态自然接着往前走。
                        if (gate.isPaused) continue
                        break
                    }
                }
            }
        } finally {
            // 无论从哪条路径出去（正常收尾、通道关闭、超时、协程被取消），
            // 保活计数都必须在这里减掉 —— 只减一次。
            keepAlive.onTransferFinished()
            control.unregister(gate)
            ops.close()
        }
    }

    /**
     * 等下一个动作。
     *
     * 三种可能都必须能分辨，因为它们的处理完全不同：
     *  - 一个具体的 [Op]：正常路径；
     *  - [Op.Idle]：等超时了 —— 要写一条"中断"收尾，否则记录永远停在"进行中"；
     *  - [Op.Closed]：通道已经关了（我们自己收尾过）—— 什么都不该写。
     *
     * 这里刻意不去查 `isClosedForReceive`：它是 delicate API（读到的值与读取时机有关，
     * 编译器会一直告警），而且它回答的是"此刻通道关没关"，并不是
     * "刚才那次接收为什么什么都没拿到"。用一个哨兵值把答案带出来，语义是确定的。
     */
    private suspend fun nextOp(ops: Channel<Op>, gate: TransferControl.Gate): Op {
        val timeoutMs = when {
            idleTimeoutMs <= 0L -> 0L

            // 取消之后**保留一段时间**的闸门，叫它墓碑期。
            // 用处是挡住迟到的分片：此刻浏览器可能还有一两个 PUT 在飞，
            // 闸门一注销它们就会因为"没有闸门"而被当成全新上传，把刚删掉的文件重新写一遍。
            gate.isCanceled -> CANCEL_TOMBSTONE_MS

            // 暂停期间不判"没有进度"。用户已经明确说了先别传，拿没有进度去判它中断是文不对题。
            // 但仍给一个上限：按下暂停就再也没回来的话，记录不该永远停在"进行中"。
            gate.isPaused -> maxOf(pausedHoldMs, idleTimeoutMs)

            else -> idleTimeoutMs
        }

        if (timeoutMs <= 0L) {
            // 超时是关着的（测试用）：这时 receiveCatching 只会在通道被关时才返回 null
            return ops.receiveCatching().getOrNull() ?: Op.Closed
        }
        // 块里永远不会返回 null，所以外层这个 null 只可能来自"超时"
        return withTimeoutOrNull(timeoutMs) {
            ops.receiveCatching().getOrNull() ?: Op.Closed
        } ?: Op.Idle
    }

    /**
     * 卡死的传输收尾。
     *
     * 只改记录，不碰 socket —— socket 归各通道自己的循环管，
     * 在这里强关它对端会看到一个莫名其妙的连接重置。记录如实写"中断"就够了：
     * 用户在「传输」页看到的是"失败 / 长时间没有进度"，而不是永远转圈。
     */
    private suspend fun closeStaleTransfer(
        ops: Channel<Op>,
        gate: TransferControl.Gate,
        id: Long?,
        highWater: Long
    ) {
        val current = id
        if (current != null) {
            val reason = when {
                gate.isCanceled -> {
                    PocketLog.i(TAG, "取消的墓碑期结束，闸门可以退休了")
                    null
                }

                gate.isPaused -> {
                    PocketLog.w(TAG, "暂停已超过 ${pausedHoldMs / 60_000} 分钟，判定为放弃")
                    "暂停后一直没有继续，已结束"
                }

                else -> {
                    PocketLog.w(TAG, "传输超过 ${idleTimeoutMs / 1000} 秒没有任何进度，判定为中断")
                    "长时间没有任何进度，已中断"
                }
            }
            // reason 为 null 表示"用户已经取消了"，这时只把状态再确认一遍 ——
            // 不能写 FAILED，否则用户按下的取消会在几分钟后被一条"失败"覆盖掉。
            runCatching {
                when {
                    gate.isCanceled -> repo.cancel(current, highWater)
                    reason != null -> repo.fail(current, highWater, reason)
                    else -> Unit
                }
            }.onFailure { PocketLog.w(TAG, "卡死收尾写入失败：${it.message}") }
        }
        // 迟到的 Finish 会进一个已关闭的通道并被静默丢弃，不会再改这条记录
        ops.close()
    }

    /**
     * 一条传输的句柄。全部方法都是非挂起且线程安全的，可从任意线程调用。
     *
     * 收尾只会生效一次：`Finish` 之后消费协程即退出，
     * 因此"分块上传里两个分片同时判定完成"这类竞态天然无害。
     */
    class Handle internal constructor(
        private val ops: Channel<Op>,
        /** 这条传输的控制闸门。各通道的 IO 循环靠它判断"还要不要继续搬"。 */
        internal val gate: TransferControl.Gate,
        throttleIntervalMs: Long
    ) {

        private val throttle = ThrottledProgress(throttleIntervalMs) { transferred ->
            ops.trySend(Op.Report(transferred))
        }

        /**
         * 上报"到目前为止已传字节数"。
         *
         * 传**累计值**而不是增量：界面显示的是进度百分比，
         * 累计值与 `totalBytes` 才能直接相除；用增量还要在服务端维护累加器，
         * 而累加器在分块并发下正是最容易错的地方。
         */
        fun report(transferred: Long) {
            throttle.update(transferred)
        }

        /** 正常结束。 */
        fun succeed(transferred: Long) {
            // 先强推一次最终进度，避免"完成态显示的字节数小于实际传输量"
            throttle.force(transferred)
            ops.trySend(Op.Finish(transferred, null))
        }

        /** 失败结束。[reason] 会原样显示在传输卡片上，所以要是人话。 */
        fun fail(transferred: Long, reason: String) {
            ops.trySend(Op.Finish(transferred, reason))
        }
    }

    /** 内部动作。`internal` 而非 `private`：[Handle] 的构造函数需要引用它。 */
    internal sealed class Op {

        /**
         * 等超时了，什么都没等到。
         *
         * 之所以要单独一个值：这一支**必须**写一条"中断"收尾，
         * 否则一次卡死的传输会永远显示"进行中"。
         */
        object Idle : Op()

        /**
         * 通道已经关了。
         *
         * 与 [Idle] 必须分开：这条路径说明我们自己前面已经收尾过，
         * 再写一次就会把一条正常的完成记录改判成"长时间没有任何进度"。
         */
        object Closed : Op()

        class Begin(
            val name: String,
            val relPath: String,
            val direction: TransferDirection,
            val totalBytes: Long,
            val peerName: String,
            val peerIp: String,
            val batchId: String
        ) : Op()

        class Report(val transferred: Long) : Op()

        class Finish(val transferred: Long, val error: String?) : Op()
    }

    private companion object {
        const val TAG = "TransferRecorder"

        /** 暂停能挂多久：半小时没有任何动静就按结束处理。 */
        const val PAUSED_HOLD_MS = 30 * 60 * 1000L

        /**
         * 取消之后闸门还要站多久的岗。
         *
         * 一分钟足够覆盖"浏览器刚发出的分片 + 一次退避重试"。
         * 再长也没意义：客户端的重试窗口只有几秒，而留着闸门会占一条内存记录。
         */
        const val CANCEL_TOMBSTONE_MS = 60 * 1000L
    }
}
