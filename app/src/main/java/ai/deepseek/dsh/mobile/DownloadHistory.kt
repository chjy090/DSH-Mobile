package ai.deepseek.dsh.mobile

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * App 内「最近下载」历史（v0.4.5）。
 *
 * 背景：部分 OEM 文件管理器的「最近」只聚合媒体库条目或系统下载记录，普通文档即使写进
 * MediaStore 也不一定出现；App 无法强制把任意文档塞进厂商「最近」。这里维护一份**本地**
 * 最近下载列表，作为可靠兜底：下载成功时由 WebHost 写入，菜单「最近下载」中可直接打开。
 *
 * 存储：独立 SharedPreferences（JSON 数组），最多 [MAX_ENTRIES] 条，最新在前。
 * 只记录文件名、content Uri / 旧版绝对路径、MIME、字节数与保存时间；
 * **不记录 URL、token、cookie 等任何网络凭据**。
 *
 * 容错：SharedPreferences/JSON 读写失败时静默降级为空列表或跳过写入，绝不影响下载本身。
 */
object DownloadHistory {

    private const val TAG = "DSHDownloadHistory"
    private const val PREFS_NAME = "dsh_download_history"
    private const val KEY_ENTRIES = "entries_v1"

    /** 与需求一致：最多保留 50 条，避免 SharedPreferences 无限增长。 */
    const val MAX_ENTRIES = 50

    private const val DEFAULT_MIME = "application/octet-stream"

    private val lock = Any()

    /**
     * 一条下载记录。
     *
     * [contentUri] 为 Android 10+ MediaStore 返回的 content Uri 字符串；
     * [legacyPath] 为 Android 8/9 公共 Download 目录下的绝对路径（可空）。
     */
    data class Entry(
        val displayName: String,
        val contentUri: String?,
        val legacyPath: String?,
        val mime: String,
        val size: Long,
        val savedAt: Long
    )

    /**
     * 写入一条成功记录。名称去空白后为空则忽略；同一条记录重复下载会保留两条（时间不同），
     * 列表总数始终不超过 [MAX_ENTRIES]。
     */
    fun record(
        context: Context,
        displayName: String,
        contentUri: String?,
        legacyPath: String?,
        mime: String,
        size: Long
    ) {
        val name = displayName.trim()
        if (name.isEmpty()) return
        val entry = Entry(
            displayName = name,
            contentUri = contentUri?.trim()?.takeIf { it.isNotEmpty() },
            legacyPath = legacyPath?.trim()?.takeIf { it.isNotEmpty() },
            mime = normalizeMime(mime),
            size = size.coerceAtLeast(0L),
            savedAt = System.currentTimeMillis()
        )
        synchronized(lock) {
            try {
                val updated = (listOf(entry) + loadLocked(context)).take(MAX_ENTRIES)
                writeLocked(context, updated)
            } catch (t: Throwable) {
                // 历史只是兜底能力：任何读写异常都不得影响下载结果。
                Log.w(TAG, "record download history failed", t)
            }
        }
    }

    /** 读取全部记录（最新在前）；任何异常都返回空列表。 */
    fun load(context: Context): List<Entry> = synchronized(lock) {
        loadLocked(context)
    }

    /** 清空历史，不删除任何已下载文件。 */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                preferences(context).edit().remove(KEY_ENTRIES).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "clear download history failed", t)
            }
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadLocked(context: Context): List<Entry> {
        val raw = try {
            preferences(context).getString(KEY_ENTRIES, null)
        } catch (t: Throwable) {
            Log.w(TAG, "read download history failed", t)
            return emptyList()
        }
        if (raw.isNullOrBlank()) return emptyList()

        return try {
            val array = JSONArray(raw)
            val entries = ArrayList<Entry>(array.length().coerceAtMost(MAX_ENTRIES))
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("displayName", "").trim()
                if (name.isEmpty()) continue
                entries += Entry(
                    displayName = name,
                    contentUri = optionalString(item, "contentUri"),
                    legacyPath = optionalString(item, "legacyPath"),
                    mime = normalizeMime(item.optString("mime", DEFAULT_MIME)),
                    size = item.optLong("size", 0L).coerceAtLeast(0L),
                    savedAt = item.optLong("savedAt", 0L)
                )
                if (entries.size >= MAX_ENTRIES) break
            }
            entries
        } catch (t: Throwable) {
            Log.w(TAG, "parse download history failed, fallback to empty", t)
            emptyList()
        }
    }

    private fun writeLocked(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        for (entry in entries) {
            array.put(JSONObject().apply {
                put("displayName", entry.displayName)
                put("contentUri", entry.contentUri ?: JSONObject.NULL)
                put("legacyPath", entry.legacyPath ?: JSONObject.NULL)
                put("mime", entry.mime)
                put("size", entry.size)
                put("savedAt", entry.savedAt)
            })
        }
        preferences(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    /** JSON 的 null 值在 optString 下会得到字符串 "null" 或空串，这里显式区分。 */
    private fun optionalString(item: JSONObject, key: String): String? {
        if (item.isNull(key)) return null
        return item.optString(key, "").trim().takeIf { it.isNotEmpty() }
    }

    private fun normalizeMime(raw: String?): String {
        val value = raw?.substringBefore(';')?.trim()?.lowercase()
        return if (!value.isNullOrEmpty() && value.length <= 255 && value.contains('/')) {
            value
        } else {
            DEFAULT_MIME
        }
    }
}
