package com.pocketserver.net

import com.pocketserver.perf.SpeedMeter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 服务端流量与当前 TCP 连接统计。控制台「当前连接数 N 条」「↑↓ 速率」的数据源（FR-1.5）。 */
class ServerStats(private val speedMeter: SpeedMeter) {

    private val clients = AtomicInteger(0)
    private val totalTx = AtomicLong(0)
    private val totalRx = AtomicLong(0)
    private val totalRequests = AtomicLong(0)

    /** 最近一次有网络活动的时间，空闲自停（FR-1.8）据此判断。 */
    @Volatile
    private var lastActivityAt: Long = System.currentTimeMillis()

    val clientCount: Int get() = clients.get()

    fun onClientOpen() {
        clients.incrementAndGet()
        touch()
    }

    fun onClientClose() {
        clients.decrementAndGet().coerceAtLeast(0)
    }

    fun onRequest() {
        totalRequests.incrementAndGet()
        touch()
    }

    fun onTx(bytes: Long) {
        if (bytes <= 0) return
        totalTx.addAndGet(bytes)
        speedMeter.onTx(bytes)
        touch()
    }

    fun onRx(bytes: Long) {
        if (bytes <= 0) return
        totalRx.addAndGet(bytes)
        speedMeter.onRx(bytes)
        touch()
    }

    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    fun idleMillis(): Long = System.currentTimeMillis() - lastActivityAt

    fun idleMinutes(): Int = (idleMillis() / 60_000).toInt()

    val totalTxBytes: Long get() = totalTx.get()
    val totalRxBytes: Long get() = totalRx.get()
    val requestCount: Long get() = totalRequests.get()

    fun reset() {
        totalTx.set(0)
        totalRx.set(0)
        totalRequests.set(0)
        lastActivityAt = System.currentTimeMillis()
        speedMeter.reset()
    }
}
