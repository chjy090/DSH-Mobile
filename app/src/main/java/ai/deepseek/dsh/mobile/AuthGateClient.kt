package ai.deepseek.dsh.mobile

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 配对兑换结果；[cookies] 是服务端下发的 Set-Cookie（设备绑定 cookie，属敏感数据）。 */
data class DeviceGrant(
    val deviceToken: String,
    val deviceId: String?,
    val instanceId: String?,
    val expiresAt: Long?,
    val cookies: List<String> = emptyList()
) {
    /** 不输出 token / cookie，避免日志泄漏。 */
    override fun toString(): String =
        "DeviceGrant(deviceId=$deviceId, instanceId=$instanceId, expiresAt=$expiresAt, " +
            "cookies=${cookies.size})"
}

/**
 * 续期结果；成功时带回新的 session Set-Cookie，供 MainActivity 注入 WebView CookieManager。
 *
 * v0.4.2 补充失败分类与可选设备有效期：
 * - [status]：HTTP 状态码（网络/TLS 异常时为 null）；
 * - [errorCode]：服务端 JSON 的 `error` 字段（如 `device_required` / `origin_changed`）；
 * - [deviceExpiresAt]：服务端可选返回的 `deviceExpiresAt`（毫秒时间戳，旧服务端没有）。
 *
 * 这些字段均不含 token/cookie，可安全用于 UI 与日志。
 */
data class RenewOutcome(
    val ok: Boolean,
    val cookies: List<String> = emptyList(),
    val status: Int? = null,
    val errorCode: String? = null,
    val deviceExpiresAt: Long? = null
) {
    /**
     * 设备凭据是否已被服务端拒绝（需要重新扫码配对）。
     *
     * 网关的 `/__auth/renew` 对 device_required / origin_changed / token 无效统一返回 401；
     * 同时兼容未来服务端把错误码放在 200/其它状态里的情况。
     */
    val deviceRejected: Boolean
        get() = status == 401 ||
            errorCode == "device_required" ||
            errorCode == "origin_changed"

    override fun toString(): String =
        "RenewOutcome(ok=$ok, status=${status ?: "-"}, error=${errorCode ?: "-"}, " +
            "cookies=${cookies.size}, hasDeviceExpiresAt=${deviceExpiresAt != null})"
}

/** 认证网关 HTTP 失败（携带 status 与服务端 error 码，便于 UI/日志定位）。 */
class AuthGateException(
    val status: Int,
    val errorCode: String?
) : Exception("auth gate request failed (status=$status, error=${errorCode ?: "unknown"})")

/**
 * 认证网关原生客户端（v0.4.0）。
 *
 * 只服务两个接口（设计契约 §4）：
 * - `POST /__auth/pair/redeem`：用扫码 ticket 兑换 deviceToken；
 * - `POST /__auth/renew`：用 deviceToken 续期会话。
 *
 * TLS 策略：不使用系统信任链，构造一个只接受“leaf 证书 SHA-256 == 扫码 fp”的
 * [X509TrustManager]；[HostnameVerifier] 仅在该 pin 验证通过后放行。因此网关使用
 * 自签证书也能连接，但证书一旦变化立即握手失败。
 *
 * 线程模型：[redeem]/[renewDetailed]/[renew] 为阻塞调用，必须后台线程使用；
 * [redeemAsync]/[renewAsync] 在共享后台线程池执行并把结果投递回主线程。
 * 所有方法都不打印 deviceToken / ticket / cookie。
 */
class AuthGateClient {

    // ---------------------------------------------------------------- 公共 API

