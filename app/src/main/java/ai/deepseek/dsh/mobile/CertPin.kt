package ai.deepseek.dsh.mobile

import android.net.http.SslCertificate
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/**
 * 证书指纹工具（v0.4.0 认证网关证书固定）。
 *
 * 只做纯计算，不做信任决策：
 * - [AuthGateClient] 用它把 TLS 握手里的 leaf 证书换算成 SHA-256，与扫码取得的 fp 比对；
 * - [WebHost] 用它把 WebView 的 [SslCertificate] 换算成 SHA-256，实现认证网关 host 的
 *   pin 严格模式。
 *
 * 指纹统一为 64 位小写 hex（无冒号），与二维码 `fp` 参数、网关证书 DER SHA-256 一致。
 */
object CertPin {

    private const val TAG = "DSHCertPin"

    /** [SslCertificate.saveState] 返回 Bundle 中存放 DER 字节数组的 key（Android 平台常量）。 */
    private const val STATE_KEY = "x509-certificate"

    private val HEX = "0123456789abcdef".toCharArray()

    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")

    /** DER → 小写 SHA-256 hex；输入为空或计算失败返回 null。 */
    fun sha256Hex(der: ByteArray?): String? {
        if (der == null || der.isEmpty()) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            out.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "compute certificate sha256 failed", t)
            null
        }
    }

    /**
     * 从 WebView 的 [SslCertificate] 取 leaf 证书 DER 并计算 SHA-256。
     * 解析失败返回 null（调用方按“无法确认 pin”处理）。
     */
    @Suppress("DEPRECATION")
    fun sha256Of(cert: SslCertificate?): String? {
        if (cert == null) return null
        return try {
            val state = SslCertificate.saveState(cert) ?: return null
            val der = state.getByteArray(STATE_KEY) ?: return null
            sha256Hex(der)
        } catch (t: Throwable) {
            Log.w(TAG, "read webview certificate failed", t)
            null
        }
    }

    /** 规范化指纹：trim + 转小写 + 校验 64 hex；非法返回 null。 */
    fun normalize(fingerprint: String?): String? {
        val value = fingerprint?.trim()?.lowercase(Locale.US) ?: return null
        return value.takeIf { SHA256_PATTERN.matches(it) }
    }

    /** 两个指纹（任意大小写/空白）是否相等；任一非法都算不匹配。 */
    fun matches(expected: String?, actual: String?): Boolean {
        val left = normalize(expected) ?: return false
        val right = normalize(actual) ?: return false
        return left == right
    }
}
