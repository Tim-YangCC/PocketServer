package com.pocketserver.net.http

import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.repo.TransferRecorder
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.net.SharingPolicy
import com.pocketserver.util.Formatters
import com.pocketserver.util.PathUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebDAV 处理（FR-5：把手机映射成电脑上的网络驱动器）。
 *
 * ## 为什么值得做
 * WebDAV 是唯一能让手机文件出现在"此电脑"里、并且能被任意软件（Office、Photoshop、
 * 播放器）直接打开的方式。网页版虽然好用，但每次都要开浏览器、且不能保存。
 * 映射成 Z: 盘之后，体验就等价于一个本地磁盘。
 *
 * ## Windows 特有的三个坑（都已处理）
 *  1. **必须响应 OPTIONS 并声明 `DAV: 1,2`**，否则 Windows 认为这不是 WebDAV 服务器，
 *     映射会直接失败并给出一个毫无信息量的 0x80070005。
 *  2. **必须实现 LOCK / UNLOCK**。Windows 的 WebClient 服务在写入前会先请求锁，
 *     不实现就会卡在"正在连接到 …"，最终超时。
 *     V1.0 采用**假锁**：返回一个格式合法但服务端不校验的锁令牌。
 *     局域网单用户场景下这足够且更简单；代价是两个客户端同时编辑同一文件不会互斥。
 *  3. **MOVE 的 Destination 头是完整 URL**（含主机与端口），要正确切出路径部分。
 *
 * ## 已知限制（有意为之）
 *  - 分块并行上传不适用（WebDAV 客户端不会切片），走单连接
 *  - SAF 降级模式下 PROPFIND 会慢（每次要跨进程查询），属预期
 */
