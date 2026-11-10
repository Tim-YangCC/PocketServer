package com.pocketserver.data.storage

import com.pocketserver.data.model.StorageMode
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel

/** 目录项。 */
data class StorageEntry(
    val name: String,
    /** 相对共享根的路径，始终以 `/` 分隔、不含前导斜杠。 */
    val relPath: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModified: Long = 0L,
    val readable: Boolean = true,
    val writable: Boolean = true,
    val mimeType: String = "",
    val childCount: Int = -1
) {
    val isFile: Boolean get() = !isDirectory
}

/**
 * 只读数据源。
 *
 * 关键设计：[channelOrNull] 与 [streamOrNull] 是**并列**的两条通路，不是二选一的关系。
 *  - 有 channel → 上层走 `FileChannel.transferTo` 零拷贝（O-2），可到 55MB/s+
 *  - 没有 channel（SAF 场景）→ 上层走池化缓冲搬运，性能下降约 15%（O-4）
 *
 * 之所以把"有没有 channel"作为能力暴露出来，而不是内部静默降级，
 * 是为了让调用方能**如实告诉用户为什么慢**，也便于诊断器区分瓶颈在谁身上。
 */
class ReadSource(
    val size: Long,
    val lastModified: Long,
    private val channel: FileChannel?,
    private val stream: InputStream?,
    private val owner: Closeable?
) : Closeable {

    fun channelOrNull(): FileChannel? = channel

    fun streamOrNull(): InputStream? = stream

    /** 至少存在一条可用通路才算有效。 */
    val usable: Boolean get() = channel != null || stream != null

    /** 能否走零拷贝。 */
    val zeroCopyCapable: Boolean get() = channel != null

    override fun close() {
        runCatching { channel?.close() }
        runCatching { stream?.close() }
        runCatching { owner?.close() }
    }

    companion object {
        fun ofFile(channel: FileChannel, size: Long, lastModified: Long): ReadSource =
            ReadSource(size, lastModified, channel, null, channel)

        fun ofStream(stream: InputStream, size: Long, lastModified: Long, owner: Closeable? = null): ReadSource =
            ReadSource(size, lastModified, null, stream, owner ?: stream)
    }
}

/**
 * 可写数据槽，同样双通路。
 *
 * [position] 非空表示"从指定位点写"（分块并行落盘 / 断点续传）。
 * SAF 通路无法随机写，遇到 position 非空会抛 UnsupportedOperationException，
 * 由上层降级为单通路 —— 这也是分块并行在 SAF 模式下自动失效的原因，属于已知且有意的取舍。
 */
interface WriteSink : Closeable {
    val channelOrNull: FileChannel?
    val streamOrNull: OutputStream?

    /** 支持随机写的位点写入。返回实际写入字节数。 */
    fun writeAt(data: ByteArray, offset: Int, length: Int, position: Long?): Int

    /** 预分配文件长度，避免边写边扩容造成的碎片与掉速（O-11）。 */
    fun preallocate(size: Long): Boolean = false

    val supportsRandomAccess: Boolean get() = channelOrNull != null
}

/**
 * 共享存储提供者。HTTP / FTP / WebDAV / 对等传输四条协议栈**只依赖这个接口**，
 * 不知道底层是真实路径还是 SAF —— 这样新增存储形态（如 SD 卡分区）不用改协议层。
 */
interface StorageProvider {

    /** 当前通道类型，用于 UI 提示与诊断。 */
    val mode: StorageMode

    /** 展示给用户看的根目录名称，如「内部存储」。 */
    val rootLabel: String

    /** 共享根是否已就绪（权限拿到、目录存在）。 */
    val ready: Boolean

    fun list(relPath: String): List<StorageEntry>

    fun stat(relPath: String): StorageEntry?

    fun exists(relPath: String): Boolean

    /** 打开只读源。调用方负责 close。文件不存在或不可读返回 null。 */
    fun openRead(relPath: String): ReadSource?

    /**
     * 打开写入槽。
     *
     * @param append   true = 追加（FTP APPE / 断点续传）；false = 截断重写
     * @param position 指定起始写入位点，用于分块并行落盘
     */
    fun openWrite(relPath: String, append: Boolean = false, position: Long? = null): WriteSink?

    fun mkdir(relPath: String): Boolean

    fun delete(relPath: String): Boolean

    fun rename(fromRel: String, toRel: String): Boolean

    fun freeSpaceBytes(): Long

    /** 总容量；系统文件框架模式可能无法提供。 */
    fun totalSpaceBytes(): Long = 0L

    /** 目标父目录下是否已存在同名项，用于生成不冲突的副本名。 */
    fun childNames(parentRel: String): Set<String>
}
