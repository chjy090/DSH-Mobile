package ai.deepseek.dsh.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 唯一 Activity：配对视图 / WebView 主界面 / 原生错误覆盖层 / 返回手势（应用菜单）。
 *
 * v0.4.1 起不再有悬浮 ⋮ 按钮：从屏幕边缘内滑（系统返回手势）或三键导航返回键，
 * 在根页面弹出「应用菜单」AlertDialog。v0.4.2 起认证网关模式支持启动静默登录、
 * 前台恢复自动续期，并把菜单拆成「退出当前会话」与「忘记此设备（重新配对）」。
 * v0.4.3 起配对页新增「从相册选择二维码」；直连模式（地址 + token）默认隐藏，
 * 在「关于」里连续点击版本号 7 次解锁/锁定（状态存独立 SharedPreferences）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var downloadPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var micPermissionLauncher: ActivityResultLauncher<String>

    /** v0.4.0 扫码配对：QrScanActivity 结果（RESULT_OK 时携带 QrPairPayload）。 */
    private lateinit var qrScanLauncher: ActivityResultLauncher<Intent>

    /**
     * v0.4.3 相册二维码：`PickVisualMedia` 在 Android 13+ 用系统照片选择器，
     * 低版本/无照片选择器时由 androidx 回退成 `ACTION_OPEN_DOCUMENT`（image 类型），
     * 两条路径都不需要存储权限。
     */
    private lateinit var qrGalleryLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    /**
     * v0.4.3 相册二维码解码状态。
     *
     * 解码全部在 [galleryDecodeExecutor] 后台线程完成（读图/降采样/EXIF），ML Kit 识别是异步
     * Task；[galleryDecodeToken] 在 Activity 销毁、新的选图或超时后自增，迟到的解码/识别
     * 回调用它作废旧请求，避免向已销毁的 Activity 弹 Toast/对话框。
     */
    private var galleryDecodeExecutor: ExecutorService? = null
    private var galleryScanner: BarcodeScanner? = null
    private var galleryDecodeToken = 0
    private var galleryDecodeWatchdog: Runnable? = null
    private var galleryDecodeInFlight = false

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
     * v0.4.2 启动静默登录 / 前台恢复续期状态。
     *
     * [authRenewInFlight] 是所有 renew（启动静默、401、前台）共享的互斥标记，
     * 保证同一时刻最多一个续期请求；[startupRenewInFlight]/[foregroundRenewInFlight]
     * 只用于区分来源与排查日志。
     */
    private var startupRenewInFlight = false
    private var foregroundRenewInFlight = false
    private var lastForegroundRenewAttemptAt = 0L

    /** v0.4.2：当前后台 renew 的 Future；Activity 销毁/退出/重新配对时尽力取消。 */
    private var authRenewFuture: Future<*>? = null

    /**
     * v0.4.2 renew 回调代际：退出登录、重新配对、忘记设备或 WebHost 重建时自增，
     * 迟到的续期结果（含 Activity 销毁后才回到主线程的）会被直接丢弃。
     */
    private var authRenewGeneration = 0

    /** v0.4.2：前台续期/导航发现设备凭据失效后，下一次前台强制回登录页。 */
    private var forceLoginOnNextResume = false

    /**
     * v0.4.2：[forceLoginOnNextResume] 的原因。true 表示 401/设备失效，
     * 下次强制登录时应提示「需要重新扫码配对」而不是泛化的“请重新登录”。
     */
    private var forceLoginDeviceRejected = false
    private var forceLoginErrorCode: String? = null

    /** v0.4.2：前台续期期间出现的主文档 401，等续期结果出来后再决定 reload/回登录页。 */
    private var authExpiredDuringRenew = false

    /** v0.4.2：启动静默续期因网络/TLS 失败；错误层的「重试」应重新尝试静默续期。 */
    private var startupRenewRetryAvailable = false

    /**
     * “登录页 → deviceToken 自动续期”本次 WebHost 生命周期内是否已尝试过。
     * 网关对未认证 HTML 主文档返回 302 登录页（不是 401），需要这个标记避免
     * 续期失败后 reload 登录页 → 再续期的死循环。v0.4.2 启动静默续期开始前也会置位。
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

    /**
     * v0.4.2：顶部条容器（TLS/HTTP 警告条 + 认证状态条）。
     * WebView 内容区按它的实际高度整体下移，两条同时显示时不会互相遮挡。
     */
    private var topBannerContainer: LinearLayout? = null

    /** v0.4.2：认证状态条（正在静默登录 / 续期失败提示），可点击重试或重新配对。 */
    private var authNoticeBanner: TextView? = null
    private var authNoticeAction: (() -> Unit)? = null

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

    /** v0.4.3：「关于」版本号连续点击计数与上次点击时间（elapsedRealtime）。 */
    private var versionTapCount = 0
    private var lastVersionTapAt = 0L

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

        // v0.4.3 相册二维码：用户取消时 uri 为 null（静默返回配对页）。
        qrGalleryLauncher = registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            onGalleryImagePicked(uri)
        }

        root = FrameLayout(this)
        root.setBackgroundColor(color(R.color.dsh_bg))
        setContentView(root)

        // v0.4.1：返回手势打开「应用菜单」——先维护输入法可见性，再注册返回状态机。
        setupImeTracking()
        setupBackHandling()

        if (session.hasPairing) {
            // v0.4.2：冷启动/首次进入主界面时优先尝试 deviceToken 静默登录。
            showWeb(startupRenew = true)
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
        message.setText(R.string.pairing_message_scan)
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        message.setTextColor(color(R.color.dsh_text_secondary))
        message.gravity = Gravity.CENTER
        message.setLineSpacing(0f, 1.2f)
        form.addView(message, formParams(12))

        // v0.4.0：认证网关扫码配对（推荐）。
        val scan = makeButton(R.string.pair_scan, primary = true)
        scan.setOnClickListener { startQrScan() }
        form.addView(scan, formParams(20))

        // v0.4.3：从相册图片识别配对二维码，供相机不可用/二维码已在相册（截图）时使用。
        val gallery = makeButton(R.string.pair_gallery, primary = false)
        gallery.setOnClickListener { startQrGalleryPick() }
        form.addView(gallery, formParams(12))

        // v0.4.3：直连模式（地址 + token）默认隐藏；「关于」里连点版本号 7 次解锁后出现。
        // 未解锁时配对页只有扫码/相册两个入口，避免普通用户误用 fail-open 直连。
        if (directModeUnlocked()) {
            addDirectModeSection(form)
        }
    }

    /**
     * v0.4.3 直连模式（高级，默认隐藏）区域：标题可点击展开/收起，展开后是
     * URL / Token / 连接控件；连接逻辑继续调用 [attemptPair]，与 v0.4.2 一致。
     */
    private fun addDirectModeSection(form: LinearLayout) {
        val toggle = TextView(this)
        toggle.text = directModeToggleText(expanded = false)
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        toggle.setTextColor(color(R.color.dsh_text_secondary))
        toggle.gravity = Gravity.CENTER
        toggle.setLineSpacing(0f, 1.2f)
        toggle.setPadding(dp(8), dp(12), dp(8), dp(12))
        toggle.isClickable = true
        toggle.isFocusable = true
        form.addView(toggle, formParams(28))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = View.GONE

        val hint = TextView(this)
        hint.setText(R.string.direct_mode_hint)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        hint.setTextColor(color(R.color.dsh_text_secondary))
        hint.setLineSpacing(0f, 1.2f)
        panel.addView(hint, formParams(0))

        val urlInput = EditText(this)
        urlInput.hint = getString(R.string.pairing_url_hint)
        urlInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        urlInput.imeOptions = EditorInfo.IME_ACTION_NEXT
        urlInput.isSingleLine = true
        urlInput.setText(session.serverUrl.orEmpty())
        urlInput.setSelection(urlInput.text?.length ?: 0)
        panel.addView(urlInput, formParams(16))

        val httpsHint = TextView(this)
        httpsHint.setText(R.string.pairing_https_hint)
        httpsHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        httpsHint.setTextColor(color(R.color.dsh_text_secondary))
        httpsHint.setLineSpacing(0f, 1.2f)
        panel.addView(httpsHint, formParams(8))

        val tokenInput = EditText(this)
        tokenInput.hint = getString(R.string.pairing_token_hint)
        tokenInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        tokenInput.imeOptions = EditorInfo.IME_ACTION_DONE
        tokenInput.isSingleLine = true
        panel.addView(tokenInput, formParams(12))

        val connect = makeButton(R.string.btn_connect, primary = false)
        connect.setOnClickListener {
            attemptPair(
                urlInput.text?.toString().orEmpty(),
                tokenInput.text?.toString().orEmpty()
            )
        }
        panel.addView(connect, formParams(24))

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

        form.addView(panel, LinearLayout.LayoutParams(matchParent, wrapContent))

        toggle.setOnClickListener {
            val expanded = panel.visibility != View.VISIBLE
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = directModeToggleText(expanded)
        }
    }

    /** 直连模式标题文案（用箭头提示可展开/收起）。 */
    private fun directModeToggleText(expanded: Boolean): String {
        val arrow = if (expanded) "▾ " else "▸ "
        return arrow + getString(R.string.direct_mode_title)
    }

    /** v0.4.3：直连模式解锁状态（独立 SharedPreferences，清配对数据不会重置）。 */
    private fun directModeUnlocked(): Boolean = try {
        getSharedPreferences(AppConfig.PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(AppConfig.KEY_DIRECT_MODE_UNLOCKED, false)
    } catch (t: Throwable) {
        Log.w(TAG, "read direct mode unlock flag failed", t)
        false
    }

    /** 写入直连模式解锁标记；失败只记日志（本进程内仍按调用方状态提示）。 */
    private fun setDirectModeUnlocked(unlocked: Boolean) {
        try {
            getSharedPreferences(AppConfig.PREFS_UI, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AppConfig.KEY_DIRECT_MODE_UNLOCKED, unlocked)
                .apply()
        } catch (t: Throwable) {
            Log.w(TAG, "persist direct mode unlock flag failed", t)
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

    // ---------------------------------------------------------- 相册二维码（v0.4.3）

    /** 打开系统照片选择器/文件选择器（不申请存储权限；取消时回调 uri = null）。 */
    private fun startQrGalleryPick() {
        if (galleryDecodeInFlight) return
        try {
            qrGalleryLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "launch gallery picker failed", t)
            Toast.makeText(this, R.string.pair_gallery_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 相册选图结果：后台线程读图/降采样/EXIF 转正，再交给 ML Kit bundled barcode 识别。
     *
     * 安全性：图片来自任意相册，识别结果仍必须通过 [QrPairPayload.parse] 严格校验
     * （只接受 https + `/__auth/pair` + 合法 fp/v=1），与扫码链路完全一致，
     * 成功后同样进入 [showPairConfirm] 确认再兑换。
     */
    private fun onGalleryImagePicked(uri: Uri?) {
        // 用户取消：静默回配对页。
        if (uri == null) return
        if (isFinishing || isDestroyed) return
        if (galleryDecodeInFlight) return

        val scanner = ensureGalleryScanner()
        val executor = ensureGalleryDecodeExecutor()
        if (scanner == null || executor == null) {
            Toast.makeText(this, R.string.pair_gallery_failed, Toast.LENGTH_LONG).show()
            return
        }

        galleryDecodeInFlight = true
        val token = ++galleryDecodeToken

        // 兜底：解码/识别异常卡住时解除“正在识别”状态并提示，避免入口永久失效。
        val watchdog = Runnable {
            if (token != galleryDecodeToken) return@Runnable
            galleryDecodeToken++
            galleryDecodeWatchdog = null
            galleryDecodeInFlight = false
            Log.w(TAG, "gallery qr decode timed out")
            Toast.makeText(this, R.string.pair_gallery_failed, Toast.LENGTH_LONG).show()
        }
        galleryDecodeWatchdog = watchdog
        mainHandler.postDelayed(watchdog, GALLERY_DECODE_TIMEOUT_MS)

        try {
            executor.execute {
                val image = try {
                    loadGalleryInputImage(applicationContext, uri)
                } catch (t: Throwable) {
                    // 含 OutOfMemoryError/损坏图片：只记异常类型，不记录图片内容。
                    Log.w(TAG, "load gallery image failed: ${t.javaClass.simpleName}")
                    null
                }
                mainHandler.post {
                    if (token != galleryDecodeToken) return@post
                    if (isFinishing || isDestroyed) return@post
                    if (image == null) {
                        finishGalleryDecode(token, null)
                        return@post
                    }
                    try {
                        // ML Kit 的 Task 回调默认在主线程；finishGalleryDecode 只做 UI 操作。
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                finishGalleryDecode(token, firstPairingPayload(barcodes))
                            }
                            .addOnFailureListener { error ->
                                Log.w(
                                    TAG,
                                    "gallery barcode process failed: " +
                                        error.javaClass.simpleName
                                )
                                finishGalleryDecode(token, null)
                            }
                    } catch (t: Throwable) {
                        Log.w(TAG, "gallery barcode process failed", t)
                        finishGalleryDecode(token, null)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "submit gallery decode failed", t)
            galleryDecodeToken++
            galleryDecodeWatchdog?.let { mainHandler.removeCallbacks(it) }
            galleryDecodeWatchdog = null
            galleryDecodeInFlight = false
            Toast.makeText(this, R.string.pair_gallery_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 收尾一次相册识别（主线程）：[token] 与当前代际不一致说明 Activity 已销毁、
     * 已超时或已被新的选图替代，直接丢弃结果，不做任何 UI 操作。
     */
    private fun finishGalleryDecode(token: Int, payload: QrPairPayload?) {
        if (token != galleryDecodeToken) return
        galleryDecodeWatchdog?.let { mainHandler.removeCallbacks(it) }
        galleryDecodeWatchdog = null
        galleryDecodeInFlight = false
        if (isFinishing || isDestroyed) return
        if (payload == null) {
            Log.w(TAG, "gallery qr rejected: no valid pairing payload in image")
            Toast.makeText(this, R.string.pair_gallery_failed, Toast.LENGTH_LONG).show()
            return
        }
        showPairConfirm(payload)
    }

    /** 一张图可能有多个码：取第一个通过严格校验的（其余忽略）。 */
    private fun firstPairingPayload(barcodes: List<Barcode>): QrPairPayload? {
        for (barcode in barcodes) {
            val raw = barcode.rawValue ?: continue
            val payload = QrPairPayload.parse(raw) ?: continue
            return payload
        }
        return null
    }

    private fun ensureGalleryScanner(): BarcodeScanner? {
        galleryScanner?.let { return it }
        return try {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
            ).also { galleryScanner = it }
        } catch (t: Throwable) {
            Log.e(TAG, "init gallery barcode scanner failed", t)
            null
        }
    }

    private fun ensureGalleryDecodeExecutor(): ExecutorService? {
        galleryDecodeExecutor?.let { return it }
        return try {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "dsh-gallery-qr").apply { isDaemon = true }
            }.also { galleryDecodeExecutor = it }
        } catch (t: Throwable) {
            Log.e(TAG, "init gallery decode executor failed", t)
            null
        }
    }

    /**
     * 读取相册图片为 [InputImage]（后台线程调用）。
     *
     * - 先 bounds 解码算 `inSampleSize`，把最长边压到 [GALLERY_MAX_IMAGE_SIDE] 以内，
     *   避免 48MP 原图直接解码 OOM；
     * - 按 EXIF 方向把像素转正后再交给 ML Kit（截图通常无旋转，相机照片需要）；
     * - 任何异常都返回 null，由调用方提示「未能识别」，不崩溃。
     */
    private fun loadGalleryInputImage(context: Context, uri: Uri): InputImage? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "read gallery image bounds failed: ${t.javaClass.simpleName}")
        }

        var sampleSize = 1
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (longestSide / sampleSize > GALLERY_MAX_IMAGE_SIDE) {
                sampleSize *= 2
            }
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "decode gallery image failed: ${t.javaClass.simpleName}")
            null
        }
        if (decoded == null) {
            Log.w(TAG, "gallery image decode returned null")
            return null
        }

        val rotation = readGalleryRotation(resolver, uri)
        val upright = if (rotation == 0) {
            decoded
        } else {
            try {
                Bitmap.createBitmap(
                    decoded,
                    0,
                    0,
                    decoded.width,
                    decoded.height,
                    Matrix().apply { postRotate(rotation.toFloat()) },
                    true
                ).also { rotated ->
                    if (rotated !== decoded) decoded.recycle()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "rotate gallery image failed: ${t.javaClass.simpleName}")
                decoded
            }
        }
        return InputImage.fromBitmap(upright, 0)
    }

    /**
     * 读 EXIF 方向（仅处理 90/180/270 旋转；镜像翻转对二维码识别无影响，忽略）。
     *
     * 用平台 `android.media.ExifInterface` 而不是 androidx 版本：只为读一个方向值，
     * 不引入新依赖；输入是用户主动选择的本地相册图片，不解析远端/不可信来源的图片。
     */
    @SuppressLint("ExifInterface")
    private fun readGalleryRotation(resolver: ContentResolver, uri: Uri): Int {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                when (
                    exif.getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL
                    )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (t: Throwable) {
            Log.w(TAG, "read gallery exif orientation failed: ${t.javaClass.simpleName}")
            0
        }
    }

    /** 清空相册识别状态/资源（Activity 销毁时；迟到回调由 [galleryDecodeToken] 丢弃）。 */
    private fun releaseGalleryResources() {
        galleryDecodeToken++
        galleryDecodeWatchdog?.let { mainHandler.removeCallbacks(it) }
        galleryDecodeWatchdog = null
        galleryDecodeInFlight = false
        try {
            galleryScanner?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close gallery barcode scanner failed", t)
        }
        galleryScanner = null
        try {
            galleryDecodeExecutor?.shutdownNow()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown gallery decode executor failed", t)
        }
        galleryDecodeExecutor = null
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
        // v0.4.6：稳定设备标识，服务端按它去重同一设备的重复配对；
        // 「忘记设备 / 重新配对」不会重置（见 SessionStore.clear 的保留逻辑）。
        val clientDeviceId = ClientDeviceId.get(this)

        authGateClient.redeemAsync(
            baseUrl = payload.baseUrl,
            fingerprint = payload.certSha256,
            ticket = payload.ticket,
            deviceName = deviceName,
            clientDeviceId = clientDeviceId
        ) { result ->
            redeemInFlight = false
            dismissRedeemProgress()
            if (isFinishing || isDestroyed) return@redeemAsync

            result.onSuccess { grant ->
                // 只有成功才覆盖旧配置；失败路径绝不清空已有配对。
                session.saveAuthGateProfile(
                    baseUrl = payload.baseUrl,
                    fingerprint = payload.certSha256,
                    deviceToken = grant.deviceToken,
                    // 网关 redeem 响应的 expiresAt 即设备有效期；旧服务端没有时为 null。
                    deviceExpiresAt = grant.expiresAt
                )
                // 新配对必须重新登录（首次仍走用户名/密码/TOTP），清掉可能残留的抑制标记。
                suppressStartupAutoLogin = false
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

    /**
     * 重建 WebView 主界面。
     *
     * @param startupRenew v0.4.2：true 表示这是冷启动/首次进入主界面，允许先用
     *   deviceToken 静默换会话再直接加载 GUI；扫码配对成功后的首次加载传 false，
     *   仍要求用户名/密码/TOTP 登录。
     */
    private fun showWeb(startupRenew: Boolean = false) {
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
        // v0.4.2：警告条与认证状态条叠在同一个顶部容器里，WebView 内容区一次让出总高度。
        val topBars = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(4).toFloat()
        }
        val banner = createWarningBanner()
        topBars.addView(banner, LinearLayout.LayoutParams(matchParent, wrapContent))
        val notice = createAuthNoticeBanner()
        topBars.addView(notice, LinearLayout.LayoutParams(matchParent, wrapContent))
        container.addView(
            topBars,
            FrameLayout.LayoutParams(matchParent, wrapContent, Gravity.TOP)
        )
        topBannerContainer = topBars
        warningBanner = banner
        authNoticeBanner = notice
        topBars.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
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
        val pin = CertPin.normalize(pinnedCert)
        if (pin == null) {
            showError(ErrorKind.CERT_CHANGED)
            return
        }

        // v0.4.2 启动静默登录：只有“曾经成功登录过”且本进程没有刚退出当前会话时才尝试。
        // 首次扫码配对后 authDeviceTrusted/authSessionEstablished 均为 false，仍走登录页；
        // v0.4.1 升级用户用 authSessionEstablished 兼容（老版本没有 trusted 键）。
        val profile = session.authGateProfile()
        val trusted = session.authDeviceTrusted || session.authSessionEstablished
        if (startupRenew && trusted && !suppressStartupAutoLogin &&
            profile != null && profile.canRenew
        ) {
            runStartupSilentRenew(newHost, base, pin, profile, progress)
            return
        }

        // P2-4：加载 GUI 前先用 pin 严格 TLS 预检 /__auth/whoami。失败说明证书已变
        // 或网关不可达，覆盖 CERT_CHANGED 错误层并提示重新扫码；成功才加载。
        // 本次页面生命周期成功后缓存，避免每次重建都多一次 RTT。
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

    // -------------------------------------------------- 认证网关静默登录（v0.4.2）

    /**
     * 启动静默登录：用 deviceToken 换新 session，成功把 Set-Cookie 注入 WebView 后
     * **直接加载 GUI**（不先闪登录页）；失败则加载登录页，并在顶部给出可重试/重新配对提示。
     *
     * 与 [handleAuthExpired] 共用 [authRenewInFlight]，并预先置位 [loginPageRenewTried]，
     * 因此失败后登录页不会再自动触发一次同样的续期（避免循环）；成功进入 GUI 后由
     * [trackAuthGatePage] 复位该标记。
     */
    private fun runStartupSilentRenew(
        webHost: WebHost,
        base: String,
        pin: String,
        profile: AuthGateProfile,
        progress: ProgressBar?
    ) {
        if (authRenewInFlight || startupRenewInFlight) return
        val deviceToken = profile.deviceToken
        if (deviceToken.isNullOrBlank()) {
            webHost.load("$base$LOGIN_PATH")
            return
        }
        authRenewInFlight = true
        startupRenewInFlight = true
        loginPageRenewTried = true
        startupRenewRetryAvailable = false
        val generation = ++authRenewGeneration
        progress?.isIndeterminate = true
        progress?.progress = 0
        progress?.visibility = View.VISIBLE
        showAuthNotice(getString(R.string.auth_silent_login), null, isError = false)

        authRenewFuture = authGateClient.renewAsync(base, pin, deviceToken) { outcome ->
            if (generation != authRenewGeneration) return@renewAsync
            authRenewFuture = null
            authRenewInFlight = false
            startupRenewInFlight = false
            if (isFinishing || isDestroyed) return@renewAsync
            if (pendingLogoutBaseUrl != null) return@renewAsync
            if (host !== webHost) return@renewAsync

            progress?.isIndeterminate = false
            progress?.progress = 0

            if (outcome.ok) {
                pinProbePassedBase = base
                storeCookies(base, outcome.cookies)
                session.lastAuthRenewAt = System.currentTimeMillis()
                outcome.deviceExpiresAt?.takeIf { it > 0L }?.let { session.deviceExpiresAt = it }
                clearAuthNotice()
                Log.i(TAG, "startup silent renew ok; loading GUI")
                webHost.load("$base/")
                return@renewAsync
            }

            val rejected = outcome.deviceRejected
            Log.w(
                TAG,
                "startup silent renew failed: status=${outcome.status ?: "-"} " +
                    "error=${outcome.errorCode ?: "-"} rejected=$rejected"
            )
            if (rejected) {
                // 设备凭据已被服务端拒绝：用户名/密码登录也会 device_required，必须重新扫码。
                startupRenewRetryAvailable = false
                session.authSessionEstablished = false
                showAuthNotice(
                    getString(deviceRejectedMessage(outcome.errorCode)),
                    action = { confirmForgetDevice() },
                    isError = true
                )
            } else {
                // 网络/TLS 等暂时性失败：保留设备凭据，登录页顶部提供“点击重试”。
                startupRenewRetryAvailable = true
                pinProbePassedBase = null
                showAuthNotice(
                    getString(R.string.auth_renew_failed_network),
                    action = { retryStartupSilentRenew() },
                    isError = true
                )
            }
            webHost.load("$base$LOGIN_PATH")
        }
    }

    /** 顶部认证提示条上的「重试」入口：重新走一次启动静默登录。 */
    private fun retryStartupSilentRenew() {
        if (authRenewInFlight) return
        dismissError()
        val webHost = host
        if (webHost == null) {
            showWeb(startupRenew = true)
            return
        }
        val base = session.serverUrl ?: return
        val pin = session.pinnedCertSha256 ?: return
        val profile = session.authGateProfile() ?: return
        if (!profile.canRenew) {
            showPairing()
            return
        }
        runStartupSilentRenew(webHost, base, pin, profile, progressBar)
    }

    /** 设备已失效时按服务端错误码选择提示文案（不含 token）。 */
    private fun deviceRejectedMessage(errorCode: String?): Int =
        if (errorCode == "origin_changed") {
            R.string.auth_device_origin_changed
        } else {
            R.string.auth_device_invalid
        }

    /**
     * 尽力取消后台 renew（Activity 销毁/退出登录/忘记设备时）。
     *
     * `Future.cancel(true)` 只是尽力而为：HttpsURLConnection 不一定响应线程中断，
     * 但还有 15s 超时；即使回调到达，也会被 [authRenewGeneration] 丢弃。
     */
    private fun cancelAuthRenewIo() {
        try {
            authRenewFuture?.cancel(true)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel auth renew failed", t)
        }
        authRenewFuture = null
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
        // 启动静默续期结束后进度条从 indeterminate 切回具体进度。
        bar.isIndeterminate = false
        bar.progress = value.coerceIn(0, 100)
        bar.visibility = if (value >= 100) View.GONE else View.VISIBLE
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onWebPageFinished(url: String?) {
        if (errorOverlay == null) {
            progressBar?.let { bar ->
                bar.isIndeterminate = false
                bar.visibility = View.GONE
            }
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
            // v0.4.2：同时记录“设备凭据已验证”，供下次启动静默登录使用。
            session.recordAuthGateGuiLoaded()
            loginPageRenewTried = false
            forceLoginOnNextResume = false
            forceLoginDeviceRejected = false
            forceLoginErrorCode = null
            authExpiredDuringRenew = false
            startupRenewRetryAvailable = false
            suppressStartupAutoLogin = false
            clearAuthNotice()
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
     *
     * v0.4.2：若前台续期已判定设备凭据失效，则下一次 401/导航直接回登录页，不再续期。
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
        if (forceLoginOnNextResume) {
            // 前台静默续期已经确认需要重新登录：本次导航直接走登录页，不再自动续期。
            forceLoginOnNextResume = false
            Log.i(TAG, "forced login after foreground renew failure")
            openForcedLoginPage()
            return
        }
        val profile = session.authGateProfile()
        if (profile == null || profile.deviceToken.isNullOrBlank()) {
            openLoginPage(base)
            return
        }
        if (authRenewInFlight) {
            // 启动/前台续期已在进行：等它的结果决定 reload 还是回登录页，避免重复请求。
            authExpiredDuringRenew = true
            return
        }
        if (authRenewAttempts >= MAX_AUTH_RENEW_ATTEMPTS) {
            authRenewAttempts = 0
            Log.w(TAG, "auth renew attempts exhausted, fallback to login page")
            openLoginPageWithNotice(getString(R.string.auth_renew_login_required))
            return
        }

        authRenewInFlight = true
        authRenewAttempts++
        val generation = ++authRenewGeneration
        Toast.makeText(this, R.string.auth_renewing, Toast.LENGTH_SHORT).show()
        authRenewFuture = authGateClient.renewAsync(
            baseUrl = base,
            fingerprint = profile.pinnedCertSha256,
            deviceToken = profile.deviceToken
        ) { outcome ->
            if (generation != authRenewGeneration) return@renewAsync
            authRenewFuture = null
            authRenewInFlight = false
            if (isFinishing || isDestroyed) return@renewAsync
            // 用户已点登出：不要用续期结果把页面切回 GUI。
            if (pendingLogoutBaseUrl != null) return@renewAsync
            authExpiredDuringRenew = false
            if (outcome.ok) {
                storeCookies(base, outcome.cookies)
                session.lastAuthRenewAt = System.currentTimeMillis()
                outcome.deviceExpiresAt?.takeIf { it > 0L }?.let { session.deviceExpiresAt = it }
                val target = renewedPageUrl
                if (target != null) {
                    host?.load(target)
                } else {
                    host?.reload()
                }
            } else {
                authRenewAttempts = 0
                if (outcome.deviceRejected) {
                    session.authSessionEstablished = false
                    Log.w(
                        TAG,
                        "session renew rejected: status=${outcome.status ?: "-"} " +
                            "error=${outcome.errorCode ?: "-"}"
                    )
                    openLoginPageWithNotice(
                        getString(deviceRejectedMessage(outcome.errorCode)),
                        action = { confirmForgetDevice() }
                    )
                } else {
                    Log.w(TAG, "session renew failed, fallback to login page")
                    openLoginPageWithNotice(getString(R.string.auth_renew_login_required))
                }
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

    /** 当前 WebView 主文档是否停在网关登录页（前台续期成功后据此直接进 GUI）。 */
    private fun isCurrentPageLogin(): Boolean {
        val url = host?.webView?.url ?: return false
        return try {
            Uri.parse(url).path == LOGIN_PATH
        } catch (t: Throwable) {
            false
        }
    }

    /** 加载登录页并在顶部显示认证失败提示（不覆盖页面，可带重试/重新配对动作）。 */
    private fun openLoginPageWithNotice(
        message: CharSequence,
        action: (() -> Unit)? = null
    ) {
        val base = session.serverUrl
        if (base.isNullOrBlank()) {
            showPairing()
            return
        }
        openLoginPage(base)
        showAuthNotice(message, action, isError = true)
    }

    // ------------------------------------------------- 前台恢复自动续期（v0.4.2）

    /**
     * 前台恢复静默续期：GUI 已登录（[SessionStore.authSessionEstablished]）且距上次成功
     * renew 超过 [FOREGROUND_RENEW_INTERVAL_MS] 时，后台换新 session 并更新设备有效期，
     * **不刷新当前页面**。
     *
     * 失败处理：
     * - 401/device_required/origin_changed → 标记 [forceLoginOnNextResume]，下一次前台或
     *   导航时回登录页（不立即打断正在看的页面）；
     * - 网络/TLS 暂时失败 → 只记日志，保留页面，下次前台按最小间隔重试。
     */
    private fun maybeForegroundRenew() {
        if (!session.authGate || !session.authSessionEstablished) return
        if (isFinishing || isDestroyed) return
        if (pendingLogoutBaseUrl != null) return
        if (authRenewInFlight || startupRenewInFlight || foregroundRenewInFlight) return
        val webHost = host ?: return
        val profile = session.authGateProfile() ?: return
        if (!profile.canRenew) return
        val deviceToken = profile.deviceToken ?: return
        val now = System.currentTimeMillis()
        val lastSuccess = session.lastAuthRenewAt
        if (lastSuccess > 0L && now - lastSuccess < FOREGROUND_RENEW_INTERVAL_MS) return
        // 失败后不要把每次前后台切换都变成一次网络请求；成功会推进 lastAuthRenewAt。
        if (now - lastForegroundRenewAttemptAt < FOREGROUND_RENEW_RETRY_MIN_MS) return
        lastForegroundRenewAttemptAt = now

        val base = profile.baseUrl
        val pin = profile.pinnedCertSha256
        authRenewInFlight = true
        foregroundRenewInFlight = true
        val generation = ++authRenewGeneration
        Log.i(
            TAG,
            "foreground renew start: lastSuccessAgeMs=" +
                if (lastSuccess > 0L) (now - lastSuccess) else -1L
        )
        authRenewFuture = authGateClient.renewAsync(base, pin, deviceToken) { outcome ->
            if (generation != authRenewGeneration) return@renewAsync
            authRenewFuture = null
            authRenewInFlight = false
            foregroundRenewInFlight = false
            if (isFinishing || isDestroyed) return@renewAsync
            if (pendingLogoutBaseUrl != null) return@renewAsync

            if (outcome.ok) {
                storeCookies(base, outcome.cookies)
                session.lastAuthRenewAt = System.currentTimeMillis()
                outcome.deviceExpiresAt?.takeIf { it > 0L }?.let { session.deviceExpiresAt = it }
                forceLoginOnNextResume = false
                forceLoginDeviceRejected = false
                forceLoginErrorCode = null
                startupRenewRetryAvailable = false
                Log.i(
                    TAG,
                    "foreground renew ok; deviceExpiresAt=${session.deviceExpiresAt}"
                )
                if (authExpiredDuringRenew) {
                    // 续期期间页面已经 401：现在 cookie 已更新，补一次 reload。
                    authExpiredDuringRenew = false
                    host?.reload()
                } else if (isCurrentPageLogin()) {
                    // 启动静默登录失败后停在登录页：续期成功说明会话已恢复，直接进 GUI。
                    clearAuthNotice()
                    Log.i(TAG, "foreground renew ok on login page; loading GUI")
                    host?.load("$base/")
                }
                return@renewAsync
            }

            val rejected = outcome.deviceRejected
            if (rejected) {
                session.authSessionEstablished = false
                forceLoginOnNextResume = true
                forceLoginDeviceRejected = true
                forceLoginErrorCode = outcome.errorCode
                Log.w(
                    TAG,
                    "foreground renew rejected: status=${outcome.status ?: "-"} " +
                        "error=${outcome.errorCode ?: "-"}; will show login page next foreground"
                )
            } else {
                Log.w(
                    TAG,
                    "foreground renew failed (transient): status=${outcome.status ?: "-"}; " +
                        "keep current page"
                )
            }
            if (authExpiredDuringRenew) {
                authExpiredDuringRenew = false
                forceLoginOnNextResume = false
                forceLoginDeviceRejected = false
                forceLoginErrorCode = null
                if (rejected) {
                    openLoginPageWithNotice(
                        getString(deviceRejectedMessage(outcome.errorCode)),
                        action = { confirmForgetDevice() }
                    )
                } else {
                    openLoginPageWithNotice(getString(R.string.auth_renew_login_required))
                }
            }
        }
    }

    /** 消费前台续期失败标记：下一次前台恢复时回登录页（只在主线程调用）。 */
    private fun maybeHandleForcedLogin() {
        if (!forceLoginOnNextResume) return
        if (isFinishing || isDestroyed) return
        if (!session.authGate) {
            forceLoginOnNextResume = false
            forceLoginDeviceRejected = false
            forceLoginErrorCode = null
            return
        }
        if (pendingLogoutBaseUrl != null) return
        if (session.serverUrl.isNullOrBlank()) {
            forceLoginOnNextResume = false
            forceLoginDeviceRejected = false
            forceLoginErrorCode = null
            return
        }
        forceLoginOnNextResume = false
        Log.i(TAG, "foreground renew was rejected earlier; showing login page")
        openForcedLoginPage()
    }

    /**
     * 消费 [forceLoginOnNextResume]：401/设备失效显示“重新扫码配对”并给重新配对入口；
     * 其它失败显示通用的“请重新登录”。调用后清空原因标记。
     */
    private fun openForcedLoginPage() {
        val rejected = forceLoginDeviceRejected
        val errorCode = forceLoginErrorCode
        forceLoginDeviceRejected = false
        forceLoginErrorCode = null
        if (rejected) {
            openLoginPageWithNotice(
                getString(deviceRejectedMessage(errorCode)),
                action = { confirmForgetDevice() }
            )
        } else {
            openLoginPageWithNotice(getString(R.string.auth_renew_login_required))
        }
    }

    /**
     * 「退出当前会话」：用 WebView 以 POST 打开 `/__auth/logout`（服务端只接受 POST；GET 会 405），
     * 让网关真正删除 session 并清 WebView 里的 session cookie，再回到登录页；
     * deviceToken、设备绑定 cookie 与 [SessionStore.authDeviceTrusted] 保留，
     * 因此下次打开 App 会静默续期自动登录。
     * 登出页可能 302 到登录页，onPageFinished 不一定停在 logout URL，因此用
     * [pendingLogoutBaseUrl] 标记“第一页结束即收尾”，并加 4s 超时兜底。
     */
    private fun logout() {
        val base = session.serverUrl
        val webHost = host
        if (base.isNullOrBlank() || webHost == null) {
            Toast.makeText(this, R.string.logout_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 明确退出会话后不允许当前进程里的“登录页 → 自动续期”把会话又建回来；
        // 设备凭据保留，下次冷启动仍会静默登录。
        // v0.4.1 升级用户没有 trusted 键：退出前已登录过就补记为可信，保证下次仍能自动登录。
        if (session.authSessionEstablished) {
            session.authDeviceTrusted = true
        }
        session.authSessionEstablished = false
        loginPageRenewTried = true
        suppressStartupAutoLogin = true
        forceLoginOnNextResume = false
        forceLoginDeviceRejected = false
        forceLoginErrorCode = null
        authRenewGeneration++
        cancelAuthRenewIo()
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        authExpiredDuringRenew = false
        startupRenewRetryAvailable = false
        clearAuthNotice()
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
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        startupRenewRetryAvailable = false
        authExpiredDuringRenew = false
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

    // --------------------------------------------- 忘记设备（v0.4.2 重新配对）

    /**
     * 「忘记此设备（重新配对）」确认框。
     *
     * 该操作不可逆（本机 deviceToken 被删除，必须重新扫码），因此先二次确认；
     * 取消时保留当前会话与设备凭据。
     */
    private fun confirmForgetDevice() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.forget_device_title)
                .setMessage(R.string.forget_device_message)
                .setPositiveButton(R.string.forget_device_confirm) { _, _ -> forgetDeviceNow() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "show forget device confirm failed", t)
        }
    }

    /**
     * 删除该 host 的 deviceToken、该 host 全部 WebView cookie（含设备绑定）与配对 profile，
     * 回到配对页；下次必须重新扫码。先取消所有进行中的续期/登出，避免迟到回调再写 cookie。
     */
    private fun forgetDeviceNow() {
        val base = session.serverUrl
        if (base.isNullOrBlank()) {
            session.clear()
            showPairing()
            return
        }
        Log.i(TAG, "forget device requested; clearing local device credential and cookies")
        // 1) 作废迟到的 renew 回调，并取消登出超时。
        authRenewGeneration++
        cancelAuthRenewIo()
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        pendingLogoutBaseUrl = null
        cancelLogoutFallback()
        loginPageRenewTried = true
        forceLoginOnNextResume = false
        forceLoginDeviceRejected = false
        forceLoginErrorCode = null
        authExpiredDuringRenew = false
        startupRenewRetryAvailable = false
        suppressStartupAutoLogin = false
        clearAuthNotice()

        // 2) 显式删除 deviceToken（session.clear 也会删，这里先删避免中间态）。
        try {
            DeviceTokenStore(this).clear(base)
        } catch (t: Throwable) {
            Log.w(TAG, "clear device token failed", t)
        }
        // 3) 清除该 host 的全部 WebView cookie（含设备绑定 cookie）。
        clearCookiesFor(base, keepDeviceCookie = false)
        // 4) 清除服务器地址/pin/信任标记/设备有效期等全部配对状态。
        session.clear()

        dismissRedeemProgress()
        redeemInFlight = false
        pinProbePassedBase = null
        Toast.makeText(this, R.string.forget_device_done, Toast.LENGTH_SHORT).show()
        showPairing()
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
     * v0.4.2 菜单拆分（仅认证网关模式）：
     * - 刷新；
     * - 退出当前会话：结束服务端 session，保留 deviceToken/设备 cookie，下次打开自动登录；
     * - 忘记此设备（重新配对）：删除本地 deviceToken、该 host 全部 cookie 与 profile。
     *
     * 认证网关模式下不再显示旧的「重新配对」（与「忘记此设备」行为重复）；直连模式保持
     * v0.4.1 的「重新配对」。打开期间记录 [menuDialog]，返回键先关菜单。
     */
    private fun showMenu() {
        if (isFinishing || isDestroyed) return
        dismissMenuIfShowing()

        val actions = mutableListOf<Int>()
        val labels = mutableListOf<CharSequence>()
        actions += MENU_REFRESH
        labels += getString(R.string.menu_refresh)
        // v0.4.5：认证网关与直连模式都显示「最近下载」（本地历史兜底，不依赖 ROM「最近」）。
        actions += MENU_DOWNLOADS
        labels += getString(R.string.menu_download_history)
        if (session.authGate) {
            // 两项语义不同：前者只结束 session，后者删除设备凭据（必须重新扫码）。
            actions += MENU_LOGOUT_SESSION
            labels += getString(R.string.menu_logout_session)
            actions += MENU_FORGET_DEVICE
            labels += getString(R.string.menu_forget_device)
        } else {
            // 旧直连模式没有网关登录会话，保持 v0.4.1 的「重新配对」。
            actions += MENU_PAIR
            labels += getString(R.string.menu_pair)
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

    /** 菜单动作分发（认证网关模式下登出拆分为“退出会话/忘记设备”两项）。 */
    private fun runMenuAction(action: Int) {
        when (action) {
            MENU_REFRESH -> {
                dismissError()
                host?.reload()
            }
            MENU_DOWNLOADS -> showDownloadHistory()
            MENU_PAIR -> pairAgain()
            MENU_LOGOUT_SESSION -> logout()
            MENU_FORGET_DEVICE -> confirmForgetDevice()
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

    // ------------------------------------------------- 最近下载（v0.4.5）

    /**
     * 应用内「最近下载」：作为 OEM 文件管理器「最近」不显示任意文档时的可靠兜底。
     *
     * 列表来自 [DownloadHistory]（最多 50 条，最新在前），只显示文件名与保存时间；
     * 点击用 ACTION_VIEW 打开（Android 10+ MediaStore content Uri，Android 8/9 FileProvider），
     * 底部「清除记录」只清历史，不删除任何文件。
     */
    private fun showDownloadHistory() {
        if (isFinishing || isDestroyed) return
        val entries = DownloadHistory.load(this)
        if (entries.isEmpty()) {
            try {
                AlertDialog.Builder(this)
                    .setTitle(R.string.menu_download_history)
                    .setMessage(R.string.download_history_empty)
                    .setPositiveButton(R.string.download_done, null)
                    .show()
            } catch (t: Throwable) {
                Log.w(TAG, "show empty download history failed", t)
                toastSafely(getString(R.string.download_history_empty))
            }
            return
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val labels = entries.map { entry ->
            val time = if (entry.savedAt > 0L) {
                try {
                    formatter.format(Date(entry.savedAt))
                } catch (t: Throwable) {
                    getString(R.string.value_unknown)
                }
            } else {
                getString(R.string.value_unknown)
            }
            getString(R.string.download_history_item, entry.displayName, time)
        }.toTypedArray()

        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.menu_download_history)
                .setItems(labels) { _, which ->
                    entries.getOrNull(which)?.let { openDownloadHistoryEntry(it) }
                }
                .setNegativeButton(R.string.download_history_clear) { _, _ ->
                    confirmClearDownloadHistory()
                }
                .setPositiveButton(R.string.download_done, null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "show download history failed", t)
            toastSafely(getString(R.string.download_history_empty))
        }
    }

    /** 清除历史前二次确认：只清列表，不删文件。 */
    private fun confirmClearDownloadHistory() {
        if (isFinishing || isDestroyed) return
        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.download_history_clear_title)
                .setMessage(R.string.download_history_clear_message)
                .setPositiveButton(R.string.download_history_clear) { _, _ ->
                    DownloadHistory.clear(this)
                    toastSafely(getString(R.string.download_history_cleared))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "show clear download history confirm failed", t)
        }
    }

    /**
     * 打开一条历史记录。打开前先验证 Uri 可读（文件可能被用户移动/删除），
     * 失败只 Toast 说明原因，不崩溃、不申请额外权限。
     */
    private fun openDownloadHistoryEntry(entry: DownloadHistory.Entry) {
        val uri = try {
            resolveDownloadHistoryUri(entry)
        } catch (t: Throwable) {
            Log.w(TAG, "resolve download history uri failed: ${t.javaClass.simpleName}")
            null
        }
        if (uri == null) {
            toastSafely(getString(R.string.download_history_open_failed, entry.displayName))
            return
        }
        if (!canReadDownloadHistoryUri(uri)) {
            toastSafely(getString(R.string.download_history_open_failed, entry.displayName))
            return
        }

        val mime = entry.mime
            .takeIf { it.contains('/') && it != "application/octet-stream" }
            ?: try {
                contentResolver.getType(uri)?.takeIf { it.contains('/') }
            } catch (t: Throwable) {
                null
            }
            ?: "*/*"

        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (t: ActivityNotFoundException) {
            Log.w(TAG, "no activity to open download history file")
            toastSafely(getString(R.string.download_history_open_failed, entry.displayName))
        } catch (t: Throwable) {
            Log.w(TAG, "open download history file failed: ${t.javaClass.simpleName}")
            toastSafely(getString(R.string.download_history_open_failed, entry.displayName))
        }
    }

    /**
     * Android 10+ 直接用 MediaStore content Uri；Android 8/9 用 FileProvider 把
     * 公共 Download 绝对路径转成临时可授予的 content Uri（不再直接用 file://，
     * 避免 FileUriExposedException）。
     */
    private fun resolveDownloadHistoryUri(entry: DownloadHistory.Entry): Uri? {
        entry.contentUri?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
        val path = entry.legacyPath?.takeIf { it.isNotBlank() } ?: return null
        if (!path.startsWith("/")) return null
        val file = File(path)
        if (!file.isFile) return null
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    /** 只做一次只读打开验证，避免把已删除/无权限的 Uri 交给第三方应用。 */
    private fun canReadDownloadHistoryUri(uri: Uri): Boolean {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "download history uri is not readable: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun toastSafely(message: String) {
        if (isFinishing || isDestroyed) return
        try {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (t: Throwable) {
            Log.w(TAG, "show toast failed", t)
        }
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
            // 层级由父容器 topBars 统一抬高（4dp），避免两条横幅互相压盖。
            elevation = 0f
            visibility = View.GONE
            setOnClickListener {
                currentTlsInfo?.let { info -> showTlsDetails(info) }
            }
        }
    }

    /**
     * v0.4.2 认证状态条：启动静默登录时显示「正在登录…」，失败时显示原因；
     * 有重试/重新配对动作时整条可点。默认隐藏，不阻断页面加载。
     */
    private fun createAuthNoticeBanner(): TextView {
        return TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(AUTH_NOTICE_INFO_TEXT_COLOR)
            setBackgroundColor(AUTH_NOTICE_INFO_BG_COLOR)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            elevation = 0f
            visibility = View.GONE
            setOnClickListener {
                val action = authNoticeAction ?: return@setOnClickListener
                action()
            }
        }
    }

    /** 显示认证状态条；[action] 非空时整条可点（重试/重新配对）。 */
    private fun showAuthNotice(
        text: CharSequence,
        action: (() -> Unit)? = null,
        isError: Boolean = false
    ) {
        val banner = authNoticeBanner ?: return
        banner.text = text
        banner.setTextColor(
            if (isError) AUTH_NOTICE_ERROR_TEXT_COLOR else AUTH_NOTICE_INFO_TEXT_COLOR
        )
        banner.setBackgroundColor(
            if (isError) AUTH_NOTICE_ERROR_BG_COLOR else AUTH_NOTICE_INFO_BG_COLOR
        )
        authNoticeAction = action
        banner.isClickable = action != null
        banner.isFocusable = action != null
        banner.visibility = View.VISIBLE
        applyWarningBannerPadding()
    }

    /** 隐藏认证状态条（登录成功/退出登录/重新配对时）。 */
    private fun clearAuthNotice() {
        authNoticeAction = null
        val banner = authNoticeBanner ?: return
        banner.isClickable = false
        banner.isFocusable = false
        banner.visibility = View.GONE
        applyWarningBannerPadding()
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
     * 顶部条不覆盖页面：把 WebView 内容整体下移到 [topBannerContainer] 的实际高度，
     * 两条横幅都隐藏时恢复 0。WebHost 的 insets padding 加在 container 上，
     * 这里只动 WebView 自身，因此用容器高度即可，无需重复计入状态栏。
     */
    private fun applyWarningBannerPadding() {
        val topBars = topBannerContainer ?: return
        val webView = host?.webView ?: return
        val paddingTop = if (topBars.visibility == View.VISIBLE) {
            val height = if (topBars.height > 0) topBars.height else topBars.measuredHeight
            height.coerceAtLeast(0)
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
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.4.6"
        } catch (t: Throwable) {
            "0.4.6"
        }

        // v0.4.3：版本号 TextView 是「直连模式」隐藏入口（连续点击 7 次解锁/锁定）；
        // 其余信息仍与原「关于」一致，放在下方可选中复制。
        val versionView = TextView(this).apply {
            text = getString(R.string.about_version_line, getString(R.string.app_name), versionName)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(color(R.color.dsh_text))
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(24), dp(20), dp(24), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { onVersionTapped() }
        }

        val details = buildString {
            append(packageName)
            append("\nAndroid ")
            append(Build.VERSION.RELEASE)
            session.serverUrl?.let {
                append('\n')
                append(it)
            }
            if (session.authGate) {
                // v0.4.2：设备有效期来自 redeem 的 expiresAt 或 renew 的可选
                // deviceExpiresAt；旧服务端未提供时显示“—”，不伪造数据。
                append('\n')
                append(
                    getString(
                        R.string.about_device_expires,
                        formatDeviceExpiresAt(session.deviceExpiresAt)
                    )
                )
            }
        }

        val detailsView = TextView(this).apply {
            text = details
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(R.color.dsh_text))
            setPadding(dp(24), 0, dp(24), dp(16))
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(versionView, LinearLayout.LayoutParams(matchParent, wrapContent))
            addView(detailsView, LinearLayout.LayoutParams(matchParent, wrapContent))
        }
        val scroll = ScrollView(this).apply {
            addView(content, ViewGroup.LayoutParams(matchParent, wrapContent))
        }

        try {
            AlertDialog.Builder(this)
                .setTitle(R.string.menu_about)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } catch (t: Throwable) {
            Log.w(TAG, "show about dialog failed", t)
        }
    }

    /**
     * v0.4.3：「关于」版本号连点 [DIRECT_MODE_TAP_COUNT] 次（相邻两次间隔不超过
     * [DIRECT_MODE_TAP_WINDOW_MS]）切换直连模式解锁状态；解锁/锁定都 Toast 提示。
     */
    private fun onVersionTapped() {
        val now = SystemClock.elapsedRealtime()
        if (versionTapCount > 0 && now - lastVersionTapAt > DIRECT_MODE_TAP_WINDOW_MS) {
            versionTapCount = 0
        }
        lastVersionTapAt = now
        versionTapCount++
        if (versionTapCount < DIRECT_MODE_TAP_COUNT) return

        versionTapCount = 0
        lastVersionTapAt = 0L
        val unlocked = !directModeUnlocked()
        setDirectModeUnlocked(unlocked)
        Toast.makeText(
            this,
            if (unlocked) R.string.direct_mode_unlocked else R.string.direct_mode_locked,
            Toast.LENGTH_SHORT
        ).show()
        val state = if (unlocked) "unlocked" else "locked"
        Log.i(TAG, "direct mode $state via about version taps")
        // 配对页就在「关于」下面：立即重建，让直连区域出现/消失。
        // 已配对（WebView 主界面）时不重建，避免打断当前页面，下次进配对页再读标记。
        if (!session.hasPairing) {
            showPairing()
        }
    }

    /** 设备有效期展示（yyyy-MM-dd HH:mm）；0/非法值显示「—」。不涉及 token。 */
    private fun formatDeviceExpiresAt(expiresAt: Long): String {
        if (expiresAt <= 0L) return getString(R.string.value_unknown)
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expiresAt))
        } catch (t: Throwable) {
            Log.w(TAG, "format device expiry failed", t)
            getString(R.string.value_unknown)
        }
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
        // v0.4.2：启动静默续期失败（网络/TLS）后，原生错误层的“重试”应重试静默登录，
        // 而不是只刷新登录页——否则用户回到 App 后还得再输一次密码/TOTP。
        if (kind == ErrorKind.NETWORK && startupRenewRetryAvailable) {
            retryStartupSilentRenew()
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
        topBannerContainer = null
        authNoticeBanner = null
        authNoticeAction = null
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
        authRenewGeneration++
        cancelAuthRenewIo()
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        forceLoginOnNextResume = false
        forceLoginDeviceRejected = false
        forceLoginErrorCode = null
        authExpiredDuringRenew = false
        startupRenewRetryAvailable = false
        suppressStartupAutoLogin = false
        session.serverUrl?.let { clearCookiesFor(it, keepDeviceCookie = false) }
        session.clear()
        dismissRedeemProgress()
        redeemInFlight = false
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
        topBannerContainer = null
        authNoticeBanner = null
        authNoticeAction = null
        currentTlsInfo = null
        currentIsHttp = false
        dismissTlsDialog()

        // 认证网关会话/登出状态不跨 WebHost 生命周期；同时作废迟到的续期回调。
        authRenewGeneration++
        cancelAuthRenewIo()
        pendingLogoutBaseUrl = null
        cancelLogoutFallback()
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        authRenewAttempts = 0
        authExpiredDuringRenew = false
        startupRenewRetryAvailable = false
        loginPageRenewTried = false
    }

    override fun onPause() {
        host?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        host?.onResume()
        // v0.4.2：前台恢复先处理上次续期失败的强制登录，再做 1 小时节流的静默续期。
        maybeHandleForcedLogin()
        maybeForegroundRenew()
    }

    override fun onDestroy() {
        pinProbePassedBase = null
        redeemInFlight = false
        // v0.4.3：作废相册识别回调并释放 ML Kit scanner/解码线程。
        releaseGalleryResources()
        // v0.4.2：退出 Activity 后丢弃所有 renew 回调（HTTP 连接由 15s 超时自行结束）。
        authRenewGeneration++
        cancelAuthRenewIo()
        authRenewInFlight = false
        startupRenewInFlight = false
        foregroundRenewInFlight = false
        forceLoginOnNextResume = false
        forceLoginDeviceRejected = false
        forceLoginErrorCode = null
        authExpiredDuringRenew = false
        startupRenewRetryAvailable = false
        // 真正退出 App 时允许下次冷启动静默登录；仅旋转/重建时保留“刚退出会话”的抑制标记。
        if (isFinishing) {
            suppressStartupAutoLogin = false
        }
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
        const val MENU_LOGOUT_SESSION = 5
        const val MENU_EXIT = 6
        const val MENU_FORGET_DEVICE = 7
        const val MENU_DOWNLOADS = 8

        /**
         * v0.4.2：本进程内刚「退出当前会话」后，抑制 Activity 重建（如旋转）时的启动
         * 静默登录。真正退出 App（isFinishing）或冷启动（新进程）会复位，
         * 因此下次打开 App 仍会自动登录。
         */
        @Volatile
        private var suppressStartupAutoLogin = false

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

        /** v0.4.2：前台恢复静默续期间隔（1 小时）与失败后的最小重试间隔（1 分钟）。 */
        const val FOREGROUND_RENEW_INTERVAL_MS = 60L * 60L * 1000L
        const val FOREGROUND_RENEW_RETRY_MIN_MS = 60L * 1000L

        /** 登出页加载超时（ms）：页面 302/失败时仍能收尾回登录页。 */
        const val LOGOUT_FALLBACK_MS = 4000L

        const val MAX_DEVICE_NAME_LENGTH = 64

        /** v0.4.3：相册图片最长边上限（超过则按 2 的幂降采样，避免原图 OOM）。 */
        const val GALLERY_MAX_IMAGE_SIDE = 3072

        /** v0.4.3：相册识别兜底超时（ms）；超时后解除进行中状态，可再次选图。 */
        const val GALLERY_DECODE_TIMEOUT_MS = 20_000L

        /** v0.4.3：直连模式解锁——连续点击版本号次数与相邻点击最大间隔（ms）。 */
        const val DIRECT_MODE_TAP_COUNT = 7
        const val DIRECT_MODE_TAP_WINDOW_MS = 2_000L

        /** 首次「返回手势打开应用菜单」提示的 SharedPreferences 键与延迟（ms）。 */
        const val KEY_BACK_GESTURE_HINT = "back_gesture_menu_hint_v1"
        const val BACK_GESTURE_HINT_DELAY_MS = 1200L

        /** 警告条配色：黄底深棕字（不新增颜色资源，保持单文件可读）。 */
        val WARNING_BG_COLOR = 0xFFFFF3CD.toInt()
        val WARNING_TEXT_COLOR = 0xFF7A4F01.toInt()

        /** v0.4.2 认证状态条配色：信息蓝 / 错误红。 */
        val AUTH_NOTICE_INFO_BG_COLOR = 0xFFE8F0FE.toInt()
        val AUTH_NOTICE_INFO_TEXT_COLOR = 0xFF174EA6.toInt()
        val AUTH_NOTICE_ERROR_BG_COLOR = 0xFFFDECEA.toInt()
        val AUTH_NOTICE_ERROR_TEXT_COLOR = 0xFF8A1C1C.toInt()
    }
}
