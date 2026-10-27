package ai.deepseek.dsh.mobile

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 唯一 Activity：配对视图 / WebView 主界面 / 原生错误覆盖层 / 返回手势（应用菜单）。
 *
 * v0.4.1 起不再有悬浮 ⋮ 按钮：从屏幕边缘内滑（系统返回手势）或三键导航返回键，
 * 在根页面弹出「应用菜单」AlertDialog；菜单包含刷新、重新配对、登出（网关模式）、
 * 诊断日志、关于、退出应用。
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var downloadPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    /** v0.4.0 扫码配对：QrScanActivity 结果（RESULT_OK 时携带 QrPairPayload）。 */
    private lateinit var qrScanLauncher: ActivityResultLauncher<Intent>

    /** 认证网关原生网络客户端（redeem / renew）。 */
    private val authGateClient by lazy { AuthGateClient() }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 扫码配对确认后的兑换进度窗（不可取消，避免重复请求）。 */
    private var redeemDialog: AlertDialog? = null
    private var redeemInFlight = false

    /** 401 自动续期状态：同一时间只跑一次；连续失败后回登录页，防止 401 死循环。 */
    private var authRenewInFlight = false
    private var authRenewAttempts = 0

    /**
     * “登录页 → deviceToken 自动续期”本次 WebHost 生命周期内是否已尝试过。
     * 网关对未认证 HTML 主文档返回 302 登录页（不是 401），需要这个标记避免
     * 续期失败后 reload 登录页 → 再续期的死循环。
     */
    private var loginPageRenewTried = false

    /**
     * 本次页面生命周期内已通过 pin 预检的网关 base；缓存避免每次重建 WebView 都等待一次
     * `/__auth/whoami`。证书错误/重新配对/销毁 Activity 时清空。
     */
    private var pinProbePassedBase: String? = null

    /** WebView 正在加载 /__auth/logout 时记录 baseUrl，页面结束（或超时）后收尾。 */
    private var pendingLogoutBaseUrl: String? = null
    private var logoutFallback: Runnable? = null

    /** 麦克风系统弹窗是否正在显示，避免并发重复 launch。 */
    private var micPermissionInFlight = false

    private var host: WebHost? = null
    private var webContainer: FrameLayout? = null
    private var progressBar: ProgressBar? = null
    private var errorOverlay: View? = null

    /** 页面顶部 TLS / 明文 HTTP 警告条（原 ⋮ 按钮已移除，固定在内容区顶部，非阻断）。 */
    private var warningBanner: TextView? = null

    /** 当前生效的证书警告信息；null 表示没有证书警告。 */
    private var currentTlsInfo: TlsInfo? = null

    /** 会话地址是否为明文 http://，决定无证书警告时是否常驻 HTTP 提示。 */
    private var currentIsHttp = false

    /** 证书详情弹窗；同一页面重复触发时复用它，避免叠窗。 */
    private var tlsDialog: AlertDialog? = null
    private var tlsDialogBody: TextView? = null

    /** 已交给系统文件选择器的 WebView 回调，等待 ActivityResult 返回。 */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 输入法是否可见（v0.4.1）。由 root 的 window insets 监听维护，供返回手势状态机
     * 判断“先收键盘、再后退/弹菜单”。
     */
    private var keyboardVisible = false

    /** 「应用菜单」AlertDialog（v0.4.1，由返回手势从根页面弹出）；返回时先关它。 */
    private var menuDialog: AlertDialog? = null

    /** 首次使用提示是否已排队，避免一次启动里重复 post Toast。 */
    private var backGestureHintPending = false

    private val session: SessionStore by lazy { SessionStore(this) }

    private val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyEdgeToEdge(window)
        setStatusBarAppearance(window, isLightTheme())

        // 必须在 Activity STARTED 之前注册。
        filePickerLauncher = registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            try {
                callback?.onReceiveValue(
                    if (uris.isEmpty()) null else uris.toTypedArray()
                )
            } catch (t: Throwable) {
                Log.w(TAG, "deliver file chooser result failed", t)
            }
        }

        // API ≤ 28 下载到公共 Downloads 前申请存储权限；结果回来再继续被挂起的下载。
        downloadPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            val webHost = host
            if (granted) {
                webHost?.resumePendingDownload()
            } else {
                webHost?.cancelPendingDownload()
                Toast.makeText(
                    this,
                    R.string.download_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 语音输入：网页申请麦克风且系统尚未授权时，由这里弹系统权限框。
        // 授权结果必须回传给 WebHost 里挂起的 PermissionRequest（页面在等）。
        micPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            micPermissionInFlight = false
            val webHost = host
            if (granted) {
                webHost?.grantPendingWebPermission()
            } else {
                webHost?.denyPendingWebPermission()
                Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

        // v0.4.0 扫码配对：QrScanActivity 解析成功后回传 QrPairPayload。
        qrScanLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            onScanResult(result.resultCode, result.data)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(color(R.color.dsh_bg))
        setContentView(root)

        // v0.4.1：返回手势打开「应用菜单」——先维护输入法可见性，再注册返回状态机。
        setupImeTracking()
        setupBackHandling()

        if (session.hasPairing) {
            showWeb()
        } else {
            showPairing()
        }
    }

    // ---------------------------------------------------------------- 配对视图

    private fun showPairing() {
        teardownWeb()
        root.removeAllViews()

        val container = FrameLayout(this)
        container.setBackgroundColor(color(R.color.dsh_bg))
        root.addView(container, FrameLayout.LayoutParams(matchParent, matchParent))
        padByInsets(container)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.setPadding(dp(24), dp(24), dp(24), dp(24))
        container.addView(scroll, FrameLayout.LayoutParams(matchParent, matchParent))

        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        scroll.addView(form, FrameLayout.LayoutParams(matchParent, wrapContent))

        val title = TextView(this)
        title.setText(R.string.pairing_title)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.setTextColor(color(R.color.dsh_text))
        title.gravity = Gravity.CENTER
        title.setTypeface(Typeface.DEFAULT_BOLD)
        form.addView(title, formParams(0))

        val message = TextView(this)
        message.setText(R.string.pairing_message)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        message.setTextColor(color(R.color.dsh_text_secondary))
        message.gravity = Gravity.CENTER
        message.setLineSpacing(0f, 1.2f)
        form.addView(message, formParams(12))

        // v0.4.0：认证网关优先扫码配对；手动输入（旧直连 dsh 模式）保留在下方。
        val scan = makeButton(R.string.pair_scan, primary = true)
        scan.setOnClickListener { startQrScan() }
        form.addView(scan, formParams(20))

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.pairing_url_hint)
        urlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlInput.imeOptions = EditorInfo.IME_ACTION_NEXT
        urlInput.isSingleLine = true
        urlInput.setText(session.serverUrl.orEmpty())
        urlInput.setSelection(urlInput.text?.length ?: 0)
        form.addView(urlInput, formParams(24))

        val httpsHint = TextView(this)
        httpsHint.setText(R.string.pairing_https_hint)
        httpsHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        httpsHint.setTextColor(color(R.color.dsh_text_secondary))
        httpsHint.setLineSpacing(0f, 1.2f)
        form.addView(httpsHint, formParams(8))

        val tokenInput = EditText(this)
        tokenInput.hint = getString(R.string.pairing_token_hint)
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        tokenInput.imeOptions = EditorInfo.IME_ACTION_DONE
        tokenInput.isSingleLine = true
        form.addView(tokenInput, formParams(12))

        val connect = makeButton(R.string.btn_connect, primary = false)
        connect.setOnClickListener {
            attemptPair(
                urlInput.text?.toString().orEmpty(),
                tokenInput.text?.toString().orEmpty()
            )
        }
        form.addView(connect, formParams(24))

        tokenInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptPair(
                    urlInput.text?.toString().orEmpty(),
                    tokenInput.text?.toString().orEmpty()
                )
                true
            } else {
                false
            }
        }
    }

    private fun attemptPair(rawUrl: String, rawToken: String) {
        val normalized = AppConfig.normalizeUrl(rawUrl)
        if (normalized == null) {
            Toast.makeText(this, R.string.invalid_address, Toast.LENGTH_SHORT).show()
            return
        }

        val typedToken = rawToken.trim().takeIf { it.isNotEmpty() }
        val token = typedToken ?: AppConfig.extractToken(rawUrl)

        // 旧直连模式：清掉可能残留的认证网关 pin/标记，避免同 host 误走 pin 严格模式。
        session.saveDirectPairing(normalized, token)
        showWeb()
    }

    // ---------------------------------------------------------------- 扫码配对（v0.4.0）

    private fun startQrScan() {
        try {
            qrScanLauncher.launch(Intent(this, QrScanActivity::class.java))
        } catch (t: Throwable) {
            Log.e(TAG, "launch qr scanner failed", t)
            Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onScanResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK) return
        val payload = readScanPayload(data)
        if (payload == null) {
            Log.w(TAG, "scan result rejected: invalid pairing payload")
            Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_SHORT).show()
            return
        }
        showPairConfirm(payload)
    }

    @Suppress("DEPRECATION")
    private fun readScanPayload(data: Intent?): QrPairPayload? {
        if (data == null) return null
        val direct = data.getSerializableExtra(QrScanActivity.EXTRA_PAYLOAD) as? QrPairPayload
        if (direct != null) return direct
        // 兜底：极端情况下只有原始文本，重新走一遍严格解析。
        return QrPairPayload.parse(data.getStringExtra(QrScanActivity.EXTRA_RAW_PAYLOAD))
    }

    /** 配对确认页：服务器地址 + 证书 SHA-256 + “此设备首次配对”说明。 */
    private fun showPairConfirm(payload: QrPairPayload) {
        val message = getString(
            R.string.pair_confirm_message,
            payload.baseUrl,
            payload.certSha256
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.pair_confirm_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> startRedeem(payload) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startRedeem(payload: QrPairPayload) {
        if (redeemInFlight) return
        redeemInFlight = true
        showRedeemProgress()

        // 二维码的 n=<机器名> 是“服务器”名称，不能当设备名；设备名一律取本机型号。
        val deviceName = defaultDeviceName()

        authGateClient.redeemAsync(
            baseUrl = payload.baseUrl,
            fingerprint = payload.certSha256,
            ticket = payload.ticket,
            deviceName = deviceName
        ) { result ->
            redeemInFlight = false
            dismissRedeemProgress()
            if (isFinishing || isDestroyed) return@redeemAsync

            result.onSuccess { grant ->
                // 只有成功才覆盖旧配置；失败路径绝不清空已有配对。
                session.saveAuthGateProfile(
                    baseUrl = payload.baseUrl,
                    fingerprint = payload.certSha256,
                    deviceToken = grant.deviceToken
                )
                // 配对响应里的 Set-Cookie（设备绑定 cookie）交给 WebView，
                // 否则后续密码/TOTP 登录会被网关判定 device_required。
                storeCookies(payload.baseUrl, grant.cookies)
                Toast.makeText(this, R.string.pair_success, Toast.LENGTH_SHORT).show()
                showWeb()
            }.onFailure { error ->
                val detail = describeAuthError(error)
                Log.w(TAG, "auth gate redeem failed: $detail")
                showPairFailure(detail)
            }
        }
    }

    private fun showRedeemProgress() {
        dismissRedeemProgress()
        val bar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pair_scan_title)
            .setMessage(R.string.loading)
            .setView(bar)
            .setCancelable(false)
            .create()
        redeemDialog = dialog
        try {
            dialog.show()
        } catch (t: Throwable) {
            Log.w(TAG, "show redeem progress failed", t)
            redeemDialog = null
        }
    }

    private fun dismissRedeemProgress() {
        val dialog = redeemDialog ?: return
        redeemDialog = null
        try {
            dialog.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "dismiss redeem progress failed", t)
        }
    }

    private fun showPairFailure(detail: String) {
        val message = if (detail.isBlank()) {
            getString(R.string.pair_failed)
        } else {
            getString(R.string.pair_failed) + "\n\n" + detail
        }
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.pair_failed)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "show pair failure dialog failed", t)
            Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** 失败详情只含 HTTP 状态/错误码或异常类名，绝不输出 ticket/token/cookie。 */
    private fun describeAuthError(error: Throwable): String = when (error) {
        is AuthGateException -> buildString {
            append("HTTP ")
            append(error.status)
            error.errorCode?.takeIf { it.isNotBlank() }?.let {
                append(" · ")
                append(it)
            }
        }
        else -> error.javaClass.simpleName
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL.orEmpty().trim()
        return model.ifBlank { getString(R.string.device_name_default) }
            .take(MAX_DEVICE_NAME_LENGTH)
    }

    // -------------------------------------------------------------- WebView 主界面

    private fun showWeb() {
        val base = session.serverUrl
        if (base.isNullOrBlank()) {
            showPairing()
            return
        }

        teardownWeb()
        root.removeAllViews()

        // 明文 HTTP：语音输入等安全上下文能力不可用（剪贴板有原生桥兜底），常驻提示（只提示不阻断）。
        currentIsHttp = base.startsWith("http://", ignoreCase = true)

        val container = FrameLayout(this)
        container.setBackgroundColor(color(R.color.dsh_bg))
        root.addView(container, FrameLayout.LayoutParams(matchParent, matchParent))
        webContainer = container

        // 只有认证网关 profile 才启用证书 pin 严格模式与 401 自动续期；
        // 旧直连 dsh 模式继续走 v0.3.0 的 fail-open + AUTH 错误层。
        val authGate = session.authGate
        val pinnedCert = if (authGate) session.pinnedCertSha256 else null
        // P2-5 fail-closed：authGate 模式但 pin 缺失/非法时，绝不能把 null pin 传给
        // WebHost（那会退回 v0.3.0 fail-open）。直接按证书变更处理，要求重新扫码。
        if (authGate && CertPin.normalize(pinnedCert) == null) {
            Log.e(TAG, "auth gate profile has no valid pinned certificate; refusing fail-open load")
            showError(ErrorKind.CERT_CHANGED)
            return
        }
        val authExpiredHandler: (() -> Unit)? = if (authGate) {
            { runOnUiThread { handleAuthExpired() } }
        } else {
            null
        }

        val newHost = try {
            WebHost(
                activity = this,
                container = container,
                session = session,
                onProgress = { value -> onWebProgress(value) },
                onPageFinishedCallback = { url -> onWebPageFinished(url) },
                onError = { kind -> showError(kind) },
                onRendererGone = { onRendererGone() },
                filePicker = { acceptTypes, callback ->
                    launchFilePicker(acceptTypes, callback)
                },
                onAudioPermissionNeeded = { onAudioPermissionNeeded() },
                // 非认证网关的 TLS 失败仍 fail-open：WebHost 只回调警告，这里刷新顶部警告条；
                // WebView 回调可能来自非 UI 线程，统一切回主线程操作视图。
                onTlsWarning = { info ->
                    runOnUiThread { showTlsWarning(info) }
                },
                onTlsCleared = {
                    runOnUiThread { clearTlsWarning() }
                },
                // v0.4.0 认证网关：该 host 的 TLS 错误走证书 pin 严格模式。
                pinnedCertSha256 = pinnedCert,
                // 主文档 401：用 deviceToken 后台续期；无凭据/失败则回登录页。
                onAuthExpired = authExpiredHandler
            )
        } catch (t: Throwable) {
            Log.e(TAG, "WebView init failed", t)
            Toast.makeText(this, R.string.webview_init_failed, Toast.LENGTH_LONG).show()
            showPairing()
            return
        }
        host = newHost
        newHost.downloadPermissionRequester = {
            downloadPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.progress = 0
        progress.isIndeterminate = false
        progress.progressTintList = ColorStateList.valueOf(color(R.color.dsh_accent))
        container.addView(
            progress,
            FrameLayout.LayoutParams(matchParent, dp(3), Gravity.TOP)
        )
        progressBar = progress

        // v0.4.1：悬浮 ⋮ 按钮已移除（会遮挡 dsh GUI 右上角「打开右侧边栏」），
        // 应用菜单改由系统返回手势/返回键从根页面弹出（见 setupBackHandling）。
        // 警告条：贴在内容区顶部（不再为按钮让位），整条可点但不遮挡 dsh 顶栏。
        val banner = createWarningBanner()
        container.addView(
            banner,
            FrameLayout.LayoutParams(matchParent, wrapContent, Gravity.TOP)
        )
        warningBanner = banner
        banner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyWarningBannerPadding()
        }
        refreshWarningBanner()

        newHost.applyInsets()

        // 首次进入主界面提示一次「返回手势可打开应用菜单」。
        maybeShowBackGestureHint()

        val token = session.token
        val initialUrl = if (token.isNullOrBlank()) {
            "$base/"
        } else {
            "$base/?token=${Uri.encode(token)}"
        }
        if (!authGate) {
            newHost.load(initialUrl)
            return
        }
        // P2-4：加载 GUI 前先用 pin 严格 TLS 预检 /__auth/whoami。失败说明证书已变
        // 或网关不可达，覆盖 CERT_CHANGED 错误层并提示重新扫码；成功才加载。
        // 本次页面生命周期成功后缓存，避免每次重建都多一次 RTT。
        val pin = CertPin.normalize(pinnedCert)
        if (pin == null) {
            showError(ErrorKind.CERT_CHANGED)
            return
        }
        if (pinProbePassedBase == base) {
            newHost.load(initialUrl)
            return
        }
        progress.visibility = View.VISIBLE
        authGateClient.probeAsync(base, pin) { ok ->
            if (isFinishing || isDestroyed) return@probeAsync
            // 期间用户重新配对/重建了 WebHost：丢弃旧探测结果。
            if (host !== newHost) return@probeAsync
            if (ok) {
                pinProbePassedBase = base
                newHost.load(initialUrl)
            } else {
                Log.e(TAG, "auth gate pin probe failed; blocking page and requiring re-pair")
                showError(ErrorKind.CERT_CHANGED)
            }
        }
    }

    /**
     * WebView 页面申请麦克风但 App 尚未获得 RECORD_AUDIO 时的入口：
     * 已授权则直接放行挂起请求，否则拉起系统权限弹窗（并发时只拉一次）。
     */
    private fun onAudioPermissionNeeded() {
        val webHost = host ?: return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            webHost.grantPendingWebPermission()
            return
        }
        if (micPermissionInFlight) return
        micPermissionInFlight = true
        try {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } catch (t: Throwable) {
            micPermissionInFlight = false
            Log.e(TAG, "launch mic permission request failed", t)
            webHost.denyPendingWebPermission()
            Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private fun onWebProgress(value: Int) {
        val bar = progressBar ?: return
        if (errorOverlay != null) {
            bar.visibility = View.GONE
            return
        }
        bar.progress = value.coerceIn(0, 100)
        bar.visibility = if (value >= 100) View.GONE else View.VISIBLE
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onWebPageFinished(url: String?) {
        if (errorOverlay == null) {
            progressBar?.visibility = View.GONE
        }
        // 只有主文档没有 401 的正常结束才清零续期计数，避免“续期成功但 cookie 无效”
        // 引起的 401 → renew → reload 死循环。
        if (host?.authExpiredForCurrentPage != true) {
            authRenewAttempts = 0
        }
        // 登出页（可能 302 到登录页）加载结束：清本地 cookie 并回到登录页。
        val pendingLogout = pendingLogoutBaseUrl
        if (pendingLogout != null) {
            completeLogout(pendingLogout)
            return
        }
        trackAuthGatePage(url)
    }

    /**
     * 认证网关页面状态跟踪：
     * - 进入非 /__auth/ 路径的 GUI 页 → 说明本次登录成功，置 authSessionEstablished，
     *   后续 session 过期才允许 deviceToken 自动续期；
     * - 登录页 + 已登录过 + 本次生命周期未试过 → 主动 renew（当前网关对 HTML 主文档
     *   返回 302 登录页而不是 401，401 路径只有非 HTML 请求才会触发）。
     */
    private fun trackAuthGatePage(url: String?) {
        if (!session.authGate || url.isNullOrBlank()) return
        // 401 的“页面”不是正常 GUI，不能据此判定登录成功。
        if (host?.authExpiredForCurrentPage == true) return
        val base = session.serverUrl ?: return
        val pageUri = try {
            Uri.parse(url)
        } catch (t: Throwable) {
            return
        }
        val baseUri = try {
            Uri.parse(base)
        } catch (t: Throwable) {
            return
        }
        val pageHost = pageUri.host ?: return
        val baseHost = baseUri.host ?: return
        if (!pageHost.equals(baseHost, ignoreCase = true)) return

        val path = pageUri.path.orEmpty()
        if (path == LOGIN_PATH) {
            maybeRenewFromLoginPage(url)
        } else if (path.isNotEmpty() && !path.startsWith(AUTH_PATH_PREFIX)) {
            // 网关反代出来的 GUI 页面：首次登录完成（或既有 session 有效）。
            session.authSessionEstablished = true
            loginPageRenewTried = false
        }
    }

    private fun maybeRenewFromLoginPage(loginUrl: String) {
        if (loginPageRenewTried) return
        if (!session.authSessionEstablished) return
        val profile = session.authGateProfile() ?: return
        if (profile.deviceToken.isNullOrBlank()) return

        loginPageRenewTried = true
        val target = loginTargetAfterRenew(loginUrl, profile.baseUrl)
        Log.i(TAG, "login page after established session, trying device token renewal")
        handleAuthExpired(renewedPageUrl = target)
    }

    /** 登录 URL 的 `next` 安全地拼回网关 base；非法/绝对地址回退到根路径。 */
    private fun loginTargetAfterRenew(loginUrl: String, baseUrl: String): String {
        val next = try {
            Uri.parse(loginUrl).getQueryParameter("next")
        } catch (t: Throwable) {
            null
        }
        val safePath = next
            ?.trim()
            ?.takeIf { value ->
                value.startsWith("/") &&
                    !value.startsWith("//") &&
                    !value.startsWith(AUTH_PATH_PREFIX) &&
                    value.none { it.isWhitespace() || it.code < 0x20 }
            }
            ?: "/"
        return baseUrl.trimEnd('/') + safePath
    }

    // -------------------------------------------------- 认证网关会话（v0.4.0）

    /**
     * 主文档 401 / 登录页跳转：
     * - profile 有 deviceToken → 后台 POST /__auth/renew（TLS 走证书 pin）→ 成功注入新
     *   session cookie 并 reload（[renewedPageUrl] 非空时改为加载该页，用于登录页续期
     *   后直接进入 GUI）；失败/连续失败 → 加载 /__auth/login；
     * - 无 deviceToken（旧直连模式或设备凭据丢失）→ 直接加载登录页。
     */
    private fun handleAuthExpired(renewedPageUrl: String? = null) {
        // 登出流程中出现的 401 不续期（否则会把刚清掉的会话又建回来），直接回登录页。
        val logoutBase = pendingLogoutBaseUrl
        if (logoutBase != null) {
            completeLogout(logoutBase)
            return
        }
        val base = session.serverUrl
        if (base.isNullOrBlank()) {
            showPairing()
            return
        }
        val profile = session.authGateProfile()
        if (profile == null || profile.deviceToken.isNullOrBlank()) {
            openLoginPage(base)
            return
        }
        if (authRenewInFlight) return
        if (authRenewAttempts >= MAX_AUTH_RENEW_ATTEMPTS) {
            authRenewAttempts = 0
            Log.w(TAG, "auth renew attempts exhausted, fallback to login page")
            openLoginPage(base)
            return
        }

        authRenewInFlight = true
        authRenewAttempts++
        Toast.makeText(this, R.string.auth_renewing, Toast.LENGTH_SHORT).show()
        authGateClient.renewAsync(
            baseUrl = base,
            fingerprint = profile.pinnedCertSha256,
            deviceToken = profile.deviceToken
        ) { outcome ->
            authRenewInFlight = false
            if (isFinishing || isDestroyed) return@renewAsync
            // 用户已点登出：不要用续期结果把页面切回 GUI。
            if (pendingLogoutBaseUrl != null) return@renewAsync
            if (outcome.ok) {
                storeCookies(base, outcome.cookies)
                val target = renewedPageUrl
                if (target != null) {
                    host?.load(target)
                } else {
                    host?.reload()
                }
            } else {
                authRenewAttempts = 0
                Log.w(TAG, "session renew failed, fallback to login page")
                openLoginPage(base)
            }
        }
    }

    private fun openLoginPage(baseUrl: String) {
        dismissError()
        val webHost = host
        if (webHost == null) {
            showWeb()
            return
        }
        webHost.load("$baseUrl$LOGIN_PATH")
    }

    /**
     * ⋮ → 登出：用 WebView 以 POST 打开 `/__auth/logout`（服务端只接受 POST；GET 会 405），
     * 让网关真正删除 session 并清 WebView 里的 session cookie，再回到登录页；
     * deviceToken 与设备绑定 cookie 保留。登出页可能 302 到登录页，onPageFinished 不一定
     * 停在 logout URL，因此用 [pendingLogoutBaseUrl] 标记“第一页结束即收尾”，并加 4s 超时兜底。
     */
    private fun logout() {
        val base = session.serverUrl
        val webHost = host
        if (base.isNullOrBlank() || webHost == null) {
            Toast.makeText(this, R.string.logout_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 明确登出后不允许“登录页 → 自动续期”把会话又建回来；下次成功登录会重新置位。
        session.authSessionEstablished = false
        loginPageRenewTried = true
        pendingLogoutBaseUrl = base
        scheduleLogoutFallback(base)
        if (!webHost.postUrl("$base$LOGOUT_PATH")) {
            Log.w(TAG, "webview post logout failed")
            completeLogout(base)
        }
    }

    private fun scheduleLogoutFallback(base: String) {
        cancelLogoutFallback()
        val runnable = Runnable { completeLogout(base) }
        logoutFallback = runnable
        mainHandler.postDelayed(runnable, LOGOUT_FALLBACK_MS)
    }

    private fun cancelLogoutFallback() {
        logoutFallback?.let { mainHandler.removeCallbacks(it) }
        logoutFallback = null
    }

    private fun completeLogout(baseUrl: String) {
        if (pendingLogoutBaseUrl == null) return
        pendingLogoutBaseUrl = null
        cancelLogoutFallback()
        clearCookiesFor(baseUrl)
        authRenewAttempts = 0
        val webHost = host
        if (webHost == null) {
            showPairing()
            return
        }
        dismissError()
        try {
            webHost.load("$baseUrl$LOGIN_PATH")
        } catch (t: Throwable) {
            Log.e(TAG, "load login page after logout failed", t)
            Toast.makeText(this, R.string.logout_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清该 host 的 WebView cookie。
     *
     * [keepDeviceCookie] 默认 true：保留 `dsh_gate_device`（扫码配对时由 redeem 响应种下
     * 的设备绑定 cookie）。网关登录要求“已绑定设备”，若把设备 cookie 一并清掉，登出后的
     * 用户名/密码/TOTP 登录会直接 `device_required` 失败；重新配对时传 false，连设备
     * 绑定一起清掉，确保新配对必须重新登录。
     */
    private fun clearCookiesFor(baseUrl: String, keepDeviceCookie: Boolean = true) {
        try {
            val manager = CookieManager.getInstance()
            val cookies = manager.getCookie(baseUrl).orEmpty()
            for (part in cookies.split(';')) {
                val name = part.trim().substringBefore('=').trim()
                if (name.isEmpty()) continue
                if (keepDeviceCookie && name.endsWith(DEVICE_COOKIE_NAME)) continue
                manager.setCookie(baseUrl, "$name=; Max-Age=0; Path=/")
            }
            manager.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "clear webview cookies failed", t)
        }
    }

    /** 把原生 HTTP 响应里的 Set-Cookie 注入 WebView（不打印 cookie 内容）。 */
    private fun storeCookies(baseUrl: String, cookies: List<String>) {
        if (cookies.isEmpty()) return
        try {
            val manager = CookieManager.getInstance()
            for (cookie in cookies) {
                if (cookie.isBlank()) continue
                manager.setCookie(baseUrl, cookie)
            }
            manager.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "store auth gate cookies failed", t)
        }
    }

    // ------------------------------------------------- 应用菜单（v0.4.1 返回手势）

    /**
     * 「应用菜单」：v0.4.1 移除悬浮 ⋮ 按钮，改为根页面按返回（系统返回手势/三键返回键）
     * 弹出本列表。用 [AlertDialog.Builder.setItems] 实现，不引入新依赖。
     *
     * 菜单项、顺序与 v0.4.0 的 PopupMenu 一致，末尾新增「退出应用」。
     * 打开期间记录 [menuDialog]，返回键先关菜单（正常情况下 Dialog 会自行消费返回事件，
     * 这里是防御性处理）。
     */
    private fun showMenu() {
        if (isFinishing || isDestroyed) return
        dismissMenuIfShowing()

        val actions = mutableListOf<Int>()
        val labels = mutableListOf<CharSequence>()
        actions += MENU_REFRESH
        labels += getString(R.string.menu_refresh)
        actions += MENU_PAIR
        labels += getString(R.string.menu_pair)
        // 登出只在认证网关模式有意义（旧直连模式没有网关登录会话）。
        if (session.authGate) {
            actions += MENU_LOGOUT
            labels += getString(R.string.menu_logout)
        }
        actions += MENU_DIAGNOSTICS
        labels += getString(R.string.menu_diagnostics)
        actions += MENU_ABOUT
        labels += getString(R.string.menu_about)
        actions += MENU_EXIT
        labels += getString(R.string.menu_exit_app)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.app_menu_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val action = actions.getOrNull(which) ?: return@setItems
                runMenuAction(action)
            }
            .create()
        dialog.setOnDismissListener {
            if (menuDialog === dialog) {
                menuDialog = null
            }
        }
        menuDialog = dialog
        try {
            dialog.show()
            Log.i(TAG, "app menu shown via back gesture")
        } catch (t: Throwable) {
            Log.w(TAG, "show app menu failed", t)
            menuDialog = null
        }
    }

    /** 菜单动作分发：与 v0.4.0 PopupMenu 的点击行为一一对应。 */
    private fun runMenuAction(action: Int) {
        when (action) {
            MENU_REFRESH -> {
                dismissError()
                host?.reload()
            }
            MENU_PAIR -> pairAgain()
            MENU_LOGOUT -> logout()
            MENU_DIAGNOSTICS -> showDiagnostics()
            MENU_ABOUT -> showAbout()
            MENU_EXIT -> exitApp()
            else -> Log.w(TAG, "unknown app menu action: $action")
        }
    }

    /** 关闭应用菜单；返回 true 表示确实关掉了一个正在显示的菜单。 */
    private fun dismissMenuIfShowing(): Boolean {
        val dialog = menuDialog ?: return false
        if (!dialog.isShowing) {
            menuDialog = null
            return false
        }
        menuDialog = null
        try {
            dialog.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "dismiss app menu failed", t)
        }
        return true
    }

    /** 退出应用：结束本任务栈里的所有 Activity（不卸载、不清数据）。 */
    private fun exitApp() {
        Log.i(TAG, "exit app requested from app menu")
        try {
            finishAffinity()
        } catch (t: Throwable) {
            Log.w(TAG, "finishAffinity failed, fallback to finish()", t)
            finish()
        }
    }

    /**
     * 首次进入主界面时提示一次「返回手势可打开应用菜单」。
     *
     * 用 SharedPreferences（[KEY_BACK_GESTURE_HINT]）记录已提示，只弹一次；延迟到页面
     * 基本就绪再 Toast，避免和首屏加载/配对成功提示叠在同一瞬间。
     */
    private fun maybeShowBackGestureHint() {
        if (backGestureHintPending) return
        val prefs = getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACK_GESTURE_HINT, false)) return
        backGestureHintPending = true
        mainHandler.postDelayed({
            backGestureHintPending = false
            if (isFinishing || isDestroyed) return@postDelayed
            try {
                prefs.edit().putBoolean(KEY_BACK_GESTURE_HINT, true).apply()
            } catch (t: Throwable) {
                Log.w(TAG, "persist back gesture hint flag failed", t)
            }
            Toast.makeText(this, R.string.back_gesture_hint, Toast.LENGTH_LONG).show()
            Log.i(TAG, "back gesture hint shown once")
        }, BACK_GESTURE_HINT_DELAY_MS)
    }

    // -------------------------------------------------------- TLS / HTTP 警告条

    /**
     * 顶部警告条（代码构建）：默认隐藏；TLS 证书不受信任或明文 HTTP 时显示。
     * 非阻断——固定在内容区顶部；显示时由 [applyWarningBannerPadding] 下移 WebView
     * 内容区，不覆盖页面、不截获页面触摸。证书可点开详情。
     */
    private fun createWarningBanner(): TextView {
        return TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(WARNING_TEXT_COLOR)
            setBackgroundColor(WARNING_BG_COLOR)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            // 高于 WebView（0），低于后续弹出的错误覆盖层。
            elevation = dp(4).toFloat()
            visibility = View.GONE
            setOnClickListener {
                currentTlsInfo?.let { info -> showTlsDetails(info) }
            }
        }
    }

    /**
     * 按当前状态刷新警告条：证书警告优先；否则明文 HTTP 常驻提示；都没有则隐藏。
     * 只有存在证书详情时才可点击（HTTP 提示没有证书可看）。
     */
    private fun refreshWarningBanner() {
        val banner = warningBanner ?: return
        val info = currentTlsInfo
        if (info != null) {
            // TlsInfo.host 非空；万一为空白也用 ? 占位，不让横幅文字变空。
            val host = info.host.ifBlank { "?" }
            banner.text = getString(R.string.tls_warning_banner, host)
            banner.isClickable = true
            banner.isFocusable = true
            banner.visibility = View.VISIBLE
            applyWarningBannerPadding()
            return
        }
        if (currentIsHttp) {
            banner.setText(R.string.http_warning_banner)
            banner.isClickable = false
            banner.isFocusable = false
            banner.visibility = View.VISIBLE
            applyWarningBannerPadding()
            return
        }
        banner.visibility = View.GONE
        banner.isClickable = false
        banner.isFocusable = false
        applyWarningBannerPadding()
    }

    /**
     * 警告条不覆盖页面：显示时把 WebView 内容整体下移到警告条底边以下，
     * 隐藏时恢复 0。[warningBanner] 自身固定在内容区顶部（topMargin 为 0，不依赖已移除的 ⋮ 按钮）。
     *
     * 注意 WebHost 的 insets padding 加在 container 上，这里只动 WebView 自身。
     */
    private fun applyWarningBannerPadding() {
        val banner = warningBanner ?: return
        val webView = host?.webView ?: return
        val paddingTop = if (banner.visibility == View.VISIBLE) {
            // banner.bottom 相对 container，含 container 的 insets padding；减去 WebView.top
            // 得到“相对 WebView 内容区”的占位高度（实测条高），避免重复计入状态栏。
            val bottom = banner.bottom
            if (bottom > 0) {
                (bottom - webView.top).coerceAtLeast(0)
            } else {
                banner.measuredHeight
            }
        } else {
            0
        }
        if (webView.paddingTop != paddingTop) {
            webView.setPadding(0, paddingTop, 0, 0)
        }
    }

    /** WebHost 的 onTlsWarning 入口（已切回主线程）：只警告，连接照常继续。 */
    private fun showTlsWarning(info: TlsInfo) {
        currentTlsInfo = info
        refreshWarningBanner()
        // 详情窗已打开时只更新内容，不重复弹窗。
        val dialog = tlsDialog
        if (dialog != null && dialog.isShowing) {
            tlsDialogBody?.text = info.toDisplayText()
        }
    }

    /** WebHost 的 onTlsCleared 入口（已切回主线程）：撤销证书警告，HTTP 提示保留。 */
    private fun clearTlsWarning() {
        currentTlsInfo = null
        refreshWarningBanner()
        // 页面跳转/刷新后不再保留旧证书详情窗，避免展示与当前页面不一致的信息。
        dismissTlsDialog()
    }

    /** 证书详情弹窗：等宽、可选中，可一键复制全文。 */
    private fun showTlsDetails(info: TlsInfo) {
        currentTlsInfo = info
        val existing = tlsDialog
        if (existing != null && existing.isShowing) {
            tlsDialogBody?.text = info.toDisplayText()
            return
        }

        val detail = info.toDisplayText()
        val body = TextView(this).apply {
            text = detail
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(R.color.dsh_text))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setLineSpacing(0f, 1.15f)
        }
        val scroll = ScrollView(this).apply {
            addView(body, ViewGroup.LayoutParams(matchParent, wrapContent))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.tls_warning_title)
            .setView(scroll)
            .setPositiveButton(R.string.tls_warning_copy) { _, _ ->
                copyTlsDetails(currentTlsInfo?.toDisplayText() ?: detail)
            }
            .setNegativeButton(R.string.tls_warning_close, null)
            .create()
        dialog.setOnDismissListener {
            if (tlsDialog === dialog) {
                tlsDialog = null
                tlsDialogBody = null
            }
        }
        tlsDialog = dialog
        tlsDialogBody = body
        try {
            dialog.show()
        } catch (t: Throwable) {
            Log.w(TAG, "show tls details dialog failed", t)
            tlsDialog = null
            tlsDialogBody = null
        }
    }

    /** 证书详情写入系统剪贴板并 toast；失败只记日志，不打断使用。 */
    private fun copyTlsDetails(content: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (manager == null) {
                Log.w(TAG, "ClipboardManager unavailable for tls details")
                return
            }
            manager.setPrimaryClip(ClipData.newPlainText(TLS_CLIP_LABEL, content))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "copy tls details failed", t)
        }
    }

    /** 重建/销毁页面前关掉证书详情窗，避免旧窗口悬挂或泄漏。 */
    private fun dismissTlsDialog() {
        val dialog = tlsDialog
        tlsDialog = null
        tlsDialogBody = null
        try {
            dialog?.dismiss()
        } catch (t: Throwable) {
            Log.w(TAG, "dismiss tls details dialog failed", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun showAbout() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.4.1"
        } catch (t: Throwable) {
            "0.4.1"
        }
        val message = buildString {
            append(getString(R.string.app_name))
            append(' ')
            append(versionName)
            append('\n')
            append(packageName)
            append("\nAndroid ")
            append(Build.VERSION.RELEASE)
            session.serverUrl?.let {
                append('\n')
                append(it)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_about)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * 诊断日志：真机没有 adb 时，把网页 console / JS 错误 / 原生桥日志
     * 复制出来给开发者。内容来自 [Diagnostics] 的内存环形缓冲，不落盘。
     */
    private fun showDiagnostics() {
        val memoryDump = try {
            Diagnostics.dump()
        } catch (t: Throwable) {
            Log.w(TAG, "collect diagnostics failed", t)
            ""
        }
        val content = memoryDump.ifBlank { getString(R.string.diagnostics_empty) }

        val logView = TextView(this).apply {
            text = content
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(R.color.dsh_text))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setLineSpacing(0f, 1.15f)
        }
        val scroll = ScrollView(this).apply {
            addView(
                logView,
                ViewGroup.LayoutParams(matchParent, wrapContent)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(scroll)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                copyDiagnostics(content)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 把诊断内容写入系统剪贴板并 Toast 提示；失败只记日志，不打断对话。 */
    private fun copyDiagnostics(content: String) {
        try {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (manager == null) {
                Log.w(TAG, "ClipboardManager unavailable for diagnostics")
                return
            }
            manager.setPrimaryClip(ClipData.newPlainText(DIAGNOSTICS_CLIP_LABEL, content))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.w(TAG, "copy diagnostics failed", t)
        }
    }

    // ------------------------------------------------------------------ 错误覆盖层

    private fun showError(kind: ErrorKind) {
        val container = webContainer ?: return
        progressBar?.visibility = View.GONE
        if (errorOverlay != null) return

        val overlay = FrameLayout(this)
        overlay.setBackgroundColor(color(R.color.dsh_bg))
        overlay.isClickable = true
        overlay.isFocusable = true
        // 高于警告条（4dp），证书提示不会盖住错误层。
        overlay.elevation = dp(8).toFloat()

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(dp(32), dp(32), dp(32), dp(32))
        overlay.addView(box, FrameLayout.LayoutParams(matchParent, wrapContent, Gravity.CENTER))

        val title = TextView(this)
        title.setText(R.string.app_name)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextColor(color(R.color.dsh_error))
        title.gravity = Gravity.CENTER
        box.addView(title, formParams(0))

        val message = TextView(this)
        message.setText(
            when (kind) {
                ErrorKind.AUTH -> R.string.error_auth
                ErrorKind.RENDERER -> R.string.error_renderer
                // v0.4.0 认证网关证书变更：pin 严格模式下 cancel，提示重新扫码。
                ErrorKind.CERT_CHANGED -> R.string.cert_changed
                // 普通服务器证书问题仍不进错误层（fail-open 只显示顶部警告条），其余归网络。
                else -> R.string.error_network
            }
        )
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        message.setTextColor(color(R.color.dsh_text))
        message.gravity = Gravity.CENTER
        message.setLineSpacing(0f, 1.2f)
        box.addView(message, formParams(12))

        val retryButton = makeButton(R.string.btn_retry, primary = true)
        retryButton.setOnClickListener {
            dismissError()
            retryAfterError(kind)
        }
        box.addView(retryButton, formParams(24))

        val pairButton = makeButton(R.string.btn_pair_again, primary = false)
        pairButton.setOnClickListener { pairAgain() }
        box.addView(pairButton, formParams(12))

        container.addView(overlay, FrameLayout.LayoutParams(matchParent, matchParent))
        errorOverlay = overlay
    }

    /**
     * 错误覆盖层“重试”：渲染进程死亡则重建 WebHost；鉴权失败用带 token 的入口
     * URL 重试；其余错误刷新当前页（[WebHost.reload] 对 about:blank 会回退入口 URL）。
     */
    private fun retryAfterError(kind: ErrorKind) {
        if (kind == ErrorKind.RENDERER) {
            showWeb()
            return
        }
        val webHost = host
        if (webHost == null) {
            showWeb()
        } else if (kind == ErrorKind.AUTH && webHost.hasAuthUrl) {
            webHost.retryAuth()
        } else {
            webHost.reload()
        }
    }

    /**
     * 渲染进程死亡：WebHost 已销毁坏 WebView 并置空，这里清掉相关视图，
     * 显示错误覆盖层；重试按钮走 [showWeb] 重建。
     */
    private fun onRendererGone() {
        host = null
        webContainer?.removeAllViews()
        progressBar = null
        errorOverlay = null
        warningBanner = null
        currentTlsInfo = null
        dismissTlsDialog()
        showError(ErrorKind.RENDERER)
    }

    private fun dismissError() {
        val overlay = errorOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        errorOverlay = null
    }

    private fun pairAgain() {
        // 重新配对：先清该 host 的全部 WebView cookie（含 session/设备绑定），
        // 否则旧 session 仍有效会让新配对跳过首次用户名/密码/TOTP 登录。
        session.serverUrl?.let { clearCookiesFor(it, keepDeviceCookie = false) }
        session.clear()
        dismissRedeemProgress()
        redeemInFlight = false
        authRenewInFlight = false
        authRenewAttempts = 0
        loginPageRenewTried = false
        pinProbePassedBase = null
        showPairing()
    }

    // ------------------------------------------------------------------ 文件选择

    private fun launchFilePicker(
        acceptTypes: Array<String>,
        callback: ValueCallback<Array<Uri>>
    ) {
        try {
            pendingFileCallback?.onReceiveValue(null)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel previous file callback failed", t)
        }
        pendingFileCallback = callback

        try {
            // WebHost 已完成 acceptTypes → MIME 规范化；这里只做空列表兜底。
            val types = if (acceptTypes.isEmpty()) arrayOf("*/*") else acceptTypes
            filePickerLauncher.launch(types)
        } catch (t: Throwable) {
            Log.e(TAG, "launch file picker failed", t)
            val pending = pendingFileCallback
            pendingFileCallback = null
            try {
                pending?.onReceiveValue(null)
            } catch (inner: Throwable) {
                Log.w(TAG, "cancel file callback failed", inner)
            }
            Toast.makeText(this, R.string.file_picker_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------- 返回手势 / 生命周期

    /**
     * 维护 [keyboardVisible]：用 window insets 监听，而不是 InputMethodManager 猜测。
     *
     * - Android 11+（API 30）：`ime()` 是独立 inset 类型，[WindowInsetsCompat.isVisible]
     *   直接读系统可见性；
     * - Android 10 及以下：系统没有 `ime()` 类型，androidx 会把 adjustResize 压缩出的
     *   键盘高度 / root 可见区域折算到 `ime()` inset 上（[WindowInsetsCompat] 内部兼容），
     *   因此统一用 `isVisible(ime())` 判断，低版本也不会漏判。
     *
     * 监听器原样返回 insets，不影响子 view（container 自己还有 padByInsets）。
     */
    private fun setupImeTracking() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 收起输入法（API 26+ 兼容：旧版本由 WindowInsetsControllerCompat 走 InputMethodManager）。 */
    private fun hideIme() {
        // 立即置 false：hide() 有动画，快速连按返回时不应把下一次按键再吞掉。
        keyboardVisible = false
        try {
            WindowInsetsControllerCompat(window, root)
                .hide(WindowInsetsCompat.Type.ime())
        } catch (t: Throwable) {
            Log.w(TAG, "hide ime failed", t)
        }
    }

    /**
     * 返回事件状态机（v0.4.1）：
     * 1. 错误覆盖层可见 → 关闭错误层（渲染进程已死则重建 WebView，避免留下空白页）；
     * 2. 输入法可见 → 收起输入法；
     * 3. WebView 有历史 → [android.webkit.WebView.goBack]；
     * 4. 应用菜单正在显示 → 关闭菜单（正常情况下 Dialog 自己消费返回事件，这里是防御）；
     * 5. 否则 → 弹出「应用菜单」。dsh SPA 无历史时每次返回都会弹菜单（符合预期）。
     *
     * 回调在 Activity 销毁时由 OnBackPressedDispatcher 自动移除。
     */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (errorOverlay != null) {
                    dismissError()
                    if (host == null) {
                        // 渲染进程死亡（overlay 后没有 WebView）：重建，别停在空白页。
                        showWeb()
                    }
                    return
                }

                if (keyboardVisible) {
                    hideIme()
                    return
                }

                val web = host?.webView
                if (web != null && web.canGoBack()) {
                    web.goBack()
                    return
                }

                if (dismissMenuIfShowing()) return

                showMenu()
            }
        })
    }

    private fun teardownWeb() {
        dismissMenuIfShowing()
        try {
            pendingFileCallback?.onReceiveValue(null)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel pending file chooser failed", t)
        }
        pendingFileCallback = null

        host?.onDestroy()
        host = null
        webContainer = null
        progressBar = null
        errorOverlay = null

        warningBanner = null
        currentTlsInfo = null
        currentIsHttp = false
        dismissTlsDialog()

        // 认证网关会话/登出状态不跨 WebHost 生命周期。
        pendingLogoutBaseUrl = null
        cancelLogoutFallback()
        authRenewInFlight = false
        authRenewAttempts = 0
        loginPageRenewTried = false
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
    }

    override fun onDestroy() {
        pinProbePassedBase = null
        redeemInFlight = false
        dismissRedeemProgress()
        dismissMenuIfShowing()
        pendingLogoutBaseUrl = null
        cancelLogoutFallback()
        teardownWeb()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 页面 DOM / 历史不做持久化：Activity 重建后由 SessionStore + Cookie
        // 重新加载远程页面即可。
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val light = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
        setStatusBarAppearance(window, light)
    }

    // ------------------------------------------------------------------ 工具

    private fun isLightTheme(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode != Configuration.UI_MODE_NIGHT_YES
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun formParams(topMarginDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = dp(topMarginDp)
        }

    private fun makeButton(textRes: Int, primary: Boolean): Button {
        val button = Button(this)
        button.setText(textRes)
        button.isAllCaps = false
        button.minHeight = dp(48)
        val primaryText = if (isLightTheme()) Color.WHITE else color(R.color.dsh_bg)
        button.setTextColor(if (primary) primaryText else color(R.color.dsh_text))
        button.backgroundTintList = ColorStateList.valueOf(
            color(if (primary) R.color.dsh_accent else R.color.dsh_surface)
        )
        button.layoutParams = formParams(16)
        return button
    }

    private companion object {
        const val TAG = "DSHMobile"
        const val MENU_REFRESH = 1
        const val MENU_PAIR = 2
        const val MENU_DIAGNOSTICS = 3
        const val MENU_ABOUT = 4
        const val MENU_LOGOUT = 5
        const val MENU_EXIT = 6
        const val DIAGNOSTICS_CLIP_LABEL = "DSH Mobile diagnostics"
        const val TLS_CLIP_LABEL = "DSH Mobile TLS certificate"

        /** 认证网关路径（与设计契约 §4 一致）。 */
        const val LOGIN_PATH = "/__auth/login"
        const val LOGOUT_PATH = "/__auth/logout"

        /** 认证网关自有路径前缀；反代出来的 GUI 页面不以它开头。 */
        const val AUTH_PATH_PREFIX = "/__auth/"

        /** 网关设备绑定 cookie 名；登出清 cookie 时保留它，登录页才能通过 device 校验。 */
        const val DEVICE_COOKIE_NAME = "dsh_gate_device"

        /** 401 自动续期最多尝试次数，超过后回登录页，避免死循环。 */
        const val MAX_AUTH_RENEW_ATTEMPTS = 2

        /** 登出页加载超时（ms）：页面 302/失败时仍能收尾回登录页。 */
        const val LOGOUT_FALLBACK_MS = 4000L

        const val MAX_DEVICE_NAME_LENGTH = 64

        /** 首次「返回手势打开应用菜单」提示的 SharedPreferences 键与延迟（ms）。 */
        const val KEY_BACK_GESTURE_HINT = "back_gesture_menu_hint_v1"
        const val BACK_GESTURE_HINT_DELAY_MS = 1200L

        /** 警告条配色：黄底深棕字（不新增颜色资源，保持单文件可读）。 */
        val WARNING_BG_COLOR = 0xFFFFF3CD.toInt()
        val WARNING_TEXT_COLOR = 0xFF7A4F01.toInt()
    }
}
