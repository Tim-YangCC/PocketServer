package com.pocketserver.net.http

import com.pocketserver.core.PocketLog
import com.pocketserver.net.SharingPolicy
import com.pocketserver.util.PathUtil
import java.io.IOException
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Locale

/** 一次访问记录，服务层负责落库（FR-7）。刻意用轻量结构，不把 Room 类型引到协议层。 */
data class AccessRecord(
    val timestamp: Long,
    val peerIp: String,
    val deviceName: String,
    val method: String,
    val path: String,
    val status: Int,
    val bytesSent: Long,
    val bytesReceived: Long,
    val detail: String = ""
)

// ==================== 请求 ====================

class HttpRequest(
    val method: String,
    val rawTarget: String,
    /** 已解码的路径，始终以 `/` 开头。 */
    val path: String,
    val query: Map<String, String>,
    val version: String,
    /** header 名统一小写存储，取值时无需再关心大小写。 */
    private val headers: Map<String, String>,
    val remoteIp: String,
    val contentLength: Long,
    val chunked: Boolean
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.US)]

    val keepAlive: Boolean
        get() {
            val conn = header("connection")?.lowercase(Locale.US) ?: ""
            return when {
                conn.contains("close") -> false
                version == "HTTP/1.0" -> conn.contains("keep-alive")
                else -> true
            }
        }

    /** Cookie 中的会话令牌。 */
    fun cookie(name: String): String? {
        val raw = header("cookie") ?: return null
        return raw.split(';').firstNotNullOfOrNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) return@firstNotNullOfOrNull null
            if (part.substring(0, idx).trim() == name) part.substring(idx + 1).trim() else null
        }
    }

    /** 请求头里的显式令牌（WebDAV 客户端、脚本调用更方便）。 */
    fun bearerToken(): String? {
        header("x-auth-token")?.takeIf { it.isNotBlank() }?.let { return it }
        val auth = header("authorization") ?: return null
        return if (auth.startsWith("Bearer ", true)) auth.substring(7).trim() else null
    }

    /** HTTP Basic 凭据：用户名任意，密码 = 配对码。用于不支持 Cookie 的老客户端。 */
    fun basicAuth(): Pair<String, String>? {
        val auth = header("authorization") ?: return null
        if (!auth.startsWith("Basic ", true)) return null
        return runCatching {
            val decoded = String(
                android.util.Base64.decode(auth.substring(6).trim(), android.util.Base64.DEFAULT),
                Charsets.UTF_8
            )
            val idx = decoded.indexOf(':')
            if (idx < 0) decoded to "" else decoded.substring(0, idx) to decoded.substring(idx + 1)
        }.getOrNull()
    }

    val clientNameGuess: String
        get() {
            val ua = header("user-agent") ?: return "浏览器"
            return when {
                ua.contains("Windows", true) -> "Windows 电脑"
                ua.contains("Macintosh", true) || ua.contains("Mac OS", true) -> "Mac 电脑"
                ua.contains("Android", true) -> "Android 设备"
                ua.contains("iPhone", true) || ua.contains("iPad", true) -> "iOS 设备"
                ua.contains("curl", true) -> "命令行工具"
                ua.contains("WebDAV", true) || ua.contains("Microsoft-WebDAV", true) -> "网络驱动器"
                ua.contains("davfs", true) -> "Linux 挂载"
                else -> "浏览器"
            }
        }

    val isWebDav: Boolean
        get() = method in setOf("PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "OPTIONS")
}

// ==================== 响应状态 ====================

object HttpStatus {
    const val OK = 200
    const val CREATED = 201
    const val NO_CONTENT = 204
    const val PARTIAL = 206
    const val MULTI_STATUS = 207
    const val MOVED_PERMANENTLY = 301
    const val FOUND = 302
    const val NOT_MODIFIED = 304
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val CONFLICT = 409

