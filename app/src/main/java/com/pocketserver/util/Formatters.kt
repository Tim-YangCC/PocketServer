package com.pocketserver.util

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** 数值格式化：文件大小、速度、时长、剩余时间。UI 与通知栏共用。 */
object Formatters {

    private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
    private val SPEED_UNITS = arrayOf("B/s", "KB/s", "MB/s", "GB/s")

    fun bytes(value: Long): String = scale(value.toDouble(), SIZE_UNITS)

    /** 速度用 MB/s 为主口径（与需规 §5.12 指标一致），小于 1MB/s 时降级到 KB/s。 */
    fun speed(bytesPerSecond: Long): String = scale(bytesPerSecond.toDouble(), SPEED_UNITS)

    /** 测速结果展示，保留一位小数。 */
    fun megaBytesPerSecond(value: Double): String = when {
        value <= 0.0 -> "—"
        value < 1.0 -> String.format(Locale.US, "%.0f KB/s", value * 1024)
        else -> String.format(Locale.US, "%.1f MB/s", value)
    }

    private fun scale(value: Double, units: Array<String>): String {
        if (value <= 0) return "0 ${units.first()}"
        var v = value
        var idx = 0
        while (v >= 1024 && idx < units.lastIndex) {
            v /= 1024.0
            idx++
        }
        return if (v >= 100 || idx == 0) {
            String.format(Locale.US, "%.0f %s", v, units[idx])
        } else {
            String.format(Locale.US, "%.1f %s", v, units[idx])
        }
    }

    /** 已运行时长，格式 1天2小时 / 3小时5分 / 5分12秒。 */
    fun duration(millis: Long): String {
        if (millis <= 0) return "0秒"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}天${hours}小时"
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分${seconds}秒"
            else -> "${seconds}秒"
        }
    }

    /** 剩余时间（传输任务 ETA）。 */
    fun eta(remainingBytes: Long, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0 || remainingBytes <= 0) return "—"
        val seconds = remainingBytes / bytesPerSecond
        return if (seconds > 3_600) ">1小时" else "${seconds}秒"
    }

    /** 倒计时，用于配对码失效提示。 */
    fun countdown(millis: Long): String {
        val total = millis.coerceAtLeast(0)
        val minutes = total / 60_000
        val seconds = (total % 60_000) / 1000
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /** 相对时间：刚刚 / 3分钟前 / 2小时前 / 具体日期。 */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = abs(now - millis)
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000}分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000}小时前"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000L}天前"
            else -> {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                fmt.format(java.util.Date(millis))
            }
        }
    }

    fun clock(millis: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(java.util.Date(millis))

    fun dateTime(millis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(java.util.Date(millis))

    /** FTP 的 MDTM 响应格式。 */
    fun ftpTimestamp(millis: Long): String =
        java.text.SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(java.util.Date(millis))

    /** HTTP / WebDAV 的 RFC 1123 时间格式。 */
    fun httpDate(millis: Long): String {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return fmt.format(java.util.Date(millis))
    }
}
