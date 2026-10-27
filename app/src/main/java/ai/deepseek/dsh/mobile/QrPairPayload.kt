package ai.deepseek.dsh.mobile

import android.net.Uri
import java.io.Serializable

/**
 * 认证网关配对二维码载荷（v0.4.0）。
 *
 * 服务端由 `/__admin/api/qr` 生成：
 * `{publicUrl}/__auth/pair#v=1&t=<ticket>&fp=<证书 DER SHA256 小写hex>&n=<机器名?>`
 *
 * 安全约束：
 * - ticket 只存在于 fragment，不会随 HTTP 请求发给服务端；本类也不把它写进
 *   [toString] / 日志；
 * - 只接受 https，且必须是 `/__auth/pair` 路径，避免把任意链接当配对载荷；
 * - fp 必须是 64 位 hex，供 [AuthGateClient] 的 TLS pin 使用。
 *
 * 实现 [Serializable] 仅为了通过 `Activity.setResult(RESULT_OK, payload)` 在
 * QrScanActivity → MainActivity 之间传递，不落盘、不写日志。
 */
data class QrPairPayload(
    val baseUrl: String,
    val ticket: String,
    val certSha256: String,
    val serverName: String?
) : Serializable {

    override fun toString(): String =
        "QrPairPayload(baseUrl=$baseUrl, serverName=$serverName, ticket=<REDACTED>)"

    companion object {

        private const val PAIR_PATH = "/__auth/pair"

        /**
         * 解析扫码原始文本；任何一项不合法（scheme、path、v、t、fp、authority）
         * 都返回 null，调用方按扫码失败处理。
         */
        fun parse(raw: String?): QrPairPayload? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            val uri = try {
                Uri.parse(text)
            } catch (t: Throwable) {
                return null
            }

            if (!"https".equals(uri.scheme, ignoreCase = true)) return null
            if (!uri.userInfo.isNullOrBlank()) return null

            val host = uri.host?.trim().orEmpty()
            if (host.isEmpty()) return null
            if (host.any { it.isWhitespace() }) return null

            val port = uri.port
            if (port != -1 && port !in 1..65535) return null

            val path = uri.path.orEmpty().trimEnd('/')
            if (path != PAIR_PATH) return null

            // fragment 必须用 encoded 版本，交给 Uri.decode 逐值解码，避免二次解码。
            val fragment = uri.encodedFragment ?: return null
            val params = parseFragment(fragment) ?: return null
            if (params["v"]?.let { Uri.decode(it).trim() } != "1") return null

            val ticket = params["t"]?.let { Uri.decode(it).trim() }.orEmpty()
            if (ticket.isEmpty()) return null

            val fingerprint = CertPin.normalize(params["fp"]?.let { Uri.decode(it) })
                ?: return null

            val serverName = params["n"]
                ?.let { Uri.decode(it).trim() }
                ?.takeIf { it.isNotEmpty() }

            // baseUrl 只保留 scheme + authority（丢弃 path/query/fragment）。
            val authority = uri.authority?.substringAfterLast('@')?.trim().orEmpty()
            if (authority.isEmpty()) return null
            if (authority.any { it.isWhitespace() }) return null

            return QrPairPayload(
                baseUrl = "https://$authority",
                ticket = ticket,
                certSha256 = fingerprint,
                serverName = serverName
            )
        }

        /** `k=v&k=v` → Map；空 fragment、重复 key、无值条目都视为非法。 */
        private fun parseFragment(fragment: String): Map<String, String>? {
            val params = LinkedHashMap<String, String>()
            for (part in fragment.split('&')) {
                if (part.isEmpty()) continue
                val index = part.indexOf('=')
                if (index <= 0) continue
                val key = Uri.decode(part.substring(0, index))
                if (key.isEmpty() || params.containsKey(key)) return null
                params[key] = part.substring(index + 1)
            }
            return params.ifEmpty { null }
        }
    }
}
