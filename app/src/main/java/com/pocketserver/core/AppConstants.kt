package com.pocketserver.core

/**
 * 全局常量：端口约定、配对策略、性能参数默认值。
 *
 * 性能参数取值来源：《传输性能优化技术方案 v1.0》参数配置表（O-2/O-3/O-5/O-9/O-10）。
 * 需求编号来源：《需求规格说明书 V1.2》§4.4 / §5.12。
 */
object AppConstants {

    // ==================== 服务端口（需规 §4.4） ====================

    const val HTTP_PORT_DEFAULT = 8080
    const val FTP_PORT_DEFAULT = 2121

    /** FTP 被动模式端口池，共 101 个端口，占用时顺序重试（O-10）。 */
    const val PASSIVE_PORT_START = 50000
    const val PASSIVE_PORT_END = 50100

    /** 同时保持的被动监听 socket 数量上限（O-10：够用即止，避免端口耗尽）。 */
    const val PASSIVE_SOCKET_POOL = 8

    /** ServerSocket backlog。局域网设备少，64 足够且省内存。 */
    const val HTTP_BACKLOG = 64
    const val FTP_BACKLOG = 16

    // ==================== mDNS（需规 §4.4「零输入」核心） ====================

    const val MDNS_HOST_BASE = "pocketserver.local"
    const val MDNS_SERVICE_TYPE = "_pocketserver._tcp"
    const val MDNS_MULTICAST_ADDR = "224.0.0.251"
    const val MDNS_PORT = 5353

    /** 主机名冲突时的最大退避序号，即 pocketserver-2.local ~ pocketserver-9.local。 */
    const val MDNS_CONFLICT_MAX_SUFFIX = 9

    /** 周期性宣告间隔（秒）——mDNS 规范建议随 TTL 递减，此处固定值已满足局域网可靠发现。 */
    const val MDNS_ANNOUNCE_INTERVAL_S = 60L

    /** A/SRV/PTR 记录 TTL（秒）。取 120 让设备离线后较快消失，同时避免频繁泛洪。 */
    const val MDNS_TTL_S = 120

    /** 自定义 URL Scheme，用于二维码与深链（FR-2.7）。 */
    const val URL_SCHEME = "pocketserver"

    // ==================== 配对策略（FR-2） ====================

    /** 配对码有效期：5 分钟。 */
    const val PAIR_CODE_TTL_MS = 5 * 60 * 1000L

    /** 6 位数字配对码。 */
    const val PAIR_CODE_LENGTH = 6

    /** 同 IP 连续错误次数上限。 */
    const val PAIR_MAX_FAILS = 5

    /** 触发上限后的锁定时长：5 分钟。 */
    const val PAIR_LOCKOUT_MS = 5 * 60 * 1000L

    /** 识别同一 IP 的错误计数窗口，超出即清零。 */
    const val PAIR_FAIL_WINDOW_MS = 10 * 60 * 1000L

    /** 会话令牌有效期：12 小时（长时间挂机下载场景下不至于中途掉线）。 */
    const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L

    /** 已配对设备令牌有效期：90 天。 */
    const val TRUSTED_TOKEN_TTL_MS = 90L * 24 * 60 * 60 * 1000

    // ==================== 传输性能（FR-12 / O-2~O-12） ====================

    /** 主缓冲大小 256KB；低内存设备在运行时降为 128KB（O-2）。 */
    const val BUFFER_SIZE = 256 * 1024
    const val BUFFER_SIZE_LOW_RAM = 128 * 1024

    /** 缓冲池容量：8 块。超出后新申请走临时分配，避免常驻内存过高（O-12）。 */
    const val BUFFER_POOL_SIZE = 8

    /** 单文件超过该阈值才启用多连接分块并行（O-3）。 */
    const val CHUNK_THRESHOLD_BYTES = 64L * 1024 * 1024

    /** 并行路数：clamp(CPU 核数 / 2, 2, 6)。 */
    const val LANE_MIN = 2
    const val LANE_MAX = 6

    /** 全局并发传输配额：无论多少任务，同时在跑的连接数不超过该值（O-9）。 */
    const val GLOBAL_CONCURRENCY_QUOTA = 4

    /** socket 收发缓冲：512KB（O-2）。 */
    const val SOCKET_BUFFER_SIZE = 512 * 1024

    /** 小文件判定阈值：小于 4MB 走流式打包而不是逐个连接（O-7）。 */
    const val SMALL_FILE_THRESHOLD_BYTES = 4L * 1024 * 1024

    /** 小文件打包批次上限，避免单批过大导致内存与失败重试成本上升。 */
    const val SMALL_FILE_BATCH_MAX = 512

    /** 进度回调节流间隔：应用内 200ms、通知栏 500ms（O-8）。 */
    const val PROGRESS_THROTTLE_MS = 200L
    const val NOTIFICATION_THROTTLE_MS = 500L

    /** TCP keep-alive 空闲时间（秒）。 */
    const val SOCKET_KEEPALIVE_IDLE_S = 30