    /**
     * 目标已经没了。
     *
     * 本产品用它表达一件事：**这条传输被用户取消了**。
     * 客户端收到它就该彻底停手，不要重试 —— 与 [CONFLICT] 的语义刻意分开，
     * 那个表示"这次不行但可以换个方式再来"（SAF 通道不支持分块，退化成单路即可），
     * 两者混用会让前端把"取消"当成"换条路重试"，取消就变成了死不掉的循环。
     */
    const val GONE = 410
    const val LENGTH_REQUIRED = 411
    const val PRECONDITION_FAILED = 412
    const val PAYLOAD_TOO_LARGE = 413
    const val RANGE_NOT_SATISFIABLE = 416
    const val LOCKED = 423
    const val INTERNAL_ERROR = 500
    const val NOT_IMPLEMENTED = 501
    const val INSUFFICIENT_STORAGE = 507

    fun reason(status: Int): String = when (status) {
        OK -> "OK"
        CREATED -> "Created"
        NO_CONTENT -> "No Content"
        PARTIAL -> "Partial Content"
        MULTI_STATUS -> "Multi-Status"
        MOVED_PERMANENTLY -> "Moved Permanently"
        FOUND -> "Found"
        NOT_MODIFIED -> "Not Modified"
        BAD_REQUEST -> "Bad Request"
        UNAUTHORIZED -> "Unauthorized"
        FORBIDDEN -> "Forbidden"
        NOT_FOUND -> "Not Found"
        METHOD_NOT_ALLOWED -> "Method Not Allowed"
        CONFLICT -> "Conflict"
        LENGTH_REQUIRED -> "Length Required"
        PRECONDITION_FAILED -> "Precondition Failed"
        PAYLOAD_TOO_LARGE -> "Payload Too Large"
        RANGE_NOT_SATISFIABLE -> "Range Not Satisfiable"
        LOCKED -> "Locked"
        INTERNAL_ERROR -> "Internal Server Error"
        NOT_IMPLEMENTED -> "Not Implemented"
        INSUFFICIENT_STORAGE -> "Insufficient Storage"
        else -> "Unknown"
    }
}

// ==================== 字节区间（断点续传 / 分块并行 / 视频拖动） ====================

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

object RangeParser {
    /**
     * 解析 `Range: bytes=...`。
     *
     * 三种形式都要支持，少一种就会导致某个客户端的行为异常：
     *  - `bytes=0-499`  固定区间（分块并行的核心）
     *  - `bytes=500-`   到文件末尾（断点续传）
     *  - `bytes=-500`   最后 500 字节（部分播放器探测文件尾会这么发）
     */
    fun parse(header: String?, totalSize: Long): ByteRange? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("bytes=", true)) return null
        val spec = trimmed.substring(6).substringBefore(',').trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return when {
            startText.isEmpty() && endText.isEmpty() -> null

            startText.isEmpty() -> {
                val suffix = endText.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                val start = (totalSize - suffix).coerceAtLeast(0)
                ByteRange(start, totalSize - 1)
            }

            else -> {
                val start = startText.toLongOrNull() ?: return null
                if (start >= totalSize) return null
                val end = if (endText.isEmpty()) totalSize - 1 else (endText.toLongOrNull() ?: return null)
                ByteRange(start, minOf(end, totalSize - 1))
            }
        }
    }
}

// ==================== 内容处置（中文文件名） ====================

object ContentDisposition {
    /**
     * 生成 Content-Disposition。
     *
     * 这是中文文件名最常踩的坑：直接写 `filename="报告.pdf"` 时，
     * 非 ASCII 字节按 HTTP 头部的 ISO-8859-1 解释，浏览器会显示成乱码。
     * 正确做法是双写 —— 旧的 ASCII 兜底 + RFC 5987 的 `filename*`。
     * 现代浏览器优先取 `filename*`，老客户端取 ASCII 兜底，两边都不乱码。
     */
    fun attachment(fileName: String): String {
        val fallback = fileName.map { ch ->
            if (ch.code in 32..126 && ch != '"' && ch != '\\') ch else '_'
        }.joinToString("")
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''${percentEncode(fileName)}"
    }

