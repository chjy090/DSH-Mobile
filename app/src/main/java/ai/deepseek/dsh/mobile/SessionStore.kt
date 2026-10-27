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

    /**
     * v0.4.2：设备凭据是否曾在真实登录中验证成功（启动静默续期依据）。
     *
     * 与 [authSessionEstablished] 的区别：用户选择「退出当前会话」时只把后者清零，
     * 本标记保留，因此下次打开 App 仍可用 deviceToken 静默换新会话；
     * 「忘记此设备」/重新配对会清空全部 prefs，本标记随之为 false。
     */
    var authDeviceTrusted: Boolean
        get() = prefs.getBoolean(AppConfig.KEY_AUTH_DEVICE_TRUSTED, false)
        set(value) {
            prefs.edit().putBoolean(AppConfig.KEY_AUTH_DEVICE_TRUSTED, value).apply()
        }

    /** v0.4.2：设备有效期（毫秒时间戳）；0 表示服务端未提供/未知。不敏感。 */
    var deviceExpiresAt: Long
        get() = prefs.getLong(AppConfig.KEY_AUTH_DEVICE_EXPIRES_AT, 0L)
        set(value) {
            prefs.edit()
                .putLong(AppConfig.KEY_AUTH_DEVICE_EXPIRES_AT, value.coerceAtLeast(0L))
                .apply()
        }

    /** v0.4.2：最近一次成功 renew 的时间（毫秒）；0 表示从未成功，用于前台续期节流。 */
    var lastAuthRenewAt: Long
        get() = prefs.getLong(AppConfig.KEY_AUTH_LAST_RENEW_AT, 0L)
        set(value) {
            prefs.edit()
                .putLong(AppConfig.KEY_AUTH_LAST_RENEW_AT, value.coerceAtLeast(0L))
                .apply()
        }

    /**
     * 记录一次「成功进入网关后的 GUI」：设备凭据已验证，同时把从未设置过的
     * 续期节流基准初始化为当前时间，避免刚登录就立刻触发前台续期。
     */
    fun recordAuthGateGuiLoaded() {
        val editor = prefs.edit()
            .putBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, true)
            .putBoolean(AppConfig.KEY_AUTH_DEVICE_TRUSTED, true)
        if (lastAuthRenewAt <= 0L) {
            editor.putLong(AppConfig.KEY_AUTH_LAST_RENEW_AT, System.currentTimeMillis())
        }
        editor.apply()
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
     *
     * [deviceExpiresAt] 来自 redeem 响应的 `expiresAt`（网关侧设备有效期，毫秒）；
     * 旧服务端未返回时传 null，仅影响「关于」里的展示，不影响登录/续期。
     */
    fun saveAuthGateProfile(
        baseUrl: String,
        fingerprint: String,
        deviceToken: String,
        deviceExpiresAt: Long? = null
    ) {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val pin = CertPin.normalize(fingerprint) ?: return
        if (normalizedBase.isEmpty() || deviceToken.isBlank()) return

        prefs.edit()
            .putString(AppConfig.KEY_SERVER_URL, normalizedBase)
            .putString(AppConfig.KEY_PINNED_CERT, pin)
            .putBoolean(AppConfig.KEY_AUTH_GATE, true)
            .putBoolean(AppConfig.KEY_AUTH_SESSION_ESTABLISHED, false)
            .putBoolean(AppConfig.KEY_AUTH_DEVICE_TRUSTED, false)
            .putLong(
                AppConfig.KEY_AUTH_DEVICE_EXPIRES_AT,
                deviceExpiresAt?.takeIf { it > 0L } ?: 0L
            )
            .putLong(AppConfig.KEY_AUTH_LAST_RENEW_AT, 0L)
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
            .remove(AppConfig.KEY_AUTH_DEVICE_TRUSTED)
            .remove(AppConfig.KEY_AUTH_DEVICE_EXPIRES_AT)
            .remove(AppConfig.KEY_AUTH_LAST_RENEW_AT)
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
            .remove(AppConfig.KEY_AUTH_DEVICE_TRUSTED)
            .remove(AppConfig.KEY_AUTH_DEVICE_EXPIRES_AT)
            .remove(AppConfig.KEY_AUTH_LAST_RENEW_AT)
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
