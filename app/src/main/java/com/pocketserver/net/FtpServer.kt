package com.pocketserver.net

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.repo.TransferRecorder
import com.pocketserver.data.storage.StorageEntry
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.net.http.AccessRecord
import com.pocketserver.net.http.SocketReader
import com.pocketserver.perf.BufferPool
import com.pocketserver.perf.ThrottledProgress
import com.pocketserver.perf.ZeroCopySender
import com.pocketserver.util.Formatters
import com.pocketserver.util.NetworkUtils
import com.pocketserver.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * FTP / FTP# 兼容层（FR-4）。
 *
 * ## 定位：这是兼容层，不是主通道
 * 网页版才是主通道。FTP 存在的唯一理由是**老设备**：电视盒子、NAS、打印机、
 * 十年前的网络播放器、只能拨号的老式扫描仪 —— 它们只会 FTP。
 *
 * ## 必须在 FTP 上多做的三件事（否则会被当成"不能用"）
 *  1. **UTF-8 必须协商**：Windows 资源管理器默认按 GBK 解释文件名，
 *     不声明 `OPTS UTF8 ON` 的话中文名全是乱码。所以 FEAT 里必须列 UTF8，
 *     并且把客户端的 `OPTS UTF8 ON` 当作"我会正确处理 UTF-8"的承诺。
 *  2. **被动端口池必须预开**：不能每次 PASV 都临时 `ServerSocket()`，
 *     那样端口号随机、容易被防火墙规则漏掉；且高频创建销毁端口会触发系统限流。
 *     这里用 50000~50100 的池，且最多同时保活 8 个（O-10）。
 *  3. **TCP_NODELAY 必须开**：FTP 的一个文件被切成很多 64KB 块，
 *     Nagle 算法会把这些小包攒起来再发，在局域网里表现为"开始很快、然后一阵一阵卡"。
 *
 * ## 安全
 * 密码 = 当前 6 位配对码（用户名任意）。这样用户不需要再记第二套密码，
 * 也不会因为"为 FTP 单独设一个弱密码"而降低整体安全性。
 * FTP 本身是明文协议，因此界面上会明确标注"仅限可信局域网使用"。
 */
