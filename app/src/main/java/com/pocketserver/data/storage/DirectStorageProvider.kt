package com.pocketserver.data.storage

import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.StorageMode
import com.pocketserver.util.PathUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 直连文件系统的存储提供者 —— **性能最优通路**（O-4）。
 *
 * 拿到真实路径后：
 *  - 读：`FileChannel` → HTTP/WebDAV 侧可走 sendfile 零拷贝
 *  - 写：`RandomAccessFile` + `setLength` 预分配 → 分块并行可落盘到指定位点
 *
 * 安全：所有相对路径都必须过 [PathUtil.sanitize]（拒绝 `..`），
 * 再用 canonicalPath 做二次校验。两道关卡的用意是——第一道挡网络输入，
 * 第二道挡符号链接绕过（软链接指向共享根之外时 canonicalPath 会露馅）。
 */
class DirectStorageProvider(root: File) : StorageProvider {

    override val mode: StorageMode = StorageMode.DIRECT

    private val root: File = root

    override val rootLabel: String = root.name.ifBlank { "内部存储" }

    override val ready: Boolean
        get() = root.exists() && root.isDirectory && root.canRead()

    /**
     * 把相对路径解析为真实文件，并确保结果仍落在共享根之内。
     * 返回 null 表示路径非法或越界。
     */
    private fun resolve(relPath: String?): File? {
        val safe = PathUtil.sanitize(relPath) ?: return null
        val candidate = if (safe.isEmpty()) root else File(root, safe)
        return try {
            val canonicalRoot = root.canonicalPath
            val canonicalTarget = candidate.canonicalPath
            if (canonicalTarget == canonicalRoot || canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                candidate
            } else {
                PocketLog.w(TAG, "路径越界被拒绝: $relPath")
                null
            }
        } catch (t: Throwable) {
            // canonicalPath 在极少数 ROM 上会抛 IOException，此时保守拒绝
            PocketLog.w(TAG, "路径归一化失败，拒绝访问: $relPath (${t.message})")
            null
        }
    }

    override fun list(relPath: String): List<StorageEntry> {
        val dir = resolve(relPath) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        val base = PathUtil.sanitize(relPath) ?: ""
        return children.mapNotNull { child ->
            val rel = PathUtil.join(base, child.name)
            runCatching {
                StorageEntry(
                    name = child.name,
                    relPath = rel,
                    isDirectory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.length(),
                    lastModified = child.lastModified(),
                    readable = child.canRead(),
                    writable = child.canWrite(),
                    mimeType = if (child.isDirectory) "" else PathUtil.mimeType(child.name),
                    childCount = if (child.isDirectory) (child.listFiles()?.size ?: -1) else -1
                )
            }.getOrNull()
        }.sortedWith(compareByDescending<StorageEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override fun stat(relPath: String): StorageEntry? {
        val f = resolve(relPath) ?: return null
        if (!f.exists()) return null
        return StorageEntry(
            name = f.name,
            relPath = PathUtil.sanitize(relPath) ?: "",
            isDirectory = f.isDirectory,
            size = if (f.isDirectory) 0L else f.length(),
            lastModified = f.lastModified(),
            readable = f.canRead(),
            writable = f.canWrite(),
            mimeType = if (f.isDirectory) "" else PathUtil.mimeType(f.name),
            childCount = if (f.isDirectory) (f.listFiles()?.size ?: -1) else -1
        )
    }

    override fun exists(relPath: String): Boolean = resolve(relPath)?.exists() == true

    override fun openRead(relPath: String): ReadSource? {
        val f = resolve(relPath) ?: return null
        if (!f.isFile || !f.canRead()) return null
        return try {
            // 用 FileInputStream.getChannel() 而不是 FileChannel.open(Path)：
            // 后者属于 java.nio.file，minSdk 24 下需要脱糖且部分 ROM 支持不完整。
            // FileInputStream.channel 是 API 1 起就有的稳定通路，同样支持按位点读。
            ReadSource.ofFile(FileInputStream(f).channel, f.length(), f.lastModified())
        } catch (t: Throwable) {
            // 某些外置卡上的文件不支持 FileChannel，退回流式通路
            PocketLog.w(TAG, "FileChannel 打开失败，退回流式读取: ${f.name} (${t.message})")
            runCatching {
                ReadSource.ofStream(FileInputStream(f), f.length(), f.lastModified())
            }.getOrNull()
        }
    }

    override fun openWrite(relPath: String, append: Boolean, position: Long?): WriteSink? {
        val f = resolve(relPath) ?: return null
        f.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return try {
            if (position != null) {
                // 分块并行落盘：必须先按最终长度预分配，否则多路写入会互相截断
                val raf = RandomAccessFile(f, "rw")
                raf.setLength(maxOf(raf.length(), position))
                raf.seek(position)
                FileWriteSink(raf.channel, raf, FileOutputStream(raf.fd))
            } else {
                val fos = FileOutputStream(f, append)
                FileWriteSink(fos.channel, null, fos)
            }
        } catch (t: Throwable) {
            PocketLog.e(TAG, "打开写入失败: $relPath (${t.message})", t)
            null
        }
    }

    override fun mkdir(relPath: String): Boolean {
        val f = resolve(relPath) ?: return false
        return f.exists() || f.mkdirs()
    }

    override fun delete(relPath: String): Boolean {
        val f = resolve(relPath) ?: return false
        if (f == root) return false
        return runCatching { f.deleteRecursively() }.getOrDefault(false)
    }

    override fun rename(fromRel: String, toRel: String): Boolean {
        val src = resolve(fromRel) ?: return false
        val dst = resolve(toRel) ?: return false
        if (!src.exists()) return false
        dst.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return runCatching { src.renameTo(dst) }.getOrDefault(false)
    }

    override fun freeSpaceBytes(): Long = runCatching { root.usableSpace }.getOrDefault(0L)

    override fun totalSpaceBytes(): Long = runCatching { root.totalSpace }.getOrDefault(0L)

    override fun childNames(parentRel: String): Set<String> {
        val dir = resolve(parentRel) ?: return emptySet()
        return dir.list()?.toSet() ?: emptySet()
    }

    /** 真实文件写入槽：channel 用于零拷贝与随机写，stream 供普通调用方使用。 */
    private class FileWriteSink(
        private val channel: FileChannel,
        private val raf: RandomAccessFile?,
        private val stream: FileOutputStream
    ) : WriteSink {

        override val channelOrNull: FileChannel get() = channel

        override val streamOrNull: java.io.OutputStream get() = stream

        override fun writeAt(data: ByteArray, offset: Int, length: Int, position: Long?): Int {
            return if (position != null && raf != null) {
                raf.seek(position)
                raf.write(data, offset, length)
                length
            } else {
                stream.write(data, offset, length)
                length
            }
        }

        /**
         * 预分配（O-11）。用 setLength 而不是逐块写零：前者是元数据操作，
         * 在大多数文件系统上是 O(1)，后者会真的写满磁盘。
         */
        override fun preallocate(size: Long): Boolean {
            val target = raf ?: return false
            return runCatching {
                if (target.length() < size) target.setLength(size)
                true
            }.getOrDefault(false)
        }

        override fun close() {
            runCatching { stream.flush() }
            runCatching { raf?.close() }
            runCatching { stream.close() }
            runCatching { channel.close() }
        }
    }

    private companion object {
        const val TAG = "DirectStorage"
    }
}
