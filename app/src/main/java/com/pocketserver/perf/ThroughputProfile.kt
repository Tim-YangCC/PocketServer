package com.pocketserver.perf

import com.pocketserver.core.AppConstants

/**
 * 运行时吞吐参数表（优化项 O-2 / O-3 / O-9 / O-10）。
 *
 * 为什么不在代码里写死常量：同一套参数在 8 核旗舰机和 4 核电视盒子上的最优值不同。
 * 电视盒子常见 1~2GB 内存，256KB × 6 路并行 = 1.5MB 直接内存，加上 socket 缓冲会明显吃紧，
 * 反而因为内存压力导致 GC 抖动、吞吐下降。所以按设备能力裁剪。
 *
 * 取值依据（《传输性能优化技术方案 v1.0》参数配置表）：
 *  - 缓冲 256KB 自适应，低内存设备 128KB
 *  - 并行路数 clamp(核数 / 2, 2, 6)
 *  - 全局并发配额 4
 *  - 分块阈值 64MB
 *  - socket 缓冲 512KB
 *  - 反模式约束：**缓冲不得加到 4MB 以上**（内存压力升高、首字节延迟变高，吞吐无额外收益）
 */
data class ThroughputProfile(
    val bufferSize: Int,
    val lanes: Int,
    val chunkThresholdBytes: Long,
    val globalQuota: Int,
    val socketBufferBytes: Int,
    val lowRam: Boolean,
    val cpuCores: Int
) {

    /** 是否对本次传输启用多连接分块并行。 */
    fun shouldChunk(totalBytes: Long): Boolean =
        lanes > 1 && totalBytes >= chunkThresholdBytes

    /**
     * 小文件判定（O-7）：走流式打包，不逐个建连接。
     * 小文件的成本大头是连接建立与挥手，不是字节搬运。
     */
    fun shouldBatchSmallFile(sizeBytes: Long): Boolean =
        sizeBytes in 1..AppConstants.SMALL_FILE_THRESHOLD_BYTES

    fun describe(): String = buildString {
        append("缓冲 ${bufferSize / 1024}KB")
        append(" · ")
        append("${lanes} 路并行")
        append(" · ")
        append("分块阈值 ${chunkThresholdBytes / 1024 / 1024}MB")
        append(" · ")
        append("并发配额 $globalQuota")
        if (lowRam) append(" · 低内存模式")
    }

    companion object {

        fun forDevice(
            lowRam: Boolean = false,
            cpuCores: Int = Runtime.getRuntime().availableProcessors()
        ): ThroughputProfile {
            val lanes = (cpuCores / 2).coerceIn(AppConstants.LANE_MIN, AppConstants.LANE_MAX)
            return ThroughputProfile(
                bufferSize = if (lowRam) AppConstants.BUFFER_SIZE_LOW_RAM else AppConstants.BUFFER_SIZE,
                lanes = lanes,
                chunkThresholdBytes = AppConstants.CHUNK_THRESHOLD_BYTES,
                globalQuota = AppConstants.GLOBAL_CONCURRENCY_QUOTA,
                socketBufferBytes = AppConstants.SOCKET_BUFFER_SIZE,
                lowRam = lowRam,
                cpuCores = cpuCores
            )
        }

        /** 用户在设置里手动指定并行路数（FR-8.9）。 */
        fun withLanes(base: ThroughputProfile, lanes: Int): ThroughputProfile =
            base.copy(lanes = lanes.coerceIn(1, AppConstants.LANE_MAX))
    }
}
