package ai.deepseek.dsh.mobile

import android.net.Uri

/**
 * 应用常量与用户输入规范化。
 *
 * 服务器地址允许两种输入形式：
 * 1. 完整 URL（可带 `?token=...`）：规范化后丢弃 query，token 由 [extractToken] 单独取出。
 * 2. 仅 host[:port]（可带 path）：自动补 `http://`。
 */
object AppConfig {

    const val PREFS = "dsh_mobile_prefs"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_TOKEN = "token"

    /** v0.4.0 认证网关：扫码取得的网关证书 SHA-256（64 位小写 hex）。 */
    const val KEY_PINNED_CERT = "pinned_cert_sha256"

    /** v0.4.0 认证网关：当前配对是否走认证网关（deviceToken 自动续期）。 */
    const val KEY_AUTH_GATE = "auth_gate"

    /**
     * v0.4.0 认证网关：本机是否成功进入过网关后的 GUI（即完成过首次用户名/密码/TOTP 登录）。
     * 用于区分“首次扫码后必须登录”和“已登录过、session 过期可自动续期”两种登录页跳转。
     */
    const val KEY_AUTH_SESSION_ESTABLISHED = "auth_session_established"

    /**
     * 把用户输入规范化为 `scheme://authority[/path]`。
     *
     * - 去掉首尾空白；没有 scheme 时补 `http://`
     * - 只接受 http / https，host 必须非空
     * - 保留端口与 path（path 末尾 `/` 去掉），丢弃 query / fragment
     *
     * @return 规范化后的地址；输入非法时返回 null。
     */
    fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = try {
            Uri.parse(withScheme)
        } catch (t: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host
        if (host.isNullOrBlank()) return null
        if (host.any { it.isWhitespace() }) return null

        val port = uri.port
        if (port != -1 && port !in 1..65535) return null

        // authority 保留 IPv6 方括号与端口；去掉可能存在的 userinfo。
        val authority = uri.authority?.trim()?.substringAfterLast('@').orEmpty()
        if (authority.isEmpty()) return null
        if (!hasValidPortSuffix(authority)) return null

        val path = uri.path.orEmpty().trimEnd('/')
        return "$scheme://$authority$path"
    }

    /**
     * 校验 authority 里的端口部分。Android 的 Uri.parse 比较宽松，
     * 像 `javascript:alert(1)` 这种输入会被当成 host + 非法端口，这里提前拒绝。
     */
    private fun hasValidPortSuffix(authority: String): Boolean {
        val suffix = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return false
            authority.substring(close + 1)
        } else {
            val colon = authority.indexOf(':')
            if (colon < 0) {
                ""
            } else {
                authority.substring(colon)
            }
        }

        if (suffix.isEmpty()) return true
        if (!suffix.startsWith(":")) return false
        val port = suffix.substring(1).toIntOrNull() ?: return false
        return port in 1..65535
    }

    /**
     * 从用户输入里提取 `token` query 参数。
     *
     * 输入可以是完整 URL，也可以是 host[:port]/?token=...（自动补 http://）。
     * 没有 token 或输入非法时返回 null。
     */
    fun extractToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = try {
            Uri.parse(withScheme)
        } catch (t: Exception) {
            return null
        }

        return try {
            uri.getQueryParameter("token")?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Exception) {
            null
        }
    }

    /** 日志里的 token 查询参数：`?token=xxx` / `&token=xxx`。 */
    private val LOG_TOKEN_PATTERN = Regex("(?i)([?&]token=)([^&#\\s]*)")

    /**
     * 日志脱敏：把 URL 中 `token=` 参数的值替换为 `<REDACTED>`。
     *
     * 所有可能包含入口 URL（带 token）的日志都必须先经过这里，
     * 避免 token 以明文落进 logcat。
     */
    fun redactForLog(raw: String?): String {
        val text = raw.orEmpty()
        if (text.isEmpty() || !text.contains("token=", ignoreCase = true)) return text
        return LOG_TOKEN_PATTERN.replace(text) { match ->
            match.groupValues[1] + "<REDACTED>"
        }
    }
}
