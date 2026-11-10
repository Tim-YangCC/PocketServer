package com.pocketserver.net

import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.AccessRequest
import com.pocketserver.data.model.DeviceType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * 接入确认闸门（FR-2.11 / FR-2.12）。
 *
 * ## 为什么必须有这道闸
 * 产品默认是"读写 + 双向对等"，也就是**别人可以往你手机里写文件**。
 * 如果配对码被同办公室的人看到，或者用户为了省事把"每次接入需要配对码"关掉了，
 * 那么任何同网设备都能无名无姓地读写你的相册。这道闸的意义是：
 * **任何新设备的第一次接入，必须在手机/电视屏幕上被明确点一下"允许"。**
 *
 * ## 与配对码的关系
 *  - 配对码：解决"怎么知道对方是可信的"（一次性凭据）
 *  - 接入确认：解决"用户是否知情"（可见性）
 * 两者不互相替代。已进白名单的设备不会再触发确认，否则每次连接都弹窗会逼用户关掉这个功能。
 *
 * ## 超时策略
 * 60 秒无应答自动拒绝。理由是：如果用户在手机上没看到弹窗（比如手机不在身边），
 * 连接请求不应该长期挂在半空中占着线程和文件句柄。
 */
class AccessGate(
    private val timeoutMs: Long = 60_000L
) {

    private class Pending(
        val request: AccessRequest,
        val deferred: CompletableDeferred<Boolean>
    )

    private val lock = Any()
    private val pending = LinkedHashMap<String, Pending>()

    private val _requests = MutableStateFlow<List<AccessRequest>>(emptyList())
    /** UI 订阅：需要展示的待确认接入请求。 */
    val requests: StateFlow<List<AccessRequest>> = _requests.asStateFlow()

    /**
     * 发起一次确认请求并挂起等待结果。
     *
     * @return true = 允许接入
     */
    suspend fun request(
        peerIp: String,
        deviceName: String,
        deviceType: DeviceType = DeviceType.UNKNOWN
    ): Boolean {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val deferred = CompletableDeferred<Boolean>()
        val request = AccessRequest(
            id = id,
            peerIp = peerIp,
            deviceName = deviceName,
            deviceType = deviceType,
            decide = { allow -> decide(id, allow) }
        )
        synchronized(lock) {
            pending[id] = Pending(request, deferred)
            _requests.value = pending.values.map { it.request }
        }
        PocketLog.i(TAG, "等待用户确认接入：$deviceName @$peerIp")

        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            PocketLog.w(TAG, "接入确认超时（${timeoutMs / 1000}秒），自动拒绝：$peerIp")
            false
        } finally {
            synchronized(lock) {
                pending.remove(id)
                _requests.value = pending.values.map { it.request }
            }
        }
    }

    fun decide(id: String, allow: Boolean) {
        val target = synchronized(lock) { pending.remove(id) } ?: return
        target.deferred.complete(allow)
        _requests.value = pending.values.map { it.request }
    }

    /** 电视端遥控器"一键允许/拒绝"用：对最旧的请求做决定。 */
    fun decideOldest(allow: Boolean) {
        val id = synchronized(lock) { pending.keys.firstOrNull() } ?: return
        decide(id, allow)
    }

    fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    /** 服务停止时统一拒绝，避免协程悬挂到超时。 */
    fun rejectAll() {
        val all = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            _requests.value = emptyList()
            copy
        }
        all.forEach { it.deferred.complete(false) }
    }

    private companion object {
        const val TAG = "AccessGate"
    }
}