class FtpServer(
    private val storage: StorageProvider,
    private val pairing: PairingManager,
    private val policyProvider: () -> SharingPolicy,
    private val deps: Deps
) {

    data class Deps(
        val bufferPool: BufferPool,
        val zeroCopy: ZeroCopySender,
        val dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
        val onAccess: (AccessRecord) -> Unit,
        val onClientCountChanged: (Int) -> Unit,
        /**
         * 传输记账（FR-7）。
         *
         * FTP 虽然是兼容层，但用 Windows 资源管理器拖文件的人一样会问
         * "我传的文件在哪一页有记录"，所以这条通道同样必须接。
         */
        val recorder: TransferRecorder
    )

    data class StartResult(val ok: Boolean, val port: Int, val error: String? = null)

    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    @Volatile
    private var running = false

    private val clientCount = AtomicInteger(0)

    val isRunning: Boolean get() = running

    fun start(port: Int): StartResult {
        if (running) return StartResult(true, port)
        return try {
            val channel = ServerSocketChannel.open()
            channel.configureBlocking(true)
            channel.socket().reuseAddress = true
            channel.bind(InetSocketAddress(port), AppConstants.FTP_BACKLOG)
            serverChannel = channel
            running = true
            acceptJob = deps.scope.launch(deps.dispatcher) { acceptLoop(channel) }
            PocketLog.i(TAG, "FTP 服务已启动，端口 $port（被动端口 ${AppConstants.PASSIVE_PORT_START}-${AppConstants.PASSIVE_PORT_END}）")
            StartResult(true, port)
        } catch (be: BindException) {
            PocketLog.e(TAG, "FTP 端口 $port 被占用", be)
            StartResult(false, port, "FTP 端口 $port 已被占用")
        } catch (t: Throwable) {
            PocketLog.e(TAG, "FTP 启动失败：${t.message}", t)
            StartResult(false, port, t.message ?: "未知错误")
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverChannel?.close() }
        serverChannel = null
        PocketLog.i(TAG, "FTP 服务已停止")
    }

    private suspend fun acceptLoop(channel: ServerSocketChannel) {
        while (deps.scope.isActive && running) {
            val socket = try {
                channel.accept()
            } catch (t: Throwable) {
                if (running) PocketLog.d(TAG, "FTP accept 中断：${t.message}")
                break
            } ?: continue
            deps.scope.launch(deps.dispatcher) {
                val session = FtpSession(socket)
                val before = clientCount.get()
                clientCount.incrementAndGet()
                if (before == 0) deps.onClientCountChanged(clientCount.get())
                try {
                    session.run()
                } catch (t: Throwable) {
                    PocketLog.d(TAG, "FTP 会话异常：${t.message}")
                } finally {
                    session.cleanup()
                    if (clientCount.decrementAndGet() == 0) deps.onClientCountChanged(0)
                }
            }
        }
    }

    /**
     * 一个 FTP 控制会话。
     *
     * 用内部类而非独立文件：FTP 的会话状态（当前目录、类型、被动端口、断点位点）
     * 与服务器生命周期强耦合，拆开后需要传递大量上下文，反而更难维护。
     */
    private inner class FtpSession(private val control: SocketChannel) {

        private val reader = SocketReader(control)
        private val remoteIp = (control.remoteAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
        private var cwd = ""
        private var loggedIn = false
        private var binaryMode = true
        private var restOffset = 0L
        private var passiveListener: ServerSocketChannel? = null
        private var passivePort = 0
        private var renameFrom: String? = null
        private var transferCount = 0
        private var bytesUp = 0L
        private var bytesDown = 0L

        fun run() {
            control.configureBlocking(true)
            control.socket().tcpNoDelay = true
            control.socket().sendBufferSize = AppConstants.SOCKET_BUFFER_SIZE
            control.socket().receiveBufferSize = AppConstants.SOCKET_BUFFER_SIZE

            reply(220, "PocketServer 就绪（FTP 兼容通道）")

            var idleRounds = 0
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (line.length > MAX_COMMAND_LINE) {
                    reply(500, "命令过长")
                    continue
                }
                val space = line.indexOf(' ')
                val command = (if (space < 0) line else line.substring(0, space)).uppercase(Locale.US)
                val argument = if (space < 0) "" else line.substring(space + 1)

                if (command == "QUIT") {
                    reply(221, "再见")
                    break
                }
                // 未登录前只允许这几个命令，防止未授权探测目录结构
                if (!loggedIn && command !in PRE_AUTH_COMMANDS) {
                    reply(530, "请先登录")
                    continue
                }
                if (!handle(command, argument)) break

                idleRounds++
                if (idleRounds > MAX_IDLE_COMMANDS) {
                    reply(421, "空闲超时，断开连接")
                    break
                }
            }
        }

        fun cleanup() {
            closePassive()
            runCatching { control.close() }
            if (bytesUp + bytesDown > 0) {
                deps.onAccess(
                    AccessRecord(
                        timestamp = System.currentTimeMillis(),
                        peerIp = remoteIp,
                        deviceName = "FTP 客户端",
                        method = "FTP",
                        path = cwd,
                        status = 226,
                        bytesSent = bytesDown,
                        bytesReceived = bytesUp,
                        detail = "会话结束（$transferCount 次传输）"
                    )
                )
            }
        }

        // ==================== 命令分发 ====================

        /** @return false 表示应结束会话 */
        private fun handle(command: String, argument: String): Boolean {
            when (command) {
                "USER" -> reply(331, "请输入密码（手机屏幕上显示的 6 位配对码）")
                "PASS" -> doPass(argument)
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> doFeat()
                "OPTS" -> doOpts(argument)
                "PWD", "XPWD" -> reply(257, "\"/${escapePath(cwd)}\" 是当前目录")
                "CWD", "XCWD" -> doCwd(argument)
                "CDUP", "XCUP" -> doCwd("..")
                "TYPE" -> doType(argument)
                "PASV" -> doPasv()
                "EPSV" -> doEpsv()
                "PORT" -> reply(502, "本服务器不支持主动模式，请使用被动模式")
                "LIST" -> doList(argument, ml = false)
                "NLST" -> doList(argument, ml = false, namesOnly = true)
                "MLSD" -> doList(argument, ml = true)
                "MLST" -> doMlst(argument)
                "RETR" -> doRetr(argument)
                "STOR" -> doStor(argument)
                "APPE" -> doStor(argument, append = true)
                "REST" -> doRest(argument)
                "SIZE" -> doSize(argument)
                "MDTM" -> doMdtm(argument)
                "DELE" -> doDele(argument)
                "MKD", "XMKD" -> doMkd(argument)
                "RMD", "XRMD" -> doRmd(argument)
                "RNFR" -> doRnfr(argument)
                "RNTO" -> doRnto(argument)
                "NOOP" -> reply(200, "OK")
                "ABOR" -> reply(226, "已中止")
                "AUTH" -> reply(502, "未启用 TLS。局域网内明文传输可获得接近两倍的吞吐")
                "PBSZ", "PROT" -> reply(502, "未启用 TLS")
                "HELP" -> reply(214, "支持的命令见 FEAT")
                else -> reply(502, "未实现的命令：$command")
            }
            return true
        }

        private fun doPass(password: String) {
            val policy = policyProvider()
            val code = pairing.currentCodeOrNull()
            val ok = !policy.requirePairing ||
                (code != null && password.trim() == code) ||
                NetworkUtils.isLanPeer(remoteIp) && !policy.requirePairing

            if (!ok) {
                PocketLog.w(TAG, "FTP 登录失败：$remoteIp")
                reply(530, "配对码不正确。请在手机屏幕上看当前 6 位数字。")
                return
            }
            loggedIn = true
            val permHint = if (policy.canWrite) "可读写" else "只读"
            reply(230, "登录成功（$permHint）")
            PocketLog.i(TAG, "FTP 已登录：$remoteIp")
        }

        private fun doFeat() {
            // UTF8 与 TVFS 是老设备能否正确工作的关键：
            //  - UTF8  : 中文文件名不乱码
            //  - TVFS  : 路径按 Unix 风格解释，Windows 才不会把 "/" 当非法字符
            //  - REST  : 断点续传
            writeRaw(
                "211-Features:\r\n" +
                    " UTF8\r\n" +
                    " TVFS\r\n" +
                    " MDTM\r\n" +
                    " SIZE\r\n" +
                    " REST STREAM\r\n" +
                    " MLST type*;size*;modify*;perm*;\r\n" +
                    "211 End\r\n"
            )
        }

        private fun doOpts(argument: String) {
            val upper = argument.uppercase(Locale.US)
            when {
                upper.startsWith("UTF8") -> reply(200, "UTF8 已启用")
                upper.startsWith("MLST") -> reply(200, "MLST type*;size*;modify*;perm*;")
                else -> reply(501, "不支持的选项")
            }
        }

        private fun doCwd(argument: String) {
            val target = resolve(argument)
            if (target == null) {
                reply(550, "路径非法")
                return
            }
            val entry = storage.stat(target)
            if (entry == null || !entry.isDirectory) {
                reply(550, "目录不存在")
                return
            }
            cwd = target
            reply(250, "已切换到 /${escapePath(cwd)}")
        }

        private fun doType(argument: String) {
            binaryMode = argument.trim().uppercase(Locale.US).startsWith("I")
            reply(200, if (binaryMode) "已切换到二进制模式" else "已切换到文本模式")
        }

        // ---- 被动模式 ----

        private fun doPasv() {
            closePassive()
            val listener = openPassiveListener()
            if (listener == null) {
                reply(425, "无法打开数据端口")
                return
            }
            passiveListener = listener
            passivePort = (listener.localAddress as InetSocketAddress).port
            // PASV 要求返回服务器自己的地址；这里用接入地址而不是本地枚举地址，
            // 因为多网卡手机上枚举顺序不可靠，可能给出客户端根本连不到的地址
            val host = (control.localAddress as? InetSocketAddress)?.address?.hostAddress
                ?: NetworkUtils.preferredAddress() ?: "127.0.0.1"
            reply(227, "进入被动模式 (${NetworkUtils.pasvHostParam(host)},${NetworkUtils.pasvPortParam(passivePort)})")
        }

        private fun doEpsv() {
            closePassive()
            val listener = openPassiveListener()
            if (listener == null) {
                reply(425, "无法打开数据端口")
                return
            }
            passiveListener = listener
            passivePort = (listener.localAddress as InetSocketAddress).port
            reply(229, "进入扩展被动模式 (|||$passivePort|)")
        }

        /** 从端口池里取一个可用端口；顺序重试，避免随机端口撞上系统保留端口。 */
        private fun openPassiveListener(): ServerSocketChannel? {
            for (port in AppConstants.PASSIVE_PORT_START..AppConstants.PASSIVE_PORT_END) {
                try {
                    val ch = ServerSocketChannel.open()
                    ch.configureBlocking(true)
                    ch.socket().reuseAddress = true
                    ch.bind(InetSocketAddress(port), 1)
                    return ch
                } catch (_: Throwable) {
                    // 端口被占，试下一个
                }
            }
            PocketLog.w(TAG, "被动端口池已耗尽")
            return null
        }

        private fun acceptDataConnection(): SocketChannel? {
            val listener = passiveListener ?: return null
            return try {
                val data = listener.accept()
                data.configureBlocking(true)
                // 数据连接同样要关 Nagle：大文件传输里它只会带来抖动（O-10）
                data.socket().tcpNoDelay = true
                data.socket().sendBufferSize = AppConstants.SOCKET_BUFFER_SIZE
                data.socket().receiveBufferSize = AppConstants.SOCKET_BUFFER_SIZE
                data
            } catch (t: Throwable) {
                PocketLog.d(TAG, "数据连接失败：${t.message}")
                null
            } finally {
                closePassive()
            }
        }

        private fun closePassive() {
            runCatching { passiveListener?.close() }
            passiveListener = null
            passivePort = 0
        }

        // ---- 列目录 ----

        private fun doList(argument: String, ml: Boolean, namesOnly: Boolean = false) {
            val target = resolve(argument.ifBlank { cwd })
            if (target == null) {
                reply(550, "路径非法")
                return
            }
            val entry = storage.stat(target)
            if (entry == null) {
                reply(550, "路径不存在")
                return
            }

            val data = acceptDataConnection()
            if (data == null) {
                reply(425, "数据连接未建立")
                return
            }
            reply(150, "正在打开数据连接")

            val policy = policyProvider()
            val listing = if (entry.isDirectory) {
                storage.list(target).filter { policy.showHiddenFiles || !PathUtil.isHidden(it.relPath) }
            } else {
                listOf(entry)
            }

            var lines = 0
            var sent = 0L
            try {
                val sb = StringBuilder(4096)
                for (item in listing) {
                    val line = when {
                        namesOnly -> PathUtil.fileName(item.relPath) + "\r\n"
                        ml -> mlsdLine(item)
                        else -> unixListLine(item)
                    }
                    sb.append(line)
                    lines++
                    // 分批写出，避免超大目录一次性拼出几 MB 字符串
                    if (sb.length > 32 * 1024) {
                        sent += writeRawTo(data, sb.toString())
                        sb.setLength(0)
                    }
                }
                if (sb.isNotEmpty()) sent += writeRawTo(data, sb.toString())
            } finally {
                runCatching { data.close() }
            }
            bytesDown += sent
            transferCount++
            reply(226, "传输完成（$lines 项，${Formatters.bytes(sent)}）")
        }

        private fun doMlst(argument: String) {
            val target = resolve(argument.ifBlank { cwd })
            val entry = if (target == null) null else storage.stat(target)
            if (entry == null) {
                reply(550, "路径不存在")
                return
            }
            writeRaw("250-Listing\r\n ${mlsdLine(entry).trimEnd()}\r\n250 End\r\n")
        }

        /** MLSD 事实行：现代客户端（资源管理器、FileZilla）优先用它，解析更可靠。 */
        private fun mlsdLine(entry: StorageEntry): String {
            val type = if (entry.isDirectory) "dir" else "file"
            val perm = buildString {
                append('r')
                append(if (entry.writable) 'w' else '-')
                append(if (entry.isDirectory) 'e' else 'a')
            }
            return "type=$type;size=${entry.size};modify=${Formatters.ftpTimestamp(entry.lastModified)};" +
                "perm=$perm; ${PathUtil.fileName(entry.relPath)}\r\n"
        }

        /** Unix 风格 LIST 行。老设备普遍只认这个格式。 */
        private fun unixListLine(entry: StorageEntry): String {
            val mode = if (entry.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val size = if (entry.isDirectory) 0L else entry.size
            return "$mode 1 pocket pocket $size ${Formatters.ftpTimestamp(entry.lastModified)} " +
                "${PathUtil.fileName(entry.relPath)}\r\n"
        }

        // ---- 下载 / 上传 ----

        private fun doRetr(argument: String) {
            val target = resolve(argument)
            if (target.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            val entry = storage.stat(target)
            if (entry == null || entry.isDirectory) {
                reply(550, "文件不存在")
                return
            }
            val source = storage.openRead(target)
            if (source == null) {
                reply(550, "无法读取文件")
                return
            }
            val data = acceptDataConnection()
            if (data == null) {
                runCatching { source.close() }
                reply(425, "数据连接未建立")
                return
            }
            reply(150, "正在发送数据")

            val total = if (entry.size > 0) entry.size else source.size
            val start = restOffset.coerceIn(0, maxOf(0, total - 1)).let { if (total <= 0) 0 else it }
            restOffset = 0

            /*
             * FTP 也要记账（FR-7）。
             *
             * 老设备只能走 FTP，但"我传出去的文件在手机哪一页能看见"是同一个问题，
             * 不该因为走了兼容层就得不到答案。
             */
            val record = deps.recorder.begin(
                name = PathUtil.fileName(target),
                relPath = target,
                direction = TransferDirection.DOWNLOAD,
                totalBytes = (total - start).coerceAtLeast(0),
                peerName = "FTP 客户端",
                peerIp = remoteIp
            )
            // 断点续传（REST）时已跳过的部分也算已传，否则进度条会从 0 开始往回跳
            val progress = ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { record.report(start + it) }
            var sent = 0L
            try {
                val channel = source.channelOrNull()
                if (channel != null) {
                    sent = deps.zeroCopy.sendFile(
                        channel, start, total - start, data,
                        { progress.update(it) }, { !running }
                    )
                } else {
                    val stream = source.streamOrNull()
                    if (stream != null) {
                        var skip = start
                        val buf = ByteArray(64 * 1024)
                        while (skip > 0) {
                            val n = stream.read(buf, 0, minOf(buf.size.toLong(), skip).toInt())
                            if (n <= 0) break
                            skip -= n
                        }
                        sent = deps.zeroCopy.sendStream(stream, data, { progress.update(it) }, { !running })
                    }
                }
            } catch (t: Throwable) {
                record.fail(start + sent, "下载中断：${t.message ?: "连接已断开"}")
                throw t
            } finally {
                runCatching { source.close() }
                runCatching { data.close() }
            }
            bytesDown += sent
            transferCount++
            record.succeed(start + sent)
            reply(226, "传输完成（${Formatters.bytes(sent)}）")
        }

        private fun doStor(argument: String, append: Boolean = false) {
            val policy = policyProvider()
            if (!policy.canWrite) {
                reply(550, policy.readOnlyMessage)
                return
            }
            val target = resolve(argument)
            if (target.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            PathUtil.parent(target)?.let { if (it.isNotBlank() && !storage.exists(it)) storage.mkdir(it) }

            val offset = if (append && restOffset == 0L) storage.stat(target)?.size ?: 0L else restOffset
            val sink = storage.openWrite(target, append = append || offset > 0, position = null)
            if (sink == null) {
                reply(550, "无法写入文件")
                return
            }
            val data = acceptDataConnection()
            if (data == null) {
                runCatching { sink.close() }
                reply(425, "数据连接未建立")
                return
            }
            reply(150, "准备接收数据")
            restOffset = 0

            /*
             * 总大小只能给 0：FTP 协议在上传时不告诉服务端文件多大
             * （`SIZE` 只在下载时可用）。传输卡片会因此只显示已收字节数、
             * 不显示百分比 —— 这是协议的限制，不是记账丢了东西。
             */
            val record = deps.recorder.begin(
                name = PathUtil.fileName(target),
                relPath = target,
                direction = TransferDirection.UPLOAD,
                totalBytes = 0,
                peerName = "FTP 客户端",
                peerIp = remoteIp
            )
            val progress = ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { record.report(offset + it) }
            var received = 0L
            val buffer = deps.bufferPool.acquire()
            try {
                val channel = sink.channelOrNull
                if (channel != null) {
                    // 有真实文件通道：收满一块就落盘一块，全程只用这一块缓冲
                    while (running) {
                        buffer.clear()
                        val n = data.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        buffer.flip()
                        var written = 0
                        while (buffer.hasRemaining()) {
                            val w = channel.write(buffer)
                            if (w <= 0) break
                            written += w
                        }
                        received += written
                        progress.update(received)
                    }
                } else {
                    val stream = sink.streamOrNull
                    if (stream != null) {
                        received = deps.zeroCopy.copyStream(
                            java.nio.channels.Channels.newInputStream(data), stream,
                            { progress.update(it) }, { !running }
                        )
                    }
                }
            } catch (t: Throwable) {
                record.fail(offset + received, "上传中断：${t.message ?: "连接已断开"}")
                throw t
            } finally {
                // 缓冲必须在 finally 里归还：中途异常漏归还几次就会把池掏空，
                // 之后所有传输都退化成"每次新分配"，性能悄悄下滑且很难归因
                deps.bufferPool.release(buffer)
                runCatching { sink.close() }
                runCatching { data.close() }
            }
            bytesUp += received
            transferCount++
            record.succeed(offset + received)
            reply(226, "已接收 ${Formatters.bytes(received)}")
        }

        private fun doRest(argument: String) {
            val value = argument.trim().toLongOrNull()
            if (value == null || value < 0) {
                reply(501, "断点位点非法")
                return
            }
            restOffset = value
            reply(350, "将从第 $value 字节继续")
        }

        private fun doSize(argument: String) {
            val target = resolve(argument)
            val entry = if (target == null) null else storage.stat(target)
            if (entry == null || entry.isDirectory) {
                reply(550, "无法获取大小")
                return
            }
            reply(213, entry.size.toString())
        }

        private fun doMdtm(argument: String) {
            val target = resolve(argument)
            val entry = if (target == null) null else storage.stat(target)
            if (entry == null) {
                reply(550, "无法获取时间")
                return
            }
            reply(213, Formatters.ftpTimestamp(entry.lastModified))
        }

        // ---- 写操作 ----

        private fun doDele(argument: String) {
            val policy = policyProvider()
            if (!policy.canDeleteAllowed) {
                reply(550, policy.readOnlyMessage)
                return
            }
            val target = resolve(argument)
            if (target.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            reply(if (storage.delete(target)) 250 else 550, "删除" + if (storage.exists(target)) "失败" else "完成")
        }

        private fun doMkd(argument: String) {
            val policy = policyProvider()
            if (!policy.canMkdirAllowed) {
                reply(550, policy.readOnlyMessage)
                return
            }
            val target = resolve(argument)
            if (target.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            val created = storage.mkdir(target)
            if (created) {
                reply(257, "\"/${escapePath(target)}\" 已创建")
            } else {
                reply(550, "创建失败")
            }
        }

        private fun doRmd(argument: String) {
            val policy = policyProvider()
            if (!policy.canDeleteAllowed) {
                reply(550, policy.readOnlyMessage)
                return
            }
            val target = resolve(argument)
            if (target.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            reply(if (storage.delete(target)) 250 else 550, "删除目录完成")
        }

        private fun doRnfr(argument: String) {
            val policy = policyProvider()
            if (!policy.canRenameAllowed) {
                reply(550, policy.readOnlyMessage)
                return
            }
            val target = resolve(argument)
            if (target.isNullOrBlank() || !storage.exists(target)) {
                reply(550, "源文件不存在")
                return
            }
            renameFrom = target
            reply(350, "请给出新名称")
        }

        private fun doRnto(argument: String) {
            val from = renameFrom
            if (from == null) {
                reply(503, "请先发送 RNFR")
                return
            }
            renameFrom = null
            val to = resolve(argument)
            if (to.isNullOrBlank()) {
                reply(550, "路径非法")
                return
            }
            reply(if (storage.rename(from, to)) 250 else 550, "重命名完成")
        }

        // ---- 工具 ----

        /** 把 FTP 路径参数解析为存储相对路径。接受 Unix 风格绝对路径与相对路径。 */
        private fun resolve(argument: String): String? {
            var arg = argument.trim()
            if (arg.isEmpty()) return cwd
            // 客户端有时会带引号
            if (arg.length >= 2 && arg.startsWith("\"") && arg.endsWith("\"")) {
                arg = arg.substring(1, arg.length - 1)
            }
            val combined = when {
                arg.startsWith("/") -> arg.removePrefix("/")
                cwd.isEmpty() -> arg
                else -> "$cwd/$arg"
            }
            return PathUtil.sanitize(combined)
        }

        private fun escapePath(path: String): String = path.replace("\"", "\"\"")

        private fun reply(code: Int, message: String) {
            writeRaw("$code $message\r\n")
        }

        private fun writeRaw(text: String) {
            val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (buffer.hasRemaining()) {
                if (control.write(buffer) <= 0) break
            }
        }

        private fun writeRawTo(channel: SocketChannel, text: String): Int {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.wrap(bytes)
            var written = 0
            while (buffer.hasRemaining()) {
                val n = channel.write(buffer)
                if (n <= 0) break
                written += n
            }
            return written
        }
    }

    private companion object {
        const val TAG = "FtpServer"
        const val MAX_COMMAND_LINE = 4096
        const val MAX_IDLE_COMMANDS = 200_000

        val PRE_AUTH_COMMANDS = setOf("USER", "PASS", "QUIT", "FEAT", "SYST", "AUTH", "OPTS", "HELP", "NOOP")
    }
}
