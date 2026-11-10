package com.pocketserver.net.http

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.data.model.StorageMode
import com.pocketserver.data.model.StorageShare
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.repo.TransferControl
import com.pocketserver.data.repo.TransferIntent
import com.pocketserver.data.repo.TransferRecorder
import com.pocketserver.data.repo.UploadResumeStore
import com.pocketserver.data.repo.UploadSession
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.net.AccessGate
import com.pocketserver.net.PairingManager
import com.pocketserver.net.ServerStats
import com.pocketserver.net.SharingPolicy
import com.pocketserver.perf.BufferPool
import com.pocketserver.perf.SpeedMeter
import com.pocketserver.perf.ThroughputProfile
import com.pocketserver.perf.ThrottledProgress
import com.pocketserver.perf.ZeroCopySender
import com.pocketserver.util.Formatters
import com.pocketserver.util.Json
import com.pocketserver.util.NetworkUtils
import com.pocketserver.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.BindException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 网页版文件管理器（FR-3）。**P0 主通道**。
 *
 * ## 为什么它比 FTP 更该被当成主通道
 * Windows 资源管理器对 FTP 的支持很粗糙：要手输 `ftp://ip:port`、被动模式常被防火墙拦、
 * 中文名乱码、目录不刷新。而浏览器打开 `pocketserver.local` 是一切都现成的。
 * 所以 FTP 在本产品里是**兼容层**，不是主路径；主路径是这个服务器。
 *
 * ## 性能设计（与 FR-12 一一对应）
 *  - 下载：`FileChannel.transferTo` 零拷贝（O-2），支持 Range，因此断点续传与多路分块并行天然可用（O-3）
 *  - 上传：先排空框架预读缓冲，再走 `transferFrom`。这两步都不能省 ——
 *    省掉第一步会丢掉请求体开头几 KB（文件静默损坏），省掉第二步则退化成用户态二次拷贝
 *  - 流量统一过 [ServerStats]：控制台速率、空闲自停、访问日志都依赖这一处，避免多处统计口径不一致
 *
 * ## 安全设计（§7）
 *  1. 路径必须过 [PathUtil.sanitize]，再由 [StorageProvider] 做 canonicalPath 二次校验
 *  2. 未授权：HTML 走 302 跳配对页，API 走 401，绝不泄露目录结构
 *  3. 统一注入 `X-Content-Type-Options: nosniff`，防止把用户上传的伪装文件当脚本执行
 *  4. 上传永不覆盖已有文件，一律生成副本（FR-3.8）
 */
