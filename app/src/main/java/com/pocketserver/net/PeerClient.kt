package com.pocketserver.net

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.perf.ChunkedTransferEngine
import com.pocketserver.perf.TransferOutcome
import com.pocketserver.perf.ChunkRangeWithOffset
import com.pocketserver.perf.ThrottledProgress
import com.pocketserver.util.Json
import com.pocketserver.util.JsonReader
import com.pocketserver.util.NetworkUtils
import com.pocketserver.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * 对等传输客户端（FR-11：局域网设备互传与双向对等）。
 *
 * ## 它让产品从"服务器"变成"网络"
 * 只有网页版和 FTP 时，手机只是被动等着别人来取文件。加上这个类之后：
 * 手机可以主动把文件**推**给电视，也可以从另一台手机**拉**文件 —— 不需要数据线、
 * 不需要云盘、不需要任何中转。这是决策 D-3（双向对等）的技术落点。
 *
 * ## 复用而不是重写
 * 传输本身**复用 HTTP 协议栈**，不另造一套私有协议。理由很实在：
 *  - 服务端已经有完整的鉴权、Range、零拷贝、路径校验，重造一遍等于把已知漏洞重造一遍
 *  - 复用 Range 就意味着分块并行、断点续传自动可用（[ChunkedTransferEngine] 直接能套上）
 *  - 调试成本骤降：抓包看到的就是普通 HTTP
 *
 * ## 与"接入确认弹窗"的配合
 * 第一次向某台设备推送时，对方会挂起并弹窗。我们这边的表现是：连接**暂时没有响应**，
 * 最长等 60 秒。所以读超时必须大于这个值，否则会把"用户还在犹豫"误报成"网络错误"。
 */