    /**
     * 兑换设备凭据；阻塞，需在后台线程调用。
     *
     * [clientDeviceId] 是 v0.4.6 引入的稳定客户端设备标识（见 [ClientDeviceId]）：
     * 服务端据此对同一设备的重复配对去重。旧服务端会忽略该未知字段，无需降级；
     * 值非法/为空时不发送，避免多台设备被同一个空值误合并。
     */
    fun redeem(
        baseUrl: String,
        fingerprint: String,
        ticket: String,
        deviceName: String,
        clientDeviceId: String
    ): Result<DeviceGrant> {
        val endpoint = endpointOf(baseUrl, REDEEM_PATH)
            ?: return Result.failure(IllegalArgumentException("invalid auth gate base url"))
        val pin = CertPin.normalize(fingerprint)
            ?: return Result.failure(IllegalArgumentException("invalid certificate pin"))
        if (ticket.isBlank()) {
            return Result.failure(IllegalArgumentException("empty pair ticket"))
        }

        val deviceId = clientDeviceId.trim().takeIf { ClientDeviceId.isValid(it) }
        if (deviceId == null && clientDeviceId.isNotBlank()) {
            Log.w(TAG, "redeem with malformed clientDeviceId; omitting field")
        }

        val body = try {
            JSONObject()
                .put("ticket", ticket)
                .put("deviceName", deviceName.ifBlank { DEFAULT_DEVICE_NAME })
                .put("platform", PLATFORM)
                .apply {
                    // 仅身份标识，不是 token；校验通过才发送。
                    if (deviceId != null) put("clientDeviceId", deviceId)
                }
                .toString()
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        return try {
            val response = postJson(endpoint, pin, body)
            if (response.status !in 200..299) {
                return Result.failure(
                    AuthGateException(response.status, errorCodeOf(response.body))
                )
            }
            val json = JSONObject(response.body)
            val deviceToken = json.optString("deviceToken").trim()
            if (deviceToken.isEmpty()) {
                return Result.failure(AuthGateException(response.status, "missing_device_token"))
            }
            Result.success(
                DeviceGrant(
                    deviceToken = deviceToken,
                    deviceId = json.optString("deviceId").trim().takeIf { it.isNotEmpty() },
                    instanceId = json.optString("instanceId").trim().takeIf { it.isNotEmpty() },
                    expiresAt = if (json.has("expiresAt") && !json.isNull("expiresAt")) {
                        json.optLong("expiresAt").takeIf { it > 0L }
                    } else {
                        null
                    },
                    cookies = response.cookies
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "redeem failed: ${t.javaClass.simpleName} host=${endpoint.host}")
            Result.failure(t)
        }
    }

    /** 会话续期（阻塞，需后台线程）；成功即返回 true。 */
    fun renew(baseUrl: String, fingerprint: String, deviceToken: String): Boolean =
        renewDetailed(baseUrl, fingerprint, deviceToken).ok

    /** 续期并返回服务端下发的新 cookie（阻塞，需后台线程）。 */
    fun renewDetailed(
        baseUrl: String,
        fingerprint: String,
        deviceToken: String
    ): RenewOutcome {
        val endpoint = endpointOf(baseUrl, RENEW_PATH) ?: return RenewOutcome(false)
        val pin = CertPin.normalize(fingerprint) ?: return RenewOutcome(false)
        if (deviceToken.isBlank()) return RenewOutcome(false)

        val body = try {
            JSONObject().put("deviceToken", deviceToken).toString()
        } catch (t: Throwable) {
            return RenewOutcome(false)
        }

        return try {
            val response = postJson(endpoint, pin, body)
            val json = try {
                if (response.body.isBlank()) null else JSONObject(response.body)
            } catch (t: Throwable) {
                // 网关正常返回 JSON；解析失败时按旧逻辑“无 ok 字段即成功”，
                // 但保留 HTTP 状态码，让调用方能区分 401 设备失效与网络/TLS 失败。
                null
            }
            val bodyOk = json?.optBoolean("ok", true) ?: true
            val ok = response.status in 200..299 && bodyOk
            val errorCode = if (ok) null else errorCodeOf(response.body)
            if (!ok) {
                Log.w(
                    TAG,
                    "renew rejected: status=${response.status} " +
                        "error=${errorCode ?: "unknown"} host=${endpoint.host}"
                )
            }
            RenewOutcome(
                ok = ok,
                cookies = if (ok) response.cookies else emptyList(),
                status = response.status,
                errorCode = errorCode,
                // 兼容旧服务端：没有 deviceExpiresAt 时为 null，UI 显示“—”。
                deviceExpiresAt = if (ok) {
                    json?.optLong("deviceExpiresAt")?.takeIf { it > 0L }
                } else {
                    null
                }
            )
        } catch (t: Throwable) {
            Log.w(TAG, "renew failed: ${t.javaClass.simpleName} host=${endpoint.host}")
            RenewOutcome(false)
        }
    }

    /** 后台兑换，结果回主线程；回调保证只触发一次。 */
    fun redeemAsync(
        baseUrl: String,
        fingerprint: String,
        ticket: String,
        deviceName: String,
        clientDeviceId: String,
        onResult: (Result<DeviceGrant>) -> Unit
    ) {
        WORKERS.execute {
            val result = redeem(baseUrl, fingerprint, ticket, deviceName, clientDeviceId)
            MAIN.post {
                try {
                    onResult(result)
                } catch (t: Throwable) {
                    Log.w(TAG, "redeem callback failed", t)
                }
            }
        }
    }

    /**
     * 只用 pin 严格 TLS 预检网关：`GET /__auth/whoami`。
     *
     * 目的不是鉴权（whoami 对未登录也返回 200），而是确认**当前证书指纹仍等于扫码 pin**、
     * TLS 握手与网关 HTTP 都可达。任何网络/TLS/pin 错误都返回 false（fail-closed）。
     * 阻塞，需在后台线程调用。
     */
    fun probe(baseUrl: String, fingerprint: String): Boolean {
        val endpoint = endpointOf(baseUrl, WHOAMI_PATH) ?: return false
        val pin = CertPin.normalize(fingerprint) ?: return false
        return try {
            val response = get(endpoint, pin)
            val ok = response.status in 200..299
            if (!ok) Log.w(TAG, "probe rejected: status=${response.status} host=${endpoint.host}")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed: ${t.javaClass.simpleName} host=${endpoint.host}")
            false
        }
    }

    /** 后台 pin 预检，结果回主线程；回调保证只触发一次。 */
    fun probeAsync(
        baseUrl: String,
        fingerprint: String,
        onResult: (Boolean) -> Unit
    ) {
        WORKERS.execute {
            val ok = probe(baseUrl, fingerprint)
            MAIN.post {
                try {
                    onResult(ok)
                } catch (t: Throwable) {
                    Log.w(TAG, "probe callback failed", t)
                }
            }
        }
    }

    /**
     * 后台续期，结果回主线程；回调保证只触发一次。
     *
     * 返回 [Future] 便于调用方在 Activity 销毁时 `cancel(true)`（HTTP 连接本身还有
     * [TIMEOUT_MS] 上限；取消是尽力而为，回调仍可能被主线程投递，调用方需再用
     * 生命周期/代际标记过滤）。
     */
    fun renewAsync(
        baseUrl: String,
        fingerprint: String,
        deviceToken: String,
        onResult: (RenewOutcome) -> Unit
    ): Future<*> {
        return WORKERS.submit {
            val outcome = renewDetailed(baseUrl, fingerprint, deviceToken)
            MAIN.post {
                try {
                    onResult(outcome)
                } catch (t: Throwable) {
                    Log.w(TAG, "renew callback failed", t)
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP / TLS

    private fun endpointOf(baseUrl: String, path: String): URL? = try {
        val base = URL(baseUrl.trim())
        if (!"https".equals(base.protocol, ignoreCase = true)) {
            null
        } else {
            URL(base, path)
        }
    } catch (t: Throwable) {
        null
    }

    private fun postJson(endpoint: URL, pin: String, body: String): HttpResponse {
        val verifier = PinHostnameVerifier()
        val trustManager = PinTrustManager(pin, verifier)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("auth gate requires https")
        try {
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = verifier
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")

            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = readLimited(stream)
            return HttpResponse(status, text, collectCookies(connection))
        } finally {
            try {
                connection.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect failed", t)
            }
        }
    }

    /** pin 严格 TLS 的 GET（probe 专用）；不跟随重定向，避免绕过 pin 校验。 */
    private fun get(endpoint: URL, pin: String): HttpResponse {
        val verifier = PinHostnameVerifier()
        val trustManager = PinTrustManager(pin, verifier)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())

        val connection = endpoint.openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("auth gate requires https")
        try {
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = verifier
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.doOutput = false
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(status, readLimited(stream), collectCookies(connection))
        } finally {
            try {
                connection.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect failed", t)
            }
        }
    }

    private fun collectCookies(connection: HttpsURLConnection): List<String> {
        val result = mutableListOf<String>()
        val fields = try {
            connection.headerFields
        } catch (t: Throwable) {
            null
        } ?: return result
        for ((name, values) in fields) {
            if (name == null) continue
            if (!name.equals("Set-Cookie", ignoreCase = true)) continue
            for (value in values) {
                if (!value.isNullOrBlank()) result.add(value)
            }
        }
        return result
    }

    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() < MAX_RESPONSE_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val remaining = MAX_RESPONSE_BYTES - output.size()
                    output.write(buffer, 0, minOf(read, remaining))
                }
                String(output.toByteArray(), Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read response failed", t)
            ""
        }
    }

    private fun errorCodeOf(body: String): String? = try {
        JSONObject(body).optString("error").trim().takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    /** 只信指定 leaf 证书 SHA-256 的 TrustManager；同时把 pin 命中告知 HostnameVerifier。 */
    private class PinTrustManager(
        private val expectedPin: String,
        private val verifier: PinHostnameVerifier
    ) : X509TrustManager {

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificate is not accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("empty server certificate chain")
            val actual = CertPin.sha256Hex(leaf.encoded)
                ?: throw CertificateException("unable to hash server certificate")
            if (!actual.equals(expectedPin, ignoreCase = true)) {
                throw CertificateException("server certificate pin mismatch")
            }
            verifier.markPinned()
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * pin 命中前一律拒绝；[PinTrustManager] 在证书指纹相等时调用 [markPinned]。
     * 自签证书通常 hostname 不匹配，pin 相等即视为可信。
     */
    private class PinHostnameVerifier : HostnameVerifier {

        private val pinned = AtomicBoolean(false)

        fun markPinned() {
            pinned.set(true)
        }

        override fun verify(hostname: String?, session: SSLSession?): Boolean = pinned.get()
    }

    private data class HttpResponse(
        val status: Int,
        val body: String,
        val cookies: List<String>
    )

    companion object {

        private const val TAG = "DSHAuthGate"

        private const val REDEEM_PATH = "/__auth/pair/redeem"
        private const val RENEW_PATH = "/__auth/renew"
        private const val WHOAMI_PATH = "/__auth/whoami"
        private const val PLATFORM = "android"
        private const val DEFAULT_DEVICE_NAME = "android"
        private const val TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        /** 所有实例共享后台线程池（daemon，避免退出时挂住进程）。 */
        private val WORKERS: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "dsh-auth-gate").apply { isDaemon = true }
        }

        private val MAIN = Handler(Looper.getMainLooper())
    }
}
