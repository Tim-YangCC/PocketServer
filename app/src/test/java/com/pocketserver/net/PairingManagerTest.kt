package com.pocketserver.net

import com.pocketserver.core.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * 配对与会话的状态机测试（FR-2.5 ~ FR-2.10）。
 *
 * ## 为什么这个类的测试最重要
 * [PairingManager] 是"谁能读写你手机文件"的唯一裁决点，而且它的逻辑全是**时间相关**的：
 * 有效期、错误计数窗口、锁定窗口、令牌过期。时间相关的分支最容易写错，
 * 而且在手工测试里几乎不可能覆盖到（谁会真的等 5 分钟看码过期）。
 * 所以这里用可注入的 [PairingManager.clock] 把时间变成可控变量。
 *
 * ## 随机数为什么要固定种子
 * 配对码是随机生成的，而"成功配对后会立即换码"这类断言需要新旧码不同。
 * 不固定种子的话，理论上存在 1/10^6 的概率生成同一个码，测试会偶发失败 ——
 * 偶发失败的测试比没有测试更糟糕，它会让整个测试套件失去可信度。
 */
class PairingManagerTest {

    private var now = 1_700_000_000_000L

    /** 固定种子：让配对码序列可重复，测试结果稳定。 */
    private fun deterministicRandom(): SecureRandom =
        SecureRandom.getInstance("SHA1PRNG").apply { setSeed(20240923L) }

    private fun manager() = PairingManager(clock = { now }, random = deterministicRandom())

    /** 造一个确定不等于当前码的错误码，避免随机碰巧相等的偶发失败。 */
    private fun wrongCodeFor(code: String): String = if (code == "000000") "111111" else "000000"