class WebDavHandler(
    private val storage: StorageProvider,
    private val policyProvider: () -> SharingPolicy,
    private val deps: HttpFileServer.Deps
) {

    /** 假锁表：token → 路径。仅用于让 Windows 拿到一个"看起来正确"的令牌。 */
    private val locks = ConcurrentHashMap<String, String>()

    fun releaseAllLocks() {
        locks.clear()
    }

    fun handle(exchange: HttpExchange, davPath: String) {
        when (exchange.request.method) {
            "PROPFIND" -> propfind(exchange, davPath)
            "PROPPATCH" -> propPatch(exchange)
            "MKCOL" -> mkcol(exchange, davPath)
            "GET", "HEAD" -> get(exchange, davPath)
            "PUT" -> put(exchange, davPath)
            "DELETE" -> delete(exchange, davPath)
            "MOVE" -> move(exchange, davPath)
            "COPY" -> copy(exchange, davPath)
            "LOCK" -> lock(exchange, davPath)
            "UNLOCK" -> unlock(exchange)
            "OPTIONS" -> exchange.json(
                Json0.EMPTY, 200,
                mapOf("DAV" to "1,2", "Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK")
            )

            else -> exchange.error(HttpStatus.NOT_IMPLEMENTED, "WebDAV 不支持 ${exchange.request.method}")
        }
    }

    private fun storagePath(davPath: String): String? = urlPathToStoragePath(davPath)

    // ==================== PROPFIND：列目录 ====================

    /**
     * 返回 207 Multi-Status。
     *
     * Depth 语义必须正确，否则资源管理器会显示成空目录：
     *  - `0`：只返回自己（Windows 先探测根节点时会用）
     *  - `1`：返回自己 + 直接子项（列目录的关键）
     */
    private fun propfind(exchange: HttpExchange, davPath: String) {
        val relative = storagePath(davPath)
        if (relative == null) {
            exchange.error(HttpStatus.BAD_REQUEST, "路径非法")
            return
        }
        val entry = storage.stat(relative)
        if (entry == null) {
            exchange.error(HttpStatus.NOT_FOUND, "不存在：$davPath")
            return
        }

        val depth = exchange.request.header("depth")?.trim() ?: "1"
        val policy = policyProvider()

        val bodies = StringBuilder(1024)
        bodies.append(resourceXml(relative, entry.isDirectory, entry.size, entry.lastModified))

        if (depth != "0" && entry.isDirectory) {
            val children = storage.list(relative)
                .filter { policy.showHiddenFiles || !PathUtil.isHidden(it.relPath) }
            for (child in children) {
                bodies.append(
                    resourceXml(child.relPath, child.isDirectory, child.size, child.lastModified)
                )
            }
        }

        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
$bodies</D:multistatus>
"""
        exchange.xml(xml, HttpStatus.MULTI_STATUS)
    }

    private fun resourceXml(relPath: String, isDir: Boolean, size: Long, modified: Long): String {
        val href = "/dav/" + relPath.split('/').joinToString("/") {
            com.pocketserver.util.NetworkUtils.urlEncode(it)
        }.let { if (isDir) "$it/" else it }
        val resourceType = if (isDir) "<D:collection/>" else ""
        val length = if (isDir) 0 else size
        val mime = if (isDir) "" else PathUtil.mimeType(relPath)
        return """
  <D:response>
    <D:href>$href</D:href>
    <D:propstat>
      <D:prop>
        <D:displayname>${escapeXml(PathUtil.fileName(relPath).ifEmpty { "root" })}</D:displayname>
        <D:resourcetype>$resourceType</D:resourcetype>
        <D:getcontentlength>$length</D:getcontentlength>
        <D:getcontenttype>${escapeXml(mime)}</D:getcontenttype>
        <D:getlastmodified>${Formatters.httpDate(modified)}</D:getlastmodified>
        <D:creationdate>${isoDate(modified)}</D:creationdate>
        <D:supportedlock>
          <D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry>
        </D:supportedlock>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
"""
    }

    private fun propPatch(exchange: HttpExchange) {
        // Windows 会尝试写入 Win32 属性；我们要么照做，要么明确告知"这些属性没保存"
        exchange.xml(
            """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat>
      <D:prop/>
      <D:status>HTTP/1.1 403 Forbidden</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>
""",
            HttpStatus.MULTI_STATUS
        )
    }

    // ==================== 目录与文件操作 ====================

    private fun mkcol(exchange: HttpExchange, davPath: String) {
        val policy = policyProvider()
        if (!policy.canMkdirAllowed) {
            exchange.error(HttpStatus.FORBIDDEN, policy.readOnlyMessage)
            return
        }
        val relative = storagePath(davPath)
        if (relative.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "路径非法")
            return
        }
        val ok = storage.mkdir(relative)
        if (ok) {
            exchange.respondDetail = "WebDAV 建目录"
            exchange.beginBinaryResponse(HttpStatus.CREATED, "text/plain", 0)
        } else {
            exchange.error(HttpStatus.CONFLICT, "创建失败")
        }
    }

    private fun get(exchange: HttpExchange, davPath: String) {
        val relative = storagePath(davPath)
        if (relative.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "路径非法")
            return
        }
        val entry = storage.stat(relative)
        if (entry == null) {
            exchange.error(HttpStatus.NOT_FOUND, "不存在")
            return
        }
        if (entry.isDirectory) {
            propfind(exchange, davPath)
            return
        }
        val source = storage.openRead(relative)
        if (source == null) {
            exchange.error(HttpStatus.FORBIDDEN, "无法读取")
            return
        }
        // 在 try 之外声明：异常路径也要收尾，否则中断的下载会永远停在"进行中"
        var record: TransferRecorder.Handle? = null
        var sent = 0L
        try {
            val total = if (entry.size > 0) entry.size else source.size
            val range = RangeParser.parse(exchange.request.header("range"), total)
            // HEAD 只回响应头、不传字节，不算一次传输
            if (exchange.request.method == "HEAD") {
                exchange.beginBinaryResponse(
                    HttpStatus.OK, entry.mimeType.ifBlank { PathUtil.mimeType(relative) }, total
                )
                return
            }
            /*
             * WebDAV 下载也记账（FR-7）。
             *
             * 映射成 Z: 盘之后，用户从"此电脑"里拷出来的文件同样该出现在「传输」页 ——
             * 这条路径下用户甚至看不到浏览器，记录是他唯一的凭据。
             */
            record = deps.recorder.begin(
                name = PathUtil.fileName(relative),
                relPath = relative,
                direction = TransferDirection.DOWNLOAD,
                totalBytes = range?.let { it.endInclusive - it.start + 1 } ?: total,
                peerName = exchange.request.clientNameGuess,
                peerIp = exchange.request.remoteIp
            )
            sent = exchange.sendFileRange(
                readStart = 0,
                totalSize = total,
                contentType = entry.mimeType.ifBlank { PathUtil.mimeType(relative) },
                fileName = PathUtil.fileName(relative),
                range = range,
                shareChannel = source.channelOrNull(),
                shareStream = source.streamOrNull(),
                inline = true,
                zeroCopy = deps.zeroCopy,
                onProgress = { record?.report(it) },
                isCanceled = { false }
            )
        } catch (t: Throwable) {
            record?.fail(sent, "下载中断：${t.message ?: "连接已断开"}")
            throw t
        } finally {
            runCatching { source.close() }
        }
        record?.succeed(sent)
    }

    private fun put(exchange: HttpExchange, davPath: String) {
        val policy = policyProvider()
        if (!policy.canWrite) {
            exchange.error(HttpStatus.FORBIDDEN, policy.readOnlyMessage)
            return
        }
        val relative = storagePath(davPath)
        if (relative.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "路径非法")
            return
        }
        val existed = storage.exists(relative)
        PathUtil.parent(relative)?.let { if (it.isNotBlank()) storage.mkdir(it) }

        val sink = storage.openWrite(relative, append = false, position = null)
        if (sink == null) {
            exchange.error(HttpStatus.INSUFFICIENT_STORAGE, "无法写入")
            return
        }
        // WebDAV 客户端不会切片，一个 PUT 就是一个完整文件，所以这里不需要归并
        val record = deps.recorder.begin(
            name = PathUtil.fileName(relative),
            relPath = relative,
            direction = TransferDirection.UPLOAD,
            totalBytes = exchange.request.contentLength,
            peerName = exchange.request.clientNameGuess,
            peerIp = exchange.request.remoteIp
        )
        var received = 0L
        try {
            exchange.request.contentLength.takeIf { it > 0 }?.let { sink.preallocate(it) }
            received = exchange.consumeBody(
                fileChannel = sink.channelOrNull,
                sink = if (sink.channelOrNull == null) {
                    { data, off, len -> sink.writeAt(data, off, len, null) }
                } else null,
                zeroCopy = deps.zeroCopy,
                position = null,
                onProgress = { record.report(it) },
                isCanceled = { false }
            )
        } catch (t: Throwable) {
            record.fail(received, "上传中断：${t.message ?: "连接已断开"}")
            throw t
        } finally {
            runCatching { sink.close() }
        }
        record.succeed(received)
        exchange.respondDetail = "WebDAV 写入"
        // 严格按 RFC：新建返回 201，覆盖返回 204
        exchange.beginBinaryResponse(if (existed) HttpStatus.NO_CONTENT else HttpStatus.CREATED, "text/plain", 0)
    }

    private fun delete(exchange: HttpExchange, davPath: String) {
        val policy = policyProvider()
        if (!policy.canDeleteAllowed) {
            exchange.error(HttpStatus.FORBIDDEN, policy.readOnlyMessage)
            return
        }
        val relative = storagePath(davPath)
        if (relative.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "不能删除根")
            return
        }
        val ok = storage.delete(relative)
        exchange.respondDetail = "WebDAV 删除"
        exchange.beginBinaryResponse(if (ok) HttpStatus.NO_CONTENT else HttpStatus.NOT_FOUND, "text/plain", 0)
    }

    /**
     * MOVE / COPY。
     *
     * 注意 Destination 是**完整 URL**（如 `http://pocketserver.local:8080/dav/a/b.jpg`），
     * 不能按路径直接解析；而且客户端还可能做百分号编码，中文名必须解码后再用。
     */
    private fun move(exchange: HttpExchange, davPath: String) {
        val policy = policyProvider()
        if (!policy.canRenameAllowed) {
            exchange.error(HttpStatus.FORBIDDEN, policy.readOnlyMessage)
            return
        }
        val from = storagePath(davPath) ?: return run { exchange.error(HttpStatus.BAD_REQUEST, "源路径非法") }
        val destination = exchange.request.header("destination")
        if (destination.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "缺少 Destination 头")
            return
        }
        val to = destinationToStoragePath(destination)
        if (to.isNullOrBlank()) {
            exchange.error(HttpStatus.BAD_REQUEST, "目标路径非法")
            return
        }

        val overwrite = exchange.request.header("overwrite")?.equals("T", true) ?: true
        if (storage.exists(to)) {
            if (!overwrite) {
                exchange.error(HttpStatus.PRECONDITION_FAILED, "目标已存在")
                return
            }
            storage.delete(to)
        }
        PathUtil.parent(to)?.let { if (it.isNotBlank()) storage.mkdir(it) }
        val ok = storage.rename(from, to)
        exchange.respondDetail = "WebDAV 移动"
        exchange.beginBinaryResponse(if (ok) HttpStatus.CREATED else HttpStatus.CONFLICT, "text/plain", 0)
    }

    private fun copy(exchange: HttpExchange, davPath: String) {
        val policy = policyProvider()
        if (!policy.canWrite) {
            exchange.error(HttpStatus.FORBIDDEN, policy.readOnlyMessage)
            return
        }
        val from = storagePath(davPath) ?: return run { exchange.error(HttpStatus.BAD_REQUEST, "源路径非法") }
        val destination = exchange.request.header("destination") ?: return run {
            exchange.error(HttpStatus.BAD_REQUEST, "缺少 Destination 头")
        }
        val to = destinationToStoragePath(destination) ?: return run {
            exchange.error(HttpStatus.BAD_REQUEST, "目标路径非法")
        }
        val source = storage.openRead(from) ?: return run {
            exchange.error(HttpStatus.NOT_FOUND, "源不存在或不可读")
        }
        PathUtil.parent(to)?.let { if (it.isNotBlank()) storage.mkdir(it) }
        val sink = storage.openWrite(to, append = false, position = null)
        if (sink == null) {
            runCatching { source.close() }
            exchange.error(HttpStatus.INSUFFICIENT_STORAGE, "无法写入目标")
            return
        }
        val copied = try {
            val output = sink.streamOrNull
            if (output != null) {
                deps.zeroCopy.copyStream(source.streamOrNull() ?: return run {
                    exchange.error(HttpStatus.NOT_IMPLEMENTED, "源不可读")
                }, output)
            } else 0L
        } finally {
            runCatching { source.close() }
            runCatching { sink.close() }
        }
        exchange.respondDetail = "WebDAV 复制 $copied 字节"
        exchange.beginBinaryResponse(
            if (copied > 0) HttpStatus.CREATED else HttpStatus.INTERNAL_ERROR, "text/plain", 0
        )
    }

    // ==================== 锁（Windows 必需） ====================

    private fun lock(exchange: HttpExchange, davPath: String) {
        val token = "opaquelocktoken:" + UUID.randomUUID().toString()
        locks[token] = davPath
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:depth>infinity</D:depth>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:href>$token</D:href></D:locktoken>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>
"""
        PocketLog.d(TAG, "签发 WebDAV 锁：$token")
        exchange.xml(
            xml, HttpStatus.OK,
            mapOf("Lock-Token" to "<$token>")
        )
    }

    private fun unlock(exchange: HttpExchange) {
        val header = exchange.request.header("lock-token")?.trim()?.trim('<', '>')
        if (header != null) locks.remove(header)
        exchange.beginBinaryResponse(HttpStatus.NO_CONTENT, "text/plain", 0)
    }

    // ==================== 小工具 ====================

    private fun destinationToStoragePath(destination: String): String? {
        val withoutScheme = destination.substringAfter("://", destination)
        val afterHost = if (withoutScheme.contains('/')) {
            withoutScheme.substring(withoutScheme.indexOf('/'))
        } else "/"
        val pathOnly = afterHost.substringBefore('#')
        val davIndex = pathOnly.indexOf("/dav")
        val relativePath = if (davIndex >= 0) pathOnly.substring(davIndex + 4) else pathOnly
        return urlPathToStoragePath(relativePath)
    }

    private fun escapeXml(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c.code < 0x20 && c != '\t' && c != '\n') append(' ') else append(c)
            }
        }
    }

    private fun isoDate(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return fmt.format(java.util.Date(millis))
    }

    /** 占位：OPTIONS 需要一个空 JSON。 */
    private object Json0 {
        const val EMPTY = """{"ok":true}"""
    }

    private companion object {
        const val TAG = "WebDavHandler"
    }
}
