package com.pocketserver.net

import com.pocketserver.core.AppConstants
import com.pocketserver.util.NetworkUtils
import java.net.URLDecoder

/**
 * 二维码编解码（FR-2.7 扫码接入 / FR-10.4 电视端反向配对）。
 *
 * ## 载荷设计
 * `pocketserver://pair?h=pocketserver.local&p=8080&c=739214&n=小米电视`
 *
 * 为什么不直接编码 IP：IP 会因为 DHCP 续租而变，二维码通常是**打印/截图长期使用**的，
 * 编码主机名才能在 IP 变化后依然有效。IP 作为兜底参数附带。
 *
 * ## 电视端的关键取舍：用 L 级纠错
 * 电视要在 3 米外被手机扫到（FR-10.4 要求配对码 3 米可读）。二维码在屏幕上的物理尺寸
 * 受限于布局，此时唯一能提升可扫距离的就是**降低码密度** —— 每个模块物理面积越大越易扫。
 * 所以这里用 L（7%）而不是常见的 M（15%）：载荷只有几十字节，L 级完全够用，
 * 换来的是模块数下降、单模块变大，"3 米外扫得动"这个硬指标才成立。
 */
object QrCodec {

    /** 扫码解析结果。 */
    data class PairPayload(
        val host: String,
        val port: Int,
        val code: String,
        val name: String,
        val ipFallback: String = ""
    ) {
        val url: String get() = "http://$host:$port"
    }

    /**
     * 设备名在二维码里最多保留多少个字符。
     *
     * 这是**为了可扫距离而设的硬限制**，不是审美：设备名是唯一没有长度上限的字段，
     * 一个中文名经 URL 编码后每个字要占 9 个字符，10 个字就是 90 个字符 ——
     * 足以把载荷顶到二维码模块数上升一档，3 米外就扫不动了（FR-10.4）。
     * 设备名在这里只用来在扫码确认框里显示"接入「XXX」"，截断后仍然够认人；
     * 屏幕上真正起识别作用的是旁边那行大字。
     */
    private const val NAME_MAX_CHARS = 10

    /**
     * 组装二维码载荷。
     *
     * @param alias 当前持有的 mDNS 主机名（可能是 pocketserver-2.local）
     */
    fun payload(alias: String, port: Int, code: String, deviceName: String, ipFallback: String = ""): String {
        return buildString(128) {
            append(AppConstants.URL_SCHEME)
            append("://pair")
            append("?h=").append(NetworkUtils.urlEncode(alias))
            append("&p=").append(port)
            append("&c=").append(code)
            append("&n=").append(NetworkUtils.urlEncode(shortName(deviceName)))
            if (ipFallback.isNotBlank()) {
                append("&i=").append(NetworkUtils.urlEncode(ipFallback))
            }
        }
    }

    /** 超长设备名截断并加省略号；截断前先去掉尾部空格，避免出现"小米电视 …"这种别扭写法。 */
    private fun shortName(name: String): String =
        if (name.length <= NAME_MAX_CHARS) name
        else name.take(NAME_MAX_CHARS).trimEnd() + "…"

    /** 解析扫描到的文本。非本产品的码返回 null（不弹错误，静默忽略更友好）。 */
    fun parse(text: String?): PairPayload? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith("${AppConstants.URL_SCHEME}://")) {
            // 兼容用户手工粘贴 http:// 地址的情况：此时没有配对码，交由上层走手动输入流程
            return null
        }
        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return null
        val params = mutableMapOf<String, String>()
        trimmed.substring(queryStart + 1).split('&').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = decode(pair.substring(idx + 1))
                params[key] = value
            }
        }
        val host = params["h"] ?: return null
        val port = params["p"]?.toIntOrNull() ?: AppConstants.HTTP_PORT_DEFAULT
        val code = params["c"] ?: return null
        val name = params["n"] ?: host
        return PairPayload(host, port, code, name, params["i"] ?: "")
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    /**
     * 生成二维码位图。
     *
     * @param sizePx 目标边长（像素）。电视端建议给到屏幕高度的 40% 以上。
     * @param margin 静区模块数。默认 1 —— 电视是深色背景，白边太宽反而显得突兀；
     *               1 个模块的静区在深色背景下已能保证识别率。
     */
    fun bitmap(payload: String, sizePx: Int, margin: Int = 1): android.graphics.Bitmap? {
        return runCatching {
            val hints = mapOf(
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
                com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
                com.google.zxing.EncodeHintType.MARGIN to margin
            )
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] =
                        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
        }.getOrNull()
    }
}