    /** WifiLock 在无传输任务后继续持有的时间，之后释放以省电（O-5）。 */
    const val WIFI_LOCK_LINGER_MS = 10 * 1000L

    // ==================== 后台保活（FR-13） ====================

    /**
     * 传输唤醒锁的硬超时。
     *
     * 带超时是刻意的：收尾路径有正常完成、连接断开、写盘失败、进程被杀四类，
     * 只要有一条漏了释放，裸 acquire 的锁就永远不会还回去。
     * 宁可"万一漏了就在 30 分钟后自动醒"也不接受"永久持有"。
     */
    const val WAKELOCK_MAX_HOLD_MS = 30 * 60 * 1000L

    /**
     * 传输计数归零后多久才真正放下唤醒锁。
     *
     * 上传一个文件夹会连发几十个 PUT，相邻两个文件之间有几毫秒空档。
     * 一归零就放锁、下一个文件又立刻抢回来，会让射频反复进出省电态，
     * 实测比一直持有着更慢。
     */
    const val TRANSFER_LOCK_LINGER_MS = 15 * 1000L

    /**
     * 一条传输多久没有任何动作就判定为卡死。
     *
     * 正常传输每 [PROGRESS_THROTTLE_MS] 就会推一次进度，所以"十分钟一个动作都没有"
     * 只可能是链路已经死了而收尾路径没被走到。
     *
     * 这个上限不是为了修掉某一个 bug，而是为了**兜住"保活计数不归零"的后果**：
     * 计数不归零会把「空闲自停」永久关掉，用户完全看不见，只觉得特别耗电。
     * 宁可十分钟后把记录判为中断，也不能让它静默地把省电逻辑废掉。
     */
    const val TRANSFER_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

    // ==================== 内建测速（FR-12.14） ====================

    /** 纯网络上限测试的数据量：64MB（内存造数据，不碰磁盘）。 */
    const val BENCH_NETWORK_BYTES = 64L * 1024 * 1024

    /** 纯磁盘上限测试的数据量：128MB（顺序读+写临时文件）。 */
    const val BENCH_DISK_BYTES = 128L * 1024 * 1024

    /** 端到端测试的数据量：32MB。 */
    const val BENCH_E2E_BYTES = 32L * 1024 * 1024

    /** 单项测速的最长时间，超时即判为异常（避免慢链路把用户卡住）。 */
    const val BENCH_TIMEOUT_MS = 20 * 1000L

    // ==================== 服务生命周期（FR-1） ====================

    /** 手机端默认「无活动自动停止」阈值（分钟）；0 表示不自动停止（电视端默认 0）。 */
    const val IDLE_STOP_MINUTES_PHONE = 10
    const val IDLE_STOP_MINUTES_TV = 0

    /** 空闲判定：最近 N 毫秒内既无客户端连接也无传输任务。 */
    const val IDLE_DETECT_WINDOW_MS = 60 * 1000L

    // ==================== 其他 ====================

    /** 访问日志保留条数上限，超出后按时间淘汰（FR-7.6）。 */
    const val ACCESS_LOG_MAX_ROWS = 2000

    /** 单次 HTTP 上传的默认上限（0 表示不限），仅用于防止误操作打满存储。 */
    const val UPLOAD_SIZE_LIMIT_BYTES = 0L

    // ==================== 断点续传（FR-14） ====================

    /**
     * 上传会话的有效期：24 小时。
     *
     * 为什么必须有截止时间：预分配过的半截文件**在磁盘上看起来是完整的**
     * （长度已经等于整个文件），用户和系统都识别不出它是残片。
     * 唯一能认出它的就是这张会话表；会话一过期，那个文件就成了无人认领的垃圾，
     * 所以过期即删除（见 CacheJanitor）。
     */
    const val UPLOAD_RESUME_TTL_MS = 24 * 60 * 60 * 1000L

    /** 上传会话表文件名。放在 filesDir 而不是 cacheDir —— 系统清理缓存不能动它。 */
    const val UPLOAD_RESUME_FILE = "upload_resume.tsv"

    // ==================== 垃圾清理（FR-15） ====================

    /** 传输记录保留天数，超期自动清理。 */
    const val TRANSFER_RECORD_KEEP_DAYS = 30

    /** 自动清理的兜底周期：服务启动时跑一次，另外按这个间隔重复。 */
    const val AUTO_CLEAN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 测速临时文件名前缀。进程被杀时会残留，且单个文件可达 128MB，必须专门回收。 */
    const val BENCH_TEMP_PREFIX = "ps_bench_"

    /** 最近被写过的临时文件视为"可能正在使用"，清理时跳过，避免把正在跑的测速打断。 */
    const val TEMP_FILE_IN_USE_GRACE_MS = 60 * 1000L

    /** 通知渠道 ID。 */
    const val CHANNEL_SERVER = "ps_server"
    const val CHANNEL_TRANSFER = "ps_transfer"
}
