package com.pocketserver.data.model

/** 设备形态，用于附近设备列表的图标与可操作项判断（FR-11.2）。 */
enum class DeviceType { PHONE, TV, PC, WEB, UNKNOWN }

/** 共享权限（FR-6）。默认读写——产品定位是双向对等（决策 D-3）。 */
enum class SharedPermission { READ_WRITE, READ_ONLY }

/** 传输方向。UPLOAD/DOWNLOAD 是相对本机服务器而言（PC/浏览器侧）；PUSH/PULL 是本机主动发起。 */
enum class TransferDirection { UPLOAD, DOWNLOAD, PUSH, PULL }

enum class TransferStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

/** 当前 Wi-Fi 频段，决定是否提示用户切换（O-1，最大单一杠杆）。 */
enum class WifiBand { UNKNOWN, BAND_2_4, BAND_5, ETHERNET }

/** 瓶颈定位结论（FR-12.15）。 */
enum class Bottleneck { NONE, WIRELESS_LINK, DISK_IO, APP_LAYER, UNKNOWN }

/** 存储通道：直连 FileChannel 走零拷贝；SAF 需跨进程 IPC（O-4）。 */
enum class StorageMode { DIRECT, SAF }

/**
 * 「现在到底共享了哪块空间」——手机端与电脑端都要看同一份答案。
 *
 * ## 为什么必须把它做成一个显式对象
 * 未授予「所有文件访问权限」时，[StorageAccessManager] 会退到**应用私有目录**
 * （`Android/data/<包名>/files`）。那个目录永远可读写、`ready` 恒为 true、
 * 列目录也成功 —— 只是里面没有用户的照片和下载。
 *
 * 于是电脑端看到的是"一个空目录"，用户得到的结论是"这软件看不到我手机里的文件"。
 * 这不是权限没配好，而是**产品没有把真相说出来**：空目录和"明明有 2000 张照片
 * 却一个都看不到"在界面上完全无法区分。所以这里把根标签、真实路径、
 * 以及一句人话提示一起送到客户端。
 */
data class StorageShare(
    val mode: StorageMode = StorageMode.DIRECT,
    /** 给用户看的根目录名称，如「手机内部存储」。 */
    val rootLabel: String = "",
    /** 真实路径。SAF 通道下为空（那是个 documentId，对用户没有意义）。 */
    val rootPath: String = "",
    /** 是否共享了整个内部存储。false 表示只共享了一小块空间。 */
    val fullStorage: Boolean = true,
    /** 非空表示需要用户处理的问题，文案直接展示给用户。 */
    val notice: String? = null
)

/**
 * 服务运行态。UI 唯一订阅的服务端状态源（ServerController 持有）。
 *
 * 刻意做成不可变 data class：服务层多线程写入、UI 单线程读，靠 StateFlow 保证一致性。
 */
data class ServerState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val httpPort: Int = 8080,
    val ftpPort: Int = 2121,
    /** 是否启用 FTP 兼容通道（FR-4）。关闭时只跑网页版。 */
    val ftpEnabled: Boolean = true,
    /** 本次配对码（6 位数字），running 且有客户端需要接入时展示。 */
    val pairingCode: String = "",
    val pairingCodeExpiresAt: Long = 0L,
    /** 本机可用地址列表，如 ["192.168.1.5"]。 */
    val addresses: List<String> = emptyList(),
    /** mDNS 主机名，冲突退避后可能是 pocketserver-2.local。 */
    val mdnsHost: String = "pocketserver.local",
    /** HTTP 先就绪；mDNS 域名完成探测并开始广播后才切换为 true。 */
    val mdnsReady: Boolean = false,
    /** 已建立的客户端连接数。 */
    val clientCount: Int = 0,
    /** 实时速率（字节/秒），由 SpeedMeter 汇总。 */
    val txBytesPerSecond: Long = 0L,
    val rxBytesPerSecond: Long = 0L,
    val activeTransfers: Int = 0,
    val startedAt: Long = 0L,
    val wifiBand: WifiBand = WifiBand.UNKNOWN,
    val linkSpeedMbps: Int = 0,
    val storageMode: StorageMode = StorageMode.DIRECT,
    /** 当前共享根是哪里、是否整盘、有没有需要用户处理的问题。 */
    val storageShare: StorageShare = StorageShare(),
    val permission: SharedPermission = SharedPermission.READ_WRITE,
    /** 最近一次测速报告，控制台卡片直接展示（FR-12.14）。 */
    val speedReport: SpeedTestReport? = null,
    /** 非致命错误提示，如端口占用、mDNS 冲突。 */
    val warning: String? = null,
    val error: String? = null
) {
    /** 直连 IP 作为首选入口；mDNS 主机名仅作为备用地址。 */
    val primaryUrl: String
        get() = addresses.firstOrNull()?.let { "http://$it:$httpPort" } ?: "http://$mdnsHost:$httpPort"

    val fallbackUrl: String?
        get() = if (mdnsReady && addresses.isNotEmpty()) "http://$mdnsHost:$httpPort" else null
}

