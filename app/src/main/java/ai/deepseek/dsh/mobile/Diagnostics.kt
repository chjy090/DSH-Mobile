package ai.deepseek.dsh.mobile

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 线程安全的诊断日志环形缓冲（v0.2.2）。
 *
 * 真机排障时经常没有 adb：网页的 console 输出、JS 侧 getUserMedia 错误与
 * `window.DSHNative.log` 统一写进这里，再通过「⋮ → 诊断日志 → 复制」带出。
 * 最多保留 [MAX_ENTRIES] 条，超出后丢弃最旧的一条；单条消息截断，避免
 * 一次巨大的 console 输出把内存撑爆。
 */
object Diagnostics {

    /** 环形缓冲最大条数（需求约定 300）。 */
    const val MAX_ENTRIES = 300

    /** 单条消息最大字符数，避免超长 console 输出占满内存。 */
    private const val MAX_MESSAGE_CHARS = 1000

    private val lock = Any()
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private data class Entry(
        val time: String,
        val source: String,
        val level: String,
        val message: String
    )

    /**
     * 追加一条诊断记录。任何非法输入都不会抛异常。
     *
     * @param source 来源（如 `WebConsole` / `JSBridge`）
     * @param level 级别（`D` / `I` / `W` / `E` 等，内部会规范化）
     * @param message 内容；换行会被压平成单行，`token=` 会被脱敏
     */
    fun add(source: String, level: String, message: String) {
        val safeSource = source.trim().take(48).ifEmpty { "unknown" }
        val safeLevel = normalizeLevel(level)
        val safeMessage = sanitize(message)
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(
                Entry(
                    time = timeFormat.format(Date()),
                    source = safeSource,
                    level = safeLevel,
                    message = safeMessage
                )
            )
        }
    }

    /**
     * 导出全部诊断记录（按时间从旧到新）。没有记录时返回空字符串，
     * 由 UI 决定显示“暂无日志”。
     */
    fun dump(): String {
        synchronized(lock) {
            if (entries.isEmpty()) return ""
            val builder = StringBuilder()
            builder.append("DSH Mobile 诊断日志（共 ")
                .append(entries.size)
                .append(" 条，最多 ")
                .append(MAX_ENTRIES)
                .append(" 条）\n")
            for (entry in entries) {
                builder.append(entry.time)
                    .append(" [")
                    .append(entry.level)
                    .append("] ")
                    .append(entry.source)
                    .append(": ")
                    .append(entry.message)
                    .append('\n')
            }
            return builder.toString()
        }
    }

    private fun normalizeLevel(level: String): String = when (level.trim().uppercase(Locale.US)) {
        "D", "DEBUG", "V", "VERBOSE" -> "D"
        "W", "WARN", "WARNING" -> "W"
        "E", "ERROR", "FATAL" -> "E"
        "I", "INFO", "LOG", "TIP" -> "I"
        else -> level.trim().uppercase(Locale.US).take(4).ifEmpty { "I" }
    }

    private fun sanitize(message: String): String {
        val flattened = message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\n', ' ')
            .trim()
        // 网页/JS 内容不可信，可能夹带带 token 的 URL，入库前统一脱敏。
        val redacted = AppConfig.redactForLog(flattened).ifBlank { "(空)" }
        return if (redacted.length <= MAX_MESSAGE_CHARS) {
            redacted
        } else {
            redacted.take(MAX_MESSAGE_CHARS - 1) + "…"
        }
    }
}
