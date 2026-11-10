package com.pocketserver.net

import com.pocketserver.core.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二维码载荷测试（FR-2.7 / FR-10.4）。
 *
 * 载荷是**跨设备契约**：电视生成、手机解析。两边一旦不一致，后果是
 * "扫码没反应"，而且现场根本没法调试（电视上没有日志可看）。
 * 所以往返一致性必须有自动化保障。
 */
class QrCodecTest {

    @Test
    fun `载荷与解析往返一致`() {
        val payload = QrCodec.payload(
            alias = "pocketserver.local",
            port = 8080,
            code = "739214",
            deviceName = "Xiaomi TV",
            ipFallback = "192.168.1.5"
        )
        val parsed = QrCodec.parse(payload)
        assertNotNull(parsed)
        assertEquals("pocketserver.local", parsed!!.host)
        assertEquals(8080, parsed.port)
        assertEquals("739214", parsed.code)
        assertEquals("Xiaomi TV", parsed.name)
        assertEquals("192.168.1.5", parsed.ipFallback)
    }

    @Test
    fun `中文设备名能正确往返`() {
        val payload = QrCodec.payload(
            alias = "pocketserver-2.local",
            port = 8080,
            code = "000123",
            deviceName = "客厅电视",
            ipFallback = "10.0.0.8"
        )
        val parsed = QrCodec.parse(payload)
        assertNotNull(parsed)
        assertEquals("客厅电视", parsed!!.name)
        assertEquals("pocketserver-2.local", parsed.host)
    }

    @Test
    fun `设备名含特殊字符时不会破坏参数结构`() {
        // 设备名来自用户输入（设置页可改），可能包含 & = ? 空格
        val payload = QrCodec.payload(
            alias = "pocketserver.local",
            port = 8080,
            code = "112233",
            deviceName = "A&B=C?D E",
            ipFallback = "192.168.1.9"
        )
        val parsed = QrCodec.parse(payload)
        assertNotNull("特殊字符不能把查询串拆坏", parsed)
        assertEquals("A&B=C?D E", parsed!!.name)
        assertEquals("112233", parsed.code)
        assertEquals(8080, parsed.port)
    }

    @Test
    fun `载荷以自定义 scheme 开头`() {
        val payload = QrCodec.payload("pocketserver.local", 8080, "123456", "TV")
        assertTrue(
            "必须以 ${AppConstants.URL_SCHEME}:// 开头，否则系统不知道由哪个应用处理",
            payload.startsWith("${AppConstants.URL_SCHEME}://")
        )
    }

    @Test
    fun `缺少 IP 兜底参数时仍然可解析`() {
        val payload = QrCodec.payload("pocketserver.local", 8080, "654321", "TV")
        val parsed = QrCodec.parse(payload)
        assertNotNull(parsed)
        assertEquals("", parsed!!.ipFallback)
    }

    @Test
    fun `解析本产品之外的二维码返回 null 而不是抛异常`() {
        // 相机对着任何二维码都可能触发解析，抛异常会让扫码页直接崩掉
        assertNull(QrCodec.parse("https://example.com"))
        assertNull(QrCodec.parse("随便一段文字"))
        assertNull(QrCodec.parse(""))
        assertNull(QrCodec.parse(null))
    }

    @Test
    fun `缺少主机名或配对码时判定为无效`() {
        assertNull("没有 h 参数的码无法接入", QrCodec.parse("pocketserver://pair?p=8080&c=123456"))
        assertNull("没有 c 参数的码无法接入", QrCodec.parse("pocketserver://pair?h=a.local&p=8080"))
    }

    @Test
    fun `端口非法时退化为默认端口`() {
        val parsed = QrCodec.parse("pocketserver://pair?h=a.local&p=abc&c=123456")
        assertNotNull(parsed)
        assertEquals(AppConstants.HTTP_PORT_DEFAULT, parsed!!.port)
    }

    @Test
    fun `缺少设备名时用主机名兜底`() {
        val parsed = QrCodec.parse("pocketserver://pair?h=pocketserver.local&p=8080&c=123456")
        assertNotNull(parsed)
        assertEquals("pocketserver.local", parsed!!.name)
    }

    @Test
    fun `url 属性按 http 拼接`() {
        val parsed = QrCodec.parse("pocketserver://pair?h=a.local&p=9090&c=123456")
        assertEquals("http://a.local:9090", parsed!!.url)
    }

    @Test
    fun `载荷长度足够短 便于在电视上以低密度码显示`() {
        // FR-10.4 要求电视上的码 3 米外可扫，模块越少越容易扫到。
        // 载荷一旦变长（比如把整份设备信息塞进去），码密度会上升、可扫距离立刻下降。
        val payload = QrCodec.payload(
            alias = "pocketserver-2.local",
            port = 65535,
            code = "999999",
            deviceName = "客厅的小米电视 4A 55 英寸",
            ipFallback = "192.168.100.200"
        )
        assertTrue("载荷应当保持在 160 字节以内，实际 ${payload.length}", payload.length <= 160)
    }
}
