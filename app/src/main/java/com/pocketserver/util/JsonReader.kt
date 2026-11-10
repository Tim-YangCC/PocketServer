package com.pocketserver.util

/**
 * 极简 JSON 读取器。
 *
 * 只做本项目需要的四件事：取字符串、取数字、取布尔、取"对象数组"。
 * 为什么不引 Gson/Moshi：这些接口的响应结构是我们自己定义的、字段固定，
 * 引入完整 JSON 库会带来反射与 ProGuard 规则成本，且对性能敏感的服务进程没必要。
 *
 * 所有取值都做了容错：拿不到就返回 null，让调用方走默认值，而不是抛异常把整条链路打断。
 */
class JsonReader(private val text: String) {

    fun str(key: String): String? {
        val idx = indexOfKey(key) ?: return null
        val colon = skipWhitespace(text.indexOf(':', idx))
        if (colon < 0) return null
        if (colon >= text.length || text[colon] != '"') {
            // 兼容 null 与非字符串值
            return null
        }
        return readString(colon)
    }

    fun long(key: String): Long? = str(key)?.toLongOrNull() ?: numberValue(key)

    fun int(key: String): Int? = long(key)?.toInt()

    fun bool(key: String): Boolean? {
        val idx = indexOfKey(key) ?: return null
        val colon = skipWhitespace(text.indexOf(':', idx))
        if (colon < 0) return null
        return when {
            text.startsWith("true", colon) -> true
            text.startsWith("false", colon) -> false
            else -> null
        }
    }

    /** 取数组里的原始对象片段列表（每个元素是一段完整的 `{...}` 文本）。 */
    fun objects(arrayKey: String): List<String> {
        val idx = indexOfKey(arrayKey) ?: return emptyList()
        val bracket = text.indexOf('[', idx)
        if (bracket < 0) return emptyList()

        val result = mutableListOf<String>()
        var i = bracket + 1
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false

        while (i < text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }

                c == '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        result += text.substring(objStart, i + 1)
                        objStart = -1
                    }
                }

                c == ']' && depth == 0 -> return result
            }
            i++
        }
        return result
    }

    private fun numberValue(key: String): Long? {
        val idx = indexOfKey(key) ?: return null
        val colon = skipWhitespace(text.indexOf(':', idx))
        if (colon < 0) return null
        var end = colon
        while (end < text.length && (text[end].isDigit() || text[end] == '-' || text[end] == '.')) end++
        return text.substring(colon, end).toDoubleOrNull()?.toLong()
    }

    private fun indexOfKey(key: String): Int? {
        val pattern = "\"$key\""
        // 要求 key 后面紧跟冒号，避免把 "filename" 之类的前缀误判成 "name"
        var from = 0
        while (true) {
            val idx = text.indexOf(pattern, from)
            if (idx < 0) return null
            val after = skipWhitespace(idx + pattern.length)
            if (after < text.length && text[after] == ':') return idx
            from = idx + pattern.length
        }
    }

    private fun skipWhitespace(from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == ' ' || text[i] == '\n' || text[i] == '\r' || text[i] == '\t')) i++
        return i
    }

    /** 从引号位置开始读一个 JSON 字符串，处理常见转义。 */
    private fun readString(quoteIndex: Int): String? {
        if (quoteIndex >= text.length || text[quoteIndex] != '"') return null
        val sb = StringBuilder()
        var i = quoteIndex + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length -> {
                    when (text[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < text.length) {
                                text.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                        }

                        else -> sb.append(text[i + 1])
                    }
                    i += 2
                }

                c == '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString().ifEmpty { null }
    }

    companion object {
        /** 便捷入口：从一个对象文本里取字段。 */
        fun of(json: String): JsonReader = JsonReader(json)
    }
}
