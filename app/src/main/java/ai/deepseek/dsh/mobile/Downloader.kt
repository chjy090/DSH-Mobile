package ai.deepseek.dsh.mobile

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * App 内自带的下载器（v0.2.1 起替代系统下载服务）。
 *
 * 为什么不使用系统下载服务：它运行在系统进程，读不到 App 的
 * network_security_config，也无法统一 fail-open，因此 dsh HTTPS 下的下载容易失败。
 * 这里用 [HttpURLConnection] 在 App 进程内完成请求与落盘。
 *
 * v0.3.0 起与页面层保持一致：**不固定任何 CA/证书**，TLS 校验失败时照常下载
 * （接受任意证书 + 宽松 HostnameVerifier），每次放宽写一条诊断日志。
 *
 * 线程模型：所有网络/磁盘操作在后台线程执行；[onStarted]/[onFailed]/[onDone]
 * 统一切回主线程回调。
 */
object Downloader {

    private const val TAG = "DSHDownloader"
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val BUFFER_SIZE = 64 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 本进程内已生成过的下载文件名，避免同一秒内多次下载互相覆盖。 */
    private val generatedNames = mutableSetOf<String>()

    /**
     * 发起一次下载。
     *
     * @param url 下载地址（仅 http/https）
     * @param contentDisposition 服务端 Content-Disposition（可空，用于猜文件名）
     * @param cookieHeader 当前 WebView 对 [url] 的 Cookie（可空）
     * @param onStarted 连接成功、开始写盘后回调（主线程）
     * @param onFailed 失败回调，[reason] 已脱敏并可安全写日志（主线程）
     * @param onDone 写完并落到系统 Download 目录后回调（主线程）
     */
    fun download(
        activity: ComponentActivity,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        cookieHeader: String?,
        onStarted: () -> Unit,
        onFailed: (reason: String) -> Unit,
        onDone: (savedName: String) -> Unit
    ) {
        val appContext = activity.applicationContext
        Thread({
            try {
                val fileName = uniqueDownloadName(url, contentDisposition, mimeType)
                transfer(
                    context = appContext,
                    rawUrl = url,
                    userAgent = userAgent,
                    fileName = fileName,
                    suppliedMimeType = mimeType,
                    cookieHeader = cookieHeader,
                    onStarted = onStarted,
                    onDone = onDone
                )
            } catch (t: Throwable) {
                val reason = AppConfig.redactForLog(describe(t))
                Log.e(
                    TAG,
                    "download failed url=${AppConfig.redactForLog(url)} reason=$reason",
                    t
                )
                post { onFailed(reason) }
            }
        }, "dsh-download").apply { isDaemon = true }.start()
    }

    /**
     * 真正执行下载：建连（含跨协议重定向）→ 选落盘目标 → 拷贝 → 收尾。
     *
     * 失败时在 finally 里关闭流并删除半成品；成功才回调 [onDone]。
     */
    private fun transfer(
        context: Context,
        rawUrl: String,
        userAgent: String?,
        fileName: String,
        suppliedMimeType: String?,
        cookieHeader: String?,
        onStarted: () -> Unit,
        onDone: (savedName: String) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: OutputStream? = null
        var pendingUri: Uri? = null
        var legacyFile: File? = null
        var completed = false

        try {
            connection = openConnection(rawUrl, userAgent, cookieHeader)
            val mimeType = resolveMimeType(suppliedMimeType, connection.contentType, fileName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+：MediaStore.Downloads，写入公共 Download 目录且无需存储权限。
                val uri = insertPendingDownload(context.contentResolver, fileName, mimeType)
                pendingUri = uri
                output = context.contentResolver.openOutputStream(uri, "w")
                    ?: throw DownloadException("openOutputStream returned null")
            } else {
                // API 26–28：直接写公共 Download 目录（调用前已申请 WRITE_EXTERNAL_STORAGE）。
                val file = createLegacyDestination(fileName)
                legacyFile = file
                output = FileOutputStream(file)
            }

            input = connection.inputStream
            post { onStarted() }

            copyStream(input, output)
            output.close()
            output = null
            input.close()
            input = null
            connection.disconnect()
            connection = null

            val pending = pendingUri
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                finalizePendingDownload(
                    context.contentResolver,
                    pending ?: throw DownloadException("missing pending uri")
                )
                pendingUri = null
            } else {
                val saved = legacyFile ?: throw DownloadException("missing destination file")
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(saved.absolutePath),
                    arrayOf(mimeType),
                    null
                )
                legacyFile = null
            }

