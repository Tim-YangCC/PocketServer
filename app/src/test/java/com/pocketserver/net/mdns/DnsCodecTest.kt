package com.pocketserver.net.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * mDNS 报文编解码测试。
 *
 * ## 为什么这块必须自动化测
 * mDNS 是"电脑端零输入"的全部技术基础 —— 域名解析不了，用户就只能去记 IP，
 * 产品的核心卖点直接没了。而在真机上手测这件事极其低效：要两台设备、
 * 一个正常的路由器、还得等发现周期。用报文级的单元测试可以在毫秒内验证协议正确性。
 *
 * 覆盖三类风险：
 *  1. **自产自销的一致性**：自己编的包自己能解开（A/SRV/TXT/PTR 四种记录）
 *  2. **与外部实现互通**：解析手工构造的、带名称压缩指针的报文
 *     （路由器、Windows、Bonjour 都会压缩名称，我们的解码器必须认）
 *  3. **畸形输入不致命**：空包、垃圾数据、自指指针环都不能让服务挂死
 */
class DnsCodecTest {

    private fun b(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    /** 把 "a.local" 编码成 DNS 名称格式：长度前缀 + 内容 + 结尾 0。 */
    private fun name(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (segment in value.split('.')) {
            val bytes = segment.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    // ==================== 自产自销 ====================

    @Test
    fun `A 记录往返`() {
        val message = DnsMessage(
            isResponse = true,
            authoritative = true,
            answers = listOf(
                DnsRecord(
                    name = "pocketserver.local",
                    type = DnsType.A,
                    ttl = 120,
                    rdata = DnsData.a("192.168.1.5")
                )
            )
        )
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)

        assertNotNull(decoded)
        assertEquals(1, decoded!!.answers.size)
        assertEquals("pocketserver.local", decoded.answers[0].name)
        assertEquals(DnsType.A, decoded.answers[0].type)
        assertEquals(120, decoded.answers[0].ttl)
        assertEquals("192.168.1.5", DnsData.ipOf(decoded.answers[0].rdata))
    }

    @Test
    fun `响应标志位往返正确`() {
        val bytes = DnsCodec.encode(DnsMessage(isResponse = true, authoritative = true))
        val decoded = DnsCodec.decode(bytes, bytes.size)
        assertNotNull(decoded)
        assertTrue("必须保留 QR 位，否则对端会把响应当成查询", decoded!!.isResponse)
        assertTrue("必须保留 AA 位，宣告我们才是权威来源", decoded.authoritative)
    }

    @Test
    fun `SRV 记录往返保留端口与目标主机`() {
        val rdata = DnsData.srv(port = 8080, target = "pocketserver.local")
        val message = DnsMessage(
            isResponse = true,
            answers = listOf(
                DnsRecord("_pocketserver._tcp.local", DnsType.SRV, 120, rdata)
            )
        )
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)!!
        val srv = DnsData.srvOf(decoded.answers[0].rdata)
        assertNotNull("SRV 是发现服务端口的关键记录，解析失败等于发现不到设备", srv)
        assertEquals(8080, srv!!.first)
        assertEquals("pocketserver.local", srv.second)
    }

    @Test
    fun `TXT 记录往返保留键值对`() {
        val pairs = listOf(
            "ip" to "192.168.1.5",
            "name" to "客厅电视",
            "type" to "tv"
        )
        val message = DnsMessage(
            isResponse = true,
            answers = listOf(
                DnsRecord("pocketserver.local", DnsType.TXT, 120, DnsData.txt(pairs))
            )
        )
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)!!
        val txt = DnsData.txtOf(decoded.answers[0].rdata)
        assertEquals("192.168.1.5", txt["ip"])
        assertEquals("客厅电视", txt["name"])
        assertEquals("tv", txt["type"])
    }

