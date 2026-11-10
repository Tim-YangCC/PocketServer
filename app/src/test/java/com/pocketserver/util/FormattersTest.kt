package com.pocketserver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值格式化测试。
 *
 * 这些函数看起来琐碎，但它们决定了用户**判断"快不快"的唯一依据**。
 * 一个把 512KB/s 显示成 "512.0 MB/s" 的 bug，会让人以为优化生效了 ——
 * 这比报错更糟糕，因为它会让错误的结论看起来是"好结果"。
 */
class FormattersTest {

    // ==================== 字节 ====================

    @Test
    fun `零字节与负数都显示为 0 B`() {
        assertEquals("0 B", Formatters.bytes(0))
        assertEquals("0 B", Formatters.bytes(-1))
    }

    @Test
    fun `字节单位自动进位`() {
        assertEquals("512 B", Formatters.bytes(512))
        assertEquals("1.0 KB", Formatters.bytes(1024))
        assertEquals("1.5 KB", Formatters.bytes(1536))
        assertEquals("1.0 MB", Formatters.bytes(1024L * 1024))
        assertEquals("1.0 GB", Formatters.bytes(1024L * 1024 * 1024))
    }

    @Test
    fun `超过一百时省略小数位 避免数字过长`() {
        // "100.0 MB" 比 "100 MB" 多两位，在窄屏列表里会被截断
        assertEquals("100 MB", Formatters.bytes(100L * 1024 * 1024))
    }

    // ==================== 速率 ====================

    @Test
    fun `速率单位带每秒后缀`() {
        assertEquals("0 B/s", Formatters.speed(0))
        assertEquals("512 KB/s", Formatters.speed(512L * 1024))
        assertEquals("10.0 MB/s", Formatters.speed(10L * 1024 * 1024))
    }

    @Test
    fun `测速结果的特殊值处理`() {
        // 未测过的时候必须显示占位符，显示 "0 B/s" 会让人以为测出来是零速
        assertEquals("—", Formatters.megaBytesPerSecond(0.0))
        assertEquals("—", Formatters.megaBytesPerSecond(-1.0))
    }

    @Test
    fun `小于 1MB 每秒时降级到 KB 显示`() {
        // 0.3MB/s 显示成 "0.3 MB/s" 精度太低，用户看不出是 300KB/s 还是 350KB/s
        assertEquals("512 KB/s", Formatters.megaBytesPerSecond(0.5))
    }

    @Test
    fun `大于 1MB 每秒时保留一位小数`() {
        assertEquals("12.3 MB/s", Formatters.megaBytesPerSecond(12.34))
    }

    // ==================== 时长 ====================

    @Test
    fun `时长按量级切换表达`() {
        assertEquals("0秒", Formatters.duration(0))
        assertEquals("0秒", Formatters.duration(-100))
        assertEquals("45秒", Formatters.duration(45_000))
        assertEquals("1分5秒", Formatters.duration(65_000))
        assertEquals("1小时0分", Formatters.duration(3_600_000))
        assertEquals("1天1小时", Formatters.duration(90_000_000))
    }

    // ==================== 倒计时 ====================

    @Test
    fun `倒计时补零到两位`() {
        assertEquals("1:05", Formatters.countdown(65_000))
        assertEquals("0:09", Formatters.countdown(9_000))
        assertEquals("5:00", Formatters.countdown(300_000))
    }

    @Test
    fun `倒计时负数归零 不出现负号`() {
        assertEquals("0:00", Formatters.countdown(-1))
    }

    // ==================== 剩余时间 ====================

    @Test
    fun `无法估算时显示占位符`() {
        assertEquals("—", Formatters.eta(remainingBytes = 0, bytesPerSecond = 1000))
        assertEquals("—", Formatters.eta(remainingBytes = 1000, bytesPerSecond = 0))
    }

    @Test
    fun `超过一小时时不假装精确`() {
        // 报"4000秒"没有意义，用户要的是"超过一小时"这个量级判断
        assertEquals(">1小时", Formatters.eta(4_000_000, 1000))
    }

    @Test
    fun `一小时以内给出秒数`() {
        assertEquals("10秒", Formatters.eta(10_000, 1000))
    }

    // ==================== 相对时间 ====================

    @Test
    fun `相对时间按距离切换表达`() {
        val now = 1_700_000_000_000L
        assertEquals("刚刚", Formatters.relative(now - 30_000, now))
        assertEquals("5分钟前", Formatters.relative(now - 300_000, now))
        assertEquals("2小时前", Formatters.relative(now - 7_200_000, now))
        assertEquals("3天前", Formatters.relative(now - 3 * 86_400_000L, now))
    }

    @Test
    fun `超过一周给出具体日期`() {
        val now = 1_700_000_000_000L
        val text = Formatters.relative(now - 30L * 86_400_000L, now)
        assertTrue("过久的时间应当显示日期，实际为：$text", text.contains("-"))
    }

    @Test
    fun `时间格式函数输出非空`() {
        assertTrue(Formatters.clock(1_700_000_000_000L).isNotBlank())
        assertTrue(Formatters.dateTime(1_700_000_000_000L).isNotBlank())
        assertTrue(Formatters.ftpTimestamp(1_700_000_000_000L).isNotBlank())
        assertTrue(Formatters.httpDate(1_700_000_000_000L).isNotBlank())
    }

    @Test
    fun `HTTP 日期使用 GMT 且为 RFC1123 格式`() {
        // WebDAV 客户端会按 RFC 1123 解析这个字段；格式错了会导致 Windows
        // 映射网络驱动器时显示"修改时间未知"
        val text = Formatters.httpDate(1_700_000_000_000L)
        assertTrue("必须带 GMT 后缀，实际为：$text", text.endsWith("GMT"))
        assertTrue("必须包含逗号分隔的星期与日期", text.contains(","))
    }

    @Test
    fun `FTP 时间戳是 14 位数字`() {
        val text = Formatters.ftpTimestamp(1_700_000_000_000L)
        assertEquals(14, text.length)
        assertTrue(text.all { it in '0'..'9' })
    }
}