            completed = true
            post { onDone(fileName) }
        } finally {
            closeQuietly(input)
            closeQuietly(output)
            try {
                connection?.disconnect()
            } catch (t: Throwable) {
                Log.w(TAG, "disconnect failed", t)
            }
            if (!completed) {
                pendingUri?.let { deletePendingDownload(context.contentResolver, it) }
                legacyFile?.let { deleteQuietly(it) }
            }
        }
    }

    /**
     * 建立连接并处理重定向。
     *
     * `instanceFollowRedirects=true` 由 JDK 处理同协议 3xx；但 JDK 明确不跟随
     * http↔https 的跨协议跳转，这类 3xx 会原样返回，这里手动跟随（最多 5 次）。
     * Cookie 只在目标 host 与原始 host 相同时发送，避免跳转到第三方 CDN 时泄漏。
     */
    private fun openConnection(
        rawUrl: String,
        userAgent: String?,
        cookieHeader: String?
    ): HttpURLConnection {
        var current = try {
            URL(rawUrl)
        } catch (t: Throwable) {
            throw DownloadException("invalid url")
        }
        val originalHost = current.host
        var sslSocketFactory: SSLSocketFactory? = null
        var redirects = 0

        while (true) {
            val protocol = current.protocol.lowercase(Locale.US)
            if (protocol != "http" && protocol != "https") {
                throw DownloadException("unsupported scheme: $protocol")
            }

            val connection = try {
                current.openConnection() as? HttpURLConnection
            } catch (t: Throwable) {
                null
            } ?: throw DownloadException("cannot open connection")

            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Accept", "*/*")
            if (!userAgent.isNullOrBlank()) {
                connection.setRequestProperty("User-Agent", userAgent)
            }
            if (!cookieHeader.isNullOrBlank() && current.host.equals(originalHost, true)) {
                connection.setRequestProperty("Cookie", cookieHeader)
            }

            if (connection is HttpsURLConnection) {
                // fail-open：仅 https 使用“先系统默认校验、失败才接受”的 TLS 组合；
                // http 不设置 SSL，避免影响明文下载。真正放宽时才写诊断日志。
                val factory = sslSocketFactory ?: permissiveSslSocketFactory.also {
                    sslSocketFactory = it
                }
                connection.sslSocketFactory = factory
                connection.hostnameVerifier = permissiveHostnameVerifier
                // TrustManager 拿不到 host：握手发生在 responseCode 时，用 ThreadLocal 传递。
                tlsHost.set(current.host)
            }

            val code = try {
                connection.responseCode
            } catch (t: Throwable) {
                connection.disconnect()
                throw DownloadException("request failed: ${t.javaClass.simpleName}")
            } finally {
                tlsHost.remove()
            }

            if (code in 300..399) {
                val location = try {
                    connection.getHeaderField("Location")
                } catch (t: Throwable) {
                    null
                }
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    throw DownloadException("HTTP $code without Location")
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw DownloadException("too many redirects")
                }
                redirects++
                current = try {
                    URL(current, location)
                } catch (t: Throwable) {
                    throw DownloadException("invalid redirect location")
                }
                continue
            }

            if (code !in 200..299) {
                connection.disconnect()
                throw DownloadException("HTTP $code")
            }
            return connection
        }
    }

    /**
     * 下载器的 fail-open TLS 组合（v0.3.0）：
     * - [X509TrustManager] **先走系统默认校验**，仅在校验失败时接受并记一次日志；
     * - [HostnameVerifier] **先用系统默认校验**，仅在不匹配时接受并记一次日志。
     *
     * 与 WebView 页面层策略一致；证书只用于（必要时）提示，不阻断下载。
     * 正常受信任的 HTTPS 下载不会产生任何“接受未受信任证书”日志。
     */
    private val systemTrustManager: X509TrustManager? by lazy {
        try {
            val factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            )
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (t: Throwable) {
            Log.w(TAG, "init system trust manager failed, downloads stay fail-open", t)
            null
        }
    }

    /** 已记录过证书放宽的 host（避免每个分片/重定向重复刷日志）。 */
    private val relaxedCertHosts = Collections.synchronizedSet(mutableSetOf<String>())

    /** 已记录过主机名放宽的 host。 */
    private val relaxedHostnameHosts = Collections.synchronizedSet(mutableSetOf<String>())

    /** TrustManager 无法拿到 host：握手前（responseCode 前）写入、完成后清理。 */
    private val tlsHost = ThreadLocal<String>()

    private fun currentTlsHost(): String =
        tlsHost.get()?.takeIf { it.isNotBlank() } ?: "unknown"

    /**
     * 记录一次证书放宽（同一 host 只记一次）。
     * host 本身不含 token；token 只可能出现在 URL，日志刻意不写 URL。
     */
    private fun noteRelaxedCertificate(host: String) {
        val safeHost = host.ifBlank { "unknown" }
        if (!relaxedCertHosts.add(safeHost)) return
        try {
            Diagnostics.add("Downloader", "W", "接受未受信任证书: $safeHost")
        } catch (t: Throwable) {
            Log.w(TAG, "record relaxed certificate diagnostics failed", t)
        }
    }

    /** 记录一次主机名放宽（同一 host 只记一次）。 */
    private fun noteRelaxedHostname(host: String) {
        val safeHost = host.ifBlank { "unknown" }
        if (!relaxedHostnameHosts.add(safeHost)) return
        try {
            Diagnostics.add("Downloader", "W", "接受主机名不匹配证书: $safeHost")
        } catch (t: Throwable) {
            Log.w(TAG, "record relaxed hostname diagnostics failed", t)
        }
    }

    /**
     * HostnameVerifier：系统默认校验通过就正常返回；失败才放行并记一次。
     * 这里每次回调都读取默认 verifier，避免类初始化时的平台差异。
     */
    private val permissiveHostnameVerifier = HostnameVerifier { hostname, session ->
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val verified = try {
            defaultVerifier.verify(hostname, session)
        } catch (t: Throwable) {
            false
        }
        if (verified) {
            true
        } else {
            noteRelaxedHostname(hostname)
            true
        }
    }

    private val permissiveSslSocketFactory: SSLSocketFactory by lazy {
        buildPermissiveSslSocketFactory()
    }

    /** 构造“先系统校验、失败才接受”的 SSLSocketFactory；构造异常时退回系统默认。 */
    private fun buildPermissiveSslSocketFactory(): SSLSocketFactory {
        return try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String
                ) {
                    // fail-open：客户端证书不参与下载器校验。
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>,
                    authType: String
                ) {
                    val primary = systemTrustManager
                    if (primary != null) {
                        try {
                            primary.checkServerTrusted(chain, authType)
                            return
                        } catch (t: Throwable) {
                            // 系统默认校验失败：记录一次后 fail-open 放行。
                        }
                    }
                    noteRelaxedCertificate(currentTlsHost())
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> =
                    systemTrustManager?.acceptedIssuers ?: emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            sslContext.socketFactory
        } catch (t: Throwable) {
            Log.e(TAG, "build permissive TLS context failed, fallback to system default", t)
            HttpsURLConnection.getDefaultSSLSocketFactory()
        }
    }

    // ---------------------------------------------------------------- 落盘

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPendingDownload(
        resolver: ContentResolver,
        fileName: String,
        mimeType: String
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw DownloadException("MediaStore insert returned null")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun finalizePendingDownload(resolver: ContentResolver, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val updated = resolver.update(uri, values, null, null)
        if (updated <= 0) {
            throw DownloadException("MediaStore finalize failed")
        }
    }

    private fun deletePendingDownload(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "delete pending download failed", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyDestination(fileName: String): File {
        val directory = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw DownloadException("cannot create Downloads directory")
        }
        return File(directory, fileName)
    }

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "delete partial file failed: ${file.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "delete partial file failed", t)
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            if (read == 0) continue
            output.write(buffer, 0, read)
        }
    }

    private fun closeQuietly(stream: java.io.Closeable?) {
        if (stream == null) return
        try {
            stream.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close stream failed", t)
        }
    }

    // ---------------------------------------------------------------- 命名 / MIME

    /** 生成 `base-yyyyMMdd-HHmmss.ext` 形式的唯一文件名（沿用 v0.2.0 逻辑）。 */
    private fun uniqueDownloadName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val guessed = guessFileName(url, contentDisposition, mimeType)
        val dot = guessed.lastIndexOf('.')
        val base = if (dot > 0) guessed.substring(0, dot) else guessed
        val extension = if (dot > 0) guessed.substring(dot) else ""
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        synchronized(generatedNames) {
            var candidate = "$base-$stamp$extension"
            var index = 2
            while (!generatedNames.add(candidate)) {
                candidate = "$base-$stamp-$index$extension"
                index++
            }
            return candidate
        }
    }

    private fun guessFileName(
        url: String,
        contentDisposition: String?,
        mimeType: String?
    ): String {
        val guessed = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (t: Throwable) {
            null
        }
        var name = guessed
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace("\u0000", "")
            ?.trim()
            .orEmpty()
        if (name.isEmpty() || name == "." || name == "..") {
            name = "dsh-download-${System.currentTimeMillis()}"
        }
        return name
    }

    private fun resolveMimeType(
        supplied: String?,
        responseContentType: String?,
        fileName: String
    ): String {
        val fromResponse = responseContentType
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.contains('/') && it.isNotEmpty() }
        if (fromResponse != null) return fromResponse

        val fromListener = supplied?.trim()?.takeIf { it.contains('/') && it.isNotEmpty() }
        if (fromListener != null) return fromListener

        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val fromExtension = if (extension.isEmpty()) {
            null
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
        return fromExtension ?: "application/octet-stream"
    }

    // ---------------------------------------------------------------- 工具

    private fun post(block: () -> Unit) {
        try {
            mainHandler.post {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "download callback failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post download callback failed", t)
        }
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.take(200)
        return if (message.isNullOrBlank()) {
            t.javaClass.simpleName
        } else {
            "${t.javaClass.simpleName}: $message"
        }
    }

    private class DownloadException(message: String) : Exception(message)
}
