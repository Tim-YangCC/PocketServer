package com.pocketserver.service

import android.content.Context
import com.pocketserver.core.AppConstants
import com.pocketserver.core.KeepAlive
import com.pocketserver.core.PocketLog
import com.pocketserver.data.db.AccessLogEntity
import com.pocketserver.data.model.Bottleneck
import com.pocketserver.data.model.DeviceType
import com.pocketserver.data.model.NearbyDevice
import com.pocketserver.data.model.ServerState
import com.pocketserver.data.model.SpeedTestReport
import com.pocketserver.data.model.StorageMode
import com.pocketserver.data.model.StorageShare
import com.pocketserver.data.model.TransferDirection
import com.pocketserver.data.model.TransferStatus
import com.pocketserver.data.prefs.AppSettings
import com.pocketserver.data.prefs.SettingsStore
import com.pocketserver.data.repo.CacheJanitor
import com.pocketserver.data.repo.DeviceRepository
import com.pocketserver.data.repo.LogRepository
import com.pocketserver.data.repo.TransferCanceledException
import com.pocketserver.data.repo.TransferControl
import com.pocketserver.data.repo.TransferRecorder
import com.pocketserver.data.repo.TransferRepository
import com.pocketserver.data.repo.UploadResumeStore
import com.pocketserver.data.storage.StorageAccessManager
import com.pocketserver.data.storage.StorageProvider
import com.pocketserver.di.ApplicationScope
import com.pocketserver.di.IoDispatcher
import com.pocketserver.net.AccessGate
import com.pocketserver.net.FtpServer
import com.pocketserver.net.PairingManager
import com.pocketserver.net.PeerClient
import com.pocketserver.net.ServerStats
import com.pocketserver.net.http.AccessRecord
import com.pocketserver.net.http.HttpFileServer
import com.pocketserver.net.mdns.MdnsEngine
import com.pocketserver.perf.BenchStage
import com.pocketserver.perf.BottleneckDiagnoser
import com.pocketserver.perf.BufferPool
import com.pocketserver.perf.ChunkedTransferEngine
import com.pocketserver.perf.ConcurrencyGate
import com.pocketserver.perf.SpeedMeter
import com.pocketserver.perf.ThroughputProfile
import com.pocketserver.perf.WifiLinkInspector
import com.pocketserver.perf.ZeroCopySender
import com.pocketserver.util.DeviceFormFactor
import com.pocketserver.util.NetworkUtils
import com.pocketserver.util.PathUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务编排器。全应用**唯一**的服务端状态源。
 *
 * ## 它负责什么
 *  1. 按设置把四条协议栈拉起来（HTTP / WebDAV / FTP / mDNS），并把失败原因如实上报
 *  2. 维护 [ServerState]：地址、配对码、连接数、实时速率、频段、存储通道、警告
 *  3. 接通"接入确认"闸门与访问日志
 *  4. 空闲自停（手机端省电）与 Wi-Fi 高性能锁（O-5）
 *  5. 对外提供对等传输（推送 / 拉取）与三段测速的入口
 *
 * ## 为什么不把逻辑放进 Service
 * 前台服务只负责"让进程活着 + 显示通知"。业务逻辑放这里的好处是：
 * 可以在没有通知、没有 Activity 的单元测试里直接驱动整套服务端。
 */
