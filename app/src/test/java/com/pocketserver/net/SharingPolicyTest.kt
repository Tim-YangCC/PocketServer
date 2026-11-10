package com.pocketserver.net

import com.pocketserver.data.model.SharedPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享策略矩阵测试（FR-6 / FR-10.6）。
 *
 * ## 为什么值得单独为"几个布尔判断"写一整个测试类
 * [SharingPolicy] 是 HTTP / FTP / WebDAV / 对等传输四条协议栈**共用的**写权限裁决点。
 * 它一旦判错，漏洞是静默的：网页端改了开关、FTP 那条通道照样能删文件。
 * 而且这个功能用户在手工测试时几乎不会覆盖（谁会专门去试"只读模式下用 FTP 删文件"）。
 */
class SharingPolicyTest {

    // ==================== 默认值（决策 D-3：双向对等） ====================

    @Test
    fun `默认是读写 且各项写权限都开放`() {
        val policy = SharingPolicy()
        assertTrue(policy.canRead)
        assertTrue(policy.canWrite)
        assertTrue(policy.canDeleteAllowed)
        assertTrue(policy.canRenameAllowed)
        assertTrue(policy.canMkdirAllowed)
    }

    // ==================== 只读模式 ====================

    @Test
    fun `只读模式下所有写操作都被拒绝 但可读不变`() {
        val policy = SharingPolicy(permission = SharedPermission.READ_ONLY)
        // 只读的意义就是"能看不能改"，所以读必须仍然可用
        assertTrue("只读不等于不可读", policy.canRead)
        assertFalse(policy.canWrite)
        assertFalse(policy.canDeleteAllowed)
        assertFalse(policy.canRenameAllowed)
        assertFalse(policy.canMkdirAllowed)
    }

    // ==================== 单项开关（读写模式下生效） ====================

    @Test
    fun `关闭上传后仍然可以删除与重命名`() {
        // 这个组合很常见："不许别人往我手机里塞东西，但可以整理已有文件"
        val policy = SharingPolicy(allowUpload = false)
        assertFalse(policy.canWrite)
        assertTrue(policy.canDeleteAllowed)
        assertTrue(policy.canRenameAllowed)
    }

    @Test
    fun `关闭删除后仍可上传`() {
        // 「允许上传但禁止删除」是最需要的一个组合：传进来的东西不许它自己删掉
        val policy = SharingPolicy(allowDelete = false)
        assertTrue(policy.canWrite)
        assertFalse(policy.canDeleteAllowed)
    }

    @Test
    fun `关闭重命名同时关闭新建文件夹`() {
        // 两者共用同一个开关：都是"改名字"这一类操作，分开给只会增加用户的决策成本
        val policy = SharingPolicy(allowRename = false)
        assertFalse(policy.canRenameAllowed)
        assertFalse(policy.canMkdirAllowed)
    }

    @Test
    fun `关闭新建文件夹不影响重命名`() {
        val policy = SharingPolicy(allowMkdir = false)
        assertFalse(policy.canMkdirAllowed)
        assertTrue("allowMkdir 是独立开关，不该连带砍掉重命名", policy.canRenameAllowed)
    }

    // ==================== 电视端裁剪（FR-10.6） ====================

    @Test
    fun `电视端关闭文本操作 但上传下载不受影响`() {
        val policy = SharingPolicy(allowTextOperations = false)
        assertFalse("电视端不提供重命名", policy.canRenameAllowed)
        assertFalse("电视端不提供新建文件夹", policy.canMkdirAllowed)
        // 关键：裁剪的只是"要敲字"的操作，不该牵连上传与删除
        assertTrue("上传不需要输入文字，应当保留", policy.canWrite)
        assertTrue("删除不需要输入文字，应当保留", policy.canDeleteAllowed)
        assertTrue(policy.canRead)
    }

    @Test
    fun `电视端与只读模式叠加时全部写操作关闭`() {
        val policy = SharingPolicy(
            permission = SharedPermission.READ_ONLY,
            allowTextOperations = false
        )
        assertTrue(policy.canRead)
        assertFalse(policy.canWrite)
        assertFalse(policy.canDeleteAllowed)
        assertFalse(policy.canRenameAllowed)
        assertFalse(policy.canMkdirAllowed)
    }

    // ==================== 文案与描述 ====================

    @Test
    fun `只读模式的拒绝文案说明了该怎么办`() {
        val message = SharingPolicy(permission = SharedPermission.READ_ONLY).readOnlyMessage
        // 只说"被拒绝"是没用的，必须告诉用户去哪里改
        assertTrue("拒绝文案应当指向设置入口，实际为：$message", message.contains("手机"))
    }

    @Test
    fun `describe 反映当前权限`() {
        assertTrue(SharingPolicy().describe().contains("读写"))
        assertTrue(
            SharingPolicy(permission = SharedPermission.READ_ONLY).describe().contains("只读")
        )
    }
}