    fun inline(fileName: String): String {
        val fallback = fileName.map { ch ->
            if (ch.code in 32..126 && ch != '"' && ch != '\\') ch else '_'
        }.joinToString("")
        return "inline; filename=\"$fallback\"; filename*=UTF-8''${percentEncode(fileName)}"
    }

    private fun percentEncode(value: String): String = buildString(value.length * 3) {
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            if (v in 0x41..0x5A || v in 0x61..0x7A || v in 0x30..0x39 ||
                v == 0x2D || v == 0x2E || v == 0x5F || v == 0x7E
            ) {
                append(v.toChar())
            } else {
                append('%').append("%02X".format(v))
            }
        }
    }
}

// ==================== 请求解析 ====================

/**
 * socket 读取器：自己做行缓冲。
 *
 * 为什么不直接用 `BufferedInputStream`：读文件上传时，如果框架已经预读了几 KB 到
 * 流缓冲里，就无法再用 `FileChannel.transferFrom` 做零拷贝落盘（会丢掉那几 KB）。
 * 自己管理缓冲才能在最恰当的时点把控制权交给通道。
 */
class SocketReader(private val channel: SocketChannel) {

    private val buffer: ByteBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private val line = StringBuilder(256)
    /** 当前是否处于"通道里还有数据"状态；由 [drain] 显式排空后才允许直读通道。 */
    private var drained = false

    init {
        buffer.limit(0)
    }

    private fun fill(): Boolean {
        if (drained) return false
        buffer.compact()
        val n = try {
            channel.read(buffer)
        } catch (_: IOException) {
            -1
        }
        buffer.flip()
        if (n <= 0) {
            drained = true
            return false
        }
        return true
    }

    /** 逐字节读一行（不含 CRLF）。返回 null 表示连接已结束。 */
    fun readLine(): String? {
        line.setLength(0)
        while (true) {
            if (buffer.hasRemaining()) {
                val b = (buffer.get().toInt() and 0xFF).toChar()
                if (b == '\n') {
                    if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                    return line.toString()
                }
                line.append(b)
                if (line.length > MAX_LINE) return null
            } else if (!fill()) {
                return if (line.isEmpty()) null else line.toString()
            }
        }
    }

    fun readInto(dst: ByteArray, offset: Int, length: Int): Int {
        if (!buffer.hasRemaining() && !fill()) {
            return if (buffer.hasRemaining()) continueRead(dst, offset, length) else -1
        }
        return continueRead(dst, offset, length)
    }

    private fun continueRead(dst: ByteArray, offset: Int, length: Int): Int {
        if (!buffer.hasRemaining()) return 0
        val n = minOf(buffer.remaining(), length)
        buffer.get(dst, offset, n)
        return n
    }

    /** 已缓冲但未消费的字节数。 */
    fun bufferedCount(): Int = buffer.remaining()

    /** 把已缓冲的数据交给 [sink]，之后 [channel] 可以被直读。 */
    fun drain(sink: (ByteArray, Int, Int) -> Unit) {
        if (buffer.hasRemaining()) {
            val arr = ByteArray(buffer.remaining())
            buffer.get(arr)
            sink(arr, 0, arr.size)
        }
        drained = true
    }

    fun channel(): SocketChannel = channel

    private companion object {
        const val BUFFER_SIZE = 8 * 1024
        const val MAX_LINE = 8 * 1024
    }
}

object HttpParser {

    /** 允许的最大请求头总字节数，防止构造超大头做内存耗尽攻击。 */
    private const val MAX_HEADER_BYTES = 32 * 1024
    private const val MAX_HEADER_COUNT = 64

    /**
     * 解析请求行与请求头。返回 null 表示对端关闭或报文非法。
     */
    fun readRequest(reader: SocketReader, remoteIp: String): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        if (requestLine.isBlank()) return null

        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val rawTarget = parts[1]
        val version = if (parts.size >= 3) parts[2] else "HTTP/1.0"

