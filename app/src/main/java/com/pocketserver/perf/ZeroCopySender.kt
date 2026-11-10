package com.pocketserver.perf

import com.pocketserver.core.PocketLog
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel

/**
 * 零拷贝发送（优化项 O-2）。
 *
 * 只有两条通路，按能力自动选择，**刻意不留第三条**——第三条就是"代码看起来正确但只有
 * 8MB/s"的元凶：`while(read(8KB)) write(8KB)`。
 *
 *  1. **真零拷贝**（[sendFile]）：`FileChannel.transferTo(pos, count, socketChannel)`。
 *     Android/Linux 下走 sendfile(2)，数据从 page cache 直达网卡，不经过用户态。
 *     前提：源是真实文件路径打开的 FileChannel，目标是 SocketChannel。
 *  2. **池化缓冲搬运**（[sendStream] / [copyStream]）：源来自 SAF（InputStream）时，
 *     用池化的 256KB 直接缓冲搬。相比 8KB 缓冲，系统调用次数降到 1/32，
 *     实测单项增益就有 ×1.5。
 *
 * 降级保护很关键：部分内核 + 文件系统组合下 `transferTo` 会返回 0 而不是抛异常，
 * 不做保护就是死循环。用 [MAX_STALL_ROUNDS] 兜住并自动降级。
 */
class ZeroCopySender(private val bufferPool: BufferPool) {

    /**
     * 零拷贝发送文件区间。
     *
     * @param position   起始偏移（断点续传 / 分块并行时非 0）
     * @param count      要发送的字节数
     * @param onProgress 已发送字节数回调（**调用方负责节流**，见 O-8）
     * @param isCanceled 取消探针，每轮检查
     * @return 实际发送字节数。调用方应比对 count 以判断是否完整。
     */
    fun sendFile(
        fileChannel: FileChannel,
        position: Long,
        count: Long,
        socket: SocketChannel,
        onProgress: (Long) -> Unit = {},
        isCanceled: () -> Boolean = { false }
    ): Long {
        var sent = 0L
        var stall = 0
        while (sent < count) {
            if (isCanceled()) break
            val n = try {
                fileChannel.transferTo(position + sent, count - sent, socket)
            } catch (io: IOException) {
                // 对端主动断开（浏览器取消下载）属于常态，按已发送量如实返回即可
                PocketLog.d(TAG, "transferTo 中断于 $sent/$count: ${io.message}")
                break
            }
            when {
                n > 0L -> {
                    sent += n
                    stall = 0
                    onProgress(sent)
                }

                n == 0L -> {
                    stall++
                    if (stall > MAX_STALL_ROUNDS) {
                        PocketLog.w(TAG, "transferTo 连续 $stall 轮无进展，降级为缓冲拷贝")
                        sent += copyFileChannel(
                            fileChannel, socket, position + sent, count - sent, onProgress, isCanceled
                        )
                        break
                    }
                    // 内核发送窗口暂时满，让出 CPU 而不是空转
                    Thread.yield()
                }

                else -> break
            }
        }
        return sent
    }

    /**
     * 从流发送到 socket（SAF 兜底通路）。
     * 用 `Channels.newChannel` 包装，才能把字节读进直接缓冲（直接缓冲没有 array()）。
     */
    fun sendStream(
        input: InputStream,
        socket: SocketChannel,
        onProgress: (Long) -> Unit = {},
        isCanceled: () -> Boolean = { false }
    ): Long {
        val source: ReadableByteChannel = Channels.newChannel(input)
        val buffer = bufferPool.acquire()
        var sent = 0L
        try {
            while (true) {
                if (isCanceled()) break
                buffer.clear()
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    Thread.yield()
                    continue
                }
                buffer.flip()
                sent += writeFully(socket, buffer)
                onProgress(sent)
            }
        } catch (io: IOException) {
            PocketLog.d(TAG, "流式发送中断于 $sent 字节: ${io.message}")
        } finally {
            bufferPool.release(buffer)
        }
        return sent
    }

    /**
     * 流到流的搬运（接收方向：HTTP PUT 上传、FTP STOR 写 SAF 目标）。
     * 输出为普通 OutputStream 时无法用直接缓冲的散列写，故用堆缓冲——
     * 这条路径只在 SAF 场景出现，真实文件路径一律走 FileChannel.transferFrom。
     */
    fun copyStream(
        input: InputStream,
        output: OutputStream,
        onProgress: (Long) -> Unit = {},
        isCanceled: () -> Boolean = { false }
    ): Long {
        val buffer = ByteArray(bufferPool.bufferSizeBytes)
        var written = 0L
        try {
            while (true) {
                if (isCanceled()) break
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                written += read
                onProgress(written)
            }
        } catch (io: IOException) {
            PocketLog.d(TAG, "拷贝中断于 $written 字节: ${io.message}")
        }
        return written
    }

    /**
     * 接收方向的零拷贝：从 socket 直接落到文件通道。
     * `transferFrom` 同样是内核态搬运，不经过用户态。
     *
     * @param position 文件写入偏移（分块并行时非 0）
     */
    fun receiveToFile(
        socket: SocketChannel,
        fileChannel: FileChannel,
        position: Long,
        count: Long,
        onProgress: (Long) -> Unit = {},
        isCanceled: () -> Boolean = { false }
    ): Long {
        var received = 0L
        var stall = 0
        while (received < count) {
            if (isCanceled()) break
            val n = try {
                fileChannel.transferFrom(socket, position + received, count - received)
            } catch (io: IOException) {
                PocketLog.d(TAG, "transferFrom 中断于 $received/$count: ${io.message}")
                break
            }
            when {
                n > 0L -> {
                    received += n
                    stall = 0
                    onProgress(received)
                }

                n == 0L -> {
                    stall++
                    if (stall > MAX_STALL_ROUNDS) break
                    // transferFrom 在无数据可读时会立刻返回 0；此处用它做"等下一批"的让出
                    Thread.yield()
                }

                else -> break
            }
        }
        return received
    }

    /** transferTo 不可用时的兜底：读指针/写指针分离，避免同一缓冲反复 flip。 */
    private fun copyFileChannel(
        fileChannel: FileChannel,
        socket: SocketChannel,
        position: Long,
        count: Long,
        onProgress: (Long) -> Unit,
        isCanceled: () -> Boolean
    ): Long {
        val buffer = bufferPool.acquire()
        var copied = 0L
        try {
            var pos = position
            while (copied < count) {
                if (isCanceled()) break
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), count - copied).toInt())
                val read = fileChannel.read(buffer, pos)
                if (read <= 0) break
                pos += read
                buffer.flip()
                copied += writeFully(socket, buffer)
                onProgress(copied)
            }
        } finally {
            bufferPool.release(buffer)
        }
        return copied
    }

    /** 写满一块缓冲为止。SocketChannel 在阻塞模式下可能部分写入。 */
    private fun writeFully(socket: SocketChannel, buffer: ByteBuffer): Int {
        var written = 0
        while (buffer.hasRemaining()) {
            val n = try {
                socket.write(buffer)
            } catch (io: IOException) {
                break
            }
            if (n <= 0) {
                Thread.yield()
                continue
            }
            written += n
        }
        return written
    }

    private companion object {
        const val TAG = "ZeroCopySender"

        /** 连续多少轮无进展就判定为"不支持零拷贝"。3 轮足以区分窗口暂满与能力缺失。 */
        const val MAX_STALL_ROUNDS = 3
    }
}
