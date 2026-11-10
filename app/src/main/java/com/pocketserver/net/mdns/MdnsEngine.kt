package com.pocketserver.net.mdns

import android.content.Context
import android.net.wifi.WifiManager
import com.pocketserver.core.AppConstants
import com.pocketserver.core.PocketLog
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.data.model.SharedPermission
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * mDNS 应答器 + 浏览器（需求 FR-2.4 / FR-2.8 / FR-11.1）。
 *
 * ## 它解决的核心问题
 * 电脑端要访问手机，得知道地址。让用户手输 `192.168.1.5:8080` 是产品失败的原因之一：
 * IP 会变、记不住、输错。mDNS 让用户只需要在浏览器里打 `pocketserver.local`。
 *
 * ## 为什么必须自研
 * Android 官方的 `NsdManager` 只能注册服务实例，系统会用自己的主机名广播
 * （形如 `Android.local`），**无法提供固定主机名**。而"固定、好听、好记"恰恰是全部价值。
 * 所以这里直接开 5353 端口收发 mDNS 报文。
 *
 * ## 主机名冲突策略（局域网有多个 PocketServer 时）
 *  - 每个设备先申请"友好名" `pocketserver.local`；
 *  - 启动时先探测 800ms，若已有别的设备占着这个名字，就退到 `pocketserver-2.local`…；
 *  - 运行期若发现别人抢了我们的名字（A 记录指向别的 IP），立刻降级并重新宣告；
 *  - 同时每个设备始终广播一个**唯一的规范名** `<deviceId>.local`，保证 SRV 记录永远可解析。
 *
 * ## 一个容易踩的坑
 * 手机息屏后 Wi-Fi 会进入省电模式，组播报文被硬件过滤掉，表现为"服务在跑但电脑发现不了"。
 * 因此**必须持有 MulticastLock**，否则这个问题会以"偶发性失效"的形式长期存在，极难排查。
 */
class MdnsEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher
) {

    /** 对外宣告的服务描述。 */
    data class ServiceInfo(
        val deviceId: String,
        val displayName: String,
        val deviceType: DeviceType,
        val httpPort: Int,
        val ftpPort: Int,
        val permission: SharedPermission,
        val version: Int = 1
    )

    private val serviceType = "${AppConstants.MDNS_SERVICE_TYPE}.local"

    private val _hostAlias = MutableStateFlow(AppConstants.MDNS_HOST_BASE)
    /** 当前持有的友好主机名（可能带退避序号）。UI 展示的就是它。 */
    val hostAlias: StateFlow<String> = _hostAlias.asStateFlow()

    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    /** 局域网内发现到的其他 PocketServer（FR-11.1）。 */
    val devices: StateFlow<List<NearbyDevice>> = _devices.asStateFlow()

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()

    private var socket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var receiveJob: Job? = null
    private var announceJob: Job? = null

    private val running = AtomicBoolean(false)

    private var service: ServiceInfo? = null
    private var localIp: String = ""
    private var canonicalHost: String = ""
    /** 稳定的设备专属主机名；与可能冲突退避的 pocketserver.local 别名分开。 */
    val canonicalHostName: String get() = canonicalHost
    @Volatile private var probeDone = false

    private val claimedAliases = java.util.concurrent.ConcurrentHashMap<String, String>() // alias -> ip
    private val resolvingConflict = AtomicBoolean(false)

    // ==================== 生命周期 ====================

    /**
     * 启动应答器。返回最终生效的友好主机名（可能与请求的不同）。
     */
    suspend fun start(info: ServiceInfo, ip: String): String {
        stop()
        service = info
        localIp = ip
        canonicalHost = "${info.deviceId}.local"

        acquireMulticastLock()
        openSocket()

        claimedAliases.clear()
        probeDone = false
        running.set(true)
        // 探测期间也必须接收 mDNS 响应。旧实现先发起探测、等待 800ms 后才启动接收器，
        // 冲突响应无法及时进入 claimedAliases，多个设备会一起认领 pocketserver.local。
        startReceiver()
        // 先探测冲突，再决定用哪个友好名；探测期间不宣告，避免制造更多冲突
        probeAlias()
        probeDone = true

        announceNow()
        startAnnounceLoop()

        PocketLog.i(TAG, "mDNS 就绪：alias=${_hostAlias.value} canonical=$canonicalHost ip=$ip")
        return _hostAlias.value
    }

    fun stop() {
        running.set(false)
        probeDone = false
        receiveJob?.cancel()
        announceJob?.cancel()
        receiveJob = null
        announceJob = null

        // 发"告别"报文（PTR + TTL=0），让对方立刻摘掉我们的条目，而不是等 TTL 到期
        runCatching { sendMulticast(buildGoodbye()) }

        runCatching { socket?.close() }
        socket = null
        releaseMulticastLock()
        _devices.value = emptyList()
        claimedAliases.clear()
        PocketLog.i(TAG, "mDNS 已停止")
    }

    /** 立即主动发起一次服务发现（UI 下拉刷新用）。 */
    fun refresh() {
        runCatching { sendMulticast(buildBrowseQuery()) }
    }

    // ==================== socket 与锁 ====================

    private fun acquireMulticastLock() {
        runCatching {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("pocketserver-mdns").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { PocketLog.w(TAG, "MulticastLock 获取失败：${it.message}") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun openSocket() {
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(AppConstants.MDNS_PORT))
        runCatching { s.timeToLive = 255 }

        // 指定走 Wi-Fi 网卡：手机常有蜂窝 + Wi-Fi 双栈，不指定可能把组播发到蜂窝口
        val nif = findInterfaceForIp(localIp)
        if (nif != null) {
            runCatching { s.networkInterface = nif }
        }
        runCatching {
            val group = InetAddress.getByName(AppConstants.MDNS_MULTICAST_ADDR)
            if (nif != null) {
                s.joinGroup(InetSocketAddress(group, AppConstants.MDNS_PORT), nif)
            } else {
                @Suppress("DEPRECATION")
                s.joinGroup(group)
            }
        }.onFailure { PocketLog.w(TAG, "加入组播组失败：${it.message}") }

        // 关掉环回：我们自己发的报文不必再处理一遍，否则每轮宣告都会触发一次自我"冲突"误判
        runCatching { s.loopbackMode = true }

        socket = s
    }

    private fun findInterfaceForIp(ip: String): NetworkInterface? = runCatching {
        if (ip.isBlank()) return@runCatching null
        NetworkInterface.getByInetAddress(InetAddress.getByName(ip))
    }.getOrNull()

    private val groupAddress: InetSocketAddress by lazy {
        InetSocketAddress(InetAddress.getByName(AppConstants.MDNS_MULTICAST_ADDR), AppConstants.MDNS_PORT)
    }

    // ==================== 收发 ====================

    private fun startReceiver() {
        receiveJob = scope.launch(dispatcher) {
            val buffer = ByteArray(MAX_PACKET)
            while (isActive && running.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    if (packet.length <= 0) continue
                    val data = buffer.copyOf(packet.length)
                    handlePacket(data, packet.address, packet.port)
                } catch (t: Throwable) {
                    if (!running.get()) break
                    PocketLog.d(TAG, "接收异常：${t.message}")
                    delay(200)
                }
            }
        }
    }

    private fun handlePacket(data: ByteArray, from: InetAddress, fromPort: Int) {
        val message = DnsCodec.decode(data, data.size) ?: return
        val fromIp = from.hostAddress ?: return
        if (fromIp == localIp) return

        if (message.isResponse) {
            handleResponse(message, fromIp)
        } else {
            handleQuery(message, from, fromPort)
        }
    }

    private fun handleQuery(message: DnsMessage, from: InetAddress, fromPort: Int) {
        if (service == null) return

        var wantsA = false
        var wantsService = false
        var unicast = false

        for (q in message.questions) {
            unicast = unicast || q.unicastResponse
            when {
                (q.type == DnsType.A || q.type == DnsType.ANY) &&
                    (q.name.equals(_hostAlias.value, true) || q.name.equals(canonicalHost, true)) -> wantsA = true

                q.type == DnsType.PTR && q.name.equals(serviceType, true) -> wantsService = true

                // 服务枚举查询：路由器/发现工具常先查这个再查具体类型
                q.type == DnsType.PTR && q.name.equals(META_SERVICE_TYPE, true) -> wantsService = true
            }
        }
        if (!wantsA && !wantsService) return

        val response = if (wantsA && !wantsService) {
            DnsMessage(
                isResponse = true,
                authoritative = true,
                answers = buildAddressRecords(AppConstants.MDNS_TTL_S)
            )
        } else {
            buildServiceResponse(AppConstants.MDNS_TTL_S, includeMeta = message.questions.any {
                it.name.equals(META_SERVICE_TYPE, true)
            })
        }

        val bytes = DnsCodec.encode(response)
        if (unicast) {
            runCatching {
                socket?.send(DatagramPacket(bytes, bytes.size, from, fromPort))
            }
        } else {
            sendMulticast(bytes)
        }
        PocketLog.d(TAG, "已响应查询 from=${from.hostAddress} wantsA=$wantsA wantsService=$wantsService")
    }

    private fun handleResponse(message: DnsMessage, fromIp: String) {
        val all = message.answers + message.additionals
        checkConflict(all, fromIp)
        collectDevices(all, fromIp)
    }

    private fun sendMulticast(bytes: ByteArray = ByteArray(0)) {
        if (bytes.isEmpty()) return
        runCatching {
            socket?.send(DatagramPacket(bytes, bytes.size, groupAddress))
        }.onFailure { PocketLog.d(TAG, "组播发送失败：${it.message}") }
    }

    // ==================== 报文构造 ====================

    private fun buildAddressRecords(ttl: Int): List<DnsRecord> {
        val list = mutableListOf<DnsRecord>()
        val alias = _hostAlias.value
        if (alias.isNotBlank()) {
            list += DnsRecord(alias, DnsType.A, ttl, DnsData.a(localIp), cacheFlush = true)
        }
        if (canonicalHost.isNotBlank() && !canonicalHost.equals(alias, true)) {
            list += DnsRecord(canonicalHost, DnsType.A, ttl, DnsData.a(localIp), cacheFlush = true)
        }
        return list
    }

    private fun buildServiceResponse(ttl: Int, includeMeta: Boolean): DnsMessage {
        val info = service ?: return DnsMessage(isResponse = true)
        val instanceName = "${info.deviceId}.$serviceType"

        val txt = DnsData.txt(
            listOf(
                "id" to info.deviceId,
                "name" to info.displayName,
                "type" to when (info.deviceType) {
                    DeviceType.TV -> "tv"
                    DeviceType.PHONE -> "phone"
                    DeviceType.PC -> "pc"
                    else -> "unknown"
                },
                "http" to info.httpPort.toString(),
                "ftp" to info.ftpPort.toString(),
                "perm" to if (info.permission == SharedPermission.READ_ONLY) "ro" else "rw",
                // 把 IP 直接放进 TXT：省掉跨记录解析 A 的步骤，局域网场景下更可靠
                "ip" to localIp,
                "v" to info.version.toString()
            )
        )

        val answers = mutableListOf(
            DnsRecord(serviceType, DnsType.PTR, ttl, DnsData.ptr(instanceName)),
            DnsRecord(instanceName, DnsType.SRV, ttl, DnsData.srv(info.httpPort, canonicalHost), cacheFlush = true),
            DnsRecord(instanceName, DnsType.TXT, ttl, txt, cacheFlush = true)
        )
        if (includeMeta) {
            answers.add(0, DnsRecord(META_SERVICE_TYPE, DnsType.PTR, ttl, DnsData.ptr(serviceType)))
        }

        return DnsMessage(
            isResponse = true,
            authoritative = true,
            answers = answers,
            additionals = buildAddressRecords(ttl)
        )
    }

    private fun announceBytes(): ByteArray =
        DnsCodec.encode(buildServiceResponse(AppConstants.MDNS_TTL_S, includeMeta = true))

    private fun buildGoodbye(): ByteArray {
        val info = service ?: return ByteArray(0)
        val instanceName = "${info.deviceId}.$serviceType"
        return DnsCodec.encode(
            DnsMessage(
                isResponse = true,
                authoritative = true,
                answers = listOf(
                    DnsRecord(serviceType, DnsType.PTR, 0, DnsData.ptr(instanceName)),
                    DnsRecord(instanceName, DnsType.SRV, 0, DnsData.srv(info.httpPort, canonicalHost), cacheFlush = true)
                )
            )
        )
    }

    private fun buildBrowseQuery(): ByteArray = DnsCodec.encode(
        DnsMessage(
            isResponse = false,
            questions = listOf(
                DnsQuestion(serviceType, DnsType.PTR),
                DnsQuestion(META_SERVICE_TYPE, DnsType.PTR)
            )
        )
    )

    private fun buildAliasProbe(): ByteArray = DnsCodec.encode(
        DnsMessage(
            isResponse = false,
            questions = listOf(DnsQuestion(AppConstants.MDNS_HOST_BASE, DnsType.A))
        )
    )

    // ==================== 冲突处理 ====================

    /**
     * 启动探测：问一句"pocketserver.local 是谁"。
     * 若有人应答且 IP 不是我们，就依次尝试 pocketserver-2 ~ -9。
     */
    private suspend fun probeAlias() {
        val wanted = AppConstants.MDNS_HOST_BASE
        sendMulticast(buildAliasProbe())
        delay(PROBE_WAIT_MS)

        val takenBy = claimedAliases[wanted]
        if (takenBy == null || takenBy == localIp) {
            _hostAlias.value = wanted
            _warning.value = null
            return
        }

        for (n in 2..AppConstants.MDNS_CONFLICT_MAX_SUFFIX) {
            val candidate = "${wanted.removeSuffix(".local")}-$n.local"
            sendMulticast(DnsCodec.encode(DnsMessage(isResponse = false, questions = listOf(DnsQuestion(candidate, DnsType.A)))))
            delay(PROBE_WAIT_MS)
            val holder = claimedAliases[candidate]
            if (holder == null || holder == localIp) {
                _hostAlias.value = candidate
                _warning.value = "同一局域网内还有另一台设备占用了 pocketserver.local，本机已改用 $candidate"
                PocketLog.w(TAG, "主机名冲突，改用 $candidate")
                return
            }
        }

        // 9 个候选都被占：退化到唯一规范名，功能不受影响，只是名字不好看
        _hostAlias.value = canonicalHost
        _warning.value = "局域网内同名设备过多，本机地址为 $canonicalHost"
    }

    /** 运行期冲突：别人宣告了我们的友好名。 */
    private fun checkConflict(all: List<DnsRecord>, fromIp: String) {
        val alias = _hostAlias.value
        for (r in all) {
            if (r.type != DnsType.A) continue
            val ip = DnsData.ipOf(r.rdata) ?: continue
            claimedAliases[r.name.lowercase()] = ip
            if (r.name.equals(alias, true) && ip != localIp) {
                PocketLog.w(TAG, "检测到主机名被占用：$alias → $ip，准备降级")
                if (probeDone && resolvingConflict.compareAndSet(false, true)) {
                    scope.launch(dispatcher) {
                        try { resolveConflict() }
                        finally { resolvingConflict.set(false) }
                    }
                }
                return
            }
        }
    }

    private suspend fun resolveConflict() {
        val current = _hostAlias.value
        // 同时启动的设备可能一起挑中 -2；冲突时优先使用设备 ID 生成的稳定唯一别名。
        val candidates = listOf(stableConflictAlias()) + (2..AppConstants.MDNS_CONFLICT_MAX_SUFFIX).map {
            "${AppConstants.MDNS_HOST_BASE.removeSuffix(".local")}-$it.local"
        } + canonicalHost
        for (candidate in candidates) {
            if (candidate == current) continue
            sendMulticast(DnsCodec.encode(DnsMessage(
                isResponse = false,
                questions = listOf(DnsQuestion(candidate, DnsType.A))
            )))
            delay(PROBE_WAIT_MS)
            val holder = claimedAliases[candidate.lowercase()]
            if (holder == null || holder == localIp) {
                _hostAlias.value = candidate
                _warning.value = "局域网内存在同名设备，本机已改用 $candidate"
                retireAlias(current)
                announceNow()
                return
            }
        }
        _hostAlias.value = canonicalHost
        _warning.value = "局域网内同名设备过多，本机地址为 $canonicalHost"
        retireAlias(current)
        announceNow()
    }

    private fun stableConflictAlias(): String {
        val deviceId = service?.deviceId.orEmpty().lowercase()
        val suffix = deviceId.filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            .takeLast(8).trim('-').ifBlank { "device" }
        return "pocketserver-$suffix.local"
    }

    private fun retireAlias(alias: String) {
        if (alias.isBlank() || alias.equals(canonicalHost, true)) return
        val goodbye = DnsMessage(
            isResponse = true,
            authoritative = true,
            answers = listOf(DnsRecord(alias, DnsType.A, 0, DnsData.a(localIp), cacheFlush = true))
        )
        sendMulticast(DnsCodec.encode(goodbye))
    }

    // ==================== 设备收集 ====================

    /**
     * 从响应里拼出附近设备。
     *
     * 优先用 TXT 里的 `ip`（我们自己的实现一定会带），
     * 其次回退到 SRV target → A 记录的映射，以兼容其他 mDNS 实现。
     */
    private fun collectDevices(all: List<DnsRecord>, fromIp: String) {
        val srvByInstance = mutableMapOf<String, Pair<Int, String>>()
        val txtByInstance = mutableMapOf<String, Map<String, String>>()
        val aByHost = mutableMapOf<String, String>()
        var sawGoodbye = false

        for (r in all) {
            when (r.type) {
                DnsType.SRV -> DnsData.srvOf(r.rdata)?.let { srvByInstance[r.name.lowercase()] = it }
                DnsType.TXT -> txtByInstance[r.name.lowercase()] = DnsData.txtOf(r.rdata)
                DnsType.A -> DnsData.ipOf(r.rdata)?.let { aByHost[r.name.lowercase()] = it }
                DnsType.PTR -> {
                    if (r.ttl == 0) sawGoodbye = true
                }
            }
        }

        val updates = mutableListOf<NearbyDevice>()
        for ((instance, srv) in srvByInstance) {
            val txt = txtByInstance[instance] ?: emptyMap()
            val deviceId = txt["id"] ?: instance.substringBefore(".$serviceType")
            val name = txt["name"] ?: deviceId
            val type = when (txt["type"]) {
                "tv" -> DeviceType.TV
                "phone" -> DeviceType.PHONE
                "pc" -> DeviceType.PC
                else -> DeviceType.UNKNOWN
            }
            val ip = txt["ip"]
                ?: aByHost[srv.second.lowercase()]
                ?: fromIp
            if (ip == localIp) continue

            updates += NearbyDevice(
                deviceId = deviceId,
                name = name,
                type = type,
                host = ip,
                httpPort = srv.first,
                ftpPort = txt["ftp"]?.toIntOrNull() ?: (srv.first + 1),
                mdnsHost = srv.second.trimEnd('.'),
                permission = if (txt["perm"] == "ro") SharedPermission.READ_ONLY else SharedPermission.READ_WRITE,
                lastSeen = System.currentTimeMillis()
            )
        }

        if (updates.isEmpty() && !sawGoodbye) return

        val current = _devices.value.toMutableList()
        for (u in updates) {
            val idx = current.indexOfFirst { it.deviceId == u.deviceId }
            if (idx >= 0) current[idx] = u else current += u
        }
        if (sawGoodbye) {
            val live = srvByInstance.keys
            current.removeAll { d -> live.none { it.startsWith(d.deviceId.lowercase()) } }
        }
        _devices.value = current.sortedBy { it.name }
    }

    private fun pruneStale() {
        val now = System.currentTimeMillis()
        val fresh = _devices.value.filter { now - it.lastSeen < DEVICE_STALE_MS }
        if (fresh.size != _devices.value.size) _devices.value = fresh
    }

    // ==================== 定时任务 ====================

    private fun announceNow() {
        sendMulticast(announceBytes())
    }

    private fun startAnnounceLoop() {
        announceJob = scope.launch(dispatcher) {
            var tick = 0
            while (isActive && running.get()) {
                val browseInterval = if (_devices.value.isEmpty()) BROWSE_FAST_S else BROWSE_SLOW_S
                delay(browseInterval * 1000)
                tick += browseInterval.toInt()

                // 主动查一次邻居，覆盖"对方先开机、我们后开机"以及对方没收到我们查询的情况
                runCatching { sendMulticast(buildBrowseQuery()) }

                if (tick >= AppConstants.MDNS_ANNOUNCE_INTERVAL_S) {
                    tick = 0
                    announceNow()
                    pruneStale()
                }
            }
        }
    }

    private companion object {
        const val TAG = "MdnsEngine"
        const val MAX_PACKET = 4096

        /** 冲突探测等待时间。太短会漏掉慢应答，太长会拖慢服务启动。 */
        const val PROBE_WAIT_MS = 800L

        /** 邻居列表为空时查得勤一点（2 秒），有邻居后放慢到 25 秒。 */
        const val BROWSE_FAST_S = 2L
        const val BROWSE_SLOW_S = 25L

        /** 超过 90 秒没再听到的设备视为离线。 */
        const val DEVICE_STALE_MS = 90_000L

        /** DNS-SD 服务枚举元查询。 */
        const val META_SERVICE_TYPE = "_services._dns-sd._udp.local"
    }
}
