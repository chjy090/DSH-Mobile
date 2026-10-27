package ai.deepseek.dsh.mobile

import android.content.Context

/**
 * 配对状态持久化：服务器地址与 token 存在独立的 SharedPreferences 中。
 *
 * v0.4.0 新增认证网关 profile：
 * - [pinnedCertSha256]：扫码取得的网关证书 SHA-256（仅认证网关模式非空）；
 * - [authGate]：当前配对是否来自认证网关；
 * - deviceToken 的密文由 [DeviceTokenStore] 按 host:port 单独保存，
 *   [deviceToken] 只是解密读取入口，明文不写本 SharedPreferences。
 *
 * 旧版直连 dsh 模式（serverUrl + token）保持兼容；两者互斥：
 * [saveAuthGateProfile] 会移除直连 token，[saveDirectPairing] 会清掉 pin/认证网关标记。
 */
class SessionStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext
        .getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)

    private val deviceTokens = DeviceTokenStore(appContext)

    var serverUrl: String?
        get() = prefs.getString(AppConfig.KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove(AppConfig.KEY_SERVER_URL)
            } else {
                editor.putString(AppConfig.KEY_SERVER_URL, value)
            }
            editor.apply()
        }

    var token: String?
        get() = prefs.getString(AppConfig.KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove(AppConfig.KEY_TOKEN)
            } else {
                editor.putString(AppConfig.KEY_TOKEN, value)
            }
            editor.apply()
        }

    /**
     * 认证网关证书 pin（64 位小写 hex）。读时规范化，历史/损坏数据自动当 null；
     * 写 null 或非法值会删除该键。
     */
    var pinnedCertSha256: String?
        get() = prefs.getString(AppConfig.KEY_PINNED_CERT, null)
            ?.let { CertPin.normalize(it) }
        set(value) {
            val normalized = CertPin.normalize(value)
            val editor = prefs.edit()
            if (normalized == null) {
                editor.remove(AppConfig.KEY_PINNED_CERT)
            } else {
                editor.putString(AppConfig.KEY_PINNED_CERT, normalized)
            }
            editor.apply()
        }

    /** 当前配对是否来自认证网关。 */
    var authGate: Boolean
        get() = prefs.getBoolean(AppConfig.KEY_AUTH_GATE, false)
        set(value) {
            prefs.edit().putBoolean(AppConfig.KEY_AUTH_GATE, value).apply()
        }

    /**
     * 本机是否成功进入过认证网关后的 GUI（完成过首次登录）。
     *
     * 网关对未认证 HTML 主文档返回 302 登录页而不是 401，App 只有在该标记为 true 时
     * 才会把“加载到登录页”视为 session 过期并用 deviceToken 自动续期；首次扫码配对后
     * 仍必须走用户名/密码/TOTP。登出会清零，重新配对也会清零。
     */
    var authSessionEstablished: Boolean
        get() = prefs.getBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, false)
        set(value) {
            prefs.edit().putBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, value).apply()
        }

    /** 当前服务器的 deviceToken（仅认证网关模式）；Keystore 不可用时为进程内降级值。 */
    val deviceToken: String?
        get() = if (authGate) serverUrl?.let { deviceTokens.load(it) } else null

    val hasPairing: Boolean
        get() = !serverUrl.isNullOrBlank()

    /** 当前认证网关 profile；非认证网关、缺 pin 或地址非法时返回 null。 */
    fun authGateProfile(): AuthGateProfile? {
        if (!authGate) return null
        val url = serverUrl ?: return null
        val pin = pinnedCertSha256 ?: return null
        return AuthGateProfile(
            baseUrl = url,
            pinnedCertSha256 = pin,
            deviceToken = deviceTokens.load(url)
        )
    }

    /**
     * 保存扫码配对结果（设计契约 §5.3）：服务器地址 + 证书 pin + 加密 deviceToken。
     * 成功后旧的直连 token 被移除，后续 WebView 通过网关登录页建立会话。
     */
    fun saveAuthGateProfile(baseUrl: String, fingerprint: String, deviceToken: String) {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val pin = CertPin.normalize(fingerprint) ?: return
        if (normalizedBase.isEmpty() || deviceToken.isBlank()) return

        prefs.edit()
            .putString(AppConfig.KEY_SERVER_URL, normalizedBase)
            .putString(AppConfig.KEY_PINNED_CERT, pin)
            .putBoolean(AppConfig.KEY_AUTH_GATE, true)
            .putBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, false)
            .remove(AppConfig.KEY_TOKEN)
            .apply()
        deviceTokens.save(normalizedBase, deviceToken)
    }

    /** 保存旧版直连配对（serverUrl + token），并清掉认证网关状态与 pin。 */
    fun saveDirectPairing(baseUrl: String, token: String?) {
        val editor = prefs.edit()
            .putString(AppConfig.KEY_SERVER_URL, baseUrl)
            .remove(AppConfig.KEY_PINNED_CERT)
            .putBoolean(AppConfig.KEY_AUTH_GATE, false)
            .putBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, false)
        val value = token?.trim()
        if (value.isNullOrEmpty()) {
            editor.remove(AppConfig.KEY_TOKEN)
        } else {
            editor.putString(AppConfig.KEY_TOKEN, value)
        }
        editor.apply()
    }

    /** 丢弃认证网关配对（含该服务器的 deviceToken），回到未配对状态。 */
    fun clearAuthGate() {
        val url = serverUrl
        prefs.edit()
            .remove(AppConfig.KEY_PINNED_CERT)
            .remove(AppConfig.KEY_AUTH_GATE)
            .remove(AppConfig.KEY_AUTH_SESSION_ESTABLISHED)
            .remove(AppConfig.KEY_TOKEN)
            .remove(AppConfig.KEY_SERVER_URL)
            .apply()
        if (url != null) {
            deviceTokens.clear(url)
        }
    }

    /** 清除全部配对状态（含当前服务器 deviceToken）。 */
    fun clear() {
        val url = serverUrl
        prefs.edit().clear().apply()
        if (url != null) {
            deviceTokens.clear(url)
        }
    }
}
