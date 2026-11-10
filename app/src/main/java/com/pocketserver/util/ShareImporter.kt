package com.pocketserver.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketserver.core.PocketLog
import com.pocketserver.data.storage.StorageProvider

/**
 * 系统「分享到」的入口处理（FR-11.6）。
 *
 * ## 为什么必须"先落地再推送"，不能直接推 Uri
 * 对等传输协议是**基于路径**的（服务端要能按相对路径打开 FileChannel 做零拷贝）。
 * 而系统分享给过来的是 `content://` URI，只有我们的进程在授权期内能读，
 * 对端完全访问不到。所以正确做法是：
 *  1. 把分享内容复制到本机共享根下的 `来自分享/` 目录
 *  2. 拿到它的相对路径
 *  3. 走正常的推送流程
 *
 * 顺带的好处：用户在"附近设备"页能看到这份文件，后续还能重复推送。
 */
object ShareImporter {

    /** 分享内容的落地目录名。用中文名是为了让用户在文件管理器里一眼看懂来源。 */
    const val INBOX_FOLDER = "来自分享"

    /**
     * 把一批 content URI 复制进共享根。
     *
     * @return 复制成功的相对路径列表（可直接用于推送或浏览）
     */
    fun import(
        resolver: ContentResolver,
        storage: StorageProvider,
        uris: List<Uri>
    ): List<String> {
        if (uris.isEmpty()) return emptyList()
        if (!storage.ready) {
            PocketLog.w(TAG, "存储未就绪，无法导入分享内容")
            return emptyList()
        }
        storage.mkdir(INBOX_FOLDER)

        val imported = mutableListOf<String>()
        for (uri in uris) {
            val name = displayName(resolver, uri) ?: "分享文件_${System.currentTimeMillis()}"
            val existing = storage.childNames(INBOX_FOLDER)
            val safeName = PathUtil.dedupe(name) { existing.contains(it) }
            val target = PathUtil.join(INBOX_FOLDER, safeName)

            val sink = storage.openWrite(target) ?: continue
            try {
                val input = resolver.openInputStream(uri) ?: continue
                input.use { source ->
                    val output = sink.streamOrNull
                    val channel = sink.channelOrNull
                    if (output != null) {
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = source.read(buffer)
                            if (n <= 0) break
                            output.write(buffer, 0, n)
                        }
                    } else if (channel != null) {
                        val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
                        var total = 0L
                        while (true) {
                            buffer.clear()
                            val n = source.read(buffer.array())
                            if (n <= 0) break
                            buffer.limit(n)
                            while (buffer.hasRemaining()) {
                                val w = channel.write(buffer)
                                if (w <= 0) break
                                total += w
                            }
                        }
                    }
                }
                imported += target
            } catch (t: Throwable) {
                PocketLog.w(TAG, "导入分享内容失败：${uri}（${t.message}）")
            } finally {
                runCatching { sink.close() }
            }
        }
        PocketLog.i(TAG, "已导入 ${imported.size}/${uris.size} 个分享项到「$INBOX_FOLDER」")
        return imported
    }

    /** 通过 ContentResolver 取原始文件名（分享过来的 Uri 通常带 display name）。 */
    private fun displayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && cursor.columnCount > 0) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private const val TAG = "ShareImporter"
}
