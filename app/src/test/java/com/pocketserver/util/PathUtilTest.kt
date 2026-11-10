package com.pocketserver.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路径归一化与安全测试。
 *
 * ## 这个类是全部安全性的底座
 * HTTP / WebDAV / FTP 三条协议栈收到的路径都是**外部输入**，最终都会汇到
 * [PathUtil.sanitize]。它一旦漏判一次，攻击者就能读写共享根之外的任意文件
 * （Android 上共享根是内部存储，所以 `../` 就是通向整个相册的门）。
 *
 * 因此这里的用例全部围绕"必须拒绝"来写，而不是"能解析正确"。
 */
class PathUtilTest {

    // ==================== sanitize：必须拒绝的 ====================

    @Test
    fun `拒绝向上跳目录`() {
        assertNull(PathUtil.sanitize("../secret"))
        assertNull(PathUtil.sanitize("../../etc/passwd"))
        assertNull(PathUtil.sanitize("photos/../../secret"))
        assertNull(PathUtil.sanitize("a/b/../../../c"))
    }

    @Test
    fun `拒绝 Windows 风格的向上跳`() {
        // 反斜杠会被先行统一成正斜杠，所以这种变体也必须被拦住
        assertNull(PathUtil.sanitize("..\\..\\windows\\system32"))
        assertNull(PathUtil.sanitize("a\\..\\..\\b"))
    }

    @Test
    fun `拒绝含空字符的路径`() {
        // NUL 截断是经典的绕过手法：底层 C 代码看到 \0 就认为字符串结束了
        assertNull(PathUtil.sanitize("normal\u0000../../secret"))
    }

    @Test
    fun `拒绝含控制字符的段`() {
        assertNull(PathUtil.sanitize("a/\u0001b/c"))
        assertNull(PathUtil.sanitize("a/\tb/c"))
    }

    @Test
    fun `拒绝超长单段名称`() {
        // 单段超过 255 字节在多数文件系统上会直接失败，提前拦掉可以避免把
        // 错误从"路径非法"变成更难查的"写入失败"
        val tooLong = "x".repeat(256)
        assertNull(PathUtil.sanitize(tooLong))
        assertNull(PathUtil.sanitize("dir/$tooLong"))
    }

    @Test
    fun `拒绝超深目录`() {
        val deep = (1..40).joinToString("/") { "d$it" }
        assertNull(PathUtil.sanitize(deep))
    }

    @Test
    fun `null 输入返回 null 而不是崩溃`() {
        assertNull(PathUtil.sanitize(null))
    }

    // ==================== sanitize：必须正确归一化 ====================

    @Test
    fun `去掉前导斜杠与空段`() {
        assertEquals("a/b", PathUtil.sanitize("/a/b"))
        assertEquals("a/b", PathUtil.sanitize("a//b"))
        assertEquals("a/b", PathUtil.sanitize("a/b/"))
    }

    @Test
    fun `去掉单点段`() {
        assertEquals("a/b", PathUtil.sanitize("./a/./b"))
        // "b." 是合法文件名，只有整段恰好等于 "." 才该被丢掉
        assertEquals("a/b.", PathUtil.sanitize("a/./b."))
    }

    @Test
    fun `空路径归一化为空串 代表共享根`() {
        assertEquals("", PathUtil.sanitize(""))
        assertEquals("", PathUtil.sanitize("/"))
        assertEquals("", PathUtil.sanitize("   "))
    }

    @Test
    fun `统一反斜杠为正斜杠`() {
        assertEquals("a/b/c", PathUtil.sanitize("a\\b\\c"))
    }

    @Test
    fun `保留中文与空格`() {
        assertEquals("我的照片/2024 夏天.jpg", PathUtil.sanitize("我的照片/2024 夏天.jpg"))
    }

    @Test
    fun `允许段内出现两个点但不是纯两点`() {
        // "a..b" 是合法文件名；只有整段等于 ".." 才是向上跳
        assertEquals("a..b/c", PathUtil.sanitize("a..b/c"))
    }

