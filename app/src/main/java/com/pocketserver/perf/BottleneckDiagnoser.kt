package com.pocketserver.perf

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.Bottleneck
import com.pocketserver.data.model.SpeedTestReport
import com.pocketserver.data.model.StorageMode
import com.pocketserver.data.model.WifiBand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/** 测速阶段（用于 UI 显示"正在测第几项"）。 */
data class BenchStage(val name: String, val index: Int, val total: Int)

/**
 * 端到端探针：由**对端**下载一段内存数据并回报速率。
 *
 * V1.0 不启用（需要网页端配合，且要等一个客户端在线）；
 * 这是 V1.5 的接入点 —— 有了它，[SpeedTestReport.networkMBps] 就能从"由协商速率推算"
 * 升级为"真实空中速率实测"，诊断精度会更高。此处保留接口而不是留 TODO，
 * 是为了让上层调用方从第一天起就把这个参数传进来。
 */
fun interface EndToEndProbe {
    suspend fun measureMBps(): Double
}

/**
 * 瓶颈诊断器（FR-12.14 内建测速 / FR-12.15 瓶颈判定）。
 *
 * 这是本产品相对同类工具最明显的差异化能力：用户说"慢"的时候，
 * 我们能回答**慢在哪一环**，而不是回一句"请检查网络"。
 *
 * 四段测量：
 *  1. **应用层上限**：内存造数据 → 本机回环 SocketChannel，排除网络与磁盘。
 *     它回答"是不是我们代码的问题"。
 *  2. **磁盘上限**：顺序写 + fsync 临时文件。用写而不是读，因为读会命中页缓存，
 *     会把慢速 U 盘测成 400MB/s，从而把诊断结论带偏。
 *  3. **发送通路上限**：真实文件 → 本机回环，走生产的零拷贝发送路径。
 *  4. **无线链路上限**：由协商速率换算（见 [WifiLink.effectiveCeilingMBps]）。
 *
 * 判定规则刻意简单可解释：
 *  - 三个上限里最小的那一个，就是瓶颈所在；
 *  - 若最小上限已达目标（55MB/s），说明链路健康，不下"有问题"的结论；
 *  - 若发送通路明显低于其余上限，说明问题在我们自己这边，报应用层。
 */
