package ai.deepseek.dsh.mobile

import android.net.http.SslCertificate
import android.util.Log
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TLS 证书与 SSL 错误信息（v0.3.0 fail-open 警告用）。
 *
 * App 不再固定任何 CA/证书/指纹：WebView 与服务端握手失败时仍会继续连接，
 * 只把证书详情交给 UI 做非阻断提示。这里只做“展示与脱敏”，不参与任何信任决策。
 *
 * [url] 可能带 `?token=`，因此 [toDisplayText] 不展示 URL，[toDiagnosticsLine]
 * 统一经 [AppConfig.redactForLog] 脱敏。
 */
data class TlsInfo(
    val host: String,
    val subjectDn: String,
    val issuerDn: String,
    val notBefore: String,
    val notAfter: String,
    val sha256: String,
    val primaryErrorName: String,
    val isHostnameMismatch: Boolean,
    val url: String,
) {

    /** 多行中文证书详情，供警告弹窗展示（等宽/可选中由 UI 决定）。 */
    fun toDisplayText(): String {
        return try {
            buildString {
                append("主机：").append(displayValue(host)).append('\n')
                append("主题：").append(displayValue(subjectDn)).append('\n')
                append("颁发者：").append(displayValue(issuerDn)).append('\n')
                append("有效期：").append(displayValue(notBefore))
                    .append(" 至 ").append(displayValue(notAfter)).append('\n')
                append("主机名不匹配：").append(if (isHostnameMismatch) "是" else "否").append('\n')
                append("错误类型：").append(displayValue(primaryErrorName)).append('\n')
                append("SHA-256：").append(displayValue(sha256))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "build TLS display text failed", t)
            "证书详情不可用"
        }
    }

    /** 单行诊断日志：所有字段压成一行，URL 中的 token 已脱敏。 */
    fun toDiagnosticsLine(): String {
        return try {
            val line = buildString {
                append("host=").append(oneLine(host))
                append(" subject=").append(oneLine(subjectDn))
                append(" issuer=").append(oneLine(issuerDn))
                append(" notBefore=").append(oneLine(notBefore))
                append(" notAfter=").append(oneLine(notAfter))
                append(" sha256=").append(oneLine(sha256))
                append(" error=").append(oneLine(primaryErrorName))
                append(" hostnameMismatch=").append(isHostnameMismatch)
                append(" url=").append(oneLine(url))
            }
            AppConfig.redactForLog(line)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .ifBlank { "TLS 证书信息不可用" }
        } catch (t: Throwable) {
            Log.w(TAG, "build TLS diagnostics line failed", t)
            "TLS 证书信息不可用"
        }
    }

    /**
     * 避免 data class 自动生成的 toString() 把带 token 的 [url] 原样写进日志。
     * 需要完整信息时请显式调用 [toDisplayText]/[toDiagnosticsLine]。
     */
    override fun toString(): String = toDiagnosticsLine()

    companion object {

        private const val TAG = "DSHTlsInfo"

        /** [SslCertificate.saveState] 返回 Bundle 中存放 DER 字节数组的 key（Android 平台常量）。 */
        private const val STATE_KEY = "x509-certificate"

        private const val UNKNOWN = "未知"
        private const val PARSE_FALLBACK = "（解析降级）"
        private const val SHA_UNAVAILABLE = "不可用（解析降级）"

        private val HEX = "0123456789abcdef".toCharArray()

        /**
         * 从 WebView 的 [SslCertificate] 构造信息。
         *
         * 优先解析 `saveState` 中的 X.509 DER；解析失败时降级用
         * [SslCertificate.getIssuedTo]/[SslCertificate.getIssuedBy] 的 DName
         * （`cName`/`oName`）并显式标注“解析降级”。本方法不会抛异常。
         */
        fun fromCertificate(
            cert: SslCertificate?,
            host: String,
            primaryErrorName: String,
            isHostnameMismatch: Boolean,
            url: String,
        ): TlsInfo {
            val safeHost = oneLine(host).ifBlank { UNKNOWN }
            val safeUrl = url.orEmpty()
            val safeErrorName = oneLine(primaryErrorName).ifBlank { UNKNOWN }

            var subject = UNKNOWN
            var issuer = UNKNOWN
            var notBefore = UNKNOWN
            var notAfter = UNKNOWN
            var sha256 = UNKNOWN
            var fallback = false

            try {
                val der = extractDer(cert)
                if (der == null) {
                    fallback = true
                } else {
                    val x509 = parseX509(der)
                    if (x509 == null) {
                        fallback = true
                    } else {
                        subject = oneLine(x509.subjectX500Principal.name).ifBlank { UNKNOWN }
                        issuer = oneLine(x509.issuerX500Principal.name).ifBlank { UNKNOWN }
                        notBefore = formatDate(x509.notBefore)
                        notAfter = formatDate(x509.notAfter)
                        sha256 = sha256Hex(der)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "parse TLS certificate failed, fallback to DName", t)
                fallback = true
            }

            if (fallback) {
                // 降级分支同样可能因平台 SslCertificate 实现异常而抛出；
                // 必须整体包在 try 内，保证 fromCertificate 永不抛异常。
                try {
                    val subjectDn = dnameText(cert?.issuedTo)
                    val issuerDn = dnameText(cert?.issuedBy)
                    subject = withFallbackMarker(subjectDn)
                    issuer = withFallbackMarker(issuerDn)
                    notBefore = formatDate(cert?.validNotBeforeDate)
                    notAfter = formatDate(cert?.validNotAfterDate)
                    sha256 = SHA_UNAVAILABLE
                } catch (t: Throwable) {
                    Log.w(TAG, "fallback TLS dname failed", t)
                    subject = withFallbackMarker("")
                    issuer = withFallbackMarker("")
                    notBefore = UNKNOWN
                    notAfter = UNKNOWN
                    sha256 = SHA_UNAVAILABLE
                }
            }

            return TlsInfo(
                host = safeHost,
                subjectDn = subject,
                issuerDn = issuer,
                notBefore = notBefore,
                notAfter = notAfter,
                sha256 = sha256,
                primaryErrorName = safeErrorName,
                isHostnameMismatch = isHostnameMismatch,
                url = safeUrl,
            )
        }

        /** 取 [SslCertificate] 的 DER 字节；不可用时返回 null。 */
        private fun extractDer(cert: SslCertificate?): ByteArray? {
            if (cert == null) return null
            val state = SslCertificate.saveState(cert) ?: return null
            @Suppress("DEPRECATION")
            val der = state.getByteArray(STATE_KEY) ?: return null
            return der.takeIf { it.isNotEmpty() }
        }

        private fun parseX509(der: ByteArray): X509Certificate? {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream())
            return certificate as? X509Certificate
        }

        /** SHA-256 小写 hex，无冒号。 */
        private fun sha256Hex(der: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(der)
            val out = StringBuilder(digest.size * 2)
            for (byte in digest) {
                val value = byte.toInt() and 0xFF
                out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
            }
            return out.toString()
        }

        /**
         * 解析降级：DName 里优先 CN，其次 O，都取不到时退回完整 DName。
         * 字段均按安全方式读取，异常时视为空。
         */
        private fun dnameText(dname: SslCertificate.DName?): String {
            if (dname == null) return ""
            val cName = try {
                dname.cName
            } catch (t: Throwable) {
                null
            }
            val oName = try {
                dname.oName
            } catch (t: Throwable) {
                null
            }
            val parts = buildList {
                cName?.takeIf { it.isNotBlank() }?.let { add("CN=$it") }
                oName?.takeIf { it.isNotBlank() }?.let { add("O=$it") }
            }
            if (parts.isNotEmpty()) return parts.joinToString(", ")

            return try {
                dname.dName.orEmpty()
            } catch (t: Throwable) {
                ""
            }
        }

        private fun withFallbackMarker(value: String): String {
            val safe = oneLine(value)
            return if (safe.isBlank()) {
                "$UNKNOWN$PARSE_FALLBACK"
            } else {
                "$safe$PARSE_FALLBACK"
            }
        }

        private fun formatDate(date: Date?): String {
            if (date == null) return UNKNOWN
            return try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(date)
            } catch (t: Throwable) {
                UNKNOWN
            }
        }

        private fun displayValue(value: String): String = value.ifBlank { UNKNOWN }

        private fun oneLine(value: String?): String = value
            .orEmpty()
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
    }
}