        val headers = mutableMapOf<String, String>()
        var totalBytes = requestLine.length
        var count = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            totalBytes += line.length
            count++
            if (totalBytes > MAX_HEADER_BYTES || count > MAX_HEADER_COUNT) {
                PocketLog.w(TAG, "请求头过大，拒绝：$remoteIp")
                return null
            }
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            // 同名头合并（如多个 Cookie），不合并会悄悄丢掉后面那几个
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }

        val (path, query) = splitTarget(rawTarget)
        val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true

        return HttpRequest(
            method = method,
            rawTarget = rawTarget,
            path = path,
            query = query,
            version = version,
            headers = headers,
            remoteIp = remoteIp,
            contentLength = contentLength,
            chunked = chunked
        )
    }

    /** 拆出路径与查询参数。路径按 UTF-8 解码（中文目录名必须正确解码才能列目录）。 */
    fun splitTarget(rawTarget: String): Pair<String, Map<String, String>> {
        val withoutFragment = rawTarget.substringBefore('#')
        val qIdx = withoutFragment.indexOf('?')
        val rawPath = if (qIdx >= 0) withoutFragment.substring(0, qIdx) else withoutFragment
        val rawQuery = if (qIdx >= 0) withoutFragment.substring(qIdx + 1) else ""

        val path = decodeComponent(rawPath).let { if (it.startsWith("/")) it else "/$it" }

        val query = mutableMapOf<String, String>()
        if (rawQuery.isNotEmpty()) {
            for (pair in rawQuery.split('&')) {
                if (pair.isEmpty()) continue
                val idx = pair.indexOf('=')
                if (idx < 0) {
                    query[decodeComponent(pair)] = ""
                } else {
                    query[decodeComponent(pair.substring(0, idx))] = decodeComponent(pair.substring(idx + 1))
                }
            }
        }
        return path to query
    }

    fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private const val TAG = "HttpParser"
}

// ==================== 响应写出 ====================

/**
 * 一次 HTTP 交换。所有响应都必须经过这里写出，理由：
 *  1. 统一注入安全响应头（nosniff / no-referrer / 禁用缓存），避免某个 handler 漏加；
 *  2. 统一统计流量（供 SpeedMeter 使用，是控制台实时速率的唯一数据源）；
 *  3. 统一处理 keep-alive 与 Content-Length，杜绝"响应写完了但客户端还在等"的经典挂死。
 */