class BottleneckDiagnoser(
    private val bufferPool: BufferPool,
    private val zeroCopySender: ZeroCopySender,
    private val cacheDir: File,
    private val profile: ThroughputProfile,
    private val linkInspector: WifiLinkInspector,
    private val dispatcher: CoroutineDispatcher
) {

    /** 达标线：需规 §5.12 的 Wi-Fi 5GHz 单文件目标。 */
    private val targetMBps = 55.0

    suspend fun run(
        storageMode: StorageMode,
        endToEndProbe: EndToEndProbe? = null,
        onStage: (BenchStage) -> Unit = {}
    ): SpeedTestReport = withContext(dispatcher) {
        val link = linkInspector.inspect()
        val stages = listOf("应用层通路", "磁盘", "发送通路", "端到端")
        var stageIndex = 0

        fun nextStage() {
            onStage(BenchStage(stages[stageIndex], stageIndex + 1, stages.size))
            stageIndex++
        }

        val appCeiling = runCatching {
            nextStage()
            measureAppCeiling()
        }.getOrElse {
            PocketLog.w(TAG, "应用层测速失败: ${it.message}")
            0.0
        }

        val diskCeiling = runCatching {
            nextStage()
            measureDiskCeiling()
        }.getOrElse {
            PocketLog.w(TAG, "磁盘测速失败: ${it.message}")
            0.0
        }

        val sendPath = runCatching {
            nextStage()
            measureSendPath()
        }.getOrElse {
            PocketLog.w(TAG, "发送通路测速失败: ${it.message}")
            0.0
        }

        nextStage()
        val probed = endToEndProbe?.let {
            runCatching { it.measureMBps() }.getOrElse { 0.0 }
        } ?: 0.0

        val wirelessCeiling = link.effectiveCeilingMBps()
        // 纯网络上限：优先用真实空中实测；其次由协商速率换算；都没有则退化为本机发送通路
        val networkCeiling = when {
            probed > 0.0 -> probed
            wirelessCeiling > 0.0 -> wirelessCeiling
            else -> sendPath
        }
        val endToEnd = if (wirelessCeiling > 0.0) min(sendPath, wirelessCeiling) else sendPath

        val appBound = if (sendPath > 0.0) min(appCeiling, sendPath) else appCeiling
        val verdict = classify(
            network = networkCeiling,
            disk = diskCeiling,
            app = appBound,
            endToEnd = endToEnd
        )

        SpeedTestReport(
            networkMBps = round1(networkCeiling),
            diskMBps = round1(diskCeiling),
            endToEndMBps = round1(endToEnd),
            appCeilingMBps = round1(appCeiling),
            bottleneck = verdict,
            wifiBand = link.band,
            linkSpeedMbps = link.linkSpeedMbps,
            storageMode = storageMode,
            advice = buildAdvice(verdict, link, storageMode, diskCeiling, sendPath, endToEnd)
        ).also {
            PocketLog.i(
                TAG,
                "测速完成：网络 ${it.networkMBps} / 磁盘 ${it.diskMBps} / 端到端 ${it.endToEndMBps} MB/s，判定 ${it.bottleneck}"
            )
        }
    }

    // ==================== 判定 ====================

    private fun classify(network: Double, disk: Double, app: Double, endToEnd: Double): Bottleneck {
        val candidates = listOfNotNull(
            network.takeIf { it > 0.0 }?.let { Bottleneck.WIRELESS_LINK to it },
            disk.takeIf { it > 0.0 }?.let { Bottleneck.DISK_IO to it },
            app.takeIf { it > 0.0 }?.let { Bottleneck.APP_LAYER to it }
        )
        if (candidates.isEmpty()) return Bottleneck.UNKNOWN

        val (weakest, weakestValue) = candidates.minBy { it.second }

        // 最小上限本身就达标 → 整条链路健康，不制造焦虑
        if (weakestValue >= targetMBps) return Bottleneck.NONE

        // 端到端远低于最弱上限：说明有个环节没跑到应有的水平，而不是"天花板就在这"
        if (endToEnd > 0 && endToEnd < weakestValue * 0.5) return Bottleneck.APP_LAYER

        return weakest
    }

    // ==================== 文案（必须是"人话"） ====================

    private fun buildAdvice(
        verdict: Bottleneck,
        link: WifiLink,
        storageMode: StorageMode,
        disk: Double,
        sendPath: Double,
        endToEnd: Double
    ): String {
        val head = "网络上限 ${fmt(networkOrDefault(link, sendPath))}，磁盘上限 ${fmt(disk)}，实际速度 ${fmt(endToEnd)}。"

        return when (verdict) {
            Bottleneck.NONE ->
                "$head 链路状态良好，已经达到本产品的目标速度，无需处理。"

            Bottleneck.WIRELESS_LINK ->
                buildString {
                    append(head)
                    append("瓶颈位于无线链路。")
                    when (link.band) {
                        WifiBand.BAND_2_4 -> append(
                            "当前是 2.4G 网络（协商 ${link.linkSpeedMbps}Mbps）。" +
                                "换连 5G 那个 Wi-Fi 名称后，同类文件预计可到 40MB/s 以上。"
                        )

                        WifiBand.BAND_5 -> append(
                            "当前已是 5G 网络（协商 ${link.linkSpeedMbps}Mbps），已接近该频段的实际上限。" +
                                "想更快需要路由器支持 Wi-Fi 6 并把路由与设备放在同一房间。"
                        )

                        WifiBand.ETHERNET -> append("当前走有线网络，若速度仍偏低请检查网线与交换机是否为千兆。")

                        WifiBand.UNKNOWN -> append("无法读取频段信息，建议手动连接 5G 频段的 Wi-Fi 后重测。")
                    }
                    append("应用层本身工作正常。")
                }

            Bottleneck.DISK_IO ->
                buildString {
                    append(head)
                    append("瓶颈位于存储。")
                    if (disk < 30) {
                        append("当前存储介质顺序写只有 ${fmt(disk)}，像是外接 U 盘或 USB 2.0 接口。")
                        append("把共享目录改到机身内置存储后重试，速度会明显改善。")
                    } else {
                        append("存储写入速率偏低，可能正被其他应用占用。关闭后台下载类应用后重测。")
                    }
                }

            Bottleneck.APP_LAYER ->
                buildString {
                    append(head)
                    append("瓶颈位于应用层。")
                    if (storageMode == StorageMode.SAF) {
                        append("当前通过系统文件框架访问文件，每次读写都要跨进程。")
                        append("在设置里授予「所有文件访问权限」后重试，预计可提升约 15%。")
                    } else {
                        append("网络与存储都有余量，但实际速度没跑起来。")
                        append("请把并行路数恢复为「自动（推荐）」，并确认没有同时开着多个大文件任务。")
                    }
                }

            Bottleneck.UNKNOWN ->
                "$head 测速未能得出确定结论，建议重新测一次；若反复如此请导出诊断报告。"
        }
    }

    private fun networkOrDefault(link: WifiLink, sendPath: Double): Double {
        val ceiling = link.effectiveCeilingMBps()
        return if (ceiling > 0.0) ceiling else sendPath
    }

    private fun fmt(v: Double): String =
        if (v <= 0) "—" else if (v < 1) "${(v * 1024).toInt()}KB/s" else String.format(Locale.ROOT, "%.1fMB/s", v)

    private fun round1(v: Double): Double = if (v <= 0) 0.0 else (v * 10).toInt() / 10.0

    // ==================== 测量实现 ====================

    /** 内存 → 回环 socket。排除磁盘与真实网络，衡量用户态拷贝路径与系统调用开销。 */
    private fun measureAppCeiling(): Double =
        loopbackThroughput(AppConstants.BENCH_NETWORK_BYTES) { socket, buffer ->
            var sent = 0L
            val total = AppConstants.BENCH_NETWORK_BYTES
            while (sent < total) {
                buffer.rewind()
                sent += writeFully(socket, buffer)
            }
            sent
        }

    /** 真实文件 → 回环 socket，走生产的零拷贝路径。 */
    private fun measureSendPath(): Double {
        val file = File(cacheDir, "ps_bench_send.tmp")
        val bytes = AppConstants.BENCH_E2E_BYTES
        try {
            writeTempFile(file, bytes)
            return loopbackThroughput(bytes) { socket, _ ->
                // 用 FileInputStream 的 channel，避开 java.nio.file（minSdk 24 脱糖覆盖不全）
                java.io.FileInputStream(file).channel.use { ch ->
                    zeroCopySender.sendFile(ch, 0, bytes, socket)
                }
            }        } finally {
            file.delete()
        }
    }

    /**
     * 顺序写 + fsync。
     *
     * 必须 fsync：不 fsync 时数据只写到页缓存，慢速 U 盘也能测出几百 MB/s，
     * 诊断就会得出"磁盘没问题"的错误结论，把用户引到错误方向。
     * 读方向刻意不测——读缓存命中率不可控，测出来的数字不可信。
     */
    private fun measureDiskCeiling(): Double {
        val file = File(cacheDir, "ps_bench_disk.tmp")
        val bytes = AppConstants.BENCH_DISK_BYTES
        val block = ByteArray(bufferPool.bufferSizeBytes) { (it % 251).toByte() }
        try {
            val start = System.nanoTime()
            FileOutputStream(file, false).use { out ->
                var written = 0L
                while (written < bytes) {
                    out.write(block)
                    written += block.size
                }
                out.flush()
                out.fd.sync()
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            return if (elapsed <= 0) 0.0 else bytes / elapsed / 1024 / 1024
        } finally {
            file.delete()
        }
    }

    private fun writeTempFile(file: File, bytes: Long) {
        val block = ByteArray(bufferPool.bufferSizeBytes) { (it % 251).toByte() }
        FileOutputStream(file, false).use { out ->
            var written = 0L
            while (written < bytes) {
                out.write(block)
                written += block.size
            }
            out.flush()
        }
    }

    /**
     * 回环吞吐测量骨架。
     *
     * 用独立的 reader 线程 + 阻塞 SocketChannel：写端必须有人读，否则 TCP 窗口满就卡住。
     * 回环地址固定 127.0.0.1，不经过真实网卡 —— 这正是我们要的：把网络变量剔除掉。
     */
    private fun loopbackThroughput(
        totalBytes: Long,
        producer: (SocketChannel, ByteBuffer) -> Long
    ): Double {
        val server = ServerSocketChannel.open()
        val sink = SocketChannel.open()
        var reader: Thread? = null
        try {
            server.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            val port = (server.localAddress as InetSocketAddress).port
            sink.configureBlocking(true)
            sink.socket().tcpNoDelay = true
            sink.socket().sendBufferSize = profile.socketBufferBytes
            sink.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port))

            val accepted = server.accept()
            accepted.configureBlocking(true)
            accepted.socket().tcpNoDelay = true
            accepted.socket().receiveBufferSize = profile.socketBufferBytes

            val consumed = java.util.concurrent.atomic.AtomicLong(0)
            val readerError = AtomicReference<Throwable?>(null)
            reader = Thread({
                val buf = bufferPool.acquire()
                try {
                    while (consumed.get() < totalBytes) {
                        buf.clear()
                        val n = accepted.read(buf)
                        if (n < 0) break
                        consumed.addAndGet(n.toLong())
                    }
                } catch (t: Throwable) {
                    readerError.set(t)
                } finally {
                    bufferPool.release(buf)
                    runCatching { accepted.close() }
                }
            }, "ps-bench-reader").apply { isDaemon = true }

            val data = bufferPool.acquire()
            data.clear()
            val pattern = ByteArray(data.capacity()) { (it % 251).toByte() }
            data.put(pattern)
            data.flip()

            reader.start()
            val start = System.nanoTime()
            val sent = producer(sink, data)
            val elapsed = (System.nanoTime() - start) / 1_000_000_000.0
            bufferPool.release(data)

            reader.join(READER_JOIN_TIMEOUT_MS)
            readerError.get()?.let { PocketLog.d(TAG, "回环读端异常: ${it.message}") }

            if (sent <= 0 || elapsed <= 0) return 0.0
            return sent / elapsed / 1024 / 1024
        } finally {
            runCatching { sink.close() }
            runCatching { server.close() }
            reader?.interrupt()
        }
    }

    private fun writeFully(socket: SocketChannel, buffer: ByteBuffer): Int {
        var written = 0
        while (buffer.hasRemaining()) {
            val n = socket.write(buffer)
            if (n <= 0) {
                Thread.yield()
                continue
            }
            written += n
        }
        return written
    }

    private companion object {
        const val TAG = "BottleneckDiagnoser"
        const val READER_JOIN_TIMEOUT_MS = 3_000L
    }
}