/** mDNS 发现到的局域网邻居（FR-11.1）。 */
data class NearbyDevice(
    val deviceId: String,
    val name: String,
    val type: DeviceType,
    val host: String,
    val httpPort: Int,
    val ftpPort: Int,
    /** 唯一的设备 mDNS 名称，设备切换优先用它，避免通用别名冲突和 DHCP 地址变化。 */
    val mdnsHost: String = "",
    val permission: SharedPermission = SharedPermission.READ_WRITE,
    val lastSeen: Long = System.currentTimeMillis(),
    /** 是否已在本机配对（有令牌），已配对可免确认直连。 */
    val paired: Boolean = false,
    /** 是否在免确认白名单内。 */
    val trusted: Boolean = false
) {
    val reachable: Boolean
        get() = System.currentTimeMillis() - lastSeen < 15_000
}

/** 三段测速的结果与判定（FR-12.14 / FR-12.15）。 */
data class SpeedTestReport(
    /**
     * 纯网络上限：由无线协商速率换算出的**有效**吞吐上限（系数 0.55）。
     * 有线或读不到频段时退化为本机回环实测值。
     */
    val networkMBps: Double = 0.0,
    /** 纯磁盘上限：顺序写 + 顺序读临时文件，衡量存储吞吐。 */
    val diskMBps: Double = 0.0,
    /** 真实端到端：走完整文件通路。 */
    val endToEndMBps: Double = 0.0,
    /**
     * 应用层通路上限：本机回环实测。
     * 它排除网络与磁盘，专门用于回答"是不是我们代码的问题"。
     */
    val appCeilingMBps: Double = 0.0,
    val bottleneck: Bottleneck = Bottleneck.UNKNOWN,
    val wifiBand: WifiBand = WifiBand.UNKNOWN,
    val linkSpeedMbps: Int = 0,
    val storageMode: StorageMode = StorageMode.DIRECT,
    /** 给用户看的「人话」结论，不是技术术语。 */
    val advice: String = "",
    val testedAt: Long = System.currentTimeMillis()
)

/** 一次性配对码视图模型，UI 倒计时用。 */
data class PairingCode(
    val code: String,
    val issuedAt: Long,
    val expiresAt: Long
) {
    val ttlRemaining: Long
        get() = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0)
}

/** 接入请求：未配对设备连接时需要在服务端弹窗确认（FR-2.11）。 */
data class AccessRequest(
    val id: String,
    val peerIp: String,
    val deviceName: String,
    val deviceType: DeviceType,
    val requestedAt: Long = System.currentTimeMillis(),
    /** 待确认的 CompletableDeferred 结果：true 允许，false 拒绝。 */
    val decide: (Boolean) -> Unit
)

/** 秒级速率采样点，用于控制台的迷你趋势图。 */
data class SpeedSample(val timestamp: Long, val txBps: Long, val rxBps: Long)

/** 服务启动入参。UI 只改这些，其余走设置持久化。 */
data class ServerStartOptions(
    val httpPort: Int = 8080,
    val ftpPort: Int = 2121,
    val ftpEnabled: Boolean = true,
    val permission: SharedPermission = SharedPermission.READ_WRITE,
    val requirePairing: Boolean = true,
    /** 电视端默认 0 = 不自动停止（FR-10.11）。 */
    val idleStopMinutes: Int = 10
)