@Singleton
class ServerController @Inject constructor(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val storageAccess: StorageAccessManager,
    private val pairing: PairingManager,
    private val accessGate: AccessGate,
    private val speedMeter: SpeedMeter,
    private val stats: ServerStats,
    private val bufferPool: BufferPool,
    private val zeroCopy: ZeroCopySender,
    private val transferRepo: TransferRepository,
    private val recorder: TransferRecorder,
    private val logRepo: LogRepository,
    private val deviceRepo: DeviceRepository,
    private val wifiInspector: WifiLinkInspector,
    private val keepAlive: KeepAlive,
    private val janitor: CacheJanitor,
    private val resumeStore: UploadResumeStore,
    private val control: TransferControl,
    @IoDispatcher private val io: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope
) {

    /** 启动结果，供 UI 给出准确的失败原因（而不是一句"启动失败"）。 */
    data class StartOutcome(val ok: Boolean, val error: String? = null)

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _nearby = MutableStateFlow<List<NearbyDevice>>(emptyList())
    val nearbyDevices: StateFlow<List<NearbyDevice>> = _nearby.asStateFlow()

    /** 三段测速的阶段提示（FR-12.14）。 */
    private val _benchStage = MutableStateFlow<BenchStage?>(null)
    val benchStage: StateFlow<BenchStage?> = _benchStage.asStateFlow()

    private val _benchRunning = MutableStateFlow(false)
    val benchRunning: StateFlow<Boolean> = _benchRunning.asStateFlow()

    /** 当前接入确认请求（FR-2.11）。 */
    val accessRequests: StateFlow<List<com.pocketserver.data.model.AccessRequest>> = accessGate.requests

    val isTv: Boolean get() = DeviceFormFactor.isTv(context)

    private var httpServer: HttpFileServer? = null
    private var ftpServer: FtpServer? = null
    private var mdns: MdnsEngine? = null

    @Volatile
    private var storage: StorageProvider? = null

    @Volatile
    private var share: StorageShare = StorageShare()

    /** 建当前存储提供者时用的根指纹，用于发现"授权变化了、共享根该换了"。 */
    @Volatile
    private var shareKey: String = ""

    @Volatile
    private var settings: AppSettings = AppSettings()

    @Volatile
    private var profile: ThroughputProfile = ThroughputProfile.forDevice()

    private var chunkEngine: ChunkedTransferEngine? = null
    private var gate: ConcurrencyGate? = null
    private var peerClient: PeerClient? = null

    private var tickerJob: Job? = null
    private var idleJob: Job? = null
    private var liveCountJob: Job? = null
    private var cleanJob: Job? = null
    private var networkRefreshJob: Job? = null
    @Volatile
    private var announcedIp: String? = null

    /**
     * 从传输记录表派生的"在跑条数"。
     *
     * 为什么必须从表里派生、而不是自己维护一个计数器：
     * 传输有五条通道（网页上传/下载、WebDAV、FTP、设备互传），其中**四条完全不经过本类**。
     * 自己数就只能数到设备互传那一类 —— 上一轮「传输记录只覆盖设备互传」那个问题
     * 就是同一个思路造成的。表是所有通道唯一的汇聚点，那就以表为准。
     */
    private val liveTransferCount = AtomicInteger(0)

    private var cachedDeviceId: String = ""

    init {
        /*
         * 让「策略类」设置真正即时生效。
         *
         * HttpFileServer / FtpServer / WebDAV 的 policyProvider 每次请求都会求值，
         * 而它读的就是下面这个 `settings` 字段。如果这个字段只在 start() 时赋值一次，
         * 后果是：用户在设置页把「允许删除」关掉，界面提示"已关闭"，
         * 但服务端依然会放行删除请求 —— 用户以为关掉了，实际没关。
         * 这类"界面说关了、后台还开着"的 bug 是最难被发现的一种。
         *
         * 端口类设置**不**在这里生效，它们必须重启：socket 已经绑在旧端口上了，
         * 悄悄改变监听端口只会让正在传输的任务莫名中断。
         */
        scope.launch(io) {
            settingsStore.settings.collect { latest ->
                settings = latest
            }
        }
    }

    // ==================== 启动 / 停止 ====================

    suspend fun start(): StartOutcome = withContext(io) {
        if (_state.value.running) return@withContext StartOutcome(true)
        _state.value = _state.value.copy(starting = true, error = null, warning = null)

        settings = settingsStore.current()
        cachedDeviceId = settingsStore.deviceId()
        refreshProfile()

        // 1) 网络前置检查：没有局域网地址就没有一切
        val ip = NetworkUtils.preferredAddress()
        if (ip == null) {
            val msg = "未连接到 Wi-Fi，请先连上家里的路由器"
            _state.value = _state.value.copy(starting = false, error = msg)
            PocketLog.w(TAG, msg)
            return@withContext StartOutcome(false, msg)
        }

        // 2) 存储通道
        val provider = storageAccess.build(settings)
        storage = provider
        val mode = provider.mode
        share = storageAccess.shareInfo(settings)
        shareKey = storageAccess.rootKey(settings)
        try {
            restoreTrustedWebSessions()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            PocketLog.w(TAG, "恢复已记住的浏览器授权失败：${t.message}")
        }

        // 3) 网页服务（主通道）
        val policy = { settings.toPolicy(isTv) }
        val server = HttpFileServer(
            storage = provider,
            pairing = pairing,
            policyProvider = policy,
            deps = HttpFileServer.Deps(
                bufferPool = bufferPool,
                zeroCopy = zeroCopy,
                stats = stats,
                speedMeter = speedMeter,
                profile = profile,
                accessGate = accessGate,
                dispatcher = io,
                scope = scope,
                onAccess = ::onAccess,
                nearbyProvider = { _nearby.value },
                deviceName = { settings.deviceName },
                mdnsHost = { mdns?.hostAlias?.value ?: AppConstants.MDNS_HOST_BASE },
                storageShare = { share },
                recorder = recorder,
                resumeStore = resumeStore,
                control = control,
                deviceId = { cachedDeviceId },
                canonicalMdnsHost = {
                    mdns?.canonicalHostName?.takeIf { it.isNotBlank() } ?: "$cachedDeviceId.local"
                },
                rememberTrustedSession = { session ->
                    deviceRepo.remember(
                        deviceId = "web:${session.token}",
                        name = session.deviceName,
                        type = "WEB",
                        host = session.peerIp,
                        token = session.token,
                        trusted = true
                    )
                },
                restoreTrustedSession = { token ->
                    val saved = deviceRepo.findTrustedWebSession(token)
                    if (saved == null) {
                        null
                    } else {
                        val expiresAt = saved.lastSeen + AppConstants.TRUSTED_TOKEN_TTL_MS
                        if (expiresAt <= System.currentTimeMillis()) {
                            deviceRepo.forgetTrustedWebSession(token)
                            null
                        } else {
                            PairingManager.Session(
                                token = saved.token,
                                peerIp = saved.host,
                                deviceName = saved.name,
                                createdAt = saved.lastSeen,
                                expiresAt = expiresAt,
                                trusted = true
                            )
                        }
                    }
                },
                forgetTrustedSession = { token -> deviceRepo.forgetTrustedWebSession(token) }
            )
        )
        val httpResult = server.start(settings.httpPort)
        if (!httpResult.ok) {
            val msg = httpResult.error ?: "网页服务启动失败"
            _state.value = _state.value.copy(starting = false, error = msg)
            return@withContext StartOutcome(false, msg)
        }
        httpServer = server

        // HTTP 已经开始监听：先把可直连的 IP 地址交给界面，不必等可选通道和 mDNS 探测。
        // starting 保持为 true，控制开关仍会显示启动中，避免此时重复启动/停止。
        val initialPairing = pairing.ensureCode()
        val initialAddresses = NetworkUtils.localIpv4Addresses()
        keepAlive.onServerStarted(isTv)
        _state.value = ServerState(
            running = true,
            starting = true,
            httpPort = settings.httpPort,
            ftpPort = settings.ftpPort,
            ftpEnabled = settings.ftpEnabled,
            pairingCode = initialPairing.code,
            pairingCodeExpiresAt = initialPairing.expiresAt,
            addresses = initialAddresses,
            mdnsHost = AppConstants.MDNS_HOST_BASE,
            mdnsReady = false,
            startedAt = System.currentTimeMillis(),
            storageMode = mode,
            storageShare = share,
            permission = settings.permission,
            warning = share.notice
        )

        // 4) FTP 兼容层（可关）
        var ftpWarning: String? = null
        if (settings.ftpEnabled) {
            val ftp = FtpServer(
                storage = provider,
                pairing = pairing,
                policyProvider = policy,
                deps = FtpServer.Deps(
                    bufferPool = bufferPool,
                    zeroCopy = zeroCopy,
                    dispatcher = io,
                    scope = scope,
                    onAccess = ::onAccess,
                    onClientCountChanged = { /* 控制台用 stats.clientCount，FTP 单独计数不混入 */ },
                    recorder = recorder
                )
            )
            val ftpResult = ftp.start(settings.ftpPort)
            if (ftpResult.ok) {
                ftpServer = ftp
            } else {
                // FTP 起不来不影响主通道，只给警告 —— 主通道可用就该让用户先用起来
                ftpWarning = ftpResult.error
                PocketLog.w(TAG, "FTP 启动失败但不影响主通道：${ftpResult.error}")
            }
        }

        // 5) mDNS：让电脑能打 pocketserver.local
        val engine = MdnsEngine(context, scope, io)
        var mdnsStarted = false
        var mdnsStartWarning: String? = null
        val alias = try {
            engine.start(
                MdnsEngine.ServiceInfo(
                    deviceId = cachedDeviceId,
                    displayName = settings.deviceName,
                    deviceType = if (isTv) DeviceType.TV else DeviceType.PHONE,
                    httpPort = settings.httpPort,
                    ftpPort = if (settings.ftpEnabled) settings.ftpPort else 0,
                    permission = settings.permission
                ),
                ip = ip
            ).also { mdnsStarted = true }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            // mDNS 起不来不致命：UI 会自动退化为展示 IP 地址。
            // 但必须留一条明确日志，因为"域名访问不了"是最难自查的问题之一。
            mdnsStartWarning = "设备名发现启动失败，将使用 IP 地址访问"
            PocketLog.w(TAG, "mDNS 启动失败，退化为 IP 访问：${t.message}")
            engine.stop()
            AppConstants.MDNS_HOST_BASE
        }
        announcedIp = ip
        mdns = engine

        // mDNS 运行期可能因同名设备冲突切换友好地址，控制台和二维码要同步显示新地址。
        scope.launch(io) {
            engine.hostAlias.collect { alias ->
                if (mdns === engine && _state.value.running && alias.isNotBlank()) {
                    _state.value = _state.value.copy(mdnsHost = alias)
                }
            }
        }

        // 6) 邻居列表跟随 mDNS
        scope.launch(io) {
            engine.devices.collect { _nearby.value = it }
        }

        // 7) 锁与状态
        val link = wifiInspector.inspect()
        // 存储提示排在最前：它决定"用户能不能看到自己的文件"，比"换 5GHz 更快"更该先说
        val warnings = listOfNotNull(
            share.notice,
            ftpWarning,
            mdnsStartWarning,
            engine.warning.value,
            wifiInspector.advice(link, mode)
        )
        _state.value = ServerState(
            running = true,
            starting = false,
            httpPort = settings.httpPort,
            ftpPort = settings.ftpPort,
            ftpEnabled = settings.ftpEnabled,
            pairingCode = pairing.ensureCode().code,
            pairingCodeExpiresAt = pairing.ensureCode().expiresAt,
            addresses = NetworkUtils.localIpv4Addresses(),
            mdnsHost = alias,
            mdnsReady = mdnsStarted,
            clientCount = 0,
            startedAt = System.currentTimeMillis(),
            wifiBand = link.band,
            linkSpeedMbps = link.linkSpeedMbps,
            storageMode = mode,
            storageShare = share,
            permission = settings.permission,
            warning = warnings.firstOrNull(),
            error = null
        )

        transferRepo.failStaleTasks()
        startLiveCount()
        runAutoClean()
        startTicker()
        armIdleWatch()
        PocketLog.i(TAG, "服务已就绪：http=${settings.httpPort} ftp=${settings.ftpPort} alias=$alias 存储=$mode")
        StartOutcome(true)
    }

    /**
     * 启动时做一次垃圾回收（FR-15）。
     *
     * 为什么挑"服务启动"这个时机：
     *  1. 此刻**一定没有传输在跑**（服务刚起来），删临时文件不会打断任何人
     *  2. 这是用户唯一会主动触发的时机，比后台定时器更可预期
     *  3. 进程被杀留下的半截上传、测速残片，都在这里被回收
     *
     * 之后每 [AppConstants.AUTO_CLEAN_INTERVAL_MS] 兜底跑一次，覆盖"服务连续开好几天"的场景。
     */
    private fun runAutoClean() {
        cleanJob?.cancel()
        cleanJob = scope.launch(io) {
            while (isActive) {
                val result = janitor.autoClean(storage)
                if (result.freedBytes > 0 || result.notes.isNotEmpty()) {
                    for (note in result.notes) PocketLog.i(TAG, "自动清理：$note")
                }
                delay(AppConstants.AUTO_CLEAN_INTERVAL_MS)
            }
        }
    }

    /** 订阅传输表，把"在跑条数"镜像成一个同步值供 ticker 与空闲自停使用。 */
    private fun startLiveCount() {
        liveCountJob?.cancel()
        liveCountJob = scope.launch(io) {
            transferRepo.observeActive().collect { liveTransferCount.set(it.size) }
        }
    }

    suspend fun stop() = withContext(io) {
        networkRefreshJob?.cancel(); networkRefreshJob = null
        announcedIp = null
        tickerJob?.cancel(); tickerJob = null
        idleJob?.cancel(); idleJob = null
        liveCountJob?.cancel(); liveCountJob = null
        cleanJob?.cancel(); cleanJob = null
        liveTransferCount.set(0)
        ftpServer?.stop(); ftpServer = null
        httpServer?.stop(); httpServer = null
        mdns?.stop(); mdns = null
        accessGate.rejectAll()
        pairing.invalidate()
        keepAlive.onServerStopped()
        storage = null
        speedMeter.reset()
        _state.value = _state.value.copy(
            running = false,
            starting = false,
            clientCount = 0,
            activeTransfers = 0,
            txBytesPerSecond = 0,
            rxBytesPerSecond = 0,
            startedAt = 0
        )
        PocketLog.i(TAG, "服务已停止")
    }

    suspend fun restart(): StartOutcome {
        stop()
        delay(250)
        return start()
    }

    /** 设置变更后调用：只重启受影响的部分（端口/权限/存储都走完整重启，保证状态干净）。 */
    suspend fun onSettingsChanged() {
        if (!_state.value.running) return
        settings = settingsStore.current()
        refreshProfile()
        restart()
    }

    /**
     * 存储权限可能在**应用之外**被改变（用户去系统设置里授予「所有文件访问权限」），
     * DataStore 不会有任何通知。所以调用方（Activity 回到前台、分享导入之前）需要
     * 主动问一次"共享根变了吗"。
     *
     * 为什么必须重启而不是热替换：存储提供者被 HTTP / FTP / WebDAV / 对等客户端
     * 四个协议栈以构造参数持有，替换它要么改成 lambda 间接层（改动面大且要保证
     * 传输中的任务不读到半旧半新的提供者），要么重启一次。重启的代价很小 ——
     * [PairingManager.invalidate] 只作废配对码，**不动已签发的会话令牌**，
     * 所以电脑端不需要重新配对，刷新一下页面即可。
     *
     * @return true 表示检测到变化并已重建
     */
    suspend fun refreshStorageIfChanged(): Boolean {
        if (!_state.value.running) return false
        val latest = settingsStore.current()
        val key = storageAccess.rootKey(latest)
        if (key == shareKey) return false

        PocketLog.i(TAG, "共享根变化：$shareKey → $key，重建存储通道")
        settings = latest
        refreshProfile()
        restart()
        return true
    }

    fun rotatePairingCode() {
        val code = pairing.rotate()
        _state.value = _state.value.copy(pairingCode = code.code, pairingCodeExpiresAt = code.expiresAt)
        PocketLog.i(TAG, "用户手动更换了配对码")
    }

    /**
     * 立即主动发起一次局域网发现，不等 mDNS 的轮询周期。
     * "附近设备"页下拉刷新走这里 —— 用户主动刷新时等 2 秒已经算慢了。
     */
    fun refreshDiscovery() {
        mdns?.refresh() ?: PocketLog.d(TAG, "服务未启动，无法刷新发现")
    }

    // ==================== 状态循环 ====================

    private fun startTicker() {
        tickerJob = scope.launch(io) {
            while (isActive && _state.value.running) {
                refreshNetworkBindingIfNeeded()
                val sample = speedMeter.snapshot()
                val code = pairing.ensureCode()
                val link = wifiInspector.inspect()
                _state.value = _state.value.copy(
                    txBytesPerSecond = sample.txBps,
                    rxBytesPerSecond = sample.rxBps,
                    clientCount = stats.clientCount,
                    activeTransfers = liveTransferCount.get(),
                    pairingCode = code.code,
                    pairingCodeExpiresAt = code.expiresAt,
                    addresses = NetworkUtils.localIpv4Addresses(),
                    wifiBand = link.band,
                    linkSpeedMbps = link.linkSpeedMbps
                )
                // 每秒清理一次过期会话与过期 mDNS 邻居，避免内存长期缓慢增长
                pairing.sweep()
                delay(1000)
            }
        }
    }

    /**
     * HTTP 监听在通配地址上，Wi-Fi 地址变化时它本身仍可接受新连接；mDNS 则绑定到
     * 启动时选中的网卡和地址。若手机切换 Wi-Fi 或路由器重新分配 IP，只更新页面上的
     * IP 而不重启 mDNS，会让 pocketserver.local 继续解析到旧地址，表现为网页偶发打不开。
     * ticker 每秒检查一次当前地址并重建 mDNS，不打断仍可通过新地址进行的 HTTP 服务。
     */
    private fun refreshNetworkBindingIfNeeded() {
        val currentIp = NetworkUtils.preferredAddress()
        if (currentIp == announcedIp || networkRefreshJob?.isActive == true) return

        networkRefreshJob = scope.launch(io) {
            // 网络接口切换时地址列表可能短暂为空，稍等片刻后再读最终状态。
            delay(500)
            val settledIp = NetworkUtils.preferredAddress()
            if (settledIp == announcedIp) return@launch

            val engine = mdns ?: return@launch
            if (settledIp == null) {
                engine.stop()
                announcedIp = null
                _state.value = _state.value.copy(
                    addresses = emptyList(),
                    mdnsReady = false,
                    warning = "局域网连接已断开，网页暂时无法访问"
                )
                PocketLog.w(TAG, "网络地址消失，已停止旧的 mDNS 宣告")
                return@launch
            }

            try {
                val alias = engine.start(
                    MdnsEngine.ServiceInfo(
                        deviceId = cachedDeviceId,
                        displayName = settings.deviceName,
                        deviceType = if (isTv) DeviceType.TV else DeviceType.PHONE,
                        httpPort = settings.httpPort,
                        ftpPort = if (settings.ftpEnabled) settings.ftpPort else 0,
                        permission = settings.permission
                    ),
                    ip = settledIp
                )
                announcedIp = settledIp
                val link = wifiInspector.inspect()
                val warnings = listOfNotNull(
                    share.notice,
                    engine.warning.value,
                    wifiInspector.advice(link, storage?.mode ?: StorageMode.DIRECT)
                )
                _state.value = _state.value.copy(
                    addresses = NetworkUtils.localIpv4Addresses(),
                    mdnsHost = alias,
                    mdnsReady = true,
                    wifiBand = link.band,
                    linkSpeedMbps = link.linkSpeedMbps,
                    warning = warnings.firstOrNull()
                )
                PocketLog.i(TAG, "网络地址已变化，mDNS 已更新：$settledIp ($alias)")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                engine.stop()
                // 记住本次尝试，避免每秒重复探测；用户仍可通过状态页显示的 IP 访问。
                announcedIp = settledIp
                _state.value = _state.value.copy(
                    addresses = NetworkUtils.localIpv4Addresses(),
                    mdnsReady = false,
                    warning = "域名发现暂不可用，可改用 IP 地址访问：${t.message ?: "mDNS 启动失败"}"
                )
                PocketLog.w(TAG, "网络地址已变化但 mDNS 重启失败：${t.message}")
            }
        }
    }

    /**
     * 空闲自停（FR-1.8）。
     *
     * 电视端默认关闭（idleStopMinutes = 0）—— 它被定位成 7×24 常驻的服务端，
     * 半夜自动停掉会导致第二天早上电脑连不上，用户会以为是坏了。
     */
    private fun armIdleWatch() {
        val minutes = settings.idleStopMinutes
        if (minutes <= 0) {
            PocketLog.i(TAG, "空闲自停已关闭（常驻模式）")
            return
        }
        idleJob = scope.launch(io) {
            while (isActive && _state.value.running) {
                delay(30_000)
                if (!_state.value.running) break
                val busy = stats.clientCount > 0 ||
                    liveTransferCount.get() > 0 ||
                    keepAlive.activeTransfers > 0
                if (busy) continue
                if (stats.idleMillis() >= minutes * 60_000L) {
                    PocketLog.i(TAG, "空闲 ${minutes} 分钟，自动停止服务")
                    stop()
                    break
                }
            }
        }
    }

    // ==================== 功耗锁（O-5 / FR-13） ====================
    //
    // 锁的实现整体搬到了 [KeepAlive]。搬走的理由不是"文件太长"，
    // 而是原来这里只有 Wi-Fi 锁、CPU 唤醒锁只给电视端 —— 手机端在传输期间
    // 完全没有 CPU 锁，与"手机端也要能在后台传"（FR-13.1）是矛盾的。
    // 而"要不要在传输期间加 CPU 锁"这个判断需要知道**全部五条通道**的在跑条数，
    // 这个信息只有 [TransferRecorder] 汇聚得到，所以锁也就跟着去了那一侧。

    // ==================== 访问记录 ====================

    private fun onAccess(record: AccessRecord) {
        stats.touch()
        logRepo.enqueue(
            AccessLogEntity(
                timestamp = record.timestamp,
                peerIp = record.peerIp,
                deviceName = record.deviceName,
                method = record.method,
                path = record.path,
                status = record.status,
                bytesSent = record.bytesSent,
                bytesReceived = record.bytesReceived,
                detail = record.detail
            )
        )
    }

    // ==================== 对等传输（FR-11） ====================

    private fun ensurePeerClient(): PeerClient {
        val engine = chunkEngine ?: ChunkedTransferEngine(
            profile = profile,
            gate = gate ?: ConcurrencyGate(profile.globalQuota).also { gate = it },
            dispatcher = io
        ).also { chunkEngine = it }
        return peerClient ?: PeerClient(
            storage = storage ?: storageAccess.build(settings).also { storage = it },
            chunkEngine = engine,
            dispatcher = io
        ).also { peerClient = it }
    }

    suspend fun announceTo(device: NearbyDevice): Result<PeerClient.Session> =
        ensurePeerClient().announce(
            device = device,
            selfName = settings.deviceName.ifBlank { DeviceFormFactor.defaultDeviceName(context) },
            selfType = if (isTv) DeviceType.TV else DeviceType.PHONE
        )

    suspend fun listRemote(device: NearbyDevice, token: String, path: String = "") =
        ensurePeerClient().listRemote(device, token, path)

    /** 把本机文件推送给另一台设备（FR-11.3）。 */
    suspend fun pushTo(
        device: NearbyDevice,
        token: String,
        localRelPath: String,
        remoteDir: String = ""
    ): Result<String> {
        val name = PathUtil.fileName(localRelPath)
        val total = storage?.stat(localRelPath)?.size ?: 0L
        val taskId = transferRepo.create(
            name = name,
            relPath = localRelPath,
            direction = TransferDirection.PUSH,
            totalBytes = total,
            lanes = profile.lanes,
            peerName = device.name,
            peerIp = device.host
        )
        /*
         * 真正的搬运挂在**应用级作用域**上，不是调用者的作用域。
         *
         * 两个理由，缺一个这个功能就不成立：
         *  1. FR-13：手机在口袋里也要能传完。挂在「附近」页的 ViewModel 上，
         *     用户一离开那一页任务就断了。
         *  2. 暂停/继续：用户按下暂停后必然要切到「传输」页去看它。若任务挂在
         *     「附近」页的作用域上，切页就等于把它杀了，那里暂停的是一个死人。
         */
        return scope.async(io) { runPush(taskId, device, token, localRelPath, remoteDir, total) }
            .await()
    }

    private suspend fun runPush(
        taskId: Long,
        device: NearbyDevice,
        token: String,
        localRelPath: String,
        remoteDir: String,
        totalBytes: Long
    ): Result<String> {
        val gate = control.register(
            path = localRelPath,
            name = PathUtil.fileName(localRelPath),
            direction = TransferDirection.PUSH,
            totalBytes = totalBytes
        )
        control.bindTask(gate, taskId)
        // 设备互传**不经过** [TransferRecorder]（它直接写仓库），所以保活计数要在这里自己加。
        // 这类传输同样是"后台也要能跑"的一部分：手机在口袋里给另一台设备推 2GB 的片子，
        // 息屏后一样需要 CPU 锁，否则 socket 里的数据没人读。
        keepAlive.onTransferStarted()
        val progress = com.pocketserver.perf.ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { done ->
            gate.transferred = done
            scope.launch(io) { transferRepo.updateProgress(taskId, done, profile.lanes) }
        }
        return try {
            val result = ensurePeerClient().pushFile(
                device = device,
                token = token,
                localRelPath = localRelPath,
                remoteDir = remoteDir,
                onProgress = { progress.update(it) },
                // 暂停时挂在这里、取消时从这里抛出。取消异常会被分块引擎按"本块失败"吃掉，
                // 所以下面判断结果时不能只看成败，还要看闸门里的用户意图。
                checkpoint = { gate.checkpoint() }
            )
            result.fold(
                onSuccess = { outcome ->
                    progress.force(outcome.transferredBytes)
                    val status = control.settle(gate, taskId, outcome.transferredBytes, null)
                    if (status == TransferStatus.DONE) {
                        deviceRepo.touch(device.deviceId, device.host, token)
                        Result.success("已推送到 ${device.name}（${outcome.chunkCount} 路并行）")
                    } else {
                        Result.failure(IllegalStateException(settleHint(status)))
                    }
                },
                onFailure = { t ->
                    val status = control.settle(gate, taskId, gate.transferred, t.message ?: "推送失败")
                    Result.failure(IllegalStateException(settleMessage(status, t.message ?: "推送失败")))
                }
            )
        } catch (e: TransferCanceledException) {
            // 单路路径上会逃到这里（多路路径上被引擎按块吃掉了），两条都要收尾
            control.settle(gate, taskId, gate.transferred, null)
            Result.failure(IllegalStateException("已取消这条传输"))
        } finally {
            progress.force(gate.transferred)
            keepAlive.onTransferFinished()
            control.unregister(gate)
        }
    }

    /** 从另一台设备拉取文件到本机（FR-11.4）。 */
    suspend fun pullFrom(
        device: NearbyDevice,
        token: String,
        remotePath: String,
        localDir: String = "",
        remoteSize: Long
    ): Result<String> {
        val name = PathUtil.fileName(remotePath)
        val taskId = transferRepo.create(
            name = name,
            relPath = remotePath,
            direction = TransferDirection.PULL,
            totalBytes = remoteSize,
            lanes = profile.lanes,
            peerName = device.name,
            peerIp = device.host
        )
        return scope.async(io) { runPull(taskId, device, token, remotePath, localDir, remoteSize) }
            .await()
    }

    private suspend fun runPull(
        taskId: Long,
        device: NearbyDevice,
        token: String,
        remotePath: String,
        localDir: String,
        remoteSize: Long
    ): Result<String> {
        val gate = control.register(
            path = remotePath,
            name = PathUtil.fileName(remotePath),
            direction = TransferDirection.PULL,
            totalBytes = remoteSize
        )
        control.bindTask(gate, taskId)
        keepAlive.onTransferStarted()
        // 落盘名是 PeerClient 算出来的（要去重、拼目录），这里得接住它才可能在取消时删掉半截文件。
        var landedPath: String? = null
        val progress = com.pocketserver.perf.ThrottledProgress(AppConstants.PROGRESS_THROTTLE_MS) { done ->
            gate.transferred = done
            scope.launch(io) { transferRepo.updateProgress(taskId, done, profile.lanes) }
        }
        return try {
            val result = ensurePeerClient().pullFile(
                device = device,
                token = token,
                remotePath = remotePath,
                localDir = localDir,
                remoteSize = remoteSize,
                onProgress = { progress.update(it) },
                onOpened = { landedPath = it },
                checkpoint = { gate.checkpoint() }
            )
            result.fold(
                onSuccess = { outcome ->
                    progress.force(outcome.transferredBytes)
                    val status = control.settle(gate, taskId, outcome.transferredBytes, null)
                    if (status == TransferStatus.CANCELED) {
                        discardPartial(landedPath)
                        Result.failure(IllegalStateException("已取消这条传输"))
                    } else {
                        Result.success("已从 ${device.name} 拉取完成")
                    }
                },
                onFailure = { t ->
                    val status = control.settle(gate, taskId, gate.transferred, t.message ?: "拉取失败")
                    // 取消会把半截文件留在用户的文件夹里 —— 不清掉就是"用得越多越脏"
                    if (status == TransferStatus.CANCELED) discardPartial(landedPath)
                    Result.failure(IllegalStateException(settleMessage(status, t.message ?: "拉取失败")))
                }
            )
        } catch (e: TransferCanceledException) {
            control.settle(gate, taskId, gate.transferred, null)
            discardPartial(landedPath)
            Result.failure(IllegalStateException("已取消这条传输"))
        } finally {
            progress.force(gate.transferred)
            keepAlive.onTransferFinished()
            control.unregister(gate)
        }
    }

    /**
     * 取消之后把半截文件删掉。
     *
     * 与网页上传那条路一样要延迟几秒：可能还有分块在读盘写盘，
     * 立刻删会被它重新创建出来。各路 IO 每个缓冲区都会查闸门，几毫秒内就会停，
     * 所以延迟一刀必然落在最后。
     */
    private fun discardPartial(path: String?) {
        val target = path?.takeIf { it.isNotBlank() } ?: return
        control.scheduleDiscard(target, complete = false) { doomed ->
            storage?.delete(doomed)
        }
    }

    private fun settleHint(status: TransferStatus): String = when (status) {
        TransferStatus.CANCELED -> "已取消这条传输"
        TransferStatus.PAUSED -> "已暂停这条传输"
        else -> ""
    }

    /**
     * 失败时的用户文案。
     *
     * 用户主动取消/暂停不是失败，不能把底层那句"传输不完整（62%），失败分块 1,3"
     * 摆到他脸上 —— 那句话既不是他关心的事，还会让他以为是出错了。
     */
    private fun settleMessage(status: TransferStatus, fallback: String): String =
        settleHint(status).ifBlank { fallback }

    // ==================== 传输控制（FR-7.7 暂停 / 开始 / 取消） ====================

    /** 当前可被控制的传输。手机端传输页用它决定每张卡片显示哪几个按钮。 */
    val liveTransfers: StateFlow<List<TransferControl.LiveTransfer>> = control.live

    suspend fun pauseTransfer(taskId: Long): TransferControl.Result =
        control.apply(taskId, TransferControl.Action.PAUSE)

    suspend fun resumeTransfer(taskId: Long): TransferControl.Result =
        control.apply(taskId, TransferControl.Action.RESUME)

    /**
     * 取消一条传输。
     *
     * 取消之后本机可能留下半截文件，得顺手清掉 —— 这正是用户抱怨的
     * "用得越多垃圾越多"最典型的来源：一个传了一半的 4GB 片子，
     * 用户根本不知道它在哪，也不知道它占着空间。
     *
     * 三种方向的清理条件不一样，不能一把梭：
     *  - PULL：半截文件在本机，删掉；
     *  - UPLOAD（网页上传）：半截文件在本机，但**只有没传完才能删** ——
     *    会话的完成度是唯一可靠判据（分块上传会预分配，磁盘大小在第一个分片
     *    到达时就等于整个文件了，拿它判断会把完好的文件误删）；
     *  - PUSH：半截文件在对端设备上，我们够不着，也就不假装清干净了。
     */
    suspend fun cancelTransfer(taskId: Long): TransferControl.Result {
        val gate = control.gateForTask(taskId)
        val result = control.apply(taskId, TransferControl.Action.CANCEL)
        if (!result.ok || gate == null) return result

        when (gate.direction) {
            TransferDirection.PULL -> discardPartial(gate.path)

            TransferDirection.UPLOAD -> {
                val session = runCatching { resumeStore.find(gate.path) }.getOrNull()
                control.scheduleDiscard(gate.path, session?.isComplete() == true) { path ->
                    storage?.delete(path)
                    resumeStore.remove(path)
                }
            }

            TransferDirection.PUSH, TransferDirection.DOWNLOAD -> Unit
        }
        return result
    }

    // ==================== 测速（FR-12.14） ====================

    /**
     * 跑一次三段测速并生成瓶颈结论。
     *
     * 存储通道必须在这里重新取：用户可能在测速前刚授予了全文件访问权限，
     * 用旧的通道算出来的结论会指错方向。
     */
    suspend fun runSpeedTest(): SpeedTestReport? {
        if (_benchRunning.value) return _state.value.speedReport
        _benchRunning.value = true
        return try {
            val provider = storage
            val diagnoser = BottleneckDiagnoser(
                bufferPool = bufferPool,
                // 参数名是 zeroCopySender（诊断器里的角色是"发送通路"），
                // 而本类持有的字段叫 zeroCopy，两者刻意不同名，这里必须用具名实参显式对齐
                zeroCopySender = zeroCopy,
                cacheDir = context.cacheDir ?: File(context.filesDir, "bench").apply { mkdirs() },
                profile = profile,
                linkInspector = wifiInspector,
                dispatcher = io
            )
            val report = diagnoser.run(
                storageMode = provider?.mode ?: StorageMode.SAF,
                onStage = { _benchStage.value = it }
            )
            _state.value = _state.value.copy(speedReport = report)
            PocketLog.i(TAG, "测速结论：${report.bottleneck}｜${report.advice}")
            report
        } catch (t: Throwable) {
            PocketLog.e(TAG, "测速失败：${t.message}", t)
            null
        } finally {
            _benchStage.value = null
            _benchRunning.value = false
        }
    }

    // ==================== 供 UI 使用的只读视图 ====================

    fun storageHint(): String = storageAccess.describeHint(settings)

    /** 当前共享根的完整描述（供设置页与"换目录"入口展示）。 */
    fun storageShare(): StorageShare = share

    fun hasAllFilesAccess(): Boolean = storageAccess.hasAllFilesAccess()

    fun allFilesAccessIntent() = storageAccess.allFilesAccessIntent()

    fun currentSettings(): AppSettings = settings

    fun currentProfile(): ThroughputProfile = profile

    fun bufferStats(): String = bufferPool.stats()

    // ==================== 后台保活与清理（FR-13 / FR-15） ====================

    fun isIgnoringBatteryOptimizations(): Boolean = keepAlive.isIgnoringBatteryOptimizations()

    fun batteryOptimizationIntent() = keepAlive.batteryOptimizationIntent()

    fun batteryStatusText(): String = keepAlive.statusText()

    /** 本机型可能需要的额外后台设置提示；不需要时返回 null。 */
    fun oemBackgroundHint(): String? = keepAlive.oemBackgroundHint()

    /** 保活自检：三个锁到底有没有真的拿到，用于"我明明开了后台还是断"这类问题的自查。 */
    fun keepAliveDiagnostics(): String = buildString {
        append("Wi-Fi 高性能锁：").append(if (keepAlive.wifiLockHeld) "已持有" else "未持有")
        append("　·　传输唤醒锁：").append(if (keepAlive.transferLockHeld) "已持有（有传输在跑）" else "未持有（当前无传输）")
        append("　·　电池优化白名单：").append(if (keepAlive.isIgnoringBatteryOptimizations()) "已加入" else "未加入")
    }

    /** 扫描各类垃圾占用（FR-15.2）。 */
    suspend fun scanJunk(): CacheJanitor.Report = janitor.scan(storage)

    /** 执行清理（FR-15.3）。 */
    suspend fun cleanJunk(scope: CacheJanitor.Scope): CacheJanitor.Result = janitor.clean(scope, storage)

    fun pairingLockEvents(): Long = pairing.lockEventCount

    /** 已授权会话（FR-2.10「已连接设备」页）。 */
    fun connectedSessions(): List<PairingManager.Session> = pairing.activeSessions()

    /** 注销一个会话：用户点"断开这台设备"时调用（FR-2.10）。 */
    suspend fun disconnect(token: String) = withContext(io) {
        pairing.revoke(token)
        deviceRepo.forgetToken(token)
        PocketLog.i(TAG, "用户手动断开了一个已连接设备")
    }

    /** 清除本机保存的所有授权，并立即撤销内存中的会话。 */
    suspend fun clearDeviceAuthorizations() = withContext(io) {
        pairing.revokeAll()
        deviceRepo.forgetAll()
        accessGate.rejectAll()
    }

    fun currentCode(): String = pairing.currentCodeOrNull() ?: pairing.ensureCode().code

    fun currentCodeTtl(): Long = pairing.codeTtlRemaining()

    // ==================== 内部 ====================

    private fun refreshProfile() {
        val lowRam = DeviceFormFactor.lowRamDevice(context)
        val base = ThroughputProfile.forDevice(lowRam)
        profile = if (settings.laneOverride > 0) {
            ThroughputProfile.withLanes(base, settings.laneOverride)
        } else {
            base
        }
        // 配置变了就重建引擎与对等客户端，让新参数立即生效
        chunkEngine = null
        peerClient = null
        gate = null
        PocketLog.i(TAG, "吞吐参数：${profile.describe()}")
    }

    private suspend fun restoreTrustedWebSessions() {
        val now = System.currentTimeMillis()
        for (saved in deviceRepo.trustedWebSessions()) {
            val expiresAt = saved.lastSeen + AppConstants.TRUSTED_TOKEN_TTL_MS
            val session = PairingManager.Session(
                token = saved.token,
                peerIp = saved.host,
                deviceName = saved.name,
                createdAt = saved.lastSeen,
                expiresAt = expiresAt,
                trusted = true
            )
            if (expiresAt <= now || !pairing.restoreTrustedSession(session)) {
                deviceRepo.forgetTrustedWebSession(saved.token)
            }
        }
    }

    private companion object {
        const val TAG = "ServerController"
    }
}
