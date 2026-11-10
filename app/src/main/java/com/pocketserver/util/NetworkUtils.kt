package com.pocketserver.util

import com.pocketserver.core.PocketLog
import java.net.Inet4Address
import java.net.NetworkInterface

/** 局域网地址与网段判断。 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"

    /**
     * 取本机所有可用的站点本地 IPv4 地址。
     *
     * 刻意不用 WifiManager.getConnectionInfo()：该 API 在 Android 12+ 需要定位权限，
     * 且返回的 IP 为 int 型（字节序易错）。遍历网卡更稳定，也能覆盖以太网口的电视盒子。
     */
    fun localIpv4Addresses(): List<String> {
        val result = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in interfaces.toList()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                for (addr in nif.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        // 只保留私网地址，避免把运营商/公网地址暴露给用户
                        if (isPrivateIpv4(addr.hostAddress ?: "")) {
                            result += addr.hostAddress ?: continue
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            PocketLog.w(TAG, "枚举网卡失败: ${t.message}")
        }
        return result.distinct()
    }

    /** 首选地址：优先 192.168.x.x，其次 10.x，最后 172.16-31.x（家用路由器多为 192.168）。 */
    fun preferredAddress(): String? {
        val all = localIpv4Addresses()
        return all.firstOrNull { it.startsWith("192.168.") }
            ?: all.firstOrNull { it.startsWith("10.") }
            ?: all.firstOrNull()
    }

    fun isPrivateIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 127 -> true
            else -> false
        }
    }

    /**
     * 判断访问方是否来自本机/局域网，用于「局域网免密白名单」（FR-2.9）。
     * Android 的 socket 上拿不到真实对端 MAC（不在同一广播域时），故以 IP 段为准。
     */
    fun isLanPeer(ip: String): Boolean = isPrivateIpv4(ip)

    /** 把 IPv4 转成 FTP PASV 响应需要的 h1,h2,h3,h4 形式。 */
    fun pasvHostParam(ip: String): String = ip.split('.').joinToString(",")

    /** 端口转 PASV 的 p1,p2。 */
    fun pasvPortParam(port: Int): String = "${port / 256},${port % 256}"

    /** 从 FTP 客户端的 PORT 命令还原地址与端口（主动模式，老设备兜底）。 */
    fun parsePortCommand(arg: String): Pair<String, Int>? {
        val nums = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (nums.size != 6) return null
        if (nums.any { it !in 0..255 }) return null
        val host = nums.take(4).joinToString(".")
        val port = nums[4] * 256 + nums[5]
        return host to port
    }

    /** URL 编码，用于把文件名拼进 URL（中文名必须编码，否则浏览器解析失败）。 */
    fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun urlDecode(value: String): String =
        try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (t: Throwable) {
            value
        }

    /** 拼出给用户看的完整地址。 */
    fun httpUrl(host: String, port: Int): String = "http://$host:$port"
}