class HttpFileServer(
    private val storage: StorageProvider,
    private val pairing: PairingManager,
    private val policyProvider: () -> SharingPolicy,
    private val deps: Deps
) {

    data class Deps(
        val bufferPool: BufferPool,
        val zeroCopy: ZeroCopySender,
        val stats: ServerStats,
        val speedMeter: SpeedMeter,
        val profile: ThroughputProfile,
        val accessGate: AccessGate,
        val dispatcher: CoroutineDispatcher,
        val scope: CoroutineScope,
        val onAccess: (AccessRecord) -> Unit,
        val nearbyProvider: () -> List<NearbyDevice>,
        val deviceName: () -> String,
        /** 当前 mDNS 主机名（可能带冲突退避序号），配对页与二维码都要用它。 */
        val mdnsHost: () -> String,
        /**
         * 当前共享根的描述（哪块空间、是否整盘、有没有要用户处理的问题）。
         *
         * 网页端必须能看到它。原因见 [StorageShare] 的注释：
         * 退到应用私有目录时列目录**成功但为空**，客户端拿不到任何异常，
         * 只能把它渲染成"这个文件夹是空的"——用户因此永远找不到原因。
         */
        val storageShare: () -> StorageShare,
        /**
         * 传输记账（FR-7）。
         *
         * 见 [TransferRecorder] 的类注释：网页端是本产品的主通道，
         * 这里不接，手机上的「传输」页对绝大多数真实使用场景就永远是空的。
         */
        val recorder: TransferRecorder,
        /**
         * 断点续传的上传会话（FR-14）。
         *
         * 必须是**同一个实例**贯穿 reserve 与 PUT —— 会话表在 reserve 时写入、
         * 在每个分片 PUT 完成时推进。若这里每次请求都新建一个，续传信息永远为空，
         * 而且不会报错，只会表现为"续传功能没生效"。
         */
        val resumeStore: UploadResumeStore,
        /**
         * 传输控制（暂停 / 继续 / 取消）。
         *
         * 服务端是这套状态的**唯一权威**，两端界面都只是它的客户端。
         * 理由见 [TransferControl] 的类注释：网页上传的执行者是别人的浏览器，
         * 手机够不着它，只有让服务端持有意图、让浏览器主动来问，暂停才可能真的生效。
         */
        val control: TransferControl,
        /** 当前设备的稳定身份与专属 mDNS 地址，用于网页端按设备身份保存切换目标。 */
        val deviceId: () -> String = { "" },
        val canonicalMdnsHost: () -> String = { "" },
        /** 浏览器勾选“记住”时持久化长效会话；App 进程重启后按 cookie 恢复。 */
        val rememberTrustedSession: suspend (PairingManager.Session) -> Unit = {},
        val restoreTrustedSession: suspend (String) -> PairingManager.Session? = { null },
        val forgetTrustedSession: suspend (String) -> Unit = {}
    )

    data class StartResult(val ok: Boolean, val port: Int, val error: String? = null)

    private var serverChannel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    private val webDav = WebDavHandler(storage, policyProvider, deps)

    // ==================== 上传归并（FR-7） ====================

    /**
     * 待上传登记项，key = 目标相对路径。
     *
     * ## 为什么上传不能"一个请求记一条"
     * 大文件（≥64MB）在前端会被拆成 2~6 路并发 PUT（O-3）。若每个分片各记一条，
     * 用户会在「传输」页看到同一个文件出现 4 条一模一样的记录 —— 那比没有记录更糟：
     * 缺失只是"少了一块"，重复却会让人怀疑"是不是传了四遍、占了我四份空间"。
     *
     * 归并的锚点是 [handleReserve]：它每个文件只被调用一次，客户端会把总大小一并带来。
     * 之后所有落到同一路径的 PUT 都并入同一条记录。
     */
    private class UploadEntry(
        val totalBytes: Long,
        val lanes: Int,
        val peerName: String,
        val peerIp: String,
        val batchId: String,
        val at: Long
    ) {
        /**
         * 记录在**第一次真正写字节**时才建。
         * 只 reserve 不上传（用户点了取消、或浏览器直接关了）不该留下一条记录。
         */
        @Volatile
        var handle: TransferRecorder.Handle? = null

        /** 已完成的分片数。分片是并发的，累加必须原子。 */
        val doneChunks = AtomicInteger(0)

        /**
         * 已接收字节的累加值。
         *
         * 分片各写各的区间，谁也不知道别人收了多少，唯有把各自的 `received`
         * 加在一起才是整个文件的进度 —— 直接用请求里的 offset 或磁盘大小都会得出错的值。
         */
        val written = AtomicLong(0)
    }

    private val pendingUploads = ConcurrentHashMap<String, UploadEntry>()

    /** 上传预留的幂等缓存：HTTP 响应丢失后，同一请求重试必须拿回原目标路径。 */
    private data class ReserveReply(val body: String, val createdAt: Long)
    private val reserveMutex = Mutex()
    private val reserveReplies = ConcurrentHashMap<String, ReserveReply>()

    /**
     * reserve 阶段登记。每个文件一次。
     *
     * [totalBytes] 只用于界面显示总量；**判定完成靠 [UploadEntry.lanes] 计数**，
     * 理由见 [finishUpload]。
     */
    private fun trackUploadIntent(
        exchange: HttpExchange,
        target: String,
        totalBytes: Long,
        lanes: Int,
        batchId: String
    ) {
        purgeAbandonedUploads()
        /*
         * 用户重新挑了同一个文件，等于用行动说了"我还在弄它"。
         *
         * 所以先把闸门复位成运行态：上一轮那个"已暂停 / 已取消"是上一轮的事，
         * 不该继续拦着这次新上传 —— 否则用户会看到刚拖进去的文件显示"已暂停"，
         * 点开始也没用（闸门拦的是连接，不是这个新请求）。
         *
         * 顺序不能反：**先复位再收尾旧句柄**。反过来的话旧句柄收尾时会读到
         * 闸门里那个 PAUSED，于是把记录写成"已暂停"，而它的消费协程会按
         * "暂停不退出"的规则继续挂着 —— 一条再也没人来叫醒的僵尸记录。
         */
        deps.control.reset(target)
        pendingUploads.remove(target)?.handle?.let { previous ->
            previous.fail(previous.gate.transferred, "已被新的上传取代")
        }
        pendingUploads[target] = UploadEntry(
            totalBytes = totalBytes,
            lanes = lanes,
            peerName = exchange.request.clientNameGuess,
            peerIp = exchange.request.remoteIp,
            batchId = batchId,
            at = System.currentTimeMillis()
        )
    }

    /**
     * 取（或建）某个目标路径的传输记录。
     *
     * [totalBytesHint] 是本次请求自己能看到的总量（这条 PUT 的 contentLength）。
     * 有登记值时一律以登记值为准 —— 分块请求的 contentLength 只是"这一块"的大小，
     * 用它会让进度条永远超不过 1/4，看起来像卡住了。
     */
    private fun recordForUpload(
        exchange: HttpExchange,
        entry: UploadEntry?,
        target: String,
        totalBytesHint: Long
    ): TransferRecorder.Handle {
        // 没走 reserve 的客户端（curl -T、脚本）：一个请求就是一个完整文件
        if (entry == null) {
            return deps.recorder.begin(
                name = PathUtil.fileName(target),
                relPath = target,
                direction = TransferDirection.UPLOAD,
                totalBytes = totalBytesHint,
                peerName = exchange.request.clientNameGuess,
                peerIp = exchange.request.remoteIp
            )
        }
        // 分块 PUT 是并发的，多个线程会同时走到这里。加锁保证"一个目标路径只有一条记录"——
        // 这正是这一整套归并存在的理由，不能在这里漏掉。
        synchronized(entry) {
            entry.handle?.let { return it }
            val handle = deps.recorder.begin(
                name = PathUtil.fileName(target),
                relPath = target,
                direction = TransferDirection.UPLOAD,
                totalBytes = entry.totalBytes.takeIf { it > 0 } ?: totalBytesHint,
                lanes = if (entry.lanes > 0) entry.lanes else 1,
                peerName = entry.peerName,
                peerIp = entry.peerIp,
                batchId = entry.batchId
            )
            entry.handle = handle
            return handle
        }
    }

    /**
     * 一个分片写完之后记账并判定整文件是否完成。
     *
     * ## 完成判定为什么不能看磁盘大小
     * 分块写入会先 `setLength(offset + 本块长度)` 预分配（O-11，见
     * `DirectStorageProvider.preallocate`），**文件大小在第一个分片到达的瞬间
     * 就已经等于整个文件大小了**。拿它当完成信号，会让记录在其余几路还在写的时候
     * 就宣布"传完了"；反过来，若哪天预分配被去掉，又会在文件真正写满前一直不结束。
     *
     * ## 判定也不能只是"数够几个分片"
     * 数分片能对付正常的并发上传，但对付不了**断点续传**：断了之后重连，
     * 已经传完的那两路不会再发一遍，于是"本次会话收到的分片数"永远凑不齐，
     * 记录会永远停在"进行中" —— 那正是这一轮要修的症状，不能从另一个门再放进来。
     *
     * 所以判定交给 [UploadSession]：它看的是每一路**已经写到哪个位点**，
     * 天然幂等（同一段重传多少次，位点都停在同一个数），
     * 单路续传、多路并发、失败重传三种情况共用同一套逻辑。
     * 只有客户端没走 reserve（第三方脚本直连）时才没有会话，那时才退化为
     * "一个请求 = 一个完整文件"。
     */
    private fun finishUpload(
        entry: UploadEntry?,
        handle: TransferRecorder.Handle,
        target: String,
        progressBytes: Long,
        overallComplete: Boolean,
        requestComplete: Boolean
    ) {
        if (!requestComplete) {
            // 这一路自己就没写完整（客户端断开、存储写失败）。
            // 不删登记项：其余分片可能还在写，删了它们会各自再造一条记录出来。
            // 也**不删续传会话** —— 断点续传正是靠它才成立。
            handle.fail(progressBytes, "上传不完整，连接可能已中断")
            return
        }
        if (overallComplete) {
            pendingUploads.remove(target)
            handle.succeed(progressBytes)
        } else {
            handle.report(progressBytes)
        }
    }

    /**
     * 把某一路的结束位点写回会话，返回推进后的会话。
     *
     * [laneIndex] 为空表示客户端没说是哪一路，此时按 [offset] 反推：
     * 前端的切分是 `chunk = size / lanes`、第 i 路的起点是 `i * chunk`，
     * 所以 `offset / chunk` 就是路号。反推不出（offset 为空 = 单路首传）则记第 0 路。
     */
    private suspend fun advanceSession(
        target: String,
        session: UploadSession?,
        offset: Long?,
        laneIndex: Int?,
        endPosition: Long
    ): UploadSession? {
        if (session == null) return null
        val lane = when {
            laneIndex != null && laneIndex in 0 until session.lanes -> laneIndex
            offset != null && session.lanes > 1 && session.size > 0 -> {
                val chunk = session.size / session.lanes
                if (chunk > 0) (offset / chunk).toInt().coerceIn(0, session.lanes - 1) else 0
            }
            else -> 0
        }
        return deps.resumeStore.advance(target, lane, endPosition)
    }

    /**
     * 清掉被放弃的上传。
     *
     * 浏览器在"选中一批文件然后直接关掉标签页"这件事上非常干脆：reserve 已经发生，
     * PUT 永远不会来。不清理的话这些条目会一直躺在内存里，对应的记录也会永远停在
     * "进行中"，直到下次服务重启才被 [com.pocketserver.data.repo.TransferRepository.failStaleTasks]
     * 判为中断。挂在 reserve 上顺带清理，不需要额外的定时器；同时在飞的条目本来就是个位数。
     */
    private fun purgeAbandonedUploads() {
        if (pendingUploads.isEmpty()) return
        val cutoff = System.currentTimeMillis() - UPLOAD_ENTRY_TTL_MS
        val iterator = pendingUploads.entries.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.value.at >= cutoff) continue
            iterator.remove()
            candidate.value.handle?.fail(0, "上传未完成（连接中断或已取消）")
        }
    }

    // ==================== 生命周期 ====================

    fun start(port: Int): StartResult {
        if (running) return StartResult(true, port)
        var opened: ServerSocketChannel? = null
        return try {
            val channel = ServerSocketChannel.open().also { opened = it }
            channel.configureBlocking(true)
            channel.socket().reuseAddress = true
            channel.bind(InetSocketAddress(port), AppConstants.HTTP_BACKLOG)
            serverChannel = channel
            running = true
            acceptJob = deps.scope.launch(deps.dispatcher) { acceptLoop(channel) }
            PocketLog.i(TAG, "网页服务已启动，端口 $port")
            StartResult(true, port)
        } catch (be: BindException) {
            runCatching { opened?.close() }
            PocketLog.e(TAG, "端口 $port 被占用", be)
            StartResult(false, port, "端口 $port 已被占用，请在设置中更换")
        } catch (t: Throwable) {
            runCatching { opened?.close() }
            serverChannel = null
            running = false
            PocketLog.e(TAG, "启动失败：${t.message}", t)
            StartResult(false, port, t.message ?: "未知错误")
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverChannel?.close() }
        serverChannel = null
        webDav.releaseAllLocks()
        deps.accessGate.rejectAll()
        PocketLog.i(TAG, "网页服务已停止")
    }

    private suspend fun acceptLoop(channel: ServerSocketChannel) {
        while (deps.scope.isActive && running) {
            val socket = try {
                channel.accept()
            } catch (t: Throwable) {
                if (running) PocketLog.d(TAG, "accept 中断：${t.message}")
                break
            } ?: continue
            // 每个连接一条协程。局域网并发是个位数，不需要线程池；协程也便于挂起等待接入确认。
            deps.scope.launch(deps.dispatcher) { handleConnection(socket) }
        }
    }

    // ==================== 连接处理 ====================

    private suspend fun handleConnection(socket: SocketChannel) {
        val remoteIp = (socket.remoteAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
        deps.stats.onClientOpen()
        try {
            socket.configureBlocking(true)
            socket.socket().tcpNoDelay = true
            socket.socket().sendBufferSize = deps.profile.socketBufferBytes
            socket.socket().receiveBufferSize = deps.profile.socketBufferBytes

            val reader = SocketReader(socket)
            var served = 0
            while (running && served < MAX_KEEPALIVE_REQUESTS) {
                val request = HttpParser.readRequest(reader, remoteIp) ?: break

                val exchange = HttpExchange(request, reader, socket, policyProvider)
                try {
                    route(exchange, socket)
                } catch (t: Throwable) {
                    PocketLog.e(TAG, "处理 ${request.method} ${request.path} 出错：${t.message}", t)
                    if (!exchange.committed) {
                        exchange.error(HttpStatus.INTERNAL_ERROR, "服务器内部错误")
                    }
                }

                deps.stats.onRequest()
                deps.stats.onTx(exchange.bytesSent)
                deps.stats.onRx(exchange.bytesReceived)
                deps.onAccess(
                    AccessRecord(
                        timestamp = System.currentTimeMillis(),
                        peerIp = remoteIp,
                        deviceName = request.clientNameGuess,
                        method = request.method,
                        path = request.path,
                        status = exchange.status,
                        bytesSent = exchange.bytesSent,
                        bytesReceived = exchange.bytesReceived,
                        detail = exchange.respondDetail
                    )
                )

                served++
                // 请求体没读完就复用连接，剩余字节会被当成下一个请求 → 必然解析错乱。
                // 宁可关连接让客户端重连，也不能让流错位。
                val bodyConsumed = request.contentLength <= 0 || exchange.bytesReceived >= request.contentLength
                if (!exchange.committed || !request.keepAlive || !bodyConsumed) break
            }
        } catch (t: Throwable) {
            PocketLog.d(TAG, "连接异常($remoteIp)：${t.message}")
        } finally {
            runCatching { socket.close() }
            deps.stats.onClientClose()
        }
    }

    // ==================== 鉴权 ====================

    private class AuthOutcome(
        val allowed: Boolean,
        val session: PairingManager.Session? = null,
        val deviceName: String = "",
        val status: Int = 0,
        val message: String = ""
    )

    private fun isPublicPath(path: String): Boolean =
        path == "/pair" || path == "/api/pair" || path == "/favicon.ico"

    private suspend fun authenticate(request: HttpRequest): AuthOutcome {
        val policy = policyProvider()

        val token = request.cookie(COOKIE_TOKEN) ?: request.bearerToken()
        pairing.validateToken(token)?.let {
            return AuthOutcome(true, it, it.deviceName)
        }
        if (!token.isNullOrBlank()) {
            val restored = runCatching { deps.restoreTrustedSession(token) }
                .onFailure { PocketLog.w(TAG, "恢复已记住的浏览器授权失败：${it.message}") }
                .getOrNull()
            if (restored != null && pairing.restoreTrustedSession(restored)) {
                return AuthOutcome(true, restored, restored.deviceName)
            }
        }

        // Basic 认证：用户名任意，密码 = 当前配对码。给不支持 Cookie 的老客户端与 WebDAV 客户端用。
        request.basicAuth()?.let { (user, pass) ->
            val code = pairing.currentCodeOrNull()
            if (code != null && pass.trim() == code) {
                return AuthOutcome(true, null, user.ifBlank { request.clientNameGuess })
            }
        }

        // 关闭"每次接入需要配对码"后，局域网内直接放行（这也是接入确认弹窗存在的意义）
        if (!policy.requirePairing && NetworkUtils.isLanPeer(request.remoteIp)) {
            return AuthOutcome(true, null, request.clientNameGuess)
        }

        val msg = if (policy.requirePairing) "请先输入配对码" else "未授权"
        return AuthOutcome(false, status = HttpStatus.UNAUTHORIZED, message = msg)
    }

    private fun deny(exchange: HttpExchange, outcome: AuthOutcome) {
        val wantsHtml = exchange.request.method == "GET" &&
            !exchange.request.path.startsWith("/api/") &&
            !exchange.request.isWebDav
        if (wantsHtml) {
            exchange.redirect("/pair")
        } else {
            exchange.respondDetail = outcome.message
            exchange.json(
                Json.obj("ok" to false, "error" to outcome.message, "needPair" to true),
                outcome.status,
                mapOf("WWW-Authenticate" to "Basic realm=\"PocketServer\", charset=\"UTF-8\"")
            )
        }
    }

    // ==================== 路由 ====================

    private suspend fun route(exchange: HttpExchange, socket: SocketChannel) {
        val request = exchange.request
        val path = request.path

        if (request.method == "OPTIONS" && !path.startsWith("/dav")) {
            exchange.json(
                Json.obj("ok" to true), HttpStatus.OK,
                mapOf(
                    "Allow" to "GET, HEAD, PUT, POST, DELETE, OPTIONS",
                    "DAV" to "1,2"
                )
            )
            return
        }

        if (path == "/pair" && request.method == "GET") {
            // 直接打开 /pair 也先识别现有会话；有效授权不应再次看到配对码输入框。
            if (authenticate(request).allowed) {
                exchange.redirect("/")
                return
            }
            val code = pairing.ensureCode()
            exchange.html(
                WebUi.pairPage(
                    mdnsHost = deps.mdnsHost(),
                    port = localPort(),
                    deviceName = deps.deviceName(),
                    codeTtlSeconds = code.ttlRemaining / 1000
                )
            )
            return
        }

        if (path == "/api/pair" && request.method == "POST") {
            handlePair(exchange)
            return
        }

        if (path == "/favicon.ico") {
            exchange.beginBinaryResponse(HttpStatus.NO_CONTENT, "image/x-icon", 0)
            return
        }

        val auth = if (isPublicPath(path)) null else authenticate(request)
        if (auth != null && !auth.allowed) {
            deny(exchange, auth)
            return
        }

        // WebDAV 挂在 /dav，避免与网页版路径冲突
        if (path == "/dav" || path.startsWith("/dav/")) {
            webDav.handle(exchange, path.removePrefix("/dav"))
            return
        }

        when {
            path == "/" && request.method == "GET" -> exchange.html(
                WebUi.filePage(
                    deviceName = deps.deviceName(),
                    canWrite = exchange.policy.canWrite,
                    canDelete = exchange.policy.canDeleteAllowed,
                    canRename = exchange.policy.canRenameAllowed,
                    deviceId = deps.deviceId()
                )
            )

            path == "/api/fs/list" -> handleList(exchange)
            path == "/api/fs/reserve" -> handleReserve(exchange)
            path == "/api/fs/upload-status" -> handleUploadStatus(exchange)
            /*
             * 传输任务的两个控制接口。网页端每隔两秒来问一次"现在有哪些传输在跑、
             * 各自是什么状态"，用户的按钮则通过 control 下达。
             *
             * 为什么不做推送：局域网里这个列表最长也就几条，两秒一次的轮询
             * 总开销远低于为它维持一条长连接（那还要处理重连、心跳、乱序）。
             * 而且这个轮询本来就是页面已有的 /api/stats 定时器顺路多问一句。
             */
            path == "/api/fs/tasks" -> handleTasks(exchange)
            path == "/api/fs/control" -> handleControl(exchange)
            path == "/api/fs/file" && (request.method == "PUT" || request.method == "POST") ->
                handleUpload(exchange, socket)

            path == "/api/fs/mkdir" -> handleMkdir(exchange)
            path == "/api/fs/delete" -> handleDelete(exchange)
            path == "/api/fs/batch-delete" && request.method == "POST" -> handleBatchDelete(exchange)
            path == "/api/fs/zip" && request.method == "POST" -> handleZipDownload(exchange)
            path == "/api/fs/rename" -> handleRename(exchange)
            path == "/api/stats" -> handleStats(exchange)
            path == "/api/session" -> handleSession(exchange, auth)
            path == "/api/devices" -> handleDevices(exchange)
            path == "/api/logout" -> handleLogout(exchange, auth)
            path == "/api/peer/announce" -> handlePeerAnnounce(exchange, auth)
            path == "/dl" -> handleDownload(exchange, inline = false)
            path == "/view" -> handleDownload(exchange, inline = true)
            path == "/__bench/stream" -> handleBenchStream(exchange, socket)
            path == "/__bench/recv" -> handleBenchRecv(exchange)
            else -> exchange.error(
                HttpStatus.NOT_FOUND,
                "找不到：$path",
                asJson = path.startsWith("/api/")
            )
        }
    }

    private fun localPort(): Int =
        (serverChannel?.localAddress as? InetSocketAddress)?.port ?: AppConstants.HTTP_PORT_DEFAULT

    // ==================== 配对 ====================

    private suspend fun handlePair(exchange: HttpExchange) {
        val body = readSmallBody(exchange)
        val code = extractJsonString(body, "code") ?: ""
        val trust = body.contains("\"trust\":true") || body.contains("\"trust\": true")

        if (code.isBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "请输入配对码"), HttpStatus.BAD_REQUEST)
            return
        }

        val (result, session) = pairing.verify(
            input = code.trim(),
            peerIp = exchange.request.remoteIp,
            deviceName = exchange.request.clientNameGuess,
            trustedRequested = trust
        )

        when (result) {
            is PairingManager.VerifyResult.Ok -> {
                if (session == null) {
                    exchange.json(Json.obj("ok" to false, "error" to "会话签发失败，请重试"), HttpStatus.INTERNAL_ERROR)
                    return
                }
                if (session.trusted) {
                    try {
                        deps.rememberTrustedSession(session)
                    } catch (t: Throwable) {
                        pairing.revoke(session.token)
                        PocketLog.e(TAG, "保存已记住的浏览器授权失败", t)
                        exchange.json(
                            Json.obj("ok" to false, "error" to "无法保存免输授权，请取消勾选后重试"),
                            HttpStatus.INTERNAL_ERROR
                        )
                        return
                    }
                }
                val maxAge = if (session.trusted) {
                    AppConstants.TRUSTED_TOKEN_TTL_MS / 1000
                } else {
                    AppConstants.SESSION_TTL_MS / 1000
                }
                exchange.json(
                    Json.obj(
                        "ok" to true,
                        "name" to session.deviceName,
                        "trusted" to session.trusted,
                        "writable" to exchange.policy.canWrite
                    ),
                    HttpStatus.OK,
                    mapOf("Set-Cookie" to "$COOKIE_TOKEN=${session.token}; Path=/; Max-Age=$maxAge; SameSite=Lax")
                )
            }

            is PairingManager.VerifyResult.Wrong -> exchange.json(
                Json.obj(
                    "ok" to false,
                    "error" to "配对码不正确，还可尝试 ${result.attemptsLeft} 次",
                    "attemptsLeft" to result.attemptsLeft
                ),
                HttpStatus.UNAUTHORIZED
            )

            is PairingManager.VerifyResult.Locked -> {
                val waitMin = ((result.unlockAt - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)
                exchange.json(
                    Json.obj(
                        "ok" to false,
                        "error" to "尝试次数过多，请 $waitMin 分钟后再试",
                        "lockedMinutes" to waitMin
                    ),
                    HttpStatus.UNAUTHORIZED
                )
            }

            PairingManager.VerifyResult.Expired -> exchange.json(
                Json.obj(
                    "ok" to false,
                    "error" to "配对码已失效，请在手机上点「换一个」后重试",
                    "expired" to true
                ),
                HttpStatus.UNAUTHORIZED
            )
        }
    }

    private fun handleSession(exchange: HttpExchange, auth: AuthOutcome?) {
        exchange.json(
            Json.obj(
                "ok" to true,
                "device" to deps.deviceName(),
                "id" to deps.deviceId(),
                "mdnsHost" to deps.canonicalMdnsHost(),
                "port" to localPort(),
                "client" to (auth?.deviceName ?: ""),
                "trusted" to (auth?.session?.trusted ?: false),
                "writable" to exchange.policy.canWrite,
                "canDelete" to exchange.policy.canDeleteAllowed,
                "canRename" to exchange.policy.canRenameAllowed,
                "permission" to exchange.policy.describe()
            )
        )
    }

    private suspend fun handleLogout(exchange: HttpExchange, auth: AuthOutcome?) {
        auth?.session?.let {
            pairing.revoke(it.token)
            if (it.trusted) deps.forgetTrustedSession(it.token)
        }
        exchange.json(
            Json.obj("ok" to true),
            HttpStatus.OK,
            mapOf("Set-Cookie" to "$COOKIE_TOKEN=; Path=/; Max-Age=0")
        )
    }

    /**
     * 对等设备的接入登记（FR-2.11 接入确认弹窗）。
     *
     * 场景：另一台 PocketServer 设备要往我这台推文件。它拿不到配对码（那是给电脑用的），
     * 所以走这条路：登记 → 手机/电视弹窗 → 用户点允许 → 发长效令牌。
     * 已进白名单的设备直接放行 —— 否则每次推送都弹窗，用户会把整个功能关掉，
     * 那才是真正的安全损失。
     */
    private suspend fun handlePeerAnnounce(exchange: HttpExchange, auth: AuthOutcome?) {
        val body = readSmallBody(exchange)
        val name = extractJsonString(body, "name") ?: exchange.request.clientNameGuess
        val typeToken = extractJsonString(body, "type") ?: "phone"

        val alreadyKnown = auth?.session != null || pairing.isTrustedIp(exchange.request.remoteIp)
        if (!alreadyKnown) {
            val allowed = deps.accessGate.request(
                peerIp = exchange.request.remoteIp,
                deviceName = name,
                deviceType = when (typeToken) {
                    "tv" -> DeviceType.TV
                    "pc" -> DeviceType.PC
                    else -> DeviceType.PHONE
                }
            )
            if (!allowed) {
                exchange.respondDetail = "用户拒绝接入"
                exchange.json(Json.obj("ok" to false, "error" to "对方拒绝了接入请求"), HttpStatus.FORBIDDEN)
                return
            }
        }

        val (result, session) = pairing.verify(
            input = "",
            peerIp = exchange.request.remoteIp,
            deviceName = name,
            trustedRequested = true,
            silent = true
        )
        if (result !is PairingManager.VerifyResult.Ok || session == null) {
            exchange.json(Json.obj("ok" to false, "error" to "登记失败"), HttpStatus.INTERNAL_ERROR)
            return
        }
        exchange.json(
            Json.obj(
                "ok" to true,
                "token" to session.token,
                "device" to deps.deviceName(),
                "writable" to exchange.policy.canWrite
            )
        )
    }

    // ==================== 文件操作 ====================

    /**
     * 共享根信息随每个列表响应一起下发。
     *
     * 为什么塞进列表响应而不是单开一个接口：用户"换目录/清缓存/重新打开页面"
     * 之后看到的第一个东西就是列表。让列表响应自带这份信息，前端不需要多一次
     * 往返，也不会出现"列表已经刷新、横幅还是旧的"这种不一致。
     */
    private fun sharePairs(share: StorageShare): Array<Pair<String, Any?>> = arrayOf(
        "rootLabel" to share.rootLabel,
        // SAF 的根是个 documentId，对用户没有意义，就不往下发了
        "rootPath" to share.rootPath.ifBlank { null },
        "fullStorage" to share.fullStorage,
        "storageMode" to if (share.mode == StorageMode.DIRECT) "direct" else "saf",
        "notice" to share.notice
    )

    private fun handleList(exchange: HttpExchange) {
        val share = deps.storageShare()
        val relative = urlPathToStoragePath(exchange.request.query["path"] ?: "")
        if (relative == null) {
            exchange.json(
                Json.obj("ok" to false, "error" to "路径非法", *sharePairs(share)),
                HttpStatus.BAD_REQUEST
            )
            return
        }
        if (!storage.ready) {
            exchange.json(
                Json.obj(
                    "ok" to false,
                    "error" to "手机端还没有拿到存储访问权限，暂时读不到文件",
                    *sharePairs(share)
                ),
                HttpStatus.FORBIDDEN
            )
            return
        }
        val policy = exchange.policy
        val entries = storage.list(relative)
            .filter { policy.showHiddenFiles || !PathUtil.isHidden(it.relPath) }

        /*
         * 这里刻意**不**去猜"空列表到底是空目录还是读不到"。
         *
         * 曾经想用一个启发式（根目录 + 零条目 + 非整盘共享 → 判定为"读不到权限"）
         * 来兜住这种现象，但它在首次运行时必然误报：未授予权限时共享根是应用私有
         * 目录，而那个目录本来就是空的。告诉用户"读不到这个目录"比告诉他"这是空的"
         * 更糟 —— 后者至少是真的。
         *
         * 真正该做的是把"共享的是哪块空间"如实说出来，也就是随响应下发的
         * [sharePairs]：位置错了，横幅会明确写清楚，用户不需要从空列表里去猜。
         */

        val items = entries.map { e ->
            Json.obj(
                "name" to e.name,
                "path" to e.relPath,
                "dir" to e.isDirectory,
                "size" to e.size,
                "mtime" to e.lastModified,
                "mime" to e.mimeType,
                "playable" to (!e.isDirectory && PathUtil.isPlayableMedia(e.relPath))
            )
        }
        exchange.json(
            Json.obj(
                "ok" to true,
                "path" to relative,
                "parent" to (PathUtil.parent(relative) ?: ""),
                "writable" to policy.canWrite,
                "canDelete" to policy.canDeleteAllowed,
                "canRename" to policy.canRenameAllowed,
                "free" to storage.freeSpaceBytes(),
                *sharePairs(share),
                // 每一项已经是序列化好的 JSON 对象，直接拼数组，不能再走一遍转义
                "items" to Json.raw(items.joinToString(prefix = "[", postfix = "]", separator = ","))
            )
        )
    }

    /**
     * 上传前占位：返回一个保证不冲突的最终路径。
     *
     * 为什么必须有这一步：多路并行上传同一个大文件时，四条连接几乎同时到达。
     * 若每条连接各自做"重名则改名"，会生成四个不同名字的文件，用户拿到的是四块残片。
     * 先占位再并发写入，才能保证四条连接写的是同一个文件的不同区间。
     */
    private suspend fun handleReserve(exchange: HttpExchange) {
        val body = readSmallBody(exchange)
        val batchId = (extractJsonString(body, "batchId") ?: "")
            .takeIf { it.length in 1..80 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
            .orEmpty()
        val requestId = (extractJsonString(body, "requestId") ?: "")
            .takeIf { it.length in 1..80 && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '_' } }
        val requestKey = if (batchId.isNotBlank() && requestId != null) "$batchId:$requestId" else null

        if (requestKey == null) {
            handleReserveBody(exchange, body, batchId, null)
            return
        }

        // 一次只执行一个 reserve，确保因响应丢失而并发到达的相同 requestId 只占位一次。
        reserveMutex.withLock {
            pruneReserveReplies(System.currentTimeMillis())
            val previous = reserveReplies[requestKey]
            if (previous != null) {
                exchange.respondDetail = "重复的上传预留请求，返回原目标路径"
                exchange.json(previous.body)
                return@withLock
            }
            handleReserveBody(exchange, body, batchId, requestKey)
        }
    }

    private suspend fun handleReserveBody(
        exchange: HttpExchange,
        body: String,
        batchId: String,
        requestKey: String?
    ) {
        val name = (extractJsonString(body, "name") ?: "").trim()
        val declaredSize = (extractJsonLong(body, "size") ?: 0L).coerceAtLeast(0)
        if (!exchange.policy.canWrite) {
            recordReserveFailure(exchange, batchId, name, declaredSize, exchange.policy.readOnlyMessage)
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        val parent = urlPathToStoragePath(extractJsonString(body, "dir") ?: "") ?: ""
        if (name.isEmpty()) {
            recordReserveFailure(exchange, batchId, name, declaredSize, "缺少文件名")
            exchange.json(Json.obj("ok" to false, "error" to "缺少文件名"), HttpStatus.BAD_REQUEST)
            return
        }
        val safeName = PathUtil.sanitize(name)
        if (safeName.isNullOrBlank() || safeName.contains('/')) {
            recordReserveFailure(exchange, batchId, name, declaredSize, "文件名非法")
            exchange.json(Json.obj("ok" to false, "error" to "文件名非法"), HttpStatus.BAD_REQUEST)
            return
        }

        /*
         * 先把目标目录建出来，再算重名。
         *
         * 上传文件夹时会传来 `假期照片/2024/海边` 这类多级目录，此时目录还不存在。
         * 如果这里不建，[StorageProvider.childNames] 会返回空集合，dedupe 就以为
         * "没有同名文件"，于是覆盖语义的判断整体失效；而目录一多，用户看到的
         * 是"文件传上去了但没有被改名"——比直接失败更难发现。
         */
        if (parent.isNotBlank() && !storage.exists(parent) && !storage.mkdir(parent)) {
            recordReserveFailure(exchange, batchId, name, declaredSize, "无法创建目标文件夹")
            exchange.respondDetail = "无法创建目标文件夹"
            exchange.json(
                Json.obj("ok" to false, "error" to "无法创建目标文件夹「$parent」，请检查存储权限"),
                HttpStatus.INSUFFICIENT_STORAGE
            )
            return
        }

        /*
         * 客户端声明的全局信息。这是**唯一**能拿到它们的时机：
         * 之后的每个分块 PUT 只知道自己那一段。
         */
        /*
         * 路数必须夹一刀。
         *
         * 上界取 16：本项目实际最多 6 路（FR-12），留一倍余量足够。
         * 不夹的话，客户端报一个天大的数就能让这条记录永远收不了尾 ——
         * 而"永远停在进行中"正是这一轮要修的那类症状，不能从另一个门再放进来。
         */
        // 注意两端都要写成 Long：接收者是 Long，写成 coerceIn(0, MAX_LANES) 会因为
        // 实参是 Int 而找不到重载（Int 不会自动升成 Long），直接编译不过。
        val declaredLanes = (extractJsonLong(body, "lanes") ?: 0L)
            .coerceIn(0L, MAX_LANES.toLong())
            .toInt()
            .coerceAtLeast(1)

        // ==================== 断点续传：先看这个文件是不是"上次没传完的那个" ====================
        //
        // 判定必须放在 dedupe **之前**：上次中断留下的半截文件已经占了原名，
        // 走 dedupe 会被改名成「xxx (1).bin」，于是续传永远命中不了自己。
        val plainTarget = PathUtil.join(parent, safeName)
        val resume = runCatching { deps.resumeStore.find(plainTarget) }.getOrNull()
        if (resume != null &&
            declaredSize > 0 &&
            resume.size == declaredSize &&
            resume.lanes == declaredLanes
        ) {
            trackUploadIntent(exchange, plainTarget, declaredSize, resume.lanes, batchId)
            PocketLog.i(
                TAG,
                "续传命中：${PathUtil.fileName(plainTarget)} 已完成 ${resume.doneCount()}/${resume.lanes} 路"
            )
            sendReserveSuccess(
                exchange,
                requestKey,
                Json.obj(
                    "ok" to true,
                    "path" to plainTarget,
                    "renamed" to false,
                    "resume" to true,
                    "size" to resume.size,
                    "lanes" to resume.lanes,
                    // 每一路已写到的位点，客户端据此跳过已传完的段
                    "done" to resume.laneEnd.toList(),
                    "received" to resume.received()
                )
            )
            return
        }

        if (resume != null) {
            /*
             * 会话在、但对不上（大小或路数变了）—— 说明用户重新选了同名但内容不同的文件。
             *
             * 这里**只丢掉会话，不删那个文件**。取舍理由：
             * 会话存在意味着上次没传完，那个文件"大概率"是残片；但"大概率"不够 ——
             * 万一是"上次其实传完了、只是会话没来得及删"，删掉就等于毁掉用户一个完好的文件。
             * 而留下的坏处只是它占着地方、并且用户能在文件列表里看到并自己删掉。
             * 毁文件和占空间之间，选占空间。
             */
            PocketLog.i(TAG, "同名但大小/路数与上次不一致，丢弃旧会话并重新占位")
            runCatching { deps.resumeStore.remove(plainTarget) }
        }

        val existing = storage.childNames(parent)
        val finalName = PathUtil.dedupe(safeName) { existing.contains(it) }
        val target = PathUtil.join(parent, finalName)

        // 顺手记下"这个文件打算多大、拆几路传"（FR-7 的记账归并锚点）
        trackUploadIntent(exchange, target, declaredSize, declaredLanes, batchId)

        /*
         * 有总大小才建续传会话。
         *
         * 没总大小就没有"传完了"的判据，建出来的会话永远无法完成，
         * 24 小时后只会变成一个"待删除的残片"记录 —— 凭空给自己造垃圾。
         */
        if (declaredSize > 0) {
            runCatching {
                deps.resumeStore.upsert(
                    UploadSession(
                        path = target,
                        size = declaredSize,
                        lanes = declaredLanes,
                        laneEnd = LongArray(declaredLanes),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { PocketLog.w(TAG, "续传会话创建失败（不影响本次上传）：${it.message}") }
        }

        sendReserveSuccess(
            exchange,
            requestKey,
            Json.obj(
                "ok" to true,
                "path" to target,
                "renamed" to (finalName != safeName),
                "resume" to false,
                "size" to declaredSize,
                "lanes" to declaredLanes
            )
        )
    }

    private fun sendReserveSuccess(exchange: HttpExchange, requestKey: String?, body: String) {
        if (requestKey != null) {
            reserveReplies[requestKey] = ReserveReply(body, System.currentTimeMillis())
        }
        exchange.json(body)
    }

    private fun pruneReserveReplies(now: Long) {
        val cutoff = now - RESERVE_REPLY_TTL_MS
        reserveReplies.entries.forEach { entry ->
            if (entry.value.createdAt < cutoff) reserveReplies.remove(entry.key, entry.value)
        }
        if (reserveReplies.size > MAX_RESERVE_REPLIES) {
            reserveReplies.entries.sortedBy { it.value.createdAt }
                .take(reserveReplies.size - MAX_RESERVE_REPLIES)
                .forEach { reserveReplies.remove(it.key, it.value) }
        }
    }

    /** 把占位阶段的拒绝也写进这次网页批次，避免批次概览漏掉“占位请求失败”的文件。 */
    private fun recordReserveFailure(
        exchange: HttpExchange,
        batchId: String,
        name: String,
        totalBytes: Long,
        reason: String
    ) {
        if (batchId.isBlank()) return
        runCatching {
            val displayName = name.ifBlank { "未命名文件" }
            val handle = deps.recorder.begin(
                name = displayName,
                relPath = "__reserve_failure__/$batchId/$displayName",
                direction = TransferDirection.UPLOAD,
                totalBytes = totalBytes,
                peerName = exchange.request.clientNameGuess,
                peerIp = exchange.request.remoteIp,
                batchId = batchId
            )
            handle.fail(0L, reason)
        }.onFailure { PocketLog.w(TAG, "占位失败记录写入失败：${it.message}") }
    }

    /**
     * 供客户端查询"这个文件已经传了多少"（FR-14）。
     *
     * 多路并行时，客户端本来就记着每一路传了多少，用不上这个接口；
     * 它的真正用途是**单路大文件的续传**：单路没有分片的概念，
     * 客户端只能问服务端"你那边现在有多少字节"，才能决定从哪儿接着发。
     */
    private suspend fun handleUploadStatus(exchange: HttpExchange) {
        val target = urlPathToStoragePath(exchange.request.query["path"] ?: "")
        if (target.isNullOrBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "缺少路径参数"), HttpStatus.BAD_REQUEST)
            return
        }
        val session = runCatching { deps.resumeStore.find(target) }.getOrNull()
        if (session == null) {
            // 没有会话不是错误：要么没传过，要么早就传完了。如实回答"不可续传"。
            exchange.json(Json.obj("ok" to true, "resumable" to false))
            return
        }
        exchange.json(
            Json.obj(
                "ok" to true,
                "resumable" to true,
                "size" to session.size,
                "lanes" to session.lanes,
                "done" to session.laneEnd.toList(),
                "received" to session.received(),
                "complete" to session.isComplete()
            )
        )
    }

    /**
     * 当前**可被控制**的传输清单（FR-7.7）。
     *
     * ## 为什么网页端需要服务端告诉它"有哪些传输"
     * 因为网页只知道自己在传什么。而真实场景里同时跑的传输可能来自好几处：
     * 另一个标签页在上传、手机正在给电视推一部片子、手机自己在拉取文件。
     * 网页上那份"传输任务"如果是空的，并不代表手机那边没有传输在跑 ——
     * 而那正好是这一页最该起作用的时候。
     *
     * 清单只包含**还活着**的传输（进行中或已暂停）。历史记录在手机的「传输」页里，
     * 网页端不需要看别人昨天传了什么。
     */
    private fun handleTasks(exchange: HttpExchange) {
        val items = deps.control.liveTransfers()
        exchange.json(
            Json.obj(
                "ok" to true,
                "count" to items.size,
                "items" to items.map { t ->
                    Json.obj(
                        "id" to t.taskId,
                        "path" to t.path,
                        "name" to t.name,
                        "direction" to t.direction.name,
                        // RUNNING / PAUSED / CANCELED —— 前端据此决定显示哪几个按钮
                        "state" to t.intent.name,
                        "transferred" to t.transferred,
                        "total" to t.totalBytes,
                        "percent" to t.percent
                    )
                }
            )
        )
    }

    /**
     * 下达暂停 / 继续 / 取消（FR-7.7）。
     *
     * 手机端不走这里（它在同一个进程里直接调 [TransferControl]），
     * 这个接口主要是给网页用的 —— 而网页正是那个**够不着执行者**的一侧：
     * 只有让服务端持有意图，网页才会在轮询里读到"该继续了"并接着传。
     */
    private suspend fun handleControl(exchange: HttpExchange) {
        val body = readSmallBody(exchange)
        val actionName = (extractJsonString(body, "action") ?: "").trim().lowercase(Locale.US)
        val action = when (actionName) {
            "pause" -> TransferControl.Action.PAUSE
            "resume" -> TransferControl.Action.RESUME
            "cancel" -> TransferControl.Action.CANCEL
            else -> null
        }
        if (action == null) {
            exchange.json(
                Json.obj("ok" to false, "error" to "action 只能是 pause / resume / cancel"),
                HttpStatus.BAD_REQUEST
            )
            return
        }

        val declaredId = extractJsonLong(body, "id") ?: 0L
        val declaredPath = urlPathToStoragePath(extractJsonString(body, "path") ?: "")
        // 优先按路径查：任务主键是异步回填的，上传刚起来的那几毫秒里它还是 0，
        // 而路径从第一个字节起就是确定的。
        val gate = when {
            declaredPath != null -> deps.control.gateFor(declaredPath)
            declaredId > 0L -> deps.control.gateForTask(declaredId)
            else -> null
        }

        /*
         * 只读会话不许动别人的传输。
         *
         * 例外是下载：只读本身就能下载，"取消自己正在下的东西"是它的合理诉求，
         * 不能一并拒掉。上传就不行了 —— 让一个只读客户端去暂停"手机发给电视"的传输，
         * 显然是权限设计上的漏洞。
         */
        if (!exchange.policy.canWrite && gate?.direction != TransferDirection.DOWNLOAD) {
            exchange.json(
                Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage),
                HttpStatus.FORBIDDEN
            )
            return
        }

        val result = when {
            gate != null -> deps.control.applyToGate(gate, action)
            declaredId > 0L -> deps.control.apply(declaredId, action)
            else -> TransferControl.Result(false, "这条传输已经不在了，没有可控制的对象")
        }

        if (result.ok && action == TransferControl.Action.CANCEL && gate?.direction == TransferDirection.UPLOAD) {
            discardCanceledUpload(gate)
        }

        exchange.respondDetail = "传输控制：$actionName"
        exchange.json(
            Json.obj(
                "ok" to result.ok,
                "message" to result.message,
                "path" to (gate?.path ?: declaredPath)
            ),
            if (result.ok) HttpStatus.OK else HttpStatus.CONFLICT
        )
    }

    /**
     * 取消之后丢掉半截文件。
     *
     * **刻意不在这里收尾它的句柄。** 取消时若立刻让记录收尾，闸门就会跟着注销，
     * 而此刻浏览器可能还有一两个分片在飞 —— 它们重试时会发现"没有闸门"，
     * 于是被当成一次全新的上传，把刚删掉的文件重新写一遍，还多出一条记录。
     * 让闸门以"已取消"的状态再留一会儿（见 [TransferControl] 的墓碑超时），
     * 这些迟到的请求就会老老实实收到 410 并停手。
     */
    private suspend fun discardCanceledUpload(gate: TransferControl.Gate) {
        val session = runCatching { deps.resumeStore.find(gate.path) }.getOrNull()
        // 只有"没传完"的才删。会话的完成度是唯一可靠的判据 ——
        // 分块上传会预分配，磁盘大小在第一个分片到达时就已经等于整个文件了。
        deps.control.scheduleDiscard(gate.path, session?.isComplete() == true) { path ->
            storage.delete(path)
            deps.resumeStore.remove(path)
            pendingUploads.remove(path)
        }
    }

    private suspend fun handleUpload(exchange: HttpExchange, socket: SocketChannel) {
        if (!exchange.policy.canWrite) {
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        val target = urlPathToStoragePath(exchange.request.query["path"] ?: "")
        if (target.isNullOrBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "缺少目标路径"), HttpStatus.BAD_REQUEST)
            return
        }
        val offset = exchange.request.query["offset"]?.toLongOrNull()

        /*
         * 用户按了暂停或取消：这一路不要再往下走了。
         *
         * 必须给一个客户端认得的明确回答，而不是直接把连接掐掉 —— 浏览器会把
         * "连接莫名断开"当成网络抖动，退避重试三次才放弃，用户看到的是进度条
         * 一顿一顿地跳然后随机失败。423（稍后再来）与 410（别再来）让它一眼
         * 就知道是该等着还是该收手，前端据此把那一行标成"已暂停 / 已取消"。
         */
        when (deps.control.intentFor(target)) {
            TransferIntent.RUNNING -> Unit

            TransferIntent.PAUSED -> {
                exchange.respondDetail = "上传已暂停"
                exchange.json(
                    Json.obj(
                        "ok" to false,
                        "paused" to true,
                        "path" to target,
                        "error" to "这条上传已被暂停，等待继续"
                    ),
                    HttpStatus.LOCKED
                )
                return
            }

            TransferIntent.CANCELED -> {
                exchange.respondDetail = "上传已取消"
                exchange.json(
                    Json.obj(
                        "ok" to false,
                        "canceled" to true,
                        "path" to target,
                        "error" to "这条上传已被取消"
                    ),
                    HttpStatus.GONE
                )
                return
            }
        }

        /*
         * 从这一刻起，"用户要传这个文件"已经成立 —— 无论后面成败都要留下记录。
         * 失败也必须可见，否则用户看到的现象是"文件没传上去，而且传输页也没提过这事"，
         * 只能自己去猜是网络、权限还是空间的问题。
         */
        val entry = pendingUploads[target]
        val record = recordForUpload(exchange, entry, target, exchange.request.contentLength)

        // 逐级建父目录：上传文件夹时目标目录可能有两三层还不存在。
        // 直连通道的 mkdir 内部走 mkdirs()，SAF 通道的 mkdir 也实现了递归，
        // 因此这一处调用对两种通道都成立。
        val parentRel = PathUtil.parent(target)
        if (!parentRel.isNullOrBlank() && !storage.exists(parentRel) && !storage.mkdir(parentRel)) {
            exchange.respondDetail = "无法创建目标文件夹"
            record.fail(0, "无法创建目标文件夹「$parentRel」")
            exchange.json(
                Json.obj("ok" to false, "error" to "无法创建目标文件夹「$parentRel」，请检查手机存储权限与剩余空间"),
                HttpStatus.INSUFFICIENT_STORAGE
            )
            return
        }

        var writeSink = storage.openWrite(target, append = offset == null, position = offset)
        if (writeSink == null && offset != null) {
            // SAF 降级模式下拿不到随机写能力。明确告知客户端回退单通路，
            // 而不是静默写错位点 —— 后者会把用户的大文件写成乱码，且极难定位。
            exchange.respondDetail = "不支持分块写入"
            record.fail(0, "当前存储模式不支持分块写入")
            exchange.json(
                Json.obj("ok" to false, "error" to "当前存储模式不支持分块写入", "fallback" to "single", "path" to target),
                HttpStatus.CONFLICT
            )
            return
        }
        if (writeSink == null) {
            record.fail(0, "无法写入目标位置")
            exchange.json(
                Json.obj("ok" to false, "error" to "无法写入目标位置，请检查手机存储权限与剩余空间"),
                HttpStatus.INSUFFICIENT_STORAGE
            )
            return
        }

        val expect = exchange.request.contentLength
        var received = 0L
        try {
            // 分块并行：按"位点 + 本块长度"预分配，保证多路写入互不截断（O-11）
            if (offset != null && expect > 0) writeSink.preallocate(offset + expect)
            val channel = writeSink.channelOrNull
            received = exchange.consumeBody(
                fileChannel = channel,
                sink = if (channel == null) {
                    { data, off, len -> writeSink.writeAt(data, off, len, null) }
                } else null,
                zeroCopy = deps.zeroCopy,
                position = offset,
                // 记账不接在这个逐块回调上：分块上传时它只反映"我这一路写了多少"，
                // 拿它当进度会让四路并行显示成四段各走各的。攒到分片结束再算（见 finishUpload）。
                onProgress = { },
                // 除了服务在停，还要听用户的话：暂停/取消按下之后，这一路**立刻**停止读盘写盘。
                // 放在这里而不是"下一个分片再说"，是因为一个分片最大能到几十 MB，
                // 等它写完才停的话，用户会觉得按钮有半分多钟没反应。
                isCanceled = { !running || deps.control.haltedFor(target) }
            )
        } finally {
            runCatching { writeSink.close() }
        }

        val requestComplete = expect <= 0 || received >= expect
        val stat = storage.stat(target)

        // ---- 推进续传会话（FR-14）----
        // 会话是"这个文件整体传到哪了"的唯一权威来源：它按每一路已写到的位点记录，
        // 所以已经传完的那几路不会被要求重传，重传同一段也不会被重复计数。
        val session = runCatching { deps.resumeStore.find(target) }.getOrNull()
        val laneIndex = exchange.request.query["lane"]?.toIntOrNull()
        val advanced = if (requestComplete) {
            runCatching {
                if (offset != null) {
                    advanceSession(target, session, offset, laneIndex, offset + received)
                } else {
                    // 单路首传（截断重写）：整路从 0 写到了 received
                    advanceSession(target, session, null, 0, received)
                }
            }.getOrNull()
        } else {
            null
        }

        /*
         * 整文件是否完成。
         *
         * 三条分支对应三种客户端：
         *  1. 有会话（走 reserve 的新页面、支持续传）→ 以会话为准
         *  2. 没会话但有登记项（只调了 reserve 没报 size 的老页面）→ 数分片兜底
         *  3. 都没有（curl -T 这类一个请求传完整个文件的脚本）→ 本次请求完整即完成
         */
        val overallComplete = when {
            !requestComplete -> false
            session != null -> (advanced ?: session).isComplete()
            entry != null -> {
                // 兜底路径（客户端没报 size，所以没有会话）：沿用"数分片"。
                // 副作用式的调用是刻意的 —— 这两个计数本来就只在这里推进。
                entry.written.addAndGet(received)
                val done = entry.doneChunks.incrementAndGet()
                if (entry.lanes > 0) done >= entry.lanes else offset == null
            }
            else -> true
        }

        // 进度用会话的位点之和而不是累加值：累加在"同一段重传"下会超过文件大小，
        // 界面上进度条会冲出 100%，比不显示还糟。
        val progressBytes = when {
            advanced != null -> advanced.received()
            session != null -> session.received()
            entry != null -> entry.written.get()
            else -> received
        }

        if (overallComplete) {
            // 传完了就把会话收掉 —— 它是"未完成"的登记，留着会被当成残片清理依据
            runCatching { deps.resumeStore.remove(target) }
        }

        finishUpload(
            entry = entry,
            handle = record,
            target = target,
            progressBytes = progressBytes,
            overallComplete = overallComplete,
            requestComplete = requestComplete
        )
        exchange.respondDetail = when {
            !requestComplete -> "上传不完整"
            overallComplete -> "上传完成"
            else -> "分片已接收"
        }
        exchange.json(
            Json.obj(
                "ok" to requestComplete,
                "path" to target,
                "received" to received,
                "size" to (stat?.size ?: received),
                "offset" to offset,
                "complete" to overallComplete,
                "overallReceived" to progressBytes
            ),
            if (requestComplete) HttpStatus.CREATED else HttpStatus.INTERNAL_ERROR
        )
    }

    private fun handleMkdir(exchange: HttpExchange) {
        if (!exchange.policy.canMkdirAllowed) {
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        val body = readSmallBody(exchange)
        val target = urlPathToStoragePath(extractJsonString(body, "path") ?: "")
        if (target.isNullOrBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "缺少路径"), HttpStatus.BAD_REQUEST)
            return
        }
        val ok = storage.mkdir(target)
        exchange.json(Json.obj("ok" to ok, "error" to if (ok) null else "创建失败，可能已存在或无权访问"))
    }

    private fun handleDelete(exchange: HttpExchange) {
        if (!exchange.policy.canDeleteAllowed) {
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        val body = readSmallBody(exchange)
        val target = urlPathToStoragePath(extractJsonString(body, "path") ?: "")
        if (target.isNullOrBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "不能删除根目录"), HttpStatus.BAD_REQUEST)
            return
        }
        val ok = storage.delete(target)
        exchange.respondDetail = if (ok) "已删除" else "删除失败"
        exchange.json(Json.obj("ok" to ok, "error" to if (ok) null else "删除失败"))
    }

    private fun handleBatchDelete(exchange: HttpExchange) {
        if (!exchange.policy.canDeleteAllowed) {
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        if (exchange.request.contentLength !in 1L..(256L * 1024L)) {
            exchange.json(Json.obj("ok" to false, "error" to "选择内容无效或过大"), HttpStatus.BAD_REQUEST)
            return
        }
        val paths = readBatchPaths(exchange)
        if (paths.isEmpty() || paths.size > MAX_BATCH_PATHS || paths.any { it.isBlank() }) {
            exchange.json(Json.obj("ok" to false, "error" to "请在 1 到 $MAX_BATCH_PATHS 个文件范围内选择"), HttpStatus.BAD_REQUEST)
            return
        }
        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (path in paths) {
            val entry = storage.stat(path)
            if (entry != null && !entry.isDirectory && storage.delete(path)) deleted += path
            else failed += path
        }
        exchange.respondDetail = "批量删除 ${deleted.size} 个，失败 ${failed.size} 个"
        exchange.json(Json.obj(
            "ok" to failed.isEmpty(),
            "deleted" to deleted,
            "failed" to failed,
            "error" to if (failed.isEmpty()) null else "有 ${failed.size} 个文件删除失败"
        ), if (failed.isEmpty()) HttpStatus.OK else HttpStatus.CONFLICT)
    }

    /** 逐块生成 ZIP 响应，不把所选文件或压缩包整体装入内存。 */
    private fun handleZipDownload(exchange: HttpExchange) {
        if (exchange.request.contentLength !in 1L..(256L * 1024L)) {
            exchange.json(Json.obj("ok" to false, "error" to "选择内容无效或过大"), HttpStatus.BAD_REQUEST)
            return
        }
        val paths = readBatchPaths(exchange)
        if (paths.isEmpty() || paths.size > MAX_BATCH_PATHS || paths.any { it.isBlank() }) {
            exchange.json(Json.obj("ok" to false, "error" to "请在 1 到 $MAX_BATCH_PATHS 个文件范围内选择"), HttpStatus.BAD_REQUEST)
            return
        }
        val files = paths.mapNotNull { path -> storage.stat(path)?.takeIf { !it.isDirectory && it.readable }?.let { path to it } }
        if (files.size != paths.size) {
            exchange.json(Json.obj("ok" to false, "error" to "部分文件不存在、是文件夹或无法读取，请刷新列表后重试"), HttpStatus.NOT_FOUND)
            return
        }

        exchange.respondDetail = "打包下载 ${files.size} 个文件"
        exchange.beginChunkedBinaryResponse(
            HttpStatus.OK,
            "application/zip",
            mapOf(
                "Content-Disposition" to ContentDisposition.attachment("PocketServer-批量下载.zip"),
                "Cache-Control" to "no-store"
            )
        )

        val chunkBuffer = ByteArray(64 * 1024)
        var buffered = 0
        fun flushChunk() {
            if (buffered == 0) return
            exchange.writeChunked(chunkBuffer, 0, buffered)
            buffered = 0
        }
        val responseStream = object : java.io.OutputStream() {
            override fun write(value: Int) {
                chunkBuffer[buffered++] = value.toByte()
                if (buffered == chunkBuffer.size) flushChunk()
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                var cursor = offset
                var remaining = length
                while (remaining > 0) {
                    val take = minOf(remaining, chunkBuffer.size - buffered)
                    System.arraycopy(bytes, cursor, chunkBuffer, buffered, take)
                    buffered += take
                    cursor += take
                    remaining -= take
                    if (buffered == chunkBuffer.size) flushChunk()
                }
            }

            override fun flush() = flushChunk()
            override fun close() = flushChunk()
        }

        var currentRecord: TransferRecorder.Handle? = null
        var currentBytes = 0L
        val batchId = "web-zip-${UUID.randomUUID()}"
        try {
            ZipOutputStream(responseStream, Charsets.UTF_8).use { zip ->
                // 视频、照片等通常已压缩；存储模式避免手机再花时间重复压缩这些大文件。
                zip.setLevel(Deflater.NO_COMPRESSION)
                val usedNames = mutableSetOf<String>()
                for ((path, entry) in files) {
                    val source = storage.openRead(path) ?: throw java.io.IOException("无法读取 ${entry.name}")
                    val originalName = PathUtil.fileName(path).ifBlank { "file" }
                    var zipName = originalName
                    var duplicate = 2
                    while (!usedNames.add(zipName)) {
                        val dot = originalName.lastIndexOf('.')
                        zipName = if (dot > 0) originalName.substring(0, dot) + " ($duplicate)" + originalName.substring(dot)
                        else "$originalName ($duplicate)"
                        duplicate++
                    }
                    currentBytes = 0L
                    currentRecord = deps.recorder.begin(
                        name = originalName,
                        relPath = path,
                        direction = TransferDirection.DOWNLOAD,
                        totalBytes = entry.size,
                        peerName = exchange.request.clientNameGuess,
                        peerIp = exchange.request.remoteIp,
                        batchId = batchId
                    )
                    try {
                        zip.putNextEntry(ZipEntry(zipName))
                        val sourceChannel = source.channelOrNull()
                        val sourceStream = source.streamOrNull()
                        if (sourceChannel != null) {
                            val buffer = ByteBuffer.allocate(128 * 1024)
                            while (true) {
                                buffer.clear()
                                val count = sourceChannel.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                zip.write(buffer.array(), 0, count)
                                currentBytes += count
                                currentRecord?.report(currentBytes)
                            }
                        } else if (sourceStream != null) {
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                val count = sourceStream.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                zip.write(buffer, 0, count)
                                currentBytes += count
                                currentRecord?.report(currentBytes)
                            }
                        } else throw java.io.IOException("无法读取 ${entry.name}")
                        zip.closeEntry()
                        currentRecord?.succeed(currentBytes)
                        currentRecord = null
                    } catch (t: Throwable) {
                        currentRecord?.fail(currentBytes, "批量打包失败：${t.message ?: "文件读取中断"}")
                        currentRecord = null
                        throw t
                    } finally {
                        runCatching { source.close() }
                    }
                }
            }
        } catch (t: Throwable) {
            currentRecord?.fail(currentBytes, "批量打包失败：${t.message ?: "连接已断开"}")
            PocketLog.e(TAG, "批量 ZIP 下载失败：${t.message}", t)
        } finally {
            runCatching { exchange.finishChunkedResponse() }
        }
    }

    private fun handleRename(exchange: HttpExchange) {
        if (!exchange.policy.canRenameAllowed) {
            exchange.json(Json.obj("ok" to false, "error" to exchange.policy.readOnlyMessage), HttpStatus.FORBIDDEN)
            return
        }
        val body = readSmallBody(exchange)
        val from = urlPathToStoragePath(extractJsonString(body, "path") ?: "")
        val to = urlPathToStoragePath(extractJsonString(body, "to") ?: "")
        if (from.isNullOrBlank() || to.isNullOrBlank()) {
            exchange.json(Json.obj("ok" to false, "error" to "缺少路径参数"), HttpStatus.BAD_REQUEST)
            return
        }
        val ok = storage.rename(from, to)
        exchange.json(Json.obj("ok" to ok, "error" to if (ok) null else "重命名失败"))
    }

    private fun handleDownload(exchange: HttpExchange, inline: Boolean) {
        val target = urlPathToStoragePath(exchange.request.query["path"] ?: "")
        if (target.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "缺少路径参数")
            return
        }
        /*
         * 暂停中的文件不再供流。
         *
         * 暂停下载的含义是"先别占着这条无线链路"。浏览器那边的表现会是"下载失败"，
         * 这看起来不太好，但它是可恢复的：本服务端支持 Range 请求，
         * 用户在浏览器里重新点一次下载，浏览器会带着 `Range: bytes=N-` 回来，
         * 于是从断点接着下。这一点在网页端与手机端的提示文案里都写清楚了。
         */
        when (deps.control.intentFor(target)) {
            TransferIntent.RUNNING -> Unit

            TransferIntent.PAUSED -> {
                exchange.respondDetail = "下载已暂停"
                exchange.json(
                    Json.obj(
                        "ok" to false,
                        "paused" to true,
                        "path" to target,
                        "error" to "这条下载已被暂停，重新点下载会从断点继续"
                    ),
                    HttpStatus.LOCKED
                )
                return
            }

            TransferIntent.CANCELED -> {
                exchange.respondDetail = "下载已取消"
                exchange.json(
                    Json.obj("ok" to false, "canceled" to true, "error" to "这条下载已被取消"),
                    HttpStatus.GONE
                )
                return
            }
        }
        val entry = storage.stat(target)
        if (entry == null) {
            exchange.error(HttpStatus.NOT_FOUND, "文件不存在：${PathUtil.fileName(target)}")
            return
        }
        if (entry.isDirectory) {
            exchange.error(HttpStatus.BAD_REQUEST, "不能直接下载目录")
            return
        }
        val source = storage.openRead(target)
        if (source == null) {
            exchange.error(HttpStatus.FORBIDDEN, "无法读取该文件")
            return
        }
        // 在 try 之外声明：收尾要在 finally 之后做，而异常路径也必须收尾 ——
        // 否则一次中断的下载会在「传输」页永远显示"进行中"。
        var record: TransferRecorder.Handle? = null
        var sent = 0L
        try {
            val total = if (entry.size > 0) entry.size else source.size
            val rangeHeader = exchange.request.header("range")
            val range = RangeParser.parse(rangeHeader, total)
            if (rangeHeader != null && range == null) {
                exchange.json(
                    Json.obj("ok" to false, "error" to "请求的字节区间超出文件范围"),
                    HttpStatus.RANGE_NOT_SATISFIABLE,
                    mapOf("Content-Range" to "bytes */$total")
                )
                return
            }
            val contentType = entry.mimeType.ifBlank { PathUtil.mimeType(target) }
            /*
             * 预览只对"被动内容"开放。
             *
             * HTML / SVG / XML / JS 是**可执行**内容：它们一旦以 text/html 同源渲染，
             * 里面的脚本就能读到本会话的 Cookie（ps_token），等于把共享根里的
             * 一个文件升级成了一把钥匙。而这类文件恰恰最常被拖进来（用户自己做的网页）。
             * 所以对它们强制走下载，并额外挂一个 sandbox CSP 作为第二道保险 ——
             * 万一将来有人改了这里的判断，浏览器也仍然不会执行。
             */
            val activeContent = contentType.startsWith("text/html") ||
                contentType.startsWith("application/xhtml") ||
                contentType == "image/svg+xml" ||
                contentType.startsWith("application/xml") ||
                contentType.startsWith("text/xml") ||
                contentType.startsWith("application/javascript")
            val allowInline = inline && !activeContent

            /*
             * 只给「明确下载」记账，不给 /view 预览记账。
             *
             * 浏览器播一个视频会连发几十个 Range 请求，每个都记一条的话，
             * 「传输」页会被同一个文件刷屏 —— 那不叫记录，那是噪声。
             * 而用户点"下载"只发一次 /dl，与"一次传输"恰好一一对应。
             */
            record = if (inline) {
                null
            } else {
                deps.recorder.begin(
                    name = PathUtil.fileName(target),
                    relPath = target,
                    direction = TransferDirection.DOWNLOAD,
                    totalBytes = range?.let { it.endInclusive - it.start + 1 } ?: total,
                    peerName = exchange.request.clientNameGuess,
                    peerIp = exchange.request.remoteIp
                )
            }
            val progress = ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { done ->
                record?.report(done)
            }
            exchange.respondDetail = when {
                activeContent && inline -> "下载（可执行内容不预览）"
                range != null -> "分段下载 ${range.start}-${range.endInclusive}"
                else -> "下载"
            }
            sent = exchange.sendFileRange(
                readStart = 0,
                totalSize = total,
                contentType = contentType,
                fileName = PathUtil.fileName(target),
                range = range,
                shareChannel = source.channelOrNull(),
                shareStream = source.streamOrNull(),
                inline = allowInline,
                zeroCopy = deps.zeroCopy,
                onProgress = { progress.update(it) },
                // 同上：暂停/取消一按下就停止供流，不等这一整块发完
                isCanceled = { !running || deps.control.haltedFor(target) },
                extraHeaders = if (activeContent) mapOf("Content-Security-Policy" to "sandbox") else emptyMap()
            )
        } catch (t: Throwable) {
            record?.fail(sent, "下载中断：${t.message ?: "连接已断开"}")
            throw t
        } finally {
            runCatching { source.close() }
        }
        record?.succeed(sent)
    }

    // ==================== 状态与诊断 ====================

    private fun handleStats(exchange: HttpExchange) {
        val sample = deps.speedMeter.snapshot()
        exchange.json(
            Json.obj(
                "ok" to true,
                "tx" to Formatters.speed(sample.txBps).removeSuffix("/s"),
                "rx" to Formatters.speed(sample.rxBps).removeSuffix("/s"),
                "txBps" to sample.txBps,
                "rxBps" to sample.rxBps,
                "clients" to deps.stats.clientCount,
                "requests" to deps.stats.requestCount,
                "permission" to exchange.policy.describe(),
                "storage" to if (storage.mode == StorageMode.DIRECT) "direct" else "saf"
            )
        )
    }

    private fun handleDevices(exchange: HttpExchange) {
        val items = deps.nearbyProvider().map { d ->
            Json.obj(
                "id" to d.deviceId,
                "name" to d.name,
                "type" to d.type.name.lowercase(),
                "host" to d.host,
                "mdnsHost" to d.mdnsHost,
                "port" to d.httpPort,
                "reachable" to d.reachable
            )
        }
        exchange.json(
            Json.obj(
                "ok" to true,
                "items" to Json.raw(items.joinToString(prefix = "[", postfix = "]", separator = ","))
            )
        )
    }

    // ==================== 内建测速的对端配合端点（FR-12.14） ====================

    /**
     * 内存造数据下发，**刻意不读磁盘**。
     *
     * 这样客户端测出来的是"纯网络 + 协议栈 + 用户态搬运"的上限。
     * 把它和真实文件下载速度一对比，立刻能判断瓶颈在网络还是在存储 ——
     * 这就是三段测速能给出"人话结论"的核心手法。
     */
    private fun handleBenchStream(exchange: HttpExchange, socket: SocketChannel) {
        val bytes = exchange.request.query["bytes"]?.toLongOrNull()
            ?.coerceIn(1L, AppConstants.BENCH_NETWORK_BYTES * 4)
            ?: AppConstants.BENCH_NETWORK_BYTES

        exchange.beginBinaryResponse(
            HttpStatus.OK, "application/octet-stream", bytes,
            mapOf("Cache-Control" to "no-store")
        )

        // 用堆数组而不是池里的直接缓冲：writeDirect 需要一个真实数组，
        // 而直接缓冲没有 array()（这正是 O-2 里"两条通路"要分开的原因）。
        val payload = ByteArray(deps.profile.bufferSize) { (it % 251).toByte() }
        var sent = 0L
        while (sent < bytes && running) {
            val remaining = bytes - sent
            val n = minOf(payload.size.toLong(), remaining).toInt()
            val written = exchange.writeDirect(payload, 0, n)
            if (written <= 0) break
            sent += written
        }
        exchange.respondDetail = "测速下发 ${bytes / 1024 / 1024}MB"
    }

    /** 丢弃客户端上传的字节，测"客户端 → 手机"方向的上限。 */
    private fun handleBenchRecv(exchange: HttpExchange) {
        val expected = exchange.request.contentLength.takeIf { it > 0 }
            ?: exchange.request.query["bytes"]?.toLongOrNull()
            ?: 0L
        val received = exchange.consumeBody(
            fileChannel = null,
            sink = { _, _, len -> len },
            zeroCopy = null,
            position = null,
            onProgress = {},
            isCanceled = { !running }
        )
        exchange.respondDetail = "测速接收 $received 字节"
        exchange.json(Json.obj("ok" to true, "received" to received, "expected" to expected))
    }

    // ==================== 小工具 ====================

    /** 读一个小的 JSON 参数体，并把多余字节丢弃，避免污染下一个 keep-alive 请求。 */
    private fun readSmallBody(exchange: HttpExchange, limit: Int = 64 * 1024): String {
        val length = exchange.request.contentLength
        if (length <= 0) return ""
        val take = minOf(length, limit.toLong()).toInt()
        val buffer = ByteArray(take)
        var read = 0
        while (read < take) {
            val n = exchange.consumeBodyChunk(buffer, read, take - read)
            if (n <= 0) break
            read += n
        }
        var left = length - read
        val discard = ByteArray(8192)
        while (left > 0) {
            val n = exchange.consumeBodyChunk(discard, 0, minOf(discard.size.toLong(), left).toInt())
            if (n <= 0) break
            left -= n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    /** 批处理请求既支持 JSON（删除 API），也支持表单（让 ZIP 直接流式下载）。 */
    private fun readBatchPaths(exchange: HttpExchange): List<String> {
        val body = readSmallBody(exchange, 256 * 1024)
        val json = if (exchange.request.header("content-type")
                ?.startsWith("application/x-www-form-urlencoded", ignoreCase = true) == true
        ) {
            val encoded = body.split('&').firstOrNull { it.startsWith("paths=") }?.substringAfter('=') ?: return emptyList()
            val array = runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull() ?: return emptyList()
            "{\"paths\":$array}"
        } else body
        return extractJsonStringArray(json, "paths")
            ?.mapNotNull(PathUtil::sanitize)
            ?.distinct()
            ?: emptyList()
    }

    /** 从固定结构的小请求体读取字符串数组，逐项处理 JSON 转义。 */
    private fun extractJsonStringArray(json: String, key: String): List<String>? {
        val keyIndex = json.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + key.length + 2)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i++] != '[') return null
        val out = mutableListOf<String>()
        while (i < json.length) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length) return null
            if (json[i] == ']') return out
            if (json[i] != '"') return null
            val start = i
            i++
            var escaped = false
            while (i < json.length) {
                val c = json[i]
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') { i++; break }
                i++
            }
            if (i > json.length || json.getOrNull(i - 1) != '"') return null
            val value = extractJsonString("{\"value\":${json.substring(start, i)}}", "value") ?: return null
            out += value
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] != ',' && json[i] != ']') return null
        }
        return null
    }

    /** 从 JSON 里取一个字符串字段。刻意不引 JSON 解析库：参数体结构固定且极短。 */
    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\""
        val idx = json.indexOf(pattern)
        if (idx < 0) return null
        val colon = json.indexOf(':', idx + pattern.length)
        if (colon < 0) return null
        val start = json.indexOf('"', colon + 1)
        if (start < 0) return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < json.length) {
                            val code = json.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            i += 4
                        }
                    }

                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString().ifEmpty { null }
    }

    /**
     * 取一个 JSON 数字字段。
     *
     * 刻意不复用 [extractJsonString]：那个实现从引号开始找，遇到 `"size": 12345`
     * 这种不带引号的数字会返回 null，而恰恰是数字字段最容易踩这个坑。
     */
    private fun extractJsonLong(json: String, key: String): Long? {
        val idx = json.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = json.indexOf(':', idx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        val start = i
        while (i < json.length && (json[i].isDigit() || json[i] == '-')) i++
        if (i == start) return null
        return json.substring(start, i).toLongOrNull()
    }

    private companion object {
        const val TAG = "HttpFileServer"
        const val COOKIE_TOKEN = "ps_token"

        /** 单连接最多复用多少次。防止长连接被用来做资源耗尽。 */
        const val MAX_KEEPALIVE_REQUESTS = 200
        const val MAX_BATCH_PATHS = 300

        /**
         * 上传登记的存活时长。
         *
         * 取 30 分钟：正常上传的间隔是"reserve 完立刻 PUT"，秒级；
         * 留这么长的余量是为了不误杀"浏览器弹了下载/另存对话框、用户愣了一会儿才点确定"的情况。
         */
        const val UPLOAD_ENTRY_TTL_MS = 30L * 60 * 1000
        const val RESERVE_REPLY_TTL_MS = 30L * 60 * 1000
        const val MAX_RESERVE_REPLIES = 4096

        /**
         * 客户端可声明的并行路数上限。
         *
         * 本项目实际最多 6 路（FR-12），16 是留了一倍余量。
         * 之所以必须有上限：路数决定"要等几路才认为传完"，
         * 一个写错的脚本报上 10000 路，就会让这条上传记录永远收不了尾。
         */
        const val MAX_LANES = 16
    }
}
