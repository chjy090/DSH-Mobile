package ai.deepseek.dsh.mobile

/**
 * 认证网关配对 profile（v0.4.0）。
 *
 * 由 [SessionStore] 持久化：服务器地址 + 证书 pin（SharedPreferences 明文）+
 * deviceToken 引用（实际密文由 [DeviceTokenStore] 按 host:port 保存）。
 *
 * [deviceToken] 是长期设备凭据：本类不把它写进 [toString]，也不参与日志/诊断输出。
 */
data class AuthGateProfile(
    val baseUrl: String,
    val pinnedCertSha256: String,
    val deviceToken: String?
) {

    /** `host:port`（派生 deviceToken 存储 key 用）。 */
    val hostPort: String?
        get() = DeviceTokenStore.hostPortOf(baseUrl)

    /** 是否具备自动续期所需的最小信息（地址 + pin + deviceToken）。 */
    val canRenew: Boolean
        get() = baseUrl.isNotBlank() &&
            CertPin.normalize(pinnedCertSha256) != null &&
            !deviceToken.isNullOrBlank()

    override fun toString(): String =
        "AuthGateProfile(baseUrl=$baseUrl, pinned=${CertPin.normalize(pinnedCertSha256) != null}, " +
            "hasDeviceToken=${!deviceToken.isNullOrBlank()})"
}
