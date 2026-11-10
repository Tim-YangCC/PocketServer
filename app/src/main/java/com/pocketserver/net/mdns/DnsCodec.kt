package com.pocketserver.net.mdns

import java.io.ByteArrayOutputStream

/** DNS 记录类型（只保留 mDNS 场景用得到的几种）。 */
object DnsType {
    const val A = 1
    const val PTR = 12
    const val TXT = 16
    const val SRV = 33
    const val ANY = 255
}

/** DNS 类别。IN 的 0x8000 位在查询里表示"请单播回复"，在响应里表示"请刷新缓存"。 */
object DnsClass {
    const val IN = 1
    const val TOP_BIT = 0x8000
}

data class DnsQuestion(val name: String, val type: Int, val unicastResponse: Boolean = false)

data class DnsRecord(
    val name: String,
    val type: Int,
    val ttl: Int,
    val rdata: ByteArray,
    val cacheFlush: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DnsRecord) return false
        return name == other.name && type == other.type && ttl == other.ttl &&
            cacheFlush == other.cacheFlush && rdata.contentEquals(other.rdata)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type
        result = 31 * result + ttl
        result = 31 * result + rdata.contentHashCode()
        return result
    }

    override fun toString(): String = "$name type=$type ttl=$ttl"
}

data class DnsMessage(
    val id: Int = 0,
    val isResponse: Boolean = false,
    val authoritative: Boolean = false,
    val questions: List<DnsQuestion> = emptyList(),
    val answers: List<DnsRecord> = emptyList(),
    val additionals: List<DnsRecord> = emptyList()
)

/**
 * mDNS 报文编解码。
 *
 * **为什么自己写而不是用 androidx 的 NsdManager：**
 * NsdManager 只能注册"服务实例"，它会用系统主机名（如 `Android.local`）来广播，
 * 拿不到我们需要的固定主机名 `pocketserver.local`。而固定主机名正是"电脑端不用记 IP"
 * 的全部意义所在 —— 浏览器地址栏里能打的是 `pocketserver.local`，不是 `Android.local`。
 *
 * 实现取舍：
 *  - **编码不使用名称压缩指针**。压缩是可选项，不用它报文会大几十字节，
 *    但逻辑简单、不会出错，而且我们的报文总长稳定在 300 字节以内，远低于 MTU。
 *  - **解码支持指针**，因为要兼容其他实现（路由器、NAS、打印机）发出的压缩报文。
 *  - rdata 内的域名按 RFC 6762 §18.14 **不压缩**，因此可以独立解析，
 *    这也是 mDNS 与单播 DNS 的一处关键差异。
 *
 * 本类为纯 Kotlin，无 Android 依赖，可直接跑 JVM 单元测试。
 */
object DnsCodec {

    private const val FLAG_QR = 0x8000
    private const val FLAG_AA = 0x0400
    private const val MAX_JUMPS = 16
    private const val MAX_LABEL = 63

    // ==================== 编码 ====================

    fun encode(message: DnsMessage): ByteArray {
        val out = ByteArrayOutputStream(512)
        writeU16(out, message.id and 0xFFFF)

        var flags = 0
        if (message.isResponse) flags = flags or FLAG_QR
        if (message.authoritative) flags = flags or FLAG_AA
        writeU16(out, flags)

        writeU16(out, message.questions.size)
        writeU16(out, message.answers.size)
        writeU16(out, 0) // NSCOUNT：mDNS 不用权威段
        writeU16(out, message.additionals.size)

        for (q in message.questions) {
            writeName(out, q.name)
            writeU16(out, q.type)
            writeU16(out, if (q.unicastResponse) DnsClass.IN or DnsClass.TOP_BIT else DnsClass.IN)
        }
        for (r in message.answers) writeRecord(out, r)
        for (r in message.additionals) writeRecord(out, r)

        return out.toByteArray()
    }

    private fun writeRecord(out: ByteArrayOutputStream, r: DnsRecord) {
        writeName(out, r.name)
        writeU16(out, r.type)
        writeU16(out, if (r.cacheFlush) DnsClass.IN or DnsClass.TOP_BIT else DnsClass.IN)
        writeU32(out, r.ttl.toLong())
        writeU16(out, r.rdata.size)
        out.write(r.rdata)
    }