class HttpExchange(
    val request: HttpRequest,
    private val reader: SocketReader,
    private val socket: SocketChannel,
    private val policyProvider: () -> SharingPolicy
) {

    var bytesSent: Long = 0L
        private set
    var bytesReceived: Long = 0L
        private set
    var status: Int = 0
        private set
    /**
     * 本次请求的一句话结论，写进访问日志给用户看（"下载 1.2GB，5GHz，直连通道"）。
     *
     * setter 是 `internal` 而非 `private`：写它的是各协议处理器（HttpFileServer /
     * WebDavHandler），它们是**同一个模块**里的兄弟类，不是内部类。
     * 这里保持"本模块可写、模块外只读"，是为了让日志文案统一由处理器决定，
     * 又避免把可变状态暴露给上层业务。
     */
    var respondDetail: String = ""
        internal set

    /** 响应是否已写出（写完才能进入下一个 keep-alive 循环）。 */
    var committed = false
        private set

    val socketChannel: SocketChannel get() = socket
    val policy: SharingPolicy get() = policyProvider()

    // ---- 头部写出 ----

    private fun writeHead(
        statusCode: Int,
        contentType: String?,
        contentLength: Long,
        extraHeaders: Map<String, String>,
        chunked: Boolean = false
    ) {
        val sb = StringBuilder(512)
        sb.append("HTTP/1.1 ").append(statusCode).append(' ')
            .append(HttpStatus.reason(statusCode)).append("\r\n")
        sb.append("Server: PocketServer\r\n")
        sb.append("Date: ").append(com.pocketserver.util.Formatters.httpDate(System.currentTimeMillis()))
            .append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        if (chunked) sb.append("Transfer-Encoding: chunked\r\n")
        else sb.append("Content-Length: ").append(contentLength).append("\r\n")
        sb.append("Connection: ").append(if (request.keepAlive) "keep-alive" else "close").append("\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Referrer-Policy: no-referrer\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        for ((k, v) in extraHeaders) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")

        status = statusCode
        writeRaw(sb.toString().toByteArray(Charsets.UTF_8))
        committed = true
    }

    private fun writeRaw(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            val n = try {
                socket.write(buffer)
            } catch (_: IOException) {
                return
            }
            if (n <= 0) break
            bytesSent += n
        }
    }

    // ---- 常见响应形态 ----

    fun json(body: String, statusCode: Int = HttpStatus.OK, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(statusCode, "application/json; charset=utf-8", bytes.size.toLong(), extraHeaders)
        writeRaw(bytes)
    }

    fun text(body: String, statusCode: Int = HttpStatus.OK) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(statusCode, "text/plain; charset=utf-8", bytes.size.toLong(), emptyMap())
        writeRaw(bytes)
    }

    fun html(body: String, statusCode: Int = HttpStatus.OK) {        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(
            statusCode, "text/html; charset=utf-8", bytes.size.toLong(),
            mapOf(
                "Cache-Control" to "no-store",
                // 网页端是自研 SPA，只允许同源脚本，杜绝外部注入
                "Content-Security-Policy" to
                    "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; media-src 'self'; script-src 'self' 'unsafe-inline'"
            )
        )
        writeRaw(bytes)
    }

    /**
     * WebDAV 的 207 Multi-Status 等 XML 响应。
     * WebDAV 客户端（尤其 Windows WebClient）对 XML 声明的存在很敏感，缺了会静默失败。
     */
    fun xml(body: String, statusCode: Int = HttpStatus.OK, extraHeaders: Map<String, String> = emptyMap()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(statusCode, "application/xml; charset=utf-8", bytes.size.toLong(), extraHeaders)
        writeRaw(bytes)
    }

    fun error(statusCode: Int, message: String, asJson: Boolean = false) {
        if (asJson) {
            json("""{"ok":false,"error":${escape(message)}}""", statusCode)
        } else {
            text(message, statusCode)
        }
    }

    fun redirect(location: String, permanent: Boolean = false) {
        writeHead(
            if (permanent) HttpStatus.MOVED_PERMANENTLY else HttpStatus.FOUND,
            "text/html; charset=utf-8", 0L,
            mapOf("Location" to location)
        )
    }

    /**
     * 发送文件区间。
     *
     * 这是整个产品的性能关键路径（O-2 + O-3）：
     *  - 源有 FileChannel 时走 `transferTo` 零拷贝；
     *  - 没有（SAF 场景）时走池化缓冲搬运；
     *  - 支持 Range，因此断点续传、视频拖动、多连接分块并行都复用这一个方法。
     *
     * @return 实际发送字节数。调用方需据此判断是否为完整传输。
     */
    fun sendFileRange(
        readStart: Long,
        totalSize: Long,
        contentType: String,
        fileName: String,
        range: ByteRange?,
        shareChannel: java.nio.channels.FileChannel?,
        shareStream: java.io.InputStream?,
        inline: Boolean,
        zeroCopy: com.pocketserver.perf.ZeroCopySender,
        onProgress: (Long) -> Unit,
        isCanceled: () -> Boolean,
        /** 调用方额外的响应头，如对可执行内容补一个 `Content-Security-Policy: sandbox`。 */
        extraHeaders: Map<String, String> = emptyMap()
    ): Long {
        val start = range?.start ?: readStart
        val end = range?.endInclusive ?: (totalSize - 1)
        val length = end - start + 1

        val headers = mutableMapOf(
            "Content-Disposition" to
                if (inline) ContentDisposition.inline(fileName) else ContentDisposition.attachment(fileName),
            "Last-Modified" to com.pocketserver.util.Formatters.httpDate(System.currentTimeMillis()),
            "Cache-Control" to "no-store"
        )
        headers.putAll(extraHeaders)
        val statusCode = if (range != null) HttpStatus.PARTIAL else HttpStatus.OK
        if (range != null) {
            headers["Content-Range"] = "bytes $start-$end/$totalSize"
        }

        writeHead(statusCode, contentType, length, headers)

        val sent = when {
            shareChannel != null -> zeroCopy.sendFile(
                shareChannel, start, length, socket, onProgress, isCanceled
            )

            shareStream != null -> {
                // 流式通路：先跳过区间前的字节（SAF 无法 seek，只能顺序跳过）
                var toSkip = start
                val skipBuffer = ByteArray(64 * 1024)
                while (toSkip > 0 && !isCanceled()) {
                    val n = shareStream.read(skipBuffer, 0, minOf(skipBuffer.size.toLong(), toSkip).toInt())
                    if (n <= 0) break
                    toSkip -= n
                }
                zeroCopy.sendStream(shareStream, socket, onProgress, isCanceled)
            }

            else -> 0L
        }
        bytesSent += sent
        return sent
    }

    /**
     * 消费请求体。
     *
     * @param sink 收到数据块时调用，返回 false 表示落盘失败需要中断
     * @return 实际消费的字节数
     */
    fun consumeBody(
        fileChannel: java.nio.channels.FileChannel?,
        sink: ((ByteArray, Int, Int) -> Int)?,
        zeroCopy: com.pocketserver.perf.ZeroCopySender?,
        position: Long?,
        onProgress: (Long) -> Unit,
        isCanceled: () -> Boolean
    ): Long {
        val total = if (request.chunked) -1L else request.contentLength
        var received = 0L
        val buffer = ByteArray(FALLBACK_BODY_BUFFER)

        try {
            if (request.chunked) {
                received += consumeChunked(sink, onProgress, isCanceled)
                bytesReceived += received
                return received
            }
            if (total <= 0) return 0L

            var remaining = total
            // 优先处理框架已预读的字节：不处理会丢掉请求体开头，造成文件损坏
            val buffered = reader.bufferedCount()
            if (buffered > 0) {
                val take = minOf(buffered.toLong(), remaining).toInt()
                val arr = ByteArray(take)
                var read = 0
                while (read < take) {
                    val n = reader.readInto(arr, read, take - read)
                    if (n <= 0) break
                    read += n
                }
                if (read > 0) {
                    val accepted = sink?.invoke(arr, 0, read) ?: (if (fileChannel != null) writeChannel(fileChannel, arr, 0, read, position?.plus(received)) else read)
                    received += read
                    remaining -= read
                    onProgress(received)
                }
            }

            // 缓冲排空后：有通道就用 transferFrom 零拷贝接收
            if (fileChannel != null && zeroCopy != null && remaining > 0 && !isCanceled()) {
                reader.drain { arr, off, len -> }
                val n = zeroCopy.receiveToFile(
                    socket, fileChannel, (position ?: 0L) + received, remaining,
                    { onProgress(received + it) }, isCanceled
                )
                received += n
                bytesReceived += received
                return received
            }

            while (remaining > 0 && !isCanceled()) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val n = reader.readInto(buffer, 0, want)
                if (n <= 0) break
                if (fileChannel != null) {
                    writeChannel(fileChannel, buffer, 0, n, position?.plus(received))
                } else {
                    sink?.invoke(buffer, 0, n)
                }
                received += n
                remaining -= n
                onProgress(received)
            }
        } catch (t: Throwable) {
            PocketLog.w(TAG, "接收请求体中断于 $received 字节：${t.message}")
        }

        bytesReceived += received
        return received
    }

    private fun writeChannel(
        channel: java.nio.channels.FileChannel,
        data: ByteArray,
        offset: Int,
        length: Int,
        position: Long?
    ): Int {
        val buffer = ByteBuffer.wrap(data, offset, length)
        return if (position != null) {
            var pos = position
            var written = 0
            while (buffer.hasRemaining()) {
                val n = channel.write(buffer, pos)
                if (n <= 0) break
                pos += n
                written += n
            }
            written
        } else {
            var written = 0
            while (buffer.hasRemaining()) {
                val n = channel.write(buffer)
                if (n <= 0) break
                written += n
            }
            written
        }
    }

    /** 解析 chunked 请求体（部分 HTTP 客户端上传大文件时会用）。 */
    private fun consumeChunked(
        sink: ((ByteArray, Int, Int) -> Int)?,
        onProgress: (Long) -> Unit,
        isCanceled: () -> Boolean
    ): Long {
        var received = 0L
        val buffer = ByteArray(FALLBACK_BODY_BUFFER)
        while (!isCanceled()) {
            val sizeLine = reader.readLine() ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                reader.readLine()
                break
            }
            var remaining = size
            while (remaining > 0) {
                val n = reader.readInto(buffer, 0, minOf(buffer.size, remaining))
                if (n <= 0) return received
                sink?.invoke(buffer, 0, n)
                remaining -= n
                received += n
                onProgress(received)
            }
            reader.readLine() // 块尾 CRLF
        }
        return received
    }

    /**
     * 只写响应头，响应体由调用方自己往 socket 写。
     * 用于测速端点这类"边算边发、拿不到总字节流"的场景。
     */
    fun beginBinaryResponse(
        statusCode: Int,
        contentType: String,
        contentLength: Long,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeHead(statusCode, contentType, contentLength, extraHeaders)
    }

    /** 开启流式响应，适合 ZIP 等长度未知且不能整体缓存到内存的内容。 */
    fun beginChunkedBinaryResponse(
        statusCode: Int,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeHead(statusCode, contentType, 0, extraHeaders, chunked = true)
    }

    /** 以 HTTP/1.1 chunked framing 发送一块响应数据。 */
    fun writeChunked(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) return
        val prefix = (length.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII)
        if (writeDirect(prefix, 0, prefix.size) != prefix.size) throw IOException("响应连接已断开")
        if (writeDirect(bytes, offset, length) != length) throw IOException("响应连接已断开")
        val suffix = "\r\n".toByteArray(Charsets.US_ASCII)
        if (writeDirect(suffix, 0, suffix.size) != suffix.size) throw IOException("响应连接已断开")
    }

    /** 结束流式响应。 */
    fun finishChunkedResponse() {
        val end = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)
        if (writeDirect(end, 0, end.size) != end.size) throw IOException("响应连接已断开")
    }

    /** 头已写好后，直接往 socket 写一块数据。返回写入字节数。 */
    fun writeDirect(bytes: ByteArray, offset: Int, length: Int): Int {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        var written = 0
        while (buffer.hasRemaining()) {
            val n = try {
                socket.write(buffer)
            } catch (_: IOException) {
                break
            }
            if (n <= 0) break
            written += n
        }
        if (written > 0) bytesSent += written
        return written
    }

    /**
     * 小块读取请求体（JSON 参数体这类）。
     * 只走缓冲，不参与零拷贝判别，因此不需要通道信息。
     */
    fun consumeBodyChunk(dst: ByteArray, offset: Int, length: Int): Int {
        val n = reader.readInto(dst, offset, length)
        if (n > 0) bytesReceived += n
        return n
    }

    private fun escape(value: String): String = buildString(value.length + 8) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
        append('"')
    }

    companion object {
        private const val TAG = "HttpExchange"

        /** 收上传用的兜底缓冲：仅在非通道路径使用，256KB 与主缓冲保持一致。 */
        const val FALLBACK_BODY_BUFFER = 256 * 1024
    }
}

/** URL 路径 → 存储相对路径。是四条协议栈共用的唯一转换点（安全设计 §7.3）。 */
fun urlPathToStoragePath(urlPath: String): String? {
    val decoded = HttpParser.decodeComponent(urlPath)
    return PathUtil.sanitize(decoded)
}