    // ==================== parent / fileName / extension ====================

    @Test
    fun `取父级路径`() {
        assertEquals("a/b", PathUtil.parent("a/b/c"))
        assertEquals("", PathUtil.parent("a"))
        assertNull("根目录没有父级", PathUtil.parent(""))
        assertNull("非法路径没有父级", PathUtil.parent("../a"))
    }

    @Test
    fun `取文件名`() {
        assertEquals("c.txt", PathUtil.fileName("a/b/c.txt"))
        assertEquals("a.txt", PathUtil.fileName("a.txt"))
        assertEquals("c", PathUtil.fileName("a/b/c/"))
    }

    @Test
    fun `取扩展名统一小写`() {
        assertEquals("jpg", PathUtil.extension("a.JPG"))
        assertEquals("tar", PathUtil.extension("archive.tar"))
        assertEquals("", PathUtil.extension("noext"))
        assertEquals("", PathUtil.extension(".hidden"))
    }

    @Test
    fun `拼接路径不会产生重复斜杠`() {
        assertEquals("a/b", PathUtil.join("a", "b"))
        assertEquals("b", PathUtil.join("", "b"))
        assertEquals("a", PathUtil.join("a", ""))
        assertEquals("a/b", PathUtil.join("/a/", "/b/"))
    }

    // ==================== dedupe：上传永不覆盖（FR-3.8） ====================

    @Test
    fun `同名文件生成带序号的副本`() {
        val existing = setOf("照片.jpg", "照片 (2).jpg")
        assertEquals("照片 (3).jpg", PathUtil.dedupe("照片.jpg") { it in existing })
    }

    @Test
    fun `不冲突时保持原名`() {
        assertEquals("照片.jpg", PathUtil.dedupe("照片.jpg") { false })
    }

    @Test
    fun `多个扩展名的文件按最后一个点切分`() {
        val existing = setOf("archive.tar.gz")
        assertEquals("archive.tar (2).gz", PathUtil.dedupe("archive.tar.gz") { it in existing })
    }

    @Test
    fun `隐藏文件不会把点当成扩展名分隔符`() {
        // .nomedia 整名就是文件名，不能切成 base="" + ext=".nomedia"
        val result = PathUtil.dedupe(".nomedia") { it == ".nomedia" }
        assertEquals(".nomedia (2)", result)
    }

    @Test
    fun `无扩展名文件也能生成副本名`() {
        assertEquals("README (2)", PathUtil.dedupe("README") { it == "README" })
    }

    @Test
    fun `序号一直占用时不会返回空串或死循环`() {
        // 极端场景：目录里恰好有 1..9999 全部序号。此时必须给出一个可用名字，
        // 而不是返回空或抛异常 —— 上传一个文件不该因为命名而整体失败。
        val existing = (1..9_999).map { "a ($it).txt" }.toSet() + "a.txt"
        val result = PathUtil.dedupe("a.txt") { it in existing }
        assertTrue("必须给出一个可用名字", result.isNotBlank())
        assertFalse("不能返回已被占用的名字", result in existing)
    }

    // ==================== 其它 ====================

    @Test
    fun `识别隐藏文件`() {
        assertTrue(PathUtil.isHidden(".nomedia"))
        assertTrue(PathUtil.isHidden("dir/.thumbnails"))
        assertFalse(PathUtil.isHidden("dir/photo.jpg"))
    }

    @Test
    fun `常见类型的 MIME 映射`() {
        assertEquals("image/jpeg", PathUtil.mimeType("a.jpg"))
        assertEquals("image/jpeg", PathUtil.mimeType("a.JPEG"))
        assertEquals("image/png", PathUtil.mimeType("a.png"))
        assertTrue("未知类型应当有兜底值，不能返回空串", PathUtil.mimeType("a.unknown").isNotBlank())
    }
}