    @Test
    fun `配对码是 6 位纯数字`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        assertEquals(AppConstants.PAIR_CODE_LENGTH, code.length)
        assertTrue("配对码必须全是数字，实际为 $code", code.all { it in '0'..'9' })
    }

    @Test
    fun `有效期内的码不会变`() {
        val mgr = manager()
        val first = mgr.ensureCode().code
        now += 60_000L
        assertEquals(first, mgr.ensureCode().code)
    }

    @Test
    fun `过期后自动签发新码`() {
        val mgr = manager()
        val first = mgr.ensureCode().code
        now += AppConstants.PAIR_CODE_TTL_MS + 1
        val second = mgr.ensureCode().code
        assertNotEquals(first, second)
    }

    @Test
    fun `正确配对码签发会话`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val (result, session) = mgr.verify(code, "192.168.1.20", "客厅台式机")
        assertTrue("应当通过，实际 $result", result is PairingManager.VerifyResult.Ok)
        assertNotNull(session)
        assertEquals("192.168.1.20", session!!.peerIp)
        assertEquals("客厅台式机", session.deviceName)
        // 未勾选"记住"时是普通会话，不是长效令牌
        assertTrue(!session.trusted)
        assertEquals(1, mgr.activeSessions().size)
    }

    @Test
    fun `成功配对后旧码立即作废`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        mgr.verify(code, "192.168.1.20", "PC")
        // 旧码不能再用第二次
        val (again, session) = mgr.verify(code, "192.168.1.21", "另一台")
        assertNull(session)
        assertTrue("旧码必须失效，实际 $again", again !is PairingManager.VerifyResult.Ok)
    }

    @Test
    fun `错误码返回剩余尝试次数`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val wrong = wrongCodeFor(code)
        val (first, _) = mgr.verify(wrong, "10.0.0.5", "x")
        assertEquals(PairingManager.VerifyResult.Wrong(AppConstants.PAIR_MAX_FAILS - 1), first)
        val (second, _) = mgr.verify(wrong, "10.0.0.5", "x")
        assertEquals(PairingManager.VerifyResult.Wrong(AppConstants.PAIR_MAX_FAILS - 2), second)
    }

    @Test
    fun `连续错误达上限后锁定 且锁定期间正确码也被拒`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val wrong = wrongCodeFor(code)

        repeat(AppConstants.PAIR_MAX_FAILS - 1) {
            mgr.verify(wrong, "10.0.0.9", "x")
        }
        val (locked, _) = mgr.verify(wrong, "10.0.0.9", "x")
        assertTrue("第 ${AppConstants.PAIR_MAX_FAILS} 次错误应当触发锁定，实际 $locked",
            locked is PairingManager.VerifyResult.Locked)

        // 关键：锁定的意义就在于即使码对也进不来
        val (stillLocked, session) = mgr.verify(code, "10.0.0.9", "x")
        assertTrue(stillLocked is PairingManager.VerifyResult.Locked)
        assertNull(session)
        // 触发锁定一次 + 锁定期间被拒一次，至少要有 1 次记录（不写死次数：
        // 那是实现细节，安全事件计数只要"确实被记下来了"就够了）
        assertTrue(mgr.lockEventCount >= 1L)
    }

    @Test
    fun `锁定到期后可以重新尝试`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val wrong = wrongCodeFor(code)
        repeat(AppConstants.PAIR_MAX_FAILS) { mgr.verify(wrong, "10.0.0.9", "x") }
        assertTrue(mgr.verify(wrong, "10.0.0.9", "x").first is PairingManager.VerifyResult.Locked)

        now += AppConstants.PAIR_LOCKOUT_MS + 1
        // 锁定期间码已过期并换新，取最新码来验
        val fresh = mgr.ensureCode().code
        val (result, session) = mgr.verify(fresh, "10.0.0.9", "x")
        assertTrue("锁定到期后应当可以接入，实际 $result",
            result is PairingManager.VerifyResult.Ok)
        assertNotNull(session)
    }

    @Test
    fun `锁定只针对单个 IP 不影响其他设备`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val wrong = wrongCodeFor(code)
        repeat(AppConstants.PAIR_MAX_FAILS) { mgr.verify(wrong, "10.0.0.9", "攻击者") }

        val (result, _) = mgr.verify(code, "10.0.0.10", "自己的电脑")
        assertTrue("不应因为别人的失败而连坐，实际 $result",
            result is PairingManager.VerifyResult.Ok)
    }

    @Test
    fun `码过期后输入旧码返回已过期而不是错误`() {
        /*
         * 这条用例专门盯着一个曾经存在过的 bug：
         * verify() 里如果先调 ensureCode()（它会在过期时静默续期），
         * 那么"已过期"这个分支永远不可达，用户拿着屏幕上的旧码来输入，
         * 得到的会是「配对码不正确，还能试 4 次」——于是他会反复试到被锁定。
         */
        val mgr = manager()
        val code = mgr.ensureCode().code
        now += AppConstants.PAIR_CODE_TTL_MS + 1
        val (result, session) = mgr.verify(code, "192.168.1.30", "PC")
        assertEquals(PairingManager.VerifyResult.Expired, result)
        assertNull(session)
    }

    @Test
    fun `错误计数超出统计窗口后清零`() {
        val mgr = manager()
        val code = mgr.ensureCode().code

        repeat(AppConstants.PAIR_MAX_FAILS - 1) { mgr.verify(wrongCodeFor(code), "10.0.0.7", "x") }
        // 隔了比错误窗口更久之后，之前的失败不该再累计
        now += AppConstants.PAIR_FAIL_WINDOW_MS + 1
        // 这个跳跃同时越过了配对码本身的有效期，所以要按现实语义取一次**当前屏幕上的码**：
        // 用户隔十分钟回来，看到的是自动续期后的新码，输入它才代表一次正常的重试。
        // 若继续用旧码，verify() 会先返回 Expired（那是另一个用例覆盖的行为）。
        val current = mgr.ensureCode().code
        val (result, _) = mgr.verify(wrongCodeFor(current), "10.0.0.7", "x")
        assertEquals(
            PairingManager.VerifyResult.Wrong(AppConstants.PAIR_MAX_FAILS - 1),
            result
        )
    }

    @Test
    fun `勾选记住设备时签发长效令牌`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val (_, session) = mgr.verify(code, "192.168.1.40", "我的笔记本", trustedRequested = true)
        assertNotNull(session)
        assertTrue(session!!.trusted)
        assertTrue(mgr.isTrustedIp("192.168.1.40"))
        assertEquals(1, mgr.trustedSessions().size)
    }

    @Test
    fun `silent 模式跳过配对码校验且不受锁定影响`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val wrong = wrongCodeFor(code)
        repeat(AppConstants.PAIR_MAX_FAILS) { mgr.verify(wrong, "10.0.0.8", "x") }
        assertTrue(mgr.verify(wrong, "10.0.0.8", "x").first is PairingManager.VerifyResult.Locked)

        // 免确认白名单走的正是 silent 通道：它必须绕开限次与锁定，
        // 否则"记住这台设备"就失去了意义
        val (result, session) = mgr.verify(
            input = "",
            peerIp = "10.0.0.8",
            deviceName = "已信任设备",
            trustedRequested = true,
            silent = true
        )
        assertTrue(result is PairingManager.VerifyResult.Ok)
        assertNotNull(session)
    }

    @Test
    fun `令牌校验与注销`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val (_, session) = mgr.verify(code, "192.168.1.50", "PC")
        val token = session!!.token

        assertNotNull("刚签发的令牌应当有效", mgr.validateToken(token))
        assertNull("空令牌不能通过", mgr.validateToken(null))
        assertNull("伪造的令牌不能通过", mgr.validateToken("deadbeef"))

        mgr.revoke(token)
        assertNull("注销后令牌必须立刻失效", mgr.validateToken(token))
        assertTrue(mgr.activeSessions().isEmpty())
    }

    @Test
    fun `令牌超过有效期后失效并被清理`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        val (_, session) = mgr.verify(code, "192.168.1.60", "PC")
        val token = session!!.token

        now += AppConstants.SESSION_TTL_MS + 1
        assertNull(mgr.validateToken(token))

        // sweep 负责把死令牌从内存里清掉，避免长时间运行后集合无限增长
        mgr.sweep()
        assertTrue(mgr.activeSessions().isEmpty())
    }

    @Test
    fun `invalidate 后当前码不可用`() {
        val mgr = manager()
        val code = mgr.ensureCode().code
        mgr.invalidate()
        assertNull("服务停止后不应还留着一个可用码", mgr.currentCodeOrNull())

        val (result, session) = mgr.verify(code, "192.168.1.70", "PC")
        assertEquals(PairingManager.VerifyResult.Expired, result)
        assertNull(session)
    }

    @Test
    fun `rotate 立即更换配对码`() {
        val mgr = manager()
        val first = mgr.ensureCode().code
        val rotated = mgr.rotate().code
        assertNotEquals(first, rotated)
        assertEquals(rotated, mgr.currentCodeOrNull())
    }

    @Test
    fun `剩余有效期随时间递减到零`() {
        val mgr = manager()
        mgr.ensureCode()
        val full = mgr.codeTtlRemaining()
        assertTrue(full > 0)
        now += 60_000L
        assertTrue(mgr.codeTtlRemaining() < full)

        now += AppConstants.PAIR_CODE_TTL_MS
        assertEquals(0L, mgr.codeTtlRemaining())
    }
}
