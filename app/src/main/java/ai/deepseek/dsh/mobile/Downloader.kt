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
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 一次成功下载的结果（v0.4.5）。
 *
 * [contentUri]：Android 10+ MediaStore 实际集合返回的 Uri（图片/视频/音频/下载集合之一）；
 * [legacyPath]：Android 8/9 公共 `Download/` 下的绝对路径（可空）。
 * 两者都用于「最近下载」历史与打开入口；[mimeType]/[sizeBytes] 直接来自本次下载，
 * 避免历史记录再查一次 MediaStore。
 */
data class DownloadResult(
    val savedName: String,
    val contentUri: Uri?,
    val legacyPath: String?,
    val mimeType: String,
    val sizeBytes: Long,
    /** 实际落盘目录（带尾斜杠），如 `Pictures/`、`Movies/`、`Music/`、`Download/`。 */
    val savedDirectory: String
)

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

    /** 文件头嗅探读取的前缀上限；只读少量字节，不缓冲整个文件。 */
    private const val SNIFF_PREFIX_SIZE = 512

    private const val OCTET_STREAM = "application/octet-stream"
    private const val FALLBACK_FILE_NAME = "dsh-download"

    /** 文件名 UTF-8 字节上限，避免超过常见文件系统/MediaStore 的 255 字节限制。 */
    private const val MAX_FILE_NAME_BYTES = 180

    /** 重名递增上限；超过视为异常（正常下载不可能有 9999 个同名文件）。 */
    private const val MAX_NAME_COLLISION_INDEX = 9999

    private val illegalFileNameChars = Regex("[\\\\/:*?\"<>|]")

    /**
     * 常见 MIME → 扩展名兜底（MimeTypeMap 在个别精简 ROM 上可能缺项）。
     * 仅在 MIME 可识别但 [MimeTypeMap] 返回 null 时使用。
     */
    private val mimeExtensionFallback = mapOf(
        "application/pdf" to "pdf",
        "application/zip" to "zip",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.android.package-archive" to "apk",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "video/mp4" to "mp4",
        "audio/mpeg" to "mp3",
        "text/plain" to "txt",
        "application/json" to "json",
        "text/csv" to "csv"
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 本进程内**正在使用**（已分配但尚未收尾）的下载文件名。
     *
     * 并发下载同一名字时先在这里占位，防止两个线程同时算中同一个候选名；
     * 下载结束后移除。磁盘/MediaStore 上是否已存在由 [downloadNameExists] 另行判断。
     */
    private val generatedNames = mutableSetOf<String>()

    /**
     * 发起一次下载。
     *
     * 文件名需要等拿到响应头（Content-Type）后才能最终确定，因此**不在**这里提前算名，
     * 而是在 [transfer] 内按 Content-Disposition → URL → 兜底 的顺序解析原名，
     * 补齐扩展名并查重后再落盘。
     *
     * @param url 下载地址（仅 http/https）
     * @param contentDisposition 服务端 Content-Disposition（可空，用于猜文件名）
     * @param cookieHeader 当前 WebView 对 [url] 的 Cookie（可空）
     * @param onStarted 连接成功、开始写盘后回调（主线程）
     * @param onFailed 失败回调，[reason] 已脱敏并可安全写日志（主线程）
     * @param onDone 写完并落到系统媒体库/下载目录后回调（主线程），携带最终名字、
     *   MediaStore content Uri（API 29+）或公共 Download 绝对路径（API 26–28），
     *   以及本次下载的 MIME 与字节数，供「最近下载」历史与打开入口使用
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
        onDone: (result: DownloadResult) -> Unit
    ) {
        val appContext = activity.applicationContext
        Thread({
            try {
                transfer(
                    context = appContext,
                    rawUrl = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
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
     * 真正执行下载：建连（含跨协议重定向）→ 读少量文件头 → 定名/MIME → 选落盘目标 →
     * 拷贝 → 收尾。
     *
     * 文件头只读 [SNIFF_PREFIX_SIZE] 字节，不缓冲整个文件；若连接读不到任何字节，
     * 嗅探自然退化为 null。
     *
     * 失败时在 finally 里关闭流并删除半成品；成功才回调 [onDone]。
     */
    private fun transfer(
        context: Context,
        rawUrl: String,
        userAgent: String?,
        contentDisposition: String?,
        suppliedMimeType: String?,
        cookieHeader: String?,
        onStarted: () -> Unit,
        onDone: (result: DownloadResult) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: OutputStream? = null
        var pendingUri: Uri? = null
        var legacyFile: File? = null
        var completed = false
        var reservedName: String? = null

        try {
            connection = openConnection(rawUrl, userAgent, cookieHeader)
            input = connection.inputStream

            // 先读文件头（最多 512 字节），用于 Content-Type 缺失/为 octet-stream 时嗅探；
            // 已读出的字节随后原样写入目标文件，不丢数据、不二次请求。
            val prefix = readPrefix(input)
            val rawName = guessFileName(rawUrl, contentDisposition)
            val mimeType = resolveMimeType(
                supplied = suppliedMimeType,
                responseContentType = connection.contentType,
                fileName = rawName,
                prefix = prefix
            )
            val requestedName = buildDownloadName(rawName, mimeType)
            // v0.4.5：Android 10+ 按 MIME 分流到图片/视频/音频集合（Pictures/Movies/Music），
            // 其它文档仍进 Downloads/Download；API 26–28 统一落公共 Download（稍后媒体扫描）。
            val mediaTarget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolveMediaTarget(mimeType)
            } else {
                null
            }
            // 定名 + 查重必须串行：同名并发下载在这里占位，后到的按 (1)、(2) 递增。
            // 查重必须查“本次将写入的那个集合与目录”，不能只看 Downloads。
            val fileName = allocateUniqueName(context, requestedName, mediaTarget)
            reservedName = fileName

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+：写入 MIME 对应的 MediaStore 集合，无需存储权限。
                val target = mediaTarget ?: throw DownloadException("missing media target")
                val uri = insertPendingDownload(
                    context.contentResolver,
                    fileName,
                    mimeType,
                    target
                )
                pendingUri = uri
                output = context.contentResolver.openOutputStream(uri, "w")
                    ?: throw DownloadException("openOutputStream returned null")
            } else {
                // API 26–28：直接写公共 Download 目录（调用前已申请 WRITE_EXTERNAL_STORAGE）。
                val file = createLegacyDestination(fileName)
                legacyFile = file
                output = FileOutputStream(file)
            }

            post { onStarted() }

            var written = 0L
            if (prefix.isNotEmpty()) {
                output.write(prefix)
                written += prefix.size.toLong()
            }
            written += copyStream(input, output)
            output.flush()
            output.close()
            output = null
            input.close()
            input = null
            connection.disconnect()
            connection = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pending = pendingUri ?: throw DownloadException("missing pending uri")
                // 最终名字以 MediaStore 实际 DISPLAY_NAME 为准（系统可能因并发重名自动改名），
                // 保证 onDone 与用户在文件管理器里看到的名字一致。
                val target = mediaTarget ?: throw DownloadException("missing media target")
                val actualName = finalizePendingDownload(
                    context.contentResolver,
                    pending,
                    written,
                    target
                ) ?: fileName
                // v0.4.5 额外索引尝试：Q+ 若能查到 DATA 绝对路径则补一次媒体扫描；
                // 查不到/失败只记日志，不影响已完成的下载。
                tryScanDownloadedFile(context, pending)
                pendingUri = null
                post {
                    onDone(
                        DownloadResult(
                            savedName = actualName,
                            contentUri = pending,
                            legacyPath = null,
                            mimeType = mimeType,
                            sizeBytes = written,
                            savedDirectory = target.relativePath
                        )
                    )
                }
            } else {
                val saved = legacyFile ?: throw DownloadException("missing destination file")
                saved.setLastModified(System.currentTimeMillis())
                legacyFile = null
                scanLegacyDownload(context, saved, mimeType)
                post {
                    onDone(
                        DownloadResult(
                            savedName = fileName,
                            contentUri = null,
                            legacyPath = saved.absolutePath,
                            mimeType = mimeType,
                            sizeBytes = written,
                            savedDirectory = "${Environment.DIRECTORY_DOWNLOADS}/"
                        )
                    )
                }
            }

            completed = true
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
            releaseGeneratedName(reservedName)
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

    /**
     * Android 10+ 的写入目标：MIME 决定集合与相对目录（v0.4.5）。
     *
     * 图片 → Images/Pictures/、视频 → Video/Movies/、音频 → Audio/Music/，
     * 其它（文档/归档/apk/未知）→ Downloads/Download/。
     * 文件名、去重、日期/大小、IS_PENDING 与 notifyChange 逻辑在各集合间完全复用。
     */
    private data class MediaTarget(val collectionUri: Uri, val relativePath: String)

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun resolveMediaTarget(mimeType: String): MediaTarget {
        val mime = normalizeMimeType(mimeType) ?: OCTET_STREAM
        return when {
            mime.startsWith("image/") -> MediaTarget(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_PICTURES}/"
            )
            mime.startsWith("video/") -> MediaTarget(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/"
            )
            mime.startsWith("audio/") -> MediaTarget(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/"
            )
            else -> MediaTarget(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_DOWNLOADS}/"
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertPendingDownload(
        resolver: ContentResolver,
        fileName: String,
        mimeType: String,
        target: MediaTarget
    ): Uri {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            // MediaStore 的 DATE_ADDED / DATE_MODIFIED 单位是「秒」（官方列定义，
            // 不是毫秒）。补上后「文件 → 最近」与按时间排序的视图才能拿到正确时间。
            put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
            // 先占位 0，finalize 时再补实际字节数。
            put(MediaStore.MediaColumns.SIZE, 0L)
        }
        val inserted = try {
            resolver.insert(target.collectionUri, values)
        } catch (t: Throwable) {
            // 个别 OEM 对插入时的 DATE_*/SIZE 较严格：退回最小字段重试，保证可下载。
            Log.w(TAG, "MediaStore insert with metadata failed, retrying minimal", t)
            null
        }
        if (inserted != null) return inserted

        val minimal = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            resolver.insert(target.collectionUri, minimal)
                ?: throw DownloadException("MediaStore insert returned null")
        } catch (t: Throwable) {
            if (t is DownloadException) throw t
            throw DownloadException("MediaStore insert failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * 解除 pending 并补齐元数据，返回 MediaStore 实际使用的 DISPLAY_NAME（可能因并发重名
     * 被系统自动改名，以它为准才能与文件管理器/onDone 一致）。
     *
     * 某些 OEM 对 SIZE/DATE_* 的写入较严格：整组更新失败时退回“只解除 pending”，
     * 保证文件至少可见，再通知 MediaStore 刷新索引。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun finalizePendingDownload(
        resolver: ContentResolver,
        uri: Uri,
        sizeBytes: Long,
        target: MediaTarget
    ): String? {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val displayName = queryMediaStoreString(
            resolver,
            uri,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
            put(MediaStore.MediaColumns.SIZE, sizeBytes.coerceAtLeast(0L))
            // insert 时已写 DATE_ADDED；仅当系统没保留时补一次。
            if (queryMediaStoreLong(resolver, uri, MediaStore.MediaColumns.DATE_ADDED) == null) {
                put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
            }
        }
        var updated = try {
            resolver.update(uri, values, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "MediaStore finalize full update failed", t)
            -1
        }
        if (updated <= 0) {
            val minimal = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            updated = try {
                resolver.update(uri, minimal, null, null)
            } catch (t: Throwable) {
                Log.w(TAG, "MediaStore finalize fallback update failed", t)
                -1
            }
        }
        if (updated <= 0) {
            throw DownloadException("MediaStore finalize failed")
        }
        notifyDownloadChanged(resolver, uri, target.collectionUri)
        return displayName
    }

    /**
     * 主动通知索引刷新：既通知本条记录，也通知它实际所在的媒体集合
     * （Images/Video/Audio/Downloads，v0.4.5 起不再固定通知 Downloads）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun notifyDownloadChanged(
        resolver: ContentResolver,
        uri: Uri,
        collectionUri: Uri
    ) {
        try {
            resolver.notifyChange(uri, null)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyChange download uri failed", t)
        }
        try {
            resolver.notifyChange(collectionUri, null)
        } catch (t: Throwable) {
            Log.w(TAG, "notifyChange media collection failed", t)
        }
    }

    /**
     * v0.4.5：Q+ 收尾时额外尝试一次媒体扫描（可选、低成本）。
     *
     * scoped storage 下 `DATA` 已 deprecated 且不保证可读：只有查到非空绝对路径才调用
     * [MediaScannerConnection.scanFile]，否则直接跳过。扫描失败/无回调只写日志，
     * 不影响下载结果（MediaStore 记录 + notifyChange 仍是主路径）。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    @Suppress("DEPRECATION")
    private fun tryScanDownloadedFile(context: Context, uri: Uri) {
        val path = queryMediaStoreString(
            context.contentResolver,
            uri,
            MediaStore.MediaColumns.DATA
        )
        if (path.isNullOrBlank() || !path.startsWith("/")) {
            Log.d(TAG, "supplemental media scan skipped: no absolute path")
            return
        }
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, scannedUri ->
                if (scannedUri == null) {
                    Log.d(TAG, "supplemental media scan returned no uri")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "supplemental media scan failed", t)
        }
    }

    private fun queryMediaStoreString(
        resolver: ContentResolver,
        uri: Uri,
        column: String
    ): String? {
        return try {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query MediaStore column $column failed", t)
            null
        }
    }

    private fun queryMediaStoreLong(
        resolver: ContentResolver,
        uri: Uri,
        column: String
    ): Long? {
        return try {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "query MediaStore column $column failed", t)
            null
        }
    }

    private fun deletePendingDownload(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (t: Throwable) {
            Log.w(TAG, "delete pending download failed", t)
        }
    }

    /**
     * API 26–28：落盘后再让媒体扫描器补建索引。回调只用来确认是否拿到 Uri，
     * 失败只记日志，不影响下载结果（文件已经在公共 Download 目录里）。
     */
    private fun scanLegacyDownload(context: Context, file: File, mimeType: String) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                if (uri == null) {
                    Log.w(TAG, "media scan did not index downloaded file")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "media scan failed, file remains on disk", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDownloadsDirectory(): File? {
        return try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve legacy Downloads directory failed", t)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun createLegacyDestination(fileName: String): File {
        val directory = legacyDownloadsDirectory()
            ?: throw DownloadException("cannot resolve Downloads directory")
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

    /** 读取文件头（最多 [SNIFF_PREFIX_SIZE] 字节），读到的字节由调用方原样写入目标文件。 */
    private fun readPrefix(input: InputStream): ByteArray {
        val buffer = ByteArray(SNIFF_PREFIX_SIZE)
        var total = 0
        try {
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
        } catch (t: Throwable) {
            throw DownloadException("read failed: ${t.javaClass.simpleName}")
        }
        return if (total == buffer.size) buffer else buffer.copyOf(total)
    }

    private fun copyStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
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

    // ---------------------------------------------------------------- 命名 / MIME / 索引

    /**
     * 由原始名 + 已推断的 MIME 生成最终“期望文件名”：
     * 清洗非法字符 → 补扩展名（原名没有扩展名时按 MIME 推断）→ 限制长度。
     */
    private fun buildDownloadName(rawName: String, mimeType: String): String {
        var name = sanitizeFileName(repairMojibake(rawName))
        if (name.isEmpty()) name = FALLBACK_FILE_NAME
        if (splitName(name).second.isEmpty()) {
            val extension = extensionForMime(mimeType)
            if (!extension.isNullOrEmpty()) {
                name = "$name.$extension"
            }
        }
        return truncateFileName(name)
    }

    /**
     * 分配唯一名字：`name.ext` → `name (1).ext` → `name (2).ext` …
     *
     * 串行化 + [generatedNames] 占位防止同进程并发下载同时算中同一个名字；
     * 磁盘（API 26–28）或目标 MediaStore 集合（API 29+）上已存在的名字也会跳过。
     * Android 10+ 必须按本次 MIME 对应的集合/目录查重（v0.4.5），不能只看 Downloads。
     */
    private fun allocateUniqueName(
        context: Context,
        requestedName: String,
        mediaTarget: MediaTarget?
    ): String {
        val (base, extension) = splitName(requestedName)
        val trimmedBase = base.trimEnd(' ', '.')
        synchronized(generatedNames) {
            var index = 0
            while (index <= MAX_NAME_COLLISION_INDEX) {
                val candidate = if (index == 0) {
                    requestedName
                } else {
                    // 重名后缀本身也计入长度上限，避免超长名字再加 (n) 后超过文件系统限制。
                    truncateFileName(
                        "${trimmedBase.ifEmpty { FALLBACK_FILE_NAME }} ($index)$extension"
                    )
                }
                if (!generatedNames.contains(candidate) &&
                    !downloadNameExists(context, candidate, mediaTarget)
                ) {
                    generatedNames.add(candidate)
                    return candidate
                }
                index++
            }
        }
        throw DownloadException("cannot allocate unique download name")
    }

    private fun releaseGeneratedName(name: String?) {
        if (name == null) return
        synchronized(generatedNames) {
            generatedNames.remove(name)
        }
    }

    private fun downloadNameExists(
        context: Context,
        name: String,
        mediaTarget: MediaTarget?
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = mediaTarget ?: return false
            mediaStoreNameExists(context.contentResolver, name, target)
        } else {
            val directory = legacyDownloadsDirectory() ?: return false
            File(directory, name).exists()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreNameExists(
        resolver: ContentResolver,
        fileName: String,
        target: MediaTarget
    ): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        // RELATIVE_PATH 正常带尾斜杠（Download/、Pictures/…），个别 ROM/旧数据可能不带，两种都查。
        val selection = "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf(
            target.relativePath,
            target.relativePath.trimEnd('/'),
            fileName
        )
        return try {
            resolver.query(
                target.collectionUri,
                projection,
                selection,
                args,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (t: Throwable) {
            // 查询失败按“不存在”处理：insert 时 MediaStore 自身还会去重兜底。
            Log.w(TAG, "query MediaStore for existing download name failed", t)
            false
        }
    }

    /**
     * 解析原始文件名，优先级：
     * 1. `Content-Disposition: filename*=UTF-8''...`（RFC 5987，percent-decode）；
     * 2. `Content-Disposition: filename="..."`（percent-decode + 常见转义）；
     * 3. URL 最后一段（去掉 query/fragment 后 percent-decode）；
     * 4. 兜底 `dsh-download`（扩展名稍后按 MIME 补）。
     */
    private fun guessFileName(url: String, contentDisposition: String?): String {
        dispositionFileName(contentDisposition)?.let { return it }
        urlFileName(url)?.let { return it }
        return FALLBACK_FILE_NAME
    }

    private fun dispositionFileName(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val parameters = parseContentDispositionParameters(contentDisposition)
        val extended = parameters["filename*"]
        if (!extended.isNullOrBlank()) {
            val decoded = decodeExtendedValue(extended)
            if (decoded.isNotBlank()) return decoded
        }
        val plain = parameters["filename"]
        if (!plain.isNullOrBlank()) {
            val decoded = percentDecode(plain)
            if (decoded.isNotBlank()) return decoded
        }
        return null
    }

    /**
     * 解析 Content-Disposition 参数（处理引号内的 `;`、反斜杠转义与大小写）。
     * 返回的小写 key → 原始值；`filename` 与 `filename*` 同时存在时由调用方优先选后者。
     */
    private fun parseContentDispositionParameters(header: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val value = header.take(8192)
        var index = 0
        while (index < value.length) {
            while (index < value.length &&
                (value[index] == ';' || value[index].isWhitespace())
            ) {
                index++
            }
            if (index >= value.length) break
            val nameStart = index
            while (index < value.length && value[index] != '=' && value[index] != ';') {
                index++
            }
            val name = value.substring(nameStart, index).trim().lowercase(Locale.US)
            if (index >= value.length || value[index] != '=') {
                if (index < value.length && value[index] == ';') index++
                continue
            }
            index++
            while (index < value.length && value[index] == ' ') index++
            val parameter: String
            if (index < value.length && value[index] == '"') {
                index++
                val builder = StringBuilder()
                while (index < value.length) {
                    val ch = value[index]
                    if (ch == '\\' && index + 1 < value.length) {
                        builder.append(value[index + 1])
                        index += 2
                    } else if (ch == '"') {
                        index++
                        break
                    } else {
                        builder.append(ch)
                        index++
                    }
                }
                parameter = builder.toString()
                while (index < value.length && value[index] != ';') index++
            } else {
                val start = index
                while (index < value.length && value[index] != ';') index++
                parameter = value.substring(start, index).trim()
            }
            if (name.isNotEmpty() && !result.containsKey(name)) {
                result[name] = parameter
            }
        }
        return result
    }

    /** RFC 5987：`charset'language'percent-encoded-value`；也兼容省略前缀的裸值。 */
    private fun decodeExtendedValue(value: String): String {
        val firstQuote = value.indexOf('\'')
        if (firstQuote >= 0) {
            val secondQuote = value.indexOf('\'', firstQuote + 1)
            if (secondQuote > firstQuote) {
                val charset = value.substring(0, firstQuote)
                val encoded = value.substring(secondQuote + 1)
                return percentDecode(encoded, charset)
            }
        }
        return percentDecode(value)
    }

    /**
     * 对字符串做 percent-decode：`%XX` 还原为字节，其余字符按 UTF-8 写入；
     * 最后按声明的 charset 解码（无声明时先严格 UTF-8，失败退回 ISO-8859-1）。
     */
    private fun percentDecode(raw: String, charsetName: String? = null): String {
        val output = ByteArrayOutputStream(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '%' && index + 2 < raw.length) {
                val high = hexValue(raw[index + 1])
                val low = hexValue(raw[index + 2])
                if (high >= 0 && low >= 0) {
                    output.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            val codePoint = raw.codePointAt(index)
            output.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
            index += Character.charCount(codePoint)
        }
        val bytes = output.toByteArray()
        val charset = charsetFor(charsetName)
        if (charset != null && charset != Charsets.UTF_8) {
            return try {
                String(bytes, charset)
            } catch (t: Throwable) {
                decodeUtf8OrLatin1(bytes)
            }
        }
        return decodeUtf8OrLatin1(bytes)
    }

    private fun charsetFor(name: String?): Charset? {
        if (name.isNullOrBlank()) return null
        return when (name.trim().lowercase(Locale.US)) {
            "utf-8", "utf8" -> Charsets.UTF_8
            "iso-8859-1", "latin1", "latin-1" -> Charsets.ISO_8859_1
            else -> try {
                Charset.forName(name.trim())
            } catch (t: Throwable) {
                null
            }
        }
    }

    private fun decodeUtf8OrLatin1(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (t: Throwable) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun urlFileName(url: String): String? {
        val path = try {
            URL(url).path.orEmpty()
        } catch (t: Throwable) {
            url.substringBefore('#').substringBefore('?')
        }
        val segment = path.trimEnd('/').substringAfterLast('/')
        if (segment.isBlank()) return null
        return try {
            URLDecoder.decode(segment, "UTF-8")
        } catch (t: Throwable) {
            segment
        }
    }

    /**
     * 修复常见 Latin-1/UTF-8 乱码（如 `è¯´æ˜Ž.pdf` → `说明.pdf`）：
     * 仅当字符串全部落在 Latin-1 范围、且其字节能严格解码为 UTF-8 时才替换。
     * 正常的 ASCII/中文文件名会原样返回。
     */
    private fun repairMojibake(value: String): String {
        if (value.isEmpty()) return value
        // 浏览器/WebView 把 UTF-8 文件名按 Windows-1252 误解码时，会产生
        // U+02DC、U+017D 等超出 Latin-1 的字符；这里按 CP1252 反向映射回字节。
        val bytes = ByteArray(value.length)
        for (index in value.indices) {
            val byte = cp1252Byte(value[index])
            if (byte < 0) return value
            bytes[index] = byte.toByte()
        }
        val repaired = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (t: Throwable) {
            return value
        }
        return if (repaired == value || repaired.contains('\uFFFD')) value else repaired
    }

    /** CP1252 字符 → 单字节；不可映射返回 -1（用于 mojibake 反向还原）。 */
    private fun cp1252Byte(ch: Char): Int {
        val code = ch.code
        if (code <= 0xFF) return code
        return when (code) {
            0x20AC -> 0x80
            0x201A -> 0x82
            0x0192 -> 0x83
            0x201E -> 0x84
            0x2026 -> 0x85
            0x2020 -> 0x86
            0x2021 -> 0x87
            0x02C6 -> 0x88
            0x2030 -> 0x89
            0x0160 -> 0x8A
            0x2039 -> 0x8B
            0x0152 -> 0x8C
            0x017D -> 0x8E
            0x2018 -> 0x91
            0x2019 -> 0x92
            0x201C -> 0x93
            0x201D -> 0x94
            0x2022 -> 0x95
            0x2013 -> 0x96
            0x2014 -> 0x97
            0x02DC -> 0x98
            0x2122 -> 0x99
            0x0161 -> 0x9A
            0x203A -> 0x9B
            0x0153 -> 0x9C
            0x017E -> 0x9E
            0x0178 -> 0x9F
            else -> -1
        }
    }

    /**
     * 清洗文件名：去掉路径分隔符/非法字符/控制字符、首尾空格与尾部点，
     * 防路径穿越与隐藏文件；不合法或为空时返回空串由调用方兜底。
     */
    private fun sanitizeFileName(raw: String): String {
        var name = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filterNot { it.code < 0x20 || it.code == 0x7F }
            .replace(illegalFileNameChars, "_")
            .trim()
            .trimEnd('.', ' ')
        if (name.isEmpty() || name == "." || name == ".." || name.all { it == '.' }) {
            return ""
        }
        // 以点开头会被文件管理器当隐藏文件（且可能是路径穿越残留），加前缀。
        if (name.startsWith(".")) {
            name = "_$name"
        }
        return name
    }

    /**
     * 以**最后一个点**拆分「基础名 + 扩展名」；`.tar.gz` 视为 base=`x.tar`, ext=`.gz`，
     * `说明 v2.pdf` 视为 base=`说明 v2`, ext=`.pdf`。最后一个点在最前或末尾时视为无扩展名。
     */
    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name to ""
        return name.substring(0, dot) to name.substring(dot)
    }

    private fun extensionForMime(mimeType: String?): String? {
        val mime = normalizeMimeType(mimeType) ?: return null
        val fromMap = try {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        } catch (t: Throwable) {
            null
        }
        return fromMap?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: mimeExtensionFallback[mime]
    }

    private fun truncateFileName(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES) return name
        val (base, extension) = splitName(name)
        var trimmed = base
        while (trimmed.isNotEmpty() &&
            (trimmed + extension).toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES
        ) {
            trimmed = trimmed.dropLast(1)
        }
        val result = (trimmed.trimEnd('.', ' ') + extension).trimEnd('.', ' ')
        return if (result.isEmpty()) FALLBACK_FILE_NAME else result
    }

    /**
     * MIME 推断顺序：响应 Content-Type → 下载监听 type → 扩展名 → 文件头嗅探 →
     * `application/octet-stream`。`application/octet-stream` 视为“未知”继续往下找。
     */
    private fun resolveMimeType(
        supplied: String?,
        responseContentType: String?,
        fileName: String,
        prefix: ByteArray
    ): String {
        normalizeMimeType(responseContentType)?.let { return it }
        normalizeMimeType(supplied)?.let { return it }
        mimeTypeFromExtension(fileName)?.let { return it }
        sniffMimeType(prefix)?.let { return it }
        return OCTET_STREAM
    }

    private fun normalizeMimeType(raw: String?): String? {
        val value = raw?.substringBefore(';')?.trim()?.lowercase(Locale.US) ?: return null
        if (value.length < 3 || value.length > 255 || !value.contains('/')) return null
        if (value == OCTET_STREAM || value == "binary/octet-stream") return null
        return value
    }

    private fun mimeTypeFromExtension(fileName: String): String? {
        val extension = splitName(fileName).second.removePrefix(".").lowercase(Locale.US)
        if (extension.isEmpty()) return null
        val mime = try {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } catch (t: Throwable) {
            null
        }
        return normalizeMimeType(mime)
    }

    /**
     * 文件头嗅探（只看前 512 字节，不做完整缓冲）：
     * PDF、ZIP 系（含 docx/xlsx/pptx/apk）、PNG/JPEG/GIF/WebP、MP4、MP3、UTF-8 文本。
     * 返回 null 表示无法判断，由调用方退回 octet-stream。
     */
    private fun sniffMimeType(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (startsWith(bytes, "%PDF-".toByteArray(Charsets.US_ASCII))) {
            return "application/pdf"
        }
        if (startsWith(
                bytes,
                byteArrayOf(
                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
                )
            )
        ) {
            return "image/png"
        }
        if (startsWith(bytes, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
            return "image/jpeg"
        }
        if (startsWith(bytes, "GIF87a".toByteArray(Charsets.US_ASCII)) ||
            startsWith(bytes, "GIF89a".toByteArray(Charsets.US_ASCII))
        ) {
            return "image/gif"
        }
        if (bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return "image/webp"
        }
        if (bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp") {
            return "video/mp4"
        }
        if (bytes.size >= 3 && String(bytes, 0, 3, Charsets.US_ASCII) == "ID3") {
            return "audio/mpeg"
        }
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF) {
            val second = bytes[1].toInt() and 0xFF
            if (second == 0xFB || second == 0xF3 || second == 0xF2 ||
                second == 0xFA || second == 0xE3
            ) {
                return "audio/mpeg"
            }
        }
        if (startsWith(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
            return "application/zip"
        }
        if (looksLikeUtf8Text(bytes)) return "text/plain"
        return null
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) return false
        for (index in prefix.indices) {
            if (bytes[index] != prefix[index]) return false
        }
        return true
    }

    /** UTF-8 严格解码后没有控制字符（\\t/\\n/\\r 除外）且至少有一个可见字符。 */
    private fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (t: Throwable) {
            return false
        }
        if (text.isEmpty()) return false
        var hasContent = false
        for (ch in text) {
            if (ch == '\t' || ch == '\n' || ch == '\r' || ch == '\uFEFF') continue
            if (ch.code < 0x20 || ch.code == 0x7F) return false
            hasContent = true
        }
        return hasContent
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
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
