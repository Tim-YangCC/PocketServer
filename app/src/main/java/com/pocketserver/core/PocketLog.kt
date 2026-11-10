package com.pocketserver.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量日志：同时写 logcat 与内存环形缓冲。
 *
 * 内存缓冲供「设置 → 导出诊断报告」与异常诊断页（FR-9）使用；
 * 不落盘，避免额外 IO 影响传输性能（O 系列优化的基本原则：热路径不引入磁盘写）。
 */
object PocketLog {

    private const val MAX_LINES = 600
    private const val TAG_PREFIX = "PocketServer."

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val lock = Any()

    private val _lines = MutableStateFlow<List<String>>(emptyList())

    /** 供 UI 订阅的内存日志快照。 */
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun d(tag: String, message: String) = append('D', tag, message, null)

    fun i(tag: String, message: String) = append('I', tag, message, null)

    fun w(tag: String, message: String) = append('W', tag, message, null)

    fun e(tag: String, message: String, tr: Throwable? = null) = append('E', tag, message, tr)

    private fun append(level: Char, tag: String, message: String, tr: Throwable?) {
        val line = buildString {
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(message)
            if (tr != null) {
                append(" | ")
                append(tr.javaClass.simpleName)
                append(": ")
                append(tr.message ?: "")
            }
        }
        println("$TAG_PREFIX$line")
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.removeFirst()
            buffer.addLast(line)
            // 拷贝一份快照供 StateFlow 比较；用新 List 实例保证发射不丢
            _lines.value = buffer.toList()
        }
    }

    /** 导出诊断报告用（FR-9.5）。 */
    fun dump(): String = synchronized(lock) { buffer.joinToString("\n") }

    fun clear() = synchronized(lock) {
        buffer.clear()
        _lines.value = emptyList()
    }
}