    /** 域名编码：按 `.` 切标签，每段前置长度字节，末尾 0 结束。 */
    fun writeName(out: ByteArrayOutputStream, name: String) {
        val trimmed = name.trim().trimEnd('.')
        if (trimmed.isEmpty()) {
            out.write(0)
            return
        }
        for (label in trimmed.split('.')) {
            var bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_LABEL) bytes = bytes.copyOf(MAX_LABEL)
            out.write(bytes.size)
            out.write(bytes, 0, bytes.size)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    // ==================== 解码 ====================

    fun decode(data: ByteArray, length: Int): DnsMessage? {
        if (length < 12) return null
        return try {
            val reader = Reader(data, 0, length)
            val id = reader.u16()
            val flags = reader.u16()
            val qdCount = reader.u16()
            val anCount = reader.u16()
            val nsCount = reader.u16()
            val arCount = reader.u16()

            val questions = ArrayList<DnsQuestion>(qdCount)
            repeat(qdCount) {
                val name = reader.name()
                val type = reader.u16()
                val cls = reader.u16()
                questions += DnsQuestion(name, type, (cls and DnsClass.TOP_BIT) != 0)
            }

            val answers = readRecords(reader, anCount)
            readRecords(reader, nsCount) // 权威段内容对 mDNS 无意义，但必须消费掉指针位置
            val additionals = readRecords(reader, arCount)

            DnsMessage(
                id = id,
                isResponse = (flags and FLAG_QR) != 0,
                authoritative = (flags and FLAG_AA) != 0,
                questions = questions,
                answers = answers,
                additionals = additionals
            )
        } catch (t: Throwable) {
            null
        }
    }

    private fun readRecords(reader: Reader, count: Int): List<DnsRecord> {
        if (count <= 0) return emptyList()
        val list = ArrayList<DnsRecord>(count)
        repeat(count) {
            val name = reader.name()
            val type = reader.u16()
            val cls = reader.u16()
            val ttl = reader.u32().toInt()
            val rdLength = reader.u16()
            val rdata = reader.bytes(rdLength)
            list += DnsRecord(name, type, ttl, rdata, (cls and DnsClass.TOP_BIT) != 0)
        }
        return list
    }

    /** 带指针跟随的读取器。 */
    private class Reader(private val data: ByteArray, private var pos: Int, private val end: Int) {

        fun u8(): Int {
            if (pos + 1 > end) throw IndexOutOfBoundsException("u8 越界")
            return data[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun u32(): Long {
            var v = 0L
            repeat(4) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > end) throw IndexOutOfBoundsException("bytes($n) 越界")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        /**
         * 读取域名，支持 0xC0 压缩指针。
         * 用 [MAX_JUMPS] 兜住恶意/损坏报文构造的指针环，否则会死循环。
         */
        fun name(): String {
            val sb = StringBuilder()
            var cursor = pos
            var consumedTo = -1
            var jumps = 0

            while (cursor < end) {
                val len = data[cursor].toInt() and 0xFF
                when {
                    len == 0 -> {
                        if (consumedTo < 0) consumedTo = cursor + 1
                        break
                    }

                    len and 0xC0 == 0xC0 -> {
                        if (cursor + 1 >= end) break
                        val offset = ((len and 0x3F) shl 8) or (data[cursor + 1].toInt() and 0xFF)
                        if (consumedTo < 0) consumedTo = cursor + 2
                        jumps++
                        if (jumps > MAX_JUMPS || offset >= end) break
                        cursor = offset
                    }

                    else -> {
                        cursor++
                        if (cursor + len > end) break
                        if (sb.isNotEmpty()) sb.append('.')
                        sb.append(String(data, cursor, len, Charsets.UTF_8))
                        cursor += len
                    }
                }
            }
            pos = if (consumedTo >= 0) consumedTo else cursor
            return sb.toString()
        }
    }
}

/** rdata 构造与解析。 */
object DnsData {

    fun a(ip: String): ByteArray {
        val parts = ip.split('.')
        val out = ByteArray(4)
        for (i in 0 until minOf(4, parts.size)) {
            out[i] = (parts[i].toIntOrNull() ?: 0).toByte()
        }
        return out
    }

    fun ipOf(rdata: ByteArray): String? {
        if (rdata.size != 4) return null
        return rdata.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun ptr(target: String): ByteArray {
        val out = ByteArrayOutputStream(64)
        DnsCodec.writeName(out, target)
        return out.toByteArray()
    }

    /** 解析 PTR 的 rdata（RFC 6762 要求 rdata 内不打压缩指针，故可独立解析）。 */
    fun ptrTarget(rdata: ByteArray): String? = readSingleName(rdata)

    fun srv(port: Int, target: String, priority: Int = 0, weight: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(64)
        out.write((priority ushr 8) and 0xFF)
        out.write(priority and 0xFF)
        out.write((weight ushr 8) and 0xFF)
        out.write(weight and 0xFF)
        out.write((port ushr 8) and 0xFF)
        out.write(port and 0xFF)
        DnsCodec.writeName(out, target)
        return out.toByteArray()
    }

    /** @return 端口与目标主机名；解析失败返回 null。 */
    fun srvOf(rdata: ByteArray): Pair<Int, String>? {
        if (rdata.size < 7) return null
        val port = ((rdata[4].toInt() and 0xFF) shl 8) or (rdata[5].toInt() and 0xFF)
        val target = readSingleName(rdata.copyOfRange(6, rdata.size)) ?: return null
        return port to target
    }

    fun txt(pairs: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream(256)
        for ((k, v) in pairs) {
            val entry = "$k=$v".toByteArray(Charsets.UTF_8)
            val len = minOf(entry.size, 255)
            out.write(len)
            out.write(entry, 0, len)
        }
        return out.toByteArray()
    }

    fun txtOf(rdata: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var i = 0
        while (i < rdata.size) {
            val len = rdata[i].toInt() and 0xFF
            i++
            if (i + len > rdata.size) break
            val entry = String(rdata, i, len, Charsets.UTF_8)
            i += len
            val eq = entry.indexOf('=')
            if (eq > 0) map[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
        return map
    }

    /** 从一段不含压缩指针的字节里读出一个域名。 */
    private fun readSingleName(data: ByteArray): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) return sb.toString().ifEmpty { null }
            if (len and 0xC0 == 0xC0) return null // rdata 内不应出现指针，出现即视为损坏
            i++
            if (i + len > data.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(data, i, len, Charsets.UTF_8))
            i += len
        }
        return sb.toString().ifEmpty { null }
    }
}
