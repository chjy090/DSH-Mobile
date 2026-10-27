package ai.deepseek.dsh.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale

/** WebView 主文档错误的粗分类，供原生错误覆盖层选择文案。 */
enum class ErrorKind {
    NETWORK,
    AUTH,
    RENDERER,
    OTHER,

    /** v0.4.0 认证网关：TLS certificate pin 不匹配（证书已变更/无法解析）。 */
    CERT_CHANGED
}

/**
 * 远程 dsh Web GUI 的 WebView 宿主：负责 WebView 配置、Cookie、JS 桥、
 * document-start 注入、文件选择、下载、外链与错误回调。
 *
 * 注意：不直接持有 ActivityResultLauncher；文件选择由 [filePicker] 回传给
 * MainActivity 实现。
 */
class WebHost(
    private val activity: ComponentActivity,
    private val container: ViewGroup,
    private val session: SessionStore,
    private val onProgress: (Int) -> Unit,
    private val onPageFinishedCallback: (String?) -> Unit,
    private val onError: (kind: ErrorKind) -> Unit,
    private val onRendererGone: () -> Unit,
    private val filePicker: (Array<String>, ValueCallback<Array<Uri>>) -> Unit,
    private val onAudioPermissionNeeded: () -> Unit,
    /**
     * 当前页第一次 SSL 证书错误时回调（fail-open 警告，不阻断加载）。
     * 默认空实现，保证旧调用方无需改动即可编译。
     */
    private val onTlsWarning: (TlsInfo) -> Unit = {},
    /** 主文档加载完成且本页没有 SSL 错误时回调，用于隐藏证书警告。 */
    private val onTlsCleared: () -> Unit = {},
    /**
     * v0.4.0 认证网关证书 pin。非空时：会话服务器 host 的 TLS 错误改为严格模式——
     * 只有 leaf 证书 SHA-256 与该值相等才 proceed，否则 cancel 并回调
     * [ErrorKind.CERT_CHANGED]；其它 host / 无 pin 仍走 v0.3.0 fail-open。
     * 默认 null，保持旧调用方兼容。
     */
    private val pinnedCertSha256: String? = null,
    /**
     * v0.4.0 主文档 401 回调（认证网关用 deviceToken 原生续期）。
     * 非空时不再显示 AUTH 错误层，交给 MainActivity 处理；默认 null 保持旧行为。
     */
    private val onAuthExpired: (() -> Unit)? = null
) {

    val webView: WebView = WebView(activity)

    /**
     * API ≤ 28 写公共 Downloads 需要运行时权限；由 MainActivity 注入申请入口。
     * 未注入时按"无权限"处理并提示失败。
     */
    var downloadPermissionRequester: (() -> Unit)? = null

    private val cookieManager: CookieManager = CookieManager.getInstance()

    /** 入口 URL（含 `?token=`），401 后用它重试。 */
    private var authUrl: String? = null

    /** 当前主文档是否出现过 SSL 错误（本页存在证书警告，onPageStarted 重置）。 */
    private var tlsWarningForCurrentPage: Boolean = false

    /**
     * 当前主文档是否收到过 401（onPageStarted 重置）。
     * 认证网关续期后 reload 仍 401 时，MainActivity 用它避免把“续期计数”误清零。
     */
    var authExpiredForCurrentPage: Boolean = false
        private set

    /** 等待 WRITE_EXTERNAL_STORAGE 授权后继续的下载。 */
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadUserAgent: String? = null
    private var pendingDownloadDisposition: String? = null
    private var pendingDownloadMimeType: String? = null

    private val documentStartSupported: Boolean = try {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    } catch (t: Throwable) {
        false
    }

    private val injectionScript: String = buildInjectionScript()

    /** document-start 脚本是否已成功注册；失败则退回 onPageStarted/onPageFinished 注入。 */
    private var documentStartInjected: Boolean = false

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /** 等系统 RECORD_AUDIO 授权后才放行的网页音频采集请求（不持久化）。 */
    private var pendingWebPermission: PermissionRequest? = null

    private var destroyed: Boolean = false

    init {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(ContextCompat.getColor(activity, R.color.dsh_bg))

        configureWebView()
        configureCookies()

        webView.addJavascriptInterface(NativeBridge(activity), BRIDGE_NAME)
        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        })
        webView.setWebChromeClient(createChromeClient())
        webView.setWebViewClient(createWebViewClient())

        installDocumentStartScript()
        container.addView(webView)
    }

    fun load(url: String) {
        if (destroyed || url.isBlank()) return
        // 入口 URL 带 token：401 后重放它即可让服务端 303 重新铸 cookie。
        authUrl = url
        try {
            webView.loadUrl(url)
        } catch (t: Throwable) {
            Log.e(TAG, "loadUrl failed: ${AppConfig.redactForLog(url)}", t)
            onError(ErrorKind.OTHER)
        }
    }

    /**
     * 以 POST 方式在当前 WebView 打开 [url]（v0.4.0 认证网关登出用）。
     *
     * `/__auth/logout` 只接受 POST（GET 会 405），但仍需要浏览器携带 session cookie
     * 并走 WebView 的 cookie 栈，因此用 WebView.postUrl 而不是原生请求。
     * 返回 false 表示 WebHost 已销毁或 WebView 调用失败。
     */
    fun postUrl(url: String, postData: ByteArray = ByteArray(0)): Boolean {
        if (destroyed || url.isBlank()) return false
        authUrl = url
        return try {
            webView.postUrl(url, postData)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "postUrl failed: ${AppConfig.redactForLog(url)}", t)
            false
        }
    }

    /** 是否保留着可重试的入口 URL（带 token）。 */
    val hasAuthUrl: Boolean
        get() = authUrl != null

    /** 401 后用入口 URL 重试；没有可重试地址时返回 false。 */
    fun retryAuth(): Boolean {
        val url = authUrl ?: return false
        if (destroyed) return false
        return try {
            webView.loadUrl(url)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "retryAuth failed: ${AppConfig.redactForLog(url)}", t)
            onError(ErrorKind.OTHER)
            false
        }
    }

    /** 权限授予后继续之前被拦下的下载。 */
    fun resumePendingDownload() {
        if (destroyed) {
            clearPendingDownload()
            return
        }
        val url = pendingDownloadUrl ?: return
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasWriteStoragePermission()) {
            clearPendingDownload()
            Toast.makeText(activity, R.string.download_permission_denied, Toast.LENGTH_SHORT).show()
            return
        }
        val userAgent = pendingDownloadUserAgent
        val disposition = pendingDownloadDisposition
        val mimeType = pendingDownloadMimeType
        clearPendingDownload()
        startDownload(url, userAgent, disposition, mimeType)
    }

    /** 权限被拒绝时丢弃挂起的下载。 */
    fun cancelPendingDownload() {
        clearPendingDownload()
    }

    private fun clearPendingDownload() {
        pendingDownloadUrl = null
        pendingDownloadUserAgent = null
        pendingDownloadDisposition = null
        pendingDownloadMimeType = null
    }

    /**
     * 系统麦克风授权通过后，放行挂起的网页音频采集请求。
     *
     * 没有挂起请求、WebHost 已销毁、或系统权限仍未授予时不做危险操作
     * （后两种情况直接 deny，避免页面一直等待）。
     */
    fun grantPendingWebPermission() {
        val request = pendingWebPermission ?: return
        pendingWebPermission = null
        if (destroyed || !hasRecordAudioPermission()) {
            denyWebPermission(request)
            return
        }
        val audioResources = audioResourcesOf(request)
        if (audioResources.isEmpty()) {
            denyWebPermission(request)
            return
        }
        try {
            request.grant(audioResources.toTypedArray())
        } catch (t: Throwable) {
            Log.w(TAG, "grant web audio permission failed", t)
            denyWebPermission(request)
        }
    }

    /** 麦克风授权被拒绝 / 页面销毁时，丢弃挂起的网页音频采集请求。 */
    fun denyPendingWebPermission() {
        val request = pendingWebPermission ?: return
        pendingWebPermission = null
        denyWebPermission(request)
    }

    private fun denyWebPermission(request: PermissionRequest) {
        try {
            request.deny()
        } catch (t: Throwable) {
            Log.w(TAG, "deny web permission failed", t)
        }
    }

    /** 取请求中的音频采集子集；页面同时申请其它资源时只放行音频。 */
    private fun audioResourcesOf(request: PermissionRequest): List<String> {
        val resources = request.resources.orEmpty()
        return resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun reload() {
        if (destroyed) return
        try {
            // 401 后主文档被替换成 about:blank：此时 reload 只会重载空白页，
            // 改为重放带 token 的入口 URL。
            val current = webView.url
            val entry = authUrl
            if (entry != null && (current.isNullOrBlank() || current == "about:blank")) {
                webView.loadUrl(entry)
            } else {
                webView.reload()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reload failed", t)
        }
    }

    fun onPause() {
        if (destroyed) return
        try {
            webView.onPause()
        } catch (t: Throwable) {
            Log.w(TAG, "webView.onPause failed", t)
        }
        flushCookies()
    }

    fun onResume() {
        if (destroyed) return
        try {
            webView.onResume()
        } catch (t: Throwable) {
            Log.w(TAG, "webView.onResume failed", t)
        }
    }

    fun onDestroy() {
        if (destroyed) return
        destroyed = true

        clearPendingDownload()
        // Activity 重建/配置变更/退出时丢弃挂起的麦克风请求，避免泄漏页面引用。
        denyPendingWebPermission()

        try {
            pendingFileCallback?.onReceiveValue(null)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel pending file chooser failed", t)
        }
        pendingFileCallback = null

        flushCookies()

        try {
            webView.stopLoading()
        } catch (t: Throwable) {
            Log.w(TAG, "stopLoading failed", t)
        }
        try {
            webView.removeJavascriptInterface(BRIDGE_NAME)
        } catch (t: Throwable) {
            Log.w(TAG, "removeJavascriptInterface failed", t)
        }
        try {
            // 先导航到空页，避免 destroy() 时页面还在跑 JS。
            webView.loadUrl("about:blank")
        } catch (t: Throwable) {
            Log.w(TAG, "load blank failed", t)
        }
        try {
            (webView.parent as? ViewGroup)?.removeView(webView)
        } catch (t: Throwable) {
            Log.w(TAG, "detach WebView failed", t)
        }
        try {
            webView.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy WebView failed", t)
        }
    }

    fun flushCookies() {
        try {
            cookieManager.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "CookieManager.flush failed", t)
        }
    }

    /** 由 MainActivity 在 container 加入 Window 后调用，给容器加左右/上下安全区。 */
    fun applyInsets() {
        if (destroyed) return
        padByInsets(container)
    }

    @Suppress("DEPRECATION")
    private fun configureWebView() {
        val settings = webView.settings
        settings.setJavaScriptEnabled(true)
        settings.setDomStorageEnabled(true)
        settings.setDatabaseEnabled(true)

        settings.setUseWideViewPort(true)
        settings.setLoadWithOverviewMode(false)
        settings.setSupportZoom(false)
        settings.setBuiltInZoomControls(false)
        settings.setDisplayZoomControls(false)
        settings.setMediaPlaybackRequiresUserGesture(false)

        settings.setAllowFileAccess(false)
        // 取舍：SAF 文件选择器（content://）上传需要允许内容访问；页面导航层已把
        // file:/content:/intent: 等 scheme 全部阻断，WebView 自身仍不能加载本地文件，
        // 因此这里放开风险可控。
        settings.setAllowContentAccess(true)
        settings.setAllowFileAccessFromFileURLs(false)
        settings.setAllowUniversalAccessFromFileURLs(false)
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE)
        // API 26+ 才存在；在 33+ 已废弃但保留调用以兼容旧设备。
        settings.setSafeBrowsingEnabled(true)
    }

    private fun configureCookies() {
        try {
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, false)
        } catch (t: Throwable) {
            Log.w(TAG, "configure cookies failed", t)
        }
    }

    private fun installDocumentStartScript() {
        if (!documentStartSupported) return
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, injectionScript, setOf("*"))
            documentStartInjected = true
        } catch (t: Throwable) {
            Log.w(TAG, "document-start script registration failed, fallback enabled", t)
        }
    }

    private fun injectFallback(view: WebView?) {
        if (destroyed || documentStartInjected) return
        val target = view ?: return
        try {
            target.evaluateJavascript(injectionScript, null)
        } catch (t: Throwable) {
            Log.w(TAG, "fallback JS injection failed", t)
        }
    }

    private fun createChromeClient(): WebChromeClient {
        return object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            /**
             * 网页 console 写入诊断环形缓冲（无 adb 时靠「⋮ → 诊断日志」带出）。
             * 返回 false：保留 WebView 默认的 logcat 输出，有 adb 时两者都能看。
             */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val message = consoleMessage ?: return false
                try {
                    Diagnostics.add(
                        source = "WebConsole",
                        level = consoleLevel(message.messageLevel()),
                        message = buildString {
                            append(message.message())
                            append(" (")
                            append(message.sourceId() ?: "unknown")
                            append(':')
                            append(message.lineNumber())
                            append(')')
                        }
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "record console message failed", t)
                }
                return false
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: WebChromeClient.FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false

                // 同一时间只允许一个文件选择会话；旧会话立刻回空。
                try {
                    pendingFileCallback?.onReceiveValue(null)
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel previous file callback failed", t)
                }
                pendingFileCallback = null

                // acceptTypes 来自页面，可能是扩展名 / 带点扩展名 / 混合串：
                // 统一规范化为 MIME，否则 OpenMultipleDocuments 可能抛异常。
                val acceptTypes = normalizeAcceptTypes(fileChooserParams?.acceptTypes)
                val singleUse = SingleUseFileCallback(callback)
                pendingFileCallback = singleUse

                return try {
                    filePicker(acceptTypes, singleUse)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "file picker failed", t)
                    singleUse.onReceiveValue(null)
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return

                if (destroyed) {
                    denyWebPermission(request)
                    return
                }

                // 只处理音频采集；页面若一次申请多个资源，只放行音频子集。
                val audioResources = audioResourcesOf(request)
                if (audioResources.isEmpty()) {
                    Log.w(TAG, "deny non-audio web permission request")
                    denyWebPermission(request)
                    return
                }

                // 只允许当前页面 origin 使用麦克风，防止第三方 iframe 借用权限。
                if (!isCurrentPageOrigin(request.origin)) {
                    Log.w(
                        TAG,
                        "deny web permission from origin=" +
                            AppConfig.redactForLog(request.origin?.toString())
                    )
                    denyWebPermission(request)
                    return
                }

                if (hasRecordAudioPermission()) {
                    try {
                        request.grant(audioResources.toTypedArray())
                    } catch (t: Throwable) {
                        Log.w(TAG, "grant web audio permission failed", t)
                        denyWebPermission(request)
                    }
                    return
                }

                // 尚未获得系统麦克风权限：挂起请求，交给 Activity 申请。
                denyPendingWebPermission()
                pendingWebPermission = request
                try {
                    onAudioPermissionNeeded()
                } catch (t: Throwable) {
                    Log.e(TAG, "request system mic permission failed", t)
                    denyPendingWebPermission()
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                if (request == null) return
                // 页面导航/取消时 WebView 会撤销请求；只清引用，不再 grant/deny，
                // 避免对已失效对象操作，也避免 Activity 重建后误放行旧请求。
                if (pendingWebPermission === request) {
                    pendingWebPermission = null
                    Log.i(TAG, "pending web permission canceled by page")
                }
            }
        }
    }

    /** WebView console 级别 → 诊断日志里的单字母级别。 */
    private fun consoleLevel(level: ConsoleMessage.MessageLevel?): String = when (level) {
        ConsoleMessage.MessageLevel.ERROR -> "E"
        ConsoleMessage.MessageLevel.WARNING -> "W"
        ConsoleMessage.MessageLevel.DEBUG -> "D"
        ConsoleMessage.MessageLevel.TIP -> "I"
        ConsoleMessage.MessageLevel.LOG -> "I"
        else -> "I"
    }

    /**
     * 把 `accept` 规范化为 MIME 列表：
     * 按 `,` 拆分 → trim → 含 `/` 保留 → 否则去掉前导 `.` 用
     * [MimeTypeMap] 转换 → 失败回退通配 MIME → 去重。空结果返回通配 MIME。
     */
    private fun normalizeAcceptTypes(raw: Array<String>?): Array<String> {
        val result = LinkedHashSet<String>()
        for (value in raw ?: emptyArray()) {
            for (part in value.split(',')) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val mime = if (trimmed.contains('/')) {
                    trimmed
                } else {
                    val extension = trimmed.removePrefix(".").trim().lowercase(Locale.US)
                    val resolved = if (extension.isEmpty()) {
                        null
                    } else {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    }
                    resolved ?: "*/*"
                }
                result.add(mime)
            }
        }
        return if (result.isEmpty()) arrayOf("*/*") else result.toTypedArray()
    }

    private fun createWebViewClient(): WebViewClient {
        return object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return handleNavigation(uri)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                // 旧设备/部分重定向只走字符串重载；同样必须过 scheme 白名单。
                val uri = try {
                    url?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
                } catch (t: Throwable) {
                    null
                } ?: return true
                return handleNavigation(uri)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // 新主文档开始加载：重置本页 TLS 警告 / 401 状态（about:blank 不重置）。
                if (parseWebUri(url) != null) {
                    tlsWarningForCurrentPage = false
                    authExpiredForCurrentPage = false
                }
                injectFallback(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectFallback(view)
                // 主文档正常结束且本页从未出现 SSL 错误 → 清掉可能残留的证书警告。
                if (parseWebUri(url) != null && !tlsWarningForCurrentPage) {
                    notifyTlsCleared()
                }
                onPageFinishedCallback(url)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame != true) return
                if (errorResponse?.statusCode != 401) return
                authExpiredForCurrentPage = true
                Log.w(TAG, "main frame 401: ${AppConfig.redactForLog(request.url?.toString())}")

                // v0.4.0 认证网关：交给 MainActivity 用 deviceToken 原生续期，
                // 不再显示 dsh 鉴权错误层（续期失败时由 Activity 跳登录页）。
                val authExpired = onAuthExpired
                if (authExpired != null) {
                    val target = view ?: webView
                    try {
                        target.stopLoading()
                    } catch (t: Throwable) {
                        Log.w(TAG, "stopLoading on 401 (auth gate) failed", t)
                    }
                    try {
                        authExpired()
                    } catch (t: Throwable) {
                        Log.e(TAG, "auth expired callback failed", t)
                        onError(ErrorKind.AUTH)
                    }
                    return
                }

                // 不把 401 死页留在 WebView：停掉加载并跳空白页，错误交给原生覆盖层。
                val target = view ?: webView
                try {
                    target.stopLoading()
                } catch (t: Throwable) {
                    Log.w(TAG, "stopLoading on 401 failed", t)
                }
                try {
                    target.loadUrl("about:blank")
                } catch (t: Throwable) {
                    Log.w(TAG, "load blank after 401 failed", t)
                }
                onError(ErrorKind.AUTH)
            }

            /**
             * v0.4.0 TLS 策略分两档：
             * 1. 认证网关 host（[pinnedCertSha256] 非空且 host 匹配）→ 严格 pin：
             *    只有 leaf 证书 SHA-256 == pin 才 proceed；不等/解析失败 cancel 并回调
             *    [ErrorKind.CERT_CHANGED]；
             * 2. 其它服务器 → 保持 v0.3.0 fail-open：无论 UNTRUSTED / IDMISMATCH / 过期，
             *    一律 proceed，只刷新顶部警告条。
             *
             * fail-open 分支整个回调体包裹 try/catch：任何信息解析或 UI 回调异常都不得阻断
             * proceed。finally 用局部 flag 兜底，确保 handler 至少尝试放行一次且不重复成功放行。
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                if (handlePinnedSslError(handler, error)) return

                var proceeded = false
                try {
                    val info = buildTlsInfo(error)
                    tlsWarningForCurrentPage = true

                    try {
                        Diagnostics.add("TLS", "W", info.toDiagnosticsLine())
                    } catch (t: Throwable) {
                        Log.w(TAG, "record TLS warning failed", t)
                    }
                    Log.w(
                        TAG,
                        "ssl error accepted (fail-open): error=${info.primaryErrorName} " +
                            "host=${info.host} url=${AppConfig.redactForLog(info.url)}"
                    )

                    // 每次都回调：banner 随最近一次证书错误更新（弹窗仅在点击时打开）。
                    notifyTlsWarning(info)

                    handler?.proceed()
                    proceeded = true
                } catch (t: Throwable) {
                    Log.e(TAG, "handle ssl error failed", t)
                } finally {
                    if (!proceeded) {
                        try {
                            handler?.proceed()
                            proceeded = true
                        } catch (t: Throwable) {
                            Log.e(TAG, "proceed after ssl error failed", t)
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame != true) return
                onError(ErrorKind.NETWORK)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                Log.e(TAG, "render process gone, didCrash=${detail?.didCrash()}")
                handleRendererGone(view)
                return true
            }
        }
    }

    /**
     * 渲染进程死亡后 WebView 不可再用：销毁它并通知 Activity 重建。
     * 返回 true 的契约要求调用方清理，这里同步完成销毁。
     */
    private fun handleRendererGone(view: WebView?) {
        if (destroyed) {
            try {
                view?.destroy()
            } catch (t: Throwable) {
                Log.w(TAG, "destroy dead WebView failed", t)
            }
            return
        }
        destroyed = true
        clearPendingDownload()
        denyPendingWebPermission()

        try {
            pendingFileCallback?.onReceiveValue(null)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel pending file chooser failed", t)
        }
        pendingFileCallback = null

        flushCookies()

        val dead = view ?: webView
        try {
            dead.stopLoading()
        } catch (t: Throwable) {
            Log.w(TAG, "stopLoading dead WebView failed", t)
        }
        try {
            (dead.parent as? ViewGroup)?.removeView(dead)
        } catch (t: Throwable) {
            Log.w(TAG, "detach dead WebView failed", t)
        }
        try {
            dead.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "destroy dead WebView failed", t)
        }
        onRendererGone()
    }

    /**
     * 导航 scheme 白名单：
     * - 同 origin 的 http(s) 留在 WebView
     * - 跨 origin 的 http(s)、mailto、tel 交系统 ACTION_VIEW
     * - 其余 scheme（intent/file/content/javascript/data/blob/about…）一律阻断
     */
    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme.isNullOrEmpty()) {
            Log.w(TAG, "blocked navigation with empty scheme: ${AppConfig.redactForLog(uri.toString())}")
            return true
        }
        return when (scheme) {
            "http", "https" -> {
                if (isSameOrigin(uri)) {
                    false
                } else {
                    openExternally(uri)
                    true
                }
            }
            "mailto", "tel" -> {
                openExternally(uri)
                true
            }
            else -> {
                // intent:/file:/content:/javascript:/data:/blob:/about: 等绝不交给系统，
                // 否则可能被外部应用接管或让当前页面导航走。
                Log.w(TAG, "blocked navigation scheme=$scheme url=${AppConfig.redactForLog(uri.toString())}")
                true
            }
        }
    }

    private fun isSameOrigin(target: Uri): Boolean {
        // origin 以当前页为准；页面尚未建立时（首载/401 后空白页）退回配对地址。
        val current = webView.url?.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }
        val base = current ?: session.serverUrl?.takeIf { it.isNotBlank() } ?: return false
        val baseUri = try {
            Uri.parse(base)
        } catch (t: Throwable) {
            return false
        }
        if (!baseUri.scheme.equals(target.scheme, ignoreCase = true)) return false
        if (!baseUri.host.equals(target.host, ignoreCase = true)) return false
        return effectivePort(baseUri) == effectivePort(target)
    }

    private fun effectivePort(uri: Uri): Int {
        val port = uri.port
        if (port != -1) return port
        return if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    }

    /**
     * 网页权限请求的 origin 必须严格等于当前页面 origin。
     *
     * 与 [isSameOrigin] 不同，这里不退回配对地址：页面尚未加载完成（about:blank）
     * 或跨域 iframe 发起的申请一律拒绝。
     */
    private fun isCurrentPageOrigin(origin: Uri?): Boolean {
        val requested = origin ?: return false
        if (!requested.scheme.equals("http", ignoreCase = true) &&
            !requested.scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }
        val host = requested.host
        if (host.isNullOrBlank()) return false

        val current = webView.url?.takeIf {
            it.startsWith("http://", ignoreCase = true) ||
                it.startsWith("https://", ignoreCase = true)
        } ?: return false
        val base = try {
            Uri.parse(current)
        } catch (t: Throwable) {
            return false
        }
        if (!base.scheme.equals(requested.scheme, ignoreCase = true)) return false
        if (!base.host.equals(host, ignoreCase = true)) return false
        return effectivePort(base) == effectivePort(requested)
    }

    /** 只把 http/https URL 解析成 [Uri]，其余 scheme/非法输入返回 null。 */
    private fun parseWebUri(raw: String?): Uri? {
        if (raw.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(raw)
            if (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)
            ) {
                uri.takeIf { !it.host.isNullOrBlank() }
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 认证网关 TLS pin 严格模式。
     *
     * 返回 true 表示本回调已处理（proceed 或 cancel + 错误层），调用方必须直接 return；
     * 返回 false 表示不适用（无 pin、host 不是会话服务器），继续走 v0.3.0 fail-open。
     *
     * 判断顺序：配置了 pin → error.url host == session.serverUrl host → 计算 leaf
     * 证书 SHA-256 → 与 pin 比对。解析失败按不匹配处理（宁拒不放过）。
     */
    private fun handlePinnedSslError(handler: SslErrorHandler?, error: SslError?): Boolean {
        val configuredPin = pinnedCertSha256?.takeIf { it.isNotBlank() } ?: return false
        val expectedPin = CertPin.normalize(configuredPin)
        val url = try {
            error?.url.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val targetHost = try {
            Uri.parse(url).host
        } catch (t: Throwable) {
            null
        } ?: return false
        val sessionHost = try {
            session.serverUrl?.let { Uri.parse(it).host }
        } catch (t: Throwable) {
            null
        } ?: return false
        if (!targetHost.equals(sessionHost, ignoreCase = true)) return false

        val actualPin = try {
            CertPin.sha256Of(error?.certificate)
        } catch (t: Throwable) {
            Log.w(TAG, "read pinned certificate failed", t)
            null
        }
        val matched = expectedPin != null && CertPin.matches(expectedPin, actualPin)

        try {
            Diagnostics.add(
                "TLS",
                if (matched) "I" else "E",
                "pinned host=$targetHost match=$matched actual=${actualPin ?: "unavailable"}"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "record pinned TLS result failed", t)
        }

        if (matched) {
            Log.i(TAG, "pinned certificate accepted host=$targetHost")
            try {
                handler?.proceed()
            } catch (t: Throwable) {
                Log.e(TAG, "proceed after pinned certificate match failed", t)
            }
            return true
        }

        Log.e(TAG, "pinned certificate mismatch host=$targetHost (connection cancelled)")
        try {
            handler?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel after pinned certificate mismatch failed", t)
        }
        onError(ErrorKind.CERT_CHANGED)
        return true
    }

    /** 从 [SslError] 构造展示用 [TlsInfo]；任何字段异常都降级，不能影响 fail-open。 */
    private fun buildTlsInfo(error: SslError?): TlsInfo {
        val url = try {
            error?.url.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val host = try {
            Uri.parse(url).host.orEmpty()
        } catch (t: Throwable) {
            ""
        }
        val certificate = try {
            error?.certificate
        } catch (t: Throwable) {
            null
        }
        val primaryError = try {
            error?.primaryError ?: -1
        } catch (t: Throwable) {
            -1
        }
        return TlsInfo.fromCertificate(
            cert = certificate,
            host = host,
            primaryErrorName = tlsErrorName(primaryError),
            isHostnameMismatch = primaryError == SslError.SSL_IDMISMATCH,
            url = url,
        )
    }

    /** SslError 主错误码 → 可读常量名（写入诊断/证书详情）。 */
    private fun tlsErrorName(code: Int): String = when (code) {
        SslError.SSL_NOTYETVALID -> "SSL_NOTYETVALID"
        SslError.SSL_EXPIRED -> "SSL_EXPIRED"
        SslError.SSL_IDMISMATCH -> "SSL_IDMISMATCH"
        SslError.SSL_UNTRUSTED -> "SSL_UNTRUSTED"
        SslError.SSL_DATE_INVALID -> "SSL_DATE_INVALID"
        SslError.SSL_INVALID -> "SSL_INVALID"
        else -> "SSL_ERROR_$code"
    }

    /** 回调 Activity 显示证书警告；回调实现异常不得打断页面加载。 */
    private fun notifyTlsWarning(info: TlsInfo) {
        try {
            onTlsWarning(info)
        } catch (t: Throwable) {
            Log.w(TAG, "tls warning callback failed", t)
        }
    }

    /** 回调 Activity 隐藏证书警告；回调实现异常只记日志。 */
    private fun notifyTlsCleared() {
        try {
            onTlsCleared()
        } catch (t: Throwable) {
            Log.w(TAG, "tls cleared callback failed", t)
        }
    }

    private fun openExternally(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${AppConfig.redactForLog(uri.toString())}", e)
        } catch (e: Exception) {
            Log.w(TAG, "open externally failed: ${AppConfig.redactForLog(uri.toString())}", e)
        }
    }

    private fun handleDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val target = url?.trim().orEmpty()
        if (target.isEmpty()) return

        if (target.startsWith("blob:", ignoreCase = true) ||
            target.startsWith("data:", ignoreCase = true)
        ) {
            Log.w(TAG, "unsupported download scheme: blob/data")
            Toast.makeText(activity, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!target.startsWith("http://", ignoreCase = true) &&
            !target.startsWith("https://", ignoreCase = true)
        ) {
            Log.w(TAG, "unsupported download scheme: ${target.substringBefore(':')}")
            Toast.makeText(activity, R.string.download_unsupported, Toast.LENGTH_SHORT).show()
            return
        }

        // API ≤ 28 写公共 Downloads 需要运行时权限；先挂起，等授权后再入队。
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && !hasWriteStoragePermission()) {
            pendingDownloadUrl = target
            pendingDownloadUserAgent = userAgent
            pendingDownloadDisposition = contentDisposition
            pendingDownloadMimeType = mimeType
            val requester = downloadPermissionRequester
            if (requester == null) {
                clearPendingDownload()
                Toast.makeText(activity, R.string.download_failed, Toast.LENGTH_SHORT).show()
                return
            }
            requester()
            return
        }

        startDownload(target, userAgent, contentDisposition, mimeType)
    }

    private fun hasWriteStoragePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 交给 App 内 [Downloader] 下载（后台线程；https 采用与页面层一致的
     * fail-open 策略：接受任意证书；API 29+ 经 MediaStore 落盘）。成功/失败通过主线程回调 toast。
     */
    private fun startDownload(
        target: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val cookie = try {
            cookieManager.getCookie(target)
        } catch (t: Throwable) {
            Log.w(TAG, "read cookie for download failed", t)
            null
        }
        val agent = userAgent?.takeIf { it.isNotBlank() }
            ?: webView.settings.userAgentString
        try {
            Downloader.download(
                activity = activity,
                url = target,
                userAgent = agent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                cookieHeader = cookie,
                onStarted = {
                    showToast(activity.getString(R.string.download_started), long = false)
                },
                onFailed = { reason ->
                    Log.e(
                        TAG,
                        "download failed: ${AppConfig.redactForLog(target)} reason=$reason"
                    )
                    showToast(activity.getString(R.string.download_failed), long = false)
                },
                onDone = { savedName ->
                    val message = try {
                        activity.getString(R.string.download_saved, savedName)
                    } catch (t: Throwable) {
                        Log.w(TAG, "format download message failed", t)
                        activity.getString(R.string.download_started)
                    }
                    showToast(message, long = true)
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "start download failed: ${AppConfig.redactForLog(target)}", t)
            showToast(activity.getString(R.string.download_failed), long = false)
        }
    }

    /** Activity 已销毁/正在结束时不再弹 Toast，避免 WindowLeaked 或崩溃。 */
    private fun showToast(message: String, long: Boolean) {
        if (destroyed) return
        try {
            if (activity.isFinishing || activity.isDestroyed) return
            Toast.makeText(
                activity,
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        } catch (t: Throwable) {
            Log.w(TAG, "show toast failed", t)
        }
    }

    /**
     * document-start 脚本 = 剪贴板 polyfill + getUserMedia 兜底 + 极少量基础 CSS
     * （插入 <style>）。CSS 插入对 DOM 状态容错，失败不影响页面。
     */
    private fun buildInjectionScript(): String {
        val clipboard = JsPatch.clipboardPolyfillScript()
        val getUserMedia = JsPatch.getUserMediaPatchScript()
        val css = JsPatch.baseCss()
        if (css.isBlank()) return clipboard + "\n" + getUserMedia

        val escaped = css
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\r", " ")
            .replace("\n", " ")

        val styleScript = """
            (function () {
              try {
                var css = '$escaped';
                function install() {
                  try {
                    if (document.getElementById('dsh-mobile-base-style')) return;
                    var head = document.head || document.documentElement;
                    if (!head) return;
                    var style = document.createElement('style');
                    style.id = 'dsh-mobile-base-style';
                    style.appendChild(document.createTextNode(css));
                    head.appendChild(style);
                  } catch (e) { }
                }
                if (document.readyState === 'loading' && !document.head) {
                  document.addEventListener('DOMContentLoaded', install, { once: true });
                } else {
                  install();
                }
              } catch (e) { }
            })();
        """.trimIndent()

        return clipboard + "\n" + getUserMedia + "\n" + styleScript
    }

    /** 文件选择回调的单次包装：既保证只交付一次，也顺带清理 pending 引用。 */
    private inner class SingleUseFileCallback(
        private val inner: ValueCallback<Array<Uri>>
    ) : ValueCallback<Array<Uri>> {

        private var delivered = false

        override fun onReceiveValue(value: Array<Uri>?) {
            if (delivered) return
            delivered = true
            if (pendingFileCallback === this) {
                pendingFileCallback = null
            }
            try {
                inner.onReceiveValue(value)
            } catch (t: Throwable) {
                Log.w(TAG, "file callback delivery failed", t)
            }
        }
    }

    private companion object {
        const val TAG = "DSHWebHost"
        const val BRIDGE_NAME = "DSHNative"
    }
}
