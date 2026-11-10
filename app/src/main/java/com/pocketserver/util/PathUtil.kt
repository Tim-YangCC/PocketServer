package com.pocketserver.util

/**
 * 路径解析与安全校验。
 *
 * 所有来自网络的路径（HTTP URL、WebDAV Destination、FTP 命令参数）都必须经过
 * [resolveSafe] 归一化，这是防止目录穿越（`../`）的唯一关口（安全设计 §7.3）。
 */
object PathUtil {

    /** 允许的最大路径深度，防止构造超长路径做 DoS。 */
    private const val MAX_DEPTH = 32

    /**
     * 把相对路径归一化为安全的相对路径。
     *
     * - 统一分隔符为 `/`
     * - 去掉 `.` 与空段
     * - 遇到 `..` 直接拒绝（返回 null），而不是静默弹出——静默会把越权变成难查的 bug
     * - 过滤非法字符（NUL、控制字符）
     *
     * @return 归一化后的相对路径；非法输入返回 null。
     */
    fun sanitize(relative: String?): String? {
        if (relative == null) return null
        var path = relative.replace('\\', '/').trim()
        // URL 解码由调用方处理，这里只处理路径本身
        path = path.removePrefix("/")
        if (path.isEmpty()) return ""
        if (path.contains('\u0000')) return null

        val segments = mutableListOf<String>()
        for (seg in path.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> continue
                seg == ".." -> return null
                seg.length > 255 -> return null
                // 非法字符必须在**任何归一化动作之前**判定。
                // 早期版本这里是先 `seg.trim()` 再查控制字符，于是 `a/\tb/c` 里的 \t
                // 被 trim 悄悄吃掉，检查永远看不见它，路径被"修好"成 `a/b/c`
                // —— 请求一个畸形路径却打开了另一个真实文件，正是本模块要防的别名问题。
                seg.any { it.code < 0x20 || it.code == 0x7F } -> return null
                // 刻意不做逐段 trim：`" b "` 与 `"b"` 是两个不同的文件，
                // 把前者悄悄当成后者同样是别名。只有整串首尾空白（粘贴常见）才归一化。
                else -> segments += seg
            }
        }
        if (segments.size > MAX_DEPTH) return null
        return segments.joinToString("/")
    }

    /** 取父级相对路径；已在根目录时返回 null。 */
    fun parent(relative: String): String? {
        val clean = sanitize(relative) ?: return null
        if (clean.isEmpty()) return null
        val idx = clean.lastIndexOf('/')
        return if (idx <= 0) "" else clean.substring(0, idx)
    }

    /** 取文件名（最后一段）。 */
    fun fileName(relative: String): String {
        val clean = relative.trimEnd('/')
        val idx = clean.lastIndexOf('/')
        return if (idx < 0) clean else clean.substring(idx + 1)
    }

    /** 取扩展名（小写，不含点）。 */
    fun extension(relative: String): String {
        val name = fileName(relative)
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) "" else name.substring(idx + 1).lowercase()
    }

    /** 拼接相对路径，自动归一化分隔符。 */
    fun join(parent: String, child: String): String {
        val a = parent.trim('/')
        val b = child.trim('/')
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> "$a/$b"
        }
    }

    /**
     * 在目标路径已存在时生成不冲突的副本名，如 `照片.jpg` → `照片 (2).jpg`。
     * 上传覆盖是危险默认值，本产品一律生成副本（FR-3.8）。
     */
    fun dedupe(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val lower = name.lowercase()
        val dot = if (lower.startsWith(".")) -1 else name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (n < 10_000) {
            val candidate = "$base ($n)$ext"
            if (!exists(candidate)) return candidate
            n++
        }
        return "$base-${System.currentTimeMillis()}$ext"
    }

    /** 是否为隐藏文件（`.` 开头）。网页端默认不显示。 */
    fun isHidden(relative: String): Boolean = fileName(relative).startsWith(".")

    /**
     * 常见扩展名 → MIME。不引第三方库：局域网场景下 60 个类型足够覆盖。
     */
    fun mimeType(relative: String): String {
        return when (extension(relative)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            "svg" -> "image/svg+xml"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "txt", "log", "ini", "cfg" -> "text/plain; charset=utf-8"
            "md" -> "text/markdown; charset=utf-8"
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "xml" -> "application/xml; charset=utf-8"
            "csv" -> "text/csv; charset=utf-8"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "apk" -> "application/vnd.android.package-archive"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "srt", "ass", "vtt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
    }

    /** 是否适合直接在浏览器里预览（FR-3.5）。 */
    fun isPreviewable(relative: String): Boolean {
        val mime = mimeType(relative)
        return mime.startsWith("image/") ||
            mime.startsWith("video/") ||
            mime.startsWith("audio/") ||
            mime.startsWith("text/") ||
            mime == "application/pdf"
    }

    /** 是否可作为 Android TV 上的媒体播放（FR-10.9）。 */
    fun isPlayableMedia(relative: String): Boolean {
        val mime = mimeType(relative)
        return mime.startsWith("video/") || mime.startsWith("audio/") || mime.startsWith("image/")
    }
}