    @Test
    fun `PTR 记录往返保留目标服务名`() {
        val message = DnsMessage(
            isResponse = true,
            answers = listOf(
                DnsRecord(
                    "_pocketserver._tcp.local",
                    DnsType.PTR,
                    120,
                    DnsData.ptr("口袋服务器._pocketserver._tcp.local")
                )
            )
        )
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)!!
        assertEquals(
            "口袋服务器._pocketserver._tcp.local",
            DnsData.ptrTarget(decoded.answers[0].rdata)
        )
    }

    @Test
    fun `多个记录一起往返不乱序`() {
        val message = DnsMessage(
            isResponse = true,
            answers = listOf(
                DnsRecord("a.local", DnsType.A, 120, DnsData.a("10.0.0.1")),
                DnsRecord("b.local", DnsType.A, 120, DnsData.a("10.0.0.2")),
                DnsRecord("c.local", DnsType.A, 120, DnsData.a("10.0.0.3"))
            )
        )
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)!!
        assertEquals(3, decoded.answers.size)
        assertEquals("10.0.0.1", DnsData.ipOf(decoded.answers[0].rdata))
        assertEquals("10.0.0.2", DnsData.ipOf(decoded.answers[1].rdata))
        assertEquals("10.0.0.3", DnsData.ipOf(decoded.answers[2].rdata))
    }

    @Test
    fun `带中文的设备名可以完整往返`() {
        val value = DnsData.txt(listOf("name" to "客厅电视"))
        val message = DnsMessage(isResponse = true, answers = listOf(DnsRecord("x.local", DnsType.TXT, 60, value)))
        val bytes = DnsCodec.encode(message)
        val decoded = DnsCodec.decode(bytes, bytes.size)!!
        assertEquals("客厅电视", DnsData.txtOf(decoded.answers[0].rdata)["name"])
    }

    // ==================== 与外部实现的互通：名称压缩指针 ====================

    @Test
    fun `能解析带名称压缩指针的报文`() {
        /*
         * 手工构造一个真实世界会出现的最小响应包：
         *   - 1 条 question，名称在 offset 12（"a.local"）
         *   - 1 条 answer，名称字段是一个指针 0xC00C，指回 offset 12
         *
         * 路由器、Windows、Bonjour 都这么省字节。我们的解码器如果只按
         * "长度前缀"读名称，就会把指针字节当成一段长度为 192 的名称，直接崩掉。
         */
        val packet = b(
            0x00, 0x00, // id
            0x84, 0x00, // flags: response + authoritative
            0x00, 0x01, // qdcount = 1
            0x00, 0x01, // ancount = 1
            0x00, 0x00, // nscount
            0x00, 0x00  // arcount
        ) +
            name("a.local") +
            b(0x00, 0x01, 0x00, 0x01) + // question: type A, class IN
            b(0xC0, 0x0C) + // answer name: 指针 → offset 12
            b(0x00, 0x01, 0x00, 0x01) + // type A, class IN
            b(0x00, 0x00, 0x00, 0x78) + // ttl = 120
            b(0x00, 0x04) + // rdlength = 4
            b(192, 168, 1, 5)           // rdata

        val decoded = DnsCodec.decode(packet, packet.size)
        assertNotNull("带压缩指针的报文必须能解析", decoded)
        assertEquals(1, decoded!!.questions.size)
        assertEquals("a.local", decoded.questions[0].name)
        assertEquals(1, decoded.answers.size)
        assertEquals("指针应当被还原成完整名称", "a.local", decoded.answers[0].name)
        assertEquals("192.168.1.5", DnsData.ipOf(decoded.answers[0].rdata))
    }

    @Test(timeout = 3_000L)
    fun `名称指针自指成环时不会死循环`() {
        /*
         * 恶意或损坏的报文可以让指针指向它自己。
         * 解码器必须有跳跃次数上限，否则解析线程会被卡死 —— 在手机上表现为
         * "服务还活着但再也发现不到设备"，重启才能恢复，极难定位。
         * 这个用例的 timeout 就是断言本身：能返回 = 有防护。
         */
        val packet = b(
            0x00, 0x00,
            0x84, 0x00,
            0x00, 0x00, // qdcount = 0
            0x00, 0x01, // ancount = 1
            0x00, 0x00,
            0x00, 0x00
        ) +
            b(0xC0, 0x0C) + // 指针指向 offset 12 —— 也就是它自己
            b(0x00, 0x01, 0x00, 0x01) +
            b(0x00, 0x00, 0x00, 0x78) +
            b(0x00, 0x04) +
            b(192, 168, 1, 5)

        DnsCodec.decode(packet, packet.size)
        // 不关心解析结果，只要能在 3 秒内返回
    }

    // ==================== 畸形输入 ====================

    @Test
    fun `空数据不会抛异常`() {
        val result = runCatching { DnsCodec.decode(ByteArray(0), 0) }
        assertTrue("解析空包必须安全返回，不能把接收线程打挂", result.isSuccess)
    }

    @Test
    fun `纯垃圾数据不会抛异常`() {
        val garbage = ByteArray(64) { (it * 37 % 256).toByte() }
        val result = runCatching { DnsCodec.decode(garbage, garbage.size) }
        assertTrue("随机数据是日常会发生的事（别的协议串到 5353 端口），必须能安全忽略", result.isSuccess)
    }

    @Test
    fun `头部被截断不会抛异常`() {
        val truncated = b(0x00, 0x00, 0x84)
        val result = runCatching { DnsCodec.decode(truncated, truncated.size) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun `声称有记录但数据不足时不会抛异常`() {
        // ancount 说有 5 条，实际一条都没有 —— 这是典型的"半包"，UDP 上很常见
        val packet = b(
            0x00, 0x00,
            0x84, 0x00,
            0x00, 0x00,
            0x00, 0x05,
            0x00, 0x00,
            0x00, 0x00
        )
        val result = runCatching { DnsCodec.decode(packet, packet.size) }
        assertTrue(result.isSuccess)
    }

    // ==================== 数据构造器 ====================

    @Test
    fun `A 记录的 rdata 是 4 字节 且能还原 IP`() {
        val rdata = DnsData.a("10.20.30.40")
        assertEquals(4, rdata.size)
        assertEquals("10.20.30.40", DnsData.ipOf(rdata))
    }

    @Test
    fun `非法 IP 不会让构造器崩溃`() {
        val result = runCatching { DnsData.a("not-an-ip") }
        assertTrue("设备名/IP 来自网络配置，异常配置不该让服务起不来", result.isSuccess)
    }

    @Test
    fun `空 TXT 列表也能编解码`() {
        val rdata = DnsData.txt(emptyList())
        assertEquals("空 TXT 应当被解析成空表而不是 null", emptyMap<String, String>(), DnsData.txtOf(rdata))
    }

    @Test
    fun `TXT 中的等号不会破坏解析`() {
        // 值里带 "=" 时，只能按第一个等号切分，否则值会被截断
        val rdata = DnsData.txt(listOf("token" to "a=b=c"))
        val parsed = DnsData.txtOf(rdata)
        assertEquals("a=b=c", parsed["token"])
    }
}
