package com.pocketserver.data.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.StorageMode
import com.pocketserver.util.PathUtil
import java.io.OutputStream

/**
 * 通过系统文件框架（SAF）访问的存储提供者 —— **降级通路**（O-4）。
 *
 * 什么时候会用到它：设备拒绝授予「所有文件访问权限」时（部分厂商 ROM 会直接拒绝），
 * 或者用户主动只授权某个目录时。此时功能完整，但速度有明显损失，原因是：
 *
 *  1. 每次 open 都要跨进程调到 `MediaProvider`，几 GB 的传输会稳定吃掉 10%~20% 吞吐；
 *  2. 拿不到 `FileChannel` —— 零拷贝（sendfile）与按位点随机写全部失效，
 *     分块并行也因此在 SAF 模式下自动退化为单通路。
 *
 * 所以本类的 [ReadSource.zeroCopyCapable] 恒为 false，并且 [mode] 为 SAF；
 * 服务层据此在控制台明确提示用户去授予全文件访问权限，而不是让用户自己猜为什么慢。
 */
class SafStorageProvider(
    private val context: Context,
    private val treeUri: Uri?
) : StorageProvider {

    override val mode: StorageMode = StorageMode.SAF

    private val resolver get() = context.contentResolver

    override val rootLabel: String = "已授权目录"

    private val rootDocId: String? by lazy {
        val uri = treeUri ?: return@lazy null
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    }

    override val ready: Boolean
        get() = rootDocId != null && uriOf(rootDocId!!) != null

    // ==================== 路径解析 ====================

    private fun uriOf(documentId: String): Uri? {
        val tree = treeUri ?: return null
        return runCatching {
            DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        }.getOrNull()
    }

    /**
     * 逐级下钻解析相对路径。
     *
     * 为什么不直接拼 `docId = "$root/$name"`：那是 ExternalStorageProvider 的私有约定，
     * 第三方网盘类 Provider 的 documentId 格式完全不同。逐级 query 虽然多几次 IO，
     * 但在任何 Provider 上都成立。
     */
    private fun resolveDocumentId(relPath: String?): String? {
        val root = rootDocId ?: return null
        val safe = PathUtil.sanitize(relPath) ?: return null
        if (safe.isEmpty()) return root

        var currentId = root
        for (segment in safe.split('/')) {
            if (segment.isEmpty()) continue
            val found = findChildId(currentId, segment) ?: return null
            currentId = found
        }
        return currentId
    }

    private fun findChildId(parentDocId: String, name: String): String? {
        val tree = treeUri ?: return null
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        }.getOrNull() ?: return null

        return runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == name) return@use cursor.getString(0)
                }
                null
            }
        }.getOrNull()
    }

    // ==================== 读取 ====================

    override fun list(relPath: String): List<StorageEntry> {
        val parentId = resolveDocumentId(relPath) ?: return emptyList()
        val tree = treeUri ?: return emptyList()
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        }.getOrNull() ?: return emptyList()

        val base = PathUtil.sanitize(relPath) ?: ""
        val result = mutableListOf<StorageEntry>()
        runCatching {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS
                ),
                null, null, null
            )?.use { cursor -> while (cursor.moveToNext()) result += cursor.toEntry(base) }
        }.onFailure { PocketLog.w(TAG, "SAF 列目录失败: ${it.message}") }

        return result.sortedWith(
            compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
        )
    }

    private fun Cursor.toEntry(base: String): StorageEntry {
        val docId = getString(0)
        val name = getString(1) ?: ""
        val mime = getString(2) ?: ""
        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val size = if (isDir) 0L else getLong(3)
        val modified = getLong(4)
        val flags = getInt(5)
        // SAF 的 flags 里没有独立的"可读"位，只区分可写/可删/可重命名
        val writable = (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
        return StorageEntry(
            name = name,
            relPath = PathUtil.join(base, name),
            isDirectory = isDir,
            size = size,
            lastModified = modified,
            readable = true,
            writable = writable,
            mimeType = if (isDir) "" else mime.ifBlank { PathUtil.mimeType(name) },
            childCount = -1,
            // documentId 在返回时不需要，此处仅用于调试定位问题
        ).also { if (docId.isBlank()) PocketLog.w(TAG, "空 documentId: $name") }
    }

    override fun stat(relPath: String): StorageEntry? {
        val docId = resolveDocumentId(relPath) ?: return null
        val uri = uriOf(docId) ?: return null
        val name = PathUtil.fileName(relPath)
        return runCatching {
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_FLAGS
                ),
                null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val mime = cursor.getString(0) ?: ""
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                StorageEntry(
                    name = name,
                    relPath = PathUtil.sanitize(relPath) ?: "",
                    isDirectory = isDir,
                    size = if (isDir) 0L else cursor.getLong(1),
                    lastModified = cursor.getLong(2),
                    writable = (cursor.getInt(3) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
                    mimeType = if (isDir) "" else mime.ifBlank { PathUtil.mimeType(name) }
                )
            }
        }.getOrNull()
    }

    override fun exists(relPath: String): Boolean = resolveDocumentId(relPath) != null

    override fun openRead(relPath: String): ReadSource? {
        val docId = resolveDocumentId(relPath) ?: return null
        val uri = uriOf(docId) ?: return null
        val entry = stat(relPath)
        return runCatching {
            val stream = resolver.openInputStream(uri) ?: return null
            // 无 FileChannel：通道能力为 false，上层自动走缓冲搬运
            ReadSource.ofStream(stream, entry?.size ?: -1L, entry?.lastModified ?: 0L)
        }.onFailure { PocketLog.w(TAG, "SAF 打开读取失败: ${it.message}") }.getOrNull()
    }

    // ==================== 写入 ====================

    override fun openWrite(relPath: String, append: Boolean, position: Long?): WriteSink? {
        if (position != null) {
            // SAF 无法随机写：明确拒绝并交由上层降级，而不是静默写错位置把文件写坏
            PocketLog.w(TAG, "SAF 不支持按位点写入，分块并行将退化为单通路")
            return null
        }
        val docId = resolveDocumentId(relPath) ?: createDocumentId(relPath) ?: return null
        val uri = uriOf(docId) ?: return null

        return runCatching {
            val mode = if (append) "wa" else "wt"
            @Suppress("DEPRECATION")
            val stream = resolver.openOutputStream(uri, mode) ?: return null
            SafWriteSink(stream)
        }.onFailure { PocketLog.w(TAG, "SAF 打开写入失败: ${it.message}") }.getOrNull()
    }

    /**
     * 在同目录下新建一个文档，返回它的 documentId。
     *
     * 注意**不能**把 [DocumentsContract.createDocument] 的返回值（`Uri`）直接当成
     * documentId 用：后续 `uriOf()` / `buildDocumentUriUsingTree()` 需要的是
     * `String` 形式的 docId。这里必须显式 `getDocumentId()` 转一次 ——
     * 早期版本靠 elvis 直接把 `Uri?` 接到 `String?` 上，Kotlin 会把它对齐成
     * `Comparable<...>?`，编译期直接报类型不匹配。
     */
    private fun createDocumentId(relPath: String): String? {
        // 顶层文件没有父路径（parent 返回 null），此时父目录就是共享根
        val parentRel = PathUtil.parent(relPath) ?: ""
        val parentId = resolveDocumentId(parentRel) ?: return null
        val parentUri = uriOf(parentId) ?: return null
        val created = runCatching {
            DocumentsContract.createDocument(
                resolver, parentUri,
                PathUtil.mimeType(relPath), PathUtil.fileName(relPath)
            )
        }.onFailure { PocketLog.w(TAG, "SAF 创建文档失败: ${it.message}") }.getOrNull() ?: return null
        return runCatching { DocumentsContract.getDocumentId(created) }.getOrNull()
    }

    /**
     * 新建目录，**逐级递归**。
     *
     * 递归不是可有可无的便利：上传文件夹时客户端会传来 `假期照片/2024/海边` 这样的
     * 多级路径。早期版本只创建最后一段，父级不存在时 `resolveDocumentId(parentRel)`
     * 直接返回 null，整个上传在 SAF 通道下静默失败 —— 而直连通道的 `mkdirs()` 天生
     * 递归，于是同一个操作在两种通道下表现不同，极难排查。
     */
    override fun mkdir(relPath: String): Boolean {
        val safe = PathUtil.sanitize(relPath) ?: return false
        if (safe.isEmpty()) return true
        if (exists(safe)) return true

        val parentRel = PathUtil.parent(safe) ?: ""
        if (parentRel.isNotEmpty() && !exists(parentRel) && !mkdir(parentRel)) return false

        val parentId = resolveDocumentId(parentRel) ?: return false
        val parentUri = uriOf(parentId) ?: return false
        return runCatching {
            DocumentsContract.createDocument(
                resolver, parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR, PathUtil.fileName(safe)
            ) != null
        }.onFailure { PocketLog.w(TAG, "SAF 新建目录失败: $safe (${it.message})") }.getOrDefault(false)
    }

    override fun delete(relPath: String): Boolean {
        val docId = resolveDocumentId(relPath) ?: return false
        val uri = uriOf(docId) ?: return false
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    override fun rename(fromRel: String, toRel: String): Boolean {
        val docId = resolveDocumentId(fromRel) ?: return false
        val uri = uriOf(docId) ?: return false
        val newName = PathUtil.fileName(toRel)
        return runCatching {
            DocumentsContract.renameDocument(resolver, uri, newName) != null
        }.getOrDefault(false)
    }

    override fun freeSpaceBytes(): Long = 0L // SAF 拿不到挂载点，UI 会显示为"未知"

    override fun childNames(parentRel: String): Set<String> = list(parentRel).map { it.name }.toSet()

    /** SAF 写入槽：只能用流，随机写与预分配都不可用。 */
    private class SafWriteSink(private val out: OutputStream) : WriteSink {
        override val channelOrNull: java.nio.channels.FileChannel? get() = null
        override val streamOrNull: OutputStream get() = out
        override val supportsRandomAccess: Boolean get() = false

        override fun writeAt(data: ByteArray, offset: Int, length: Int, position: Long?): Int {
            out.write(data, offset, length)
            return length
        }

        override fun close() {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    private companion object {
        const val TAG = "SafStorage"
    }
}
