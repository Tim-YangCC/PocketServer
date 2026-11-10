package com.pocketserver.net

import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.PairingCode
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 配对与会话管理（FR-2.5 ~ FR-2.10）。
 *
 * ## 为什么是 6 位数字 + 5 分钟 + 锁定
 * 电脑端要"不用装软件、不用记 IP"就能进来，凭据就必须短到能一眼读完、手敲进去。
 * 但短码天然脆弱，所以用三层约束把它兜住：
 *  1. **时效**：5 分钟过期。局域网攻击者能利用的窗口只有这么长。
 *  2. **限次 + 锁定**：同 IP 连续错 5 次锁 5 分钟。6 位码有 100 万种组合，
 *     5 次/5 分钟 ≈ 每小时 60 次，枚举到正确码的期望时间被拉到数十年。
 *  3. **会话令牌替代密码**：配对成功后发的是随机 128 位令牌，不再回传配对码。
 *
 * ## 与"免确认白名单"的关系
 * 已配对设备可换成 90 天长效令牌。**长效令牌不等于放弃安全**：它依然绑定设备指纹，
 * 且可在 UI 上一键清除（"已连接设备"页的移除按钮）。用户找得到、关得掉，
 * 这比"每次都要输码"更安全 —— 后者会逼用户把配对码贴在显示器上。
 *
 * 本类无 Android 依赖，[clock] 可注入以便单元测试。
 */
class PairingManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom()
) {

    /** 一次已授权的会话。 */
    data class Session(
        val token: String,
        val peerIp: String,
        val deviceName: String,
        val createdAt: Long,
        val expiresAt: Long,
        /** 长效令牌 = 已在白名单的设备。 */
        val trusted: Boolean
    ) {
        fun isValid(now: Long): Boolean = now < expiresAt
    }

    sealed class VerifyResult {
        /** 通过。附带剩余有效期便于 UI 提示。 */
        data class Ok(val codeTtlRemaining: Long) : VerifyResult()

        /** 码不对，告知还能试几次。 */
        data class Wrong(val attemptsLeft: Int) : VerifyResult()

        /** 已锁定。 */
        data class Locked(val unlockAt: Long) : VerifyResult()

        /** 码已过期。 */
        data object Expired : VerifyResult()
    }

    private class FailRecord {
        var count: Int = 0
        var firstAttemptAt: Long = 0
        var lockedUntil: Long = 0
    }

    private val lock = Any()
    private val fails = ConcurrentHashMap<String, FailRecord>()
    private val sessions = ConcurrentHashMap<String, Session>()

    private var code: String = ""
    private var issuedAt: Long = 0
    private var expiresAt: Long = 0

    /** 最近一次因锁定被拒的 IP，用于 UI 提示（安全事件可见性）。 */
    private val _lockEvents = AtomicLong(0)
    val lockEventCount: Long get() = _lockEvents.get()

    // ==================== 配对码 ====================

    /** 取当前有效码；已过期则自动换一个新的。 */
    fun ensureCode(): PairingCode = synchronized(lock) {
        val now = clock()
        if (code.isEmpty() || now >= expiresAt) {
            issueLocked(now)
        }
        PairingCode(code, issuedAt, expiresAt)
    }

    /**
     * 用户手动点"换一个"。
     *
     * 注意 [issueLocked] 只负责改内部状态、返回 Unit，所以这里必须**显式构造**返回值。
     * 直接把它当表达式返回会得到 Unit —— 编译器会报返回值类型不匹配，
     * 但若签名写成 `synchronized { }` 推导式就会静默变成 Unit，界面拿不到新码。
     */
    fun rotate(): PairingCode = synchronized(lock) {
        issueLocked(clock())
        PairingCode(code, issuedAt, expiresAt)
    }

    /** 服务停止或权限变更时作废当前码，避免旧码残留可用。 */
    fun invalidate() = synchronized(lock) {
        code = ""
        issuedAt = 0
        expiresAt = 0
    }

    fun codeTtlRemaining(): Long = synchronized(lock) {
        (expiresAt - clock()).coerceAtLeast(0)
    }

    private fun issueLocked(now: Long) {
        val sb = StringBuilder(AppConstants.PAIR_CODE_LENGTH)
        repeat(AppConstants.PAIR_CODE_LENGTH) { sb.append(random.nextInt(10)) }
        code = sb.toString()
        issuedAt = now
        expiresAt = now + AppConstants.PAIR_CODE_TTL_MS
        PocketLog.i(TAG, "已生成新的配对码（有效期 ${AppConstants.PAIR_CODE_TTL_MS / 60000} 分钟）")
    }

    // ==================== 校验 ====================

    /**
     * 校验配对码并签发会话。
     *
     * @param trustedRequested 客户端勾选了"记住此设备"时为 true，签发长效令牌
     * @param silent 免密接入（本机回环 / 已在白名单）时为 true，跳过码校验
     */
    fun verify(
        input: String,
        peerIp: String,
        deviceName: String,
        trustedRequested: Boolean = false,
        silent: Boolean = false
    ): Pair<VerifyResult, Session?> {
        val now = clock()

        if (silent) {
            val session = issueSession(peerIp, deviceName, trustedRequested)
            return VerifyResult.Ok(AppConstants.TRUSTED_TOKEN_TTL_MS) to session
        }

        val record = fails.getOrPut(peerIp) { FailRecord() }
        synchronized(record) {
            if (record.lockedUntil > now) {
                _lockEvents.incrementAndGet()
                return VerifyResult.Locked(record.lockedUntil) to null
            }

            // 超过错误统计窗口就清零，避免"半天前错 3 次 + 现在错 2 次"被累计锁定
            if (record.firstAttemptAt > 0 && now - record.firstAttemptAt > AppConstants.PAIR_FAIL_WINDOW_MS) {
                record.count = 0
                record.firstAttemptAt = 0
            }

            /*
             * 过期判断必须**在** ensureCode() 之前。
             *
             * ensureCode() 的语义是"取当前有效码，没有就发一个新的"，也就是过期时会静默续期。
             * 早期版本先调它、再判 `now >= current.expiresAt`，结果那个分支永远不可达：
             * 用户拿着屏幕上已经作废的旧码来输入，得到的是「配对码不正确，还能试 4 次」——
             * 而真实原因是屏幕上的码早就换了一轮。这种提示会让人反复尝试到被锁定。
             */
            val expired = synchronized(lock) { code.isEmpty() || now >= expiresAt }
            if (expired) {
                return VerifyResult.Expired to null
            }

            val current = ensureCode()

            if (!input.equals(current.code, ignoreCase = false)) {
                record.count++
                if (record.firstAttemptAt == 0L) record.firstAttemptAt = now
                if (record.count >= AppConstants.PAIR_MAX_FAILS) {
                    record.lockedUntil = now + AppConstants.PAIR_LOCKOUT_MS
                    record.count = 0
                    record.firstAttemptAt = 0
                    _lockEvents.incrementAndGet()
                    PocketLog.w(TAG, "IP $peerIp 连续错误达上限，已锁定 ${AppConstants.PAIR_LOCKOUT_MS / 60000} 分钟")
                    return VerifyResult.Locked(record.lockedUntil) to null
                }
                return VerifyResult.Wrong(AppConstants.PAIR_MAX_FAILS - record.count) to null
            }

            // 成功：清掉错误计数，并且**立即作废旧码**
            record.count = 0
            record.firstAttemptAt = 0
            ensureCode()
            rotate()
            val session = issueSession(peerIp, deviceName, trustedRequested)
            PocketLog.i(TAG, "配对成功：$deviceName @$peerIp（trusted=$trustedRequested）")
            return VerifyResult.Ok(current.expiresAt - now) to session
        }
    }

    // ==================== 会话 ====================

    private fun issueSession(peerIp: String, deviceName: String, trusted: Boolean): Session {
        val now = clock()
        val token = randomToken()
        val ttl = if (trusted) AppConstants.TRUSTED_TOKEN_TTL_MS else AppConstants.SESSION_TTL_MS
        val session = Session(
            token = token,
            peerIp = peerIp,
            deviceName = deviceName.ifBlank { "未知设备" },
            createdAt = now,
            expiresAt = now + ttl,
            trusted = trusted
        )
        sessions[token] = session
        return session
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return buildString(32) {
            for (b in bytes) append("%02x".format(b))
        }
    }

    fun validateToken(token: String?): Session? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        if (!session.isValid(clock())) {
            sessions.remove(token)
            return null
        }
        return session
    }

    /** 恢复本机数据库中仍有效的长效浏览器会话。普通短会话不允许从磁盘恢复。 */
    fun restoreTrustedSession(session: Session): Boolean {
        if (!session.trusted || !session.isValid(clock())) return false
        sessions[session.token] = session
        return true
    }

    fun revoke(token: String) {
        sessions.remove(token)
        PocketLog.i(TAG, "会话已注销")
    }

    fun revokeAll() {
        sessions.clear()
    }

    /** 已授权会话（"已连接设备"页展示，FR-2.10）。 */
    fun activeSessions(): List<Session> {
        val now = clock()
        return sessions.values.filter { it.isValid(now) }.sortedByDescending { it.createdAt }
    }

    /** 免确认白名单中的设备（长效令牌）。 */
    fun trustedSessions(): List<Session> = activeSessions().filter { it.trusted }

    fun isTrustedIp(ip: String): Boolean = activeSessions().any { it.trusted && it.peerIp == ip }

    /** 清理过期会话，由服务线程定时调用，避免内存里长期堆积死令牌。 */
    fun sweep() {
        val now = clock()
        sessions.entries.removeAll { !it.value.isValid(now) }
    }

    /** 仅用于测试与诊断。 */
    fun currentCodeOrNull(): String? = synchronized(lock) {
        if (code.isEmpty() || clock() >= expiresAt) null else code
    }

    private companion object {
        const val TAG = "PairingManager"

        @Suppress("unused")
        fun uuidFallback(): String = UUID.randomUUID().toString()
    }
}
