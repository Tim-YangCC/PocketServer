package com.pocketserver.util

/**
 * 极简 JSON 输出工具。
 *
 * 不引入 kotlinx.serialization / Gson 的理由：本项目对外只暴露十来个结构固定的接口，
 * 引一个序列化框架会带来反射、代码膨胀与 ProGuard 规则维护成本，
 * 而这些接口的字段是稳定的。代价是需要自己保证转义正确 —— 所以这里只有一处转义实现。
 */
object Json {

    /** 已序列化的原始 JSON 片段，拼接时不再转义。 */
    @JvmInline
    value class Raw(val json: String)

    fun raw(json: String): Raw = Raw(json)

    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder(128)
        sb.append('{')
        var first = true
        for ((k, v) in pairs) {
            // 值为 null 时直接省略字段：前端用 `if (x)` 判断即可，
            // 比传一堆 null 更省字节，也避免前端把 null 误当成有效值
            if (v == null) continue
            if (!first) sb.append(',')
            first = false
            sb.append(str(k)).append(':').append(value(v))
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(items: List<Any?>): String {
        val sb = StringBuilder(128)
        sb.append('[')
        items.forEachIndexed { idx, item ->
            if (idx > 0) sb.append(',')
            sb.append(value(item))
        }
        sb.append(']')
        return sb.toString()
    }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Raw -> v.json
        is String -> str(v)
        is Boolean -> if (v) "true" else "false"
        is Int, is Long, is Short, is Byte -> v.toString()
        is Double -> if (v.isNaN() || v.isInfinite()) "0" else String.format(java.util.Locale.US, "%.2f", v)
        is Float -> String.format(java.util.Locale.US, "%.2f", v)
        is Map<*, *> -> obj(*v.entries.map { it.key.toString() to it.value }.toTypedArray())
        is Iterable<*> -> arr(v.map { it })
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // 关键：`</script>` 之类的序列会提前闭合 HTML 脚本块，必须把 < 与 > 转成 \u 形式
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                '&' -> sb.append("\\u0026")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