class PeerClient(
    private val storage: StorageProvider,
    private val chunkEngine: ChunkedTransferEngine,
    private val dispatcher: CoroutineDispatcher
) {

    data class PeerEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val mime: String
    )

    data class Session(
        val token: String,
        val deviceName: String,
        val writable: Boolean
    )

    // ==================== 握手 ====================

    /**
     * 登记并取令牌。对方可能会弹窗，因此这一步最慢要等 60 秒。
     */
    suspend fun announce(
        device: NearbyDevice,
        selfName: String,
        selfType: DeviceType
    ): Result<Session> = withContext(dispatcher) {
        val body = Json.obj(
            "name" to selfName,
            "type" to when (selfType) {
                DeviceType.TV -> "tv"
                DeviceType.PC -> "pc"
                else -> "phone"
            }
        )
        val response = request(
            device = device,
            method = "POST",
            path = "/api/peer/announce",
            contentType = "application/json; charset=utf-8",
            bodyBytes = body.toByteArray(Charsets.UTF_8),
            token = null
        )
        when {
            response == null -> Result.failure(IllegalStateException("无法连接 ${device.name}，请确认对方服务已开启"))
            response.status == 403 -> Result.failure(IllegalStateException("${device.name} 拒绝了本次连接"))
            response.status !in 200..299 -> Result.failure(
                IllegalStateException("${device.name} 返回错误：${response.bodyText.take(200)}")
            )

            else -> {
                val reader = JsonReader.of(response.bodyText)
                val token = reader.str("token")
                if (token.isNullOrBlank()) {
                    Result.failure(IllegalStateException("${device.name} 未返回访问令牌"))
                } else {
                    Result.success(
                        Session(
                            token = token,
                            deviceName = reader.str("device") ?: device.name,
                            writable = reader.bool("writable") ?: false
                        )
                    )
                }
            }
        }
    }

    // ==================== 浏览对方目录 ====================

    suspend fun listRemote(device: NearbyDevice, token: String, path: String = ""): Result<List<PeerEntry>> =
        withContext(dispatcher) {
            val response = request(
                device = device,
                method = "GET",
                path = "/api/fs/list?path=" + NetworkUtils.urlEncode(path),
                token = token
            ) ?: return@withContext Result.failure(IllegalStateException("无法连接 ${device.name}"))

            if (response.status !in 200..299) {
                return@withContext Result.failure(
                    IllegalStateException("列目录失败：${response.status} ${response.bodyText.take(120)}")
                )
            }
            val reader = JsonReader.of(response.bodyText)
            if (reader.bool("ok") != true) {
                return@withContext Result.failure(
                    IllegalStateException(reader.str("error") ?: "列目录失败")
                )
            }
            val items = reader.objects("items").mapNotNull { raw ->
                val r = JsonReader.of(raw)
                val name = r.str("name") ?: return@mapNotNull null
                PeerEntry(
                    name = name,
                    path = r.str("path") ?: name,
                    isDirectory = r.bool("dir") ?: false,
                    size = r.long("size") ?: 0L,
                    mime = r.str("mime") ?: ""
                )
            }
            Result.success(items)
        }

    // ==================== 推送（本机 → 对方） ====================

    /**
     * 推送一个文件到对方设备。
     *
     * 流程：占位取名 → 大文件走多路分块并行 PUT → 小文件单路 PUT。
     * 占位这一步不能省：并行上传要求各路写同一个文件名，各自取名会产生多份残片。
     */
    suspend fun pushFile(
        device: NearbyDevice,
        token: String,
        localRelPath: String,
        remoteDir: String,
        onProgress: (Long) -> Unit = {},
        /**
         * 分片边界的挂起检查点，用来支撑"暂停"（见 [ChunkedTransferEngine.transfer]）。
         * 没有它，暂停只能靠断开连接实现，而重连要走一遍占位与权限确认。
         */
        checkpoint: suspend () -> Unit = {}
    ): Result<TransferOutcome> = withContext(dispatcher) {
        val source = storage.openRead(localRelPath)
            ?: return@withContext Result.failure(IllegalStateException("本地文件不存在：$localRelPath"))
        try {
            val size = if (source.size > 0) source.size else storage.stat(localRelPath)?.size ?: 0L
            if (size <= 0) {
                return@withContext Result.failure(IllegalStateException("文件为空或无法读取大小"))
            }

            // 1) 占位：让对方决定最终文件名（不覆盖已有文件）
            val reserve = request(
                device = device,
                method = "POST",
                path = "/api/fs/reserve",
                contentType = "application/json; charset=utf-8",
                bodyBytes = Json.obj(
                    "name" to PathUtil.fileName(localRelPath),
                    "dir" to remoteDir
                ).toByteArray(Charsets.UTF_8),
                token = token
            ) ?: return@withContext Result.failure(IllegalStateException("无法连接 ${device.name}"))
            val reserveReader = JsonReader.of(reserve.bodyText)
            val remotePath = reserveReader.str("path")
                ?: return@withContext Result.failure(
                    IllegalStateException(reserveReader.str("error") ?: "对方未返回可用文件名")
                )

            // 2) 传输
            val outcome = chunkEngine.transfer(
                totalBytes = size,
                onProgress = onProgress,
                checkpoint = checkpoint
            ) { chunk ->
                val sent = uploadChunk(device, token, remotePath, chunk, source, size, checkpoint)
                chunk.report(sent)
                sent
            }
            if (!outcome.success) {
                return@withContext Result.failure(
                    IllegalStateException("传输不完整（${outcome.percent}%），失败分块 ${outcome.failedChunks}")
                )
            }
            PocketLog.i(TAG, "推送到 ${device.name} 完成：$remotePath（${outcome.chunkCount} 路）")
            Result.success(outcome)
        } finally {
            runCatching { source.close() }
        }
    }

    private suspend fun uploadChunk(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        chunk: ChunkRangeWithOffset,
        source: com.pocketserver.data.storage.ReadSource,
        totalSize: Long,
        checkpoint: suspend () -> Unit = {}
    ): Long = withContext(dispatcher) {
        val url = URL(
            "http://${device.host}:${device.httpPort}/api/fs/file?path=" +
                NetworkUtils.urlEncode(remotePath) + "&offset=" + chunk.start
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = TRANSFER_READ_TIMEOUT_MS
            setRequestProperty("X-Auth-Token", token)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty(
                "Content-Range",
                "bytes ${chunk.start}-${chunk.endExclusive - 1}/$totalSize"
            )
            setFixedLengthStreamingMode(chunk.length)
        }

        try {
            var sent = 0L
            val buffer = ByteArray(COPY_BUFFER)
            connection.outputStream.use { out ->
                val input = source.streamOrNull()
                if (input != null) {
                    skipTo(input, chunk.start)
                    var remaining = chunk.length
                    while (remaining > 0) {
                        // 每个缓冲区都问一次，暂停才可能在"分片内部"就停下来。
                        // 只靠分片边界的话，一个 64MB 的片子要等它整块发完才有反应。
                        checkpoint()
                        val want = minOf(buffer.size.toLong(), remaining).toInt()
                        val n = input.read(buffer, 0, want)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        remaining -= n
                        sent += n
                    }
                } else {
                    // 有 FileChannel 时用自己的通道切片读，避免 InputStream 的额外包装
                    val channel = source.channelOrNull()
                    if (channel != null) {
                        val wrapped = java.nio.ByteBuffer.wrap(buffer)
                        var pos = chunk.start
                        var remaining = chunk.length
                        while (remaining > 0) {
                            checkpoint()
                            wrapped.clear()
                            wrapped.limit(minOf(buffer.size.toLong(), remaining).toInt())
                            val n = channel.read(wrapped, pos)
                            if (n <= 0) break
                            pos += n
                            remaining -= n
                            out.write(buffer, 0, n)
                            sent += n
                        }
                    }
                }
                out.flush()
            }
            val code = connection.responseCode
            if (code == 409) {
                // SAF 模式对方不支持分块：告诉上层，由它降级为单通路
                throw UnsupportedOperationException("对方存储模式不支持分块写入")
            }
            if (code !in 200..299) {
                throw IllegalStateException("分块 ${chunk.index} 上传失败：HTTP $code")
            }
            sent
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ==================== 拉取（对方 → 本机） ====================

    /**
     * 从对方设备拉一个文件到本机。
     *
     * 单路拉取即可在 5GHz 下打到 50MB/s 以上，因此**大文件才启用多路**：
     * 多路的收益来自"单条 TCP 流填不满带宽"，而局域网 RTT 极低，
     * 单流通常已接近链路上限（这也是文档里强调的"不要无限加并发"）。
     */
    suspend fun pullFile(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        localDir: String,
        remoteSize: Long,
        onProgress: (Long) -> Unit = {},
        /**
         * 本机落盘路径的回调，在文件刚被创建、还没写数据时触发。
         *
         * 存在理由只有一个：**取消时要知道删哪个文件**。落盘名是这里算出来的
         * （要去重、要拼目录），调用方拿不到，不给这个回调就只能眼睁睁看着
         * 一个半截文件留在用户的文件夹里 —— 那正是"用得越多垃圾越多"的来源。
         */
        onOpened: (String) -> Unit = {},
        checkpoint: suspend () -> Unit = {}
    ): Result<TransferOutcome> = withContext(dispatcher) {
        if (remoteSize <= 0) {
            return@withContext Result.failure(IllegalStateException("无法获取远端文件大小"))
        }
        PathUtil.parent(PathUtil.join(localDir, PathUtil.fileName(remotePath)))
            ?.let { if (it.isNotBlank() && !storage.exists(it)) storage.mkdir(it) }

        val existing = storage.childNames(localDir)
        val fileName = PathUtil.dedupe(PathUtil.fileName(remotePath)) { existing.contains(it) }
        val localPath = PathUtil.join(localDir, fileName)

        // 优先用 position=0 打开：这会走 RandomAccessFile 通路，才能 preallocate
        // 并且支持多路按位点写入。SAF 模式下拿不到随机写，只能退化为单路顺序写。
        val randomAccessSink = storage.openWrite(localPath, append = false, position = 0)
        val sink = randomAccessSink
            ?: storage.openWrite(localPath, append = false, position = null)
            ?: return@withContext Result.failure(IllegalStateException("无法在本机创建文件"))
        val canRandomWrite = randomAccessSink != null && sink.supportsRandomAccess
        onOpened(localPath)

        try {
            sink.preallocate(remoteSize)
            val outcome = chunkEngine.transfer(
                totalBytes = remoteSize,
                lanes = if (canRandomWrite) chunkEngine.defaultLanes else 1,
                onProgress = onProgress,
                checkpoint = checkpoint
            ) { chunk ->
                val written = downloadChunk(device, token, remotePath, chunk, sink.channelOrNull, sink, checkpoint)
                chunk.report(written)
                written
            }
            if (!outcome.success) {
                return@withContext Result.failure(
                    IllegalStateException("拉取不完整（${outcome.percent}%），失败分块 ${outcome.failedChunks}")
                )
            }
            PocketLog.i(TAG, "从 ${device.name} 拉取完成：$localPath")
            Result.success(outcome)
        } finally {
            runCatching { sink.close() }
        }
    }

    private suspend fun downloadChunk(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        chunk: ChunkRangeWithOffset,
        channel: java.nio.channels.FileChannel?,
        sink: com.pocketserver.data.storage.WriteSink,
        checkpoint: suspend () -> Unit = {}
    ): Long = withContext(dispatcher) {
        val connection = openDownload(device, token, remotePath, chunk.start, chunk.endExclusive - 1)
        try {
            var received = 0L
            val buffer = ByteArray(COPY_BUFFER)
            connection.inputStream.use { input ->
                if (channel != null) {
                    // 有通道：按位点写入，是分块并行能落盘的关键
                    val wrapped = java.nio.ByteBuffer.wrap(buffer)
                    var pos = chunk.start
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        wrapped.clear()
                        wrapped.limit(n)
                        while (wrapped.hasRemaining()) {
                            val w = channel.write(wrapped, pos)
                            if (w <= 0) break
                            pos += w
                        }
                        received += n
                    }
                } else {
                    // SAF 无随机写：只能顺序追加，因此分块并行会自动退化成单路
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        sink.writeAt(buffer, 0, n, null)
                        received += n
                    }
                }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("分块 ${chunk.index} 下载失败：HTTP $code")
            }
            received
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ==================== 单路下载（供大目录批量拉取用，避免每文件都开多路） ====================

    suspend fun pullSingle(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        localDir: String,
        onProgress: (Long) -> Unit = {},
        checkpoint: suspend () -> Unit = {}
    ): Result<Long> = withContext(dispatcher) {
        val existing = storage.childNames(localDir)
        val fileName = PathUtil.dedupe(PathUtil.fileName(remotePath)) { existing.contains(it) }
        val localPath = PathUtil.join(localDir, fileName)
        val sink = storage.openWrite(localPath, append = false, position = null)
            ?: return@withContext Result.failure(IllegalStateException("无法在本机创建文件"))
        val progress = ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS, onProgress)
        try {
            val connection = openDownload(device, token, remotePath, null, null)
            var total = 0L
            try {
                if (connection.responseCode !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException("下载失败：HTTP ${connection.responseCode}")
                    )
                }
                val buffer = ByteArray(COPY_BUFFER)
                connection.inputStream.use { input ->
                    while (true) {
                        checkpoint()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        sink.writeAt(buffer, 0, n, null)
                        total += n
                        progress.update(total)
                    }
                }
            } finally {
                runCatching { connection.disconnect() }
            }
            progress.force(total)
            Result.success(total)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun openDownload(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        start: Long?,
        endInclusive: Long?
    ): HttpURLConnection {
        val url = URL(
            "http://${device.host}:${device.httpPort}/dl?path=" + NetworkUtils.urlEncode(remotePath)
        )
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = TRANSFER_READ_TIMEOUT_MS
            setRequestProperty("X-Auth-Token", token)
            if (start != null && endInclusive != null) {
                setRequestProperty("Range", "bytes=$start-$endInclusive")
            }
            instanceFollowRedirects = false
        }
    }

    // ==================== 基础请求 ====================

    private class SimpleResponse(val status: Int, val bodyText: String)

    private fun request(
        device: NearbyDevice,
        method: String,
        path: String,
        contentType: String? = null,
        bodyBytes: ByteArray? = null,
        token: String?
    ): SimpleResponse? {
        return try {
            val url = URL("http://${device.host}:${device.httpPort}$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                // 必须大于接入确认的最长等待 60 秒，否则会把"用户还在犹豫"误报成"网络错误"
                readTimeout = HANDSHAKE_READ_TIMEOUT_MS
                instanceFollowRedirects = false
                if (token != null) setRequestProperty("X-Auth-Token", token)
                if (contentType != null) setRequestProperty("Content-Type", contentType)
            }
            if (bodyBytes != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                BufferedOutputStream(connection.outputStream).use { it.write(bodyBytes) }
            }
            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            runCatching { connection.disconnect() }
            SimpleResponse(status, text)
        } catch (t: Throwable) {
            PocketLog.w(TAG, "请求 ${device.name}$path 失败：${t.message}")
            null
        }
    }

    /** 跳过 InputStream 的前 n 个字节（SAF 源无法 seek，只能顺序跳过）。 */
    private fun skipTo(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    companion object {
        private const val TAG = "PeerClient"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 75_000
        private const val TRANSFER_READ_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER = 256 * 1024
    }
}
