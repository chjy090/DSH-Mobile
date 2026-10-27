package ai.deepseek.dsh.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 扫码配对页（v0.4.0）。
 *
 * - CameraX PreviewView（代码构建布局）+ ImageAnalysis；
 * - ML Kit bundled `barcode-scanning` 只识别 [Barcode.FORMAT_QR_CODE]，
 *   `InputImage.fromMediaImage` 处理 YUV 帧；
 * - 首次进入请求 CAMERA 运行时权限（拒绝则提示并结束）；
 * - 解析成 [QrPairPayload] 后震动一次，`setResult(RESULT_OK, payload)` 结束；
 *   500ms 内重复识别被丢弃；
 * - 返回键 / 取消按钮 → RESULT_CANCELED。
 */
class QrScanActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>

    private var analysisExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /** 已交付结果：阻止相机继续分析/重复 finish。 */
    private var handled = false

    /** 最近一次成功解析的时间（elapsedRealtime），用于 500ms 防重复。 */
    private var lastAcceptedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applyEdgeToEdge(window)
        // 取景框是黑底，状态栏/导航栏用浅色图标。
        setStatusBarAppearance(window, lightBackground = false)

        buildLayout()
        setupBackHandling()

        analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "dsh-qr-scan").apply { isDaemon = true }
        }
        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        cameraPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.pair_camera_permission, Toast.LENGTH_LONG).show()
                cancelScan()
            }
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    // ---------------------------------------------------------------- 布局

    private fun buildLayout() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)
        setContentView(root)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0x99000000.toInt())
        }
        val title = TextView(this).apply {
            setText(R.string.pair_scan_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
        }
        val hint = TextView(this).apply {
            setText(R.string.pair_scan_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFE0E0E0.toInt())
            setLineSpacing(0f, 1.2f)
        }
        topBar.addView(
            title,
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        topBar.addView(
            hint,
            LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )
        root.addView(
            topBar,
            FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )
        padByInsets(topBar)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(0x99000000.toInt())
        }
        val cancel = Button(this).apply {
            setText(android.R.string.cancel)
            isAllCaps = false
            setTextColor(Color.WHITE)
            minHeight = dp(48)
            backgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
            setOnClickListener { cancelScan() }
        }
        bottomBar.addView(
            cancel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        padByInsets(bottomBar)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelScan()
            }
        })
    }

    // ---------------------------------------------------------------- 权限 / 相机

    private fun requestCameraPermission() {
        try {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } catch (t: Throwable) {
            Log.e(TAG, "request camera permission failed", t)
            Toast.makeText(this, R.string.pair_camera_permission, Toast.LENGTH_LONG).show()
            cancelScan()
        }
    }

    private fun startCamera() {
        if (handled || cameraProvider != null) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (handled || isFinishing || isDestroyed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                val executor = analysisExecutor ?: return@addListener
                analysis.setAnalyzer(executor) { imageProxy -> analyze(imageProxy) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                Log.e(TAG, "start camera failed", t)
                Toast.makeText(this, R.string.pair_failed, Toast.LENGTH_LONG).show()
                cancelScan()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * ImageAnalysis 回调（后台线程）。ML Kit 的 Task 回调默认回主线程，
     * 在 complete 回调里关闭 ImageProxy，保证每个帧只 close 一次。
     */
    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyze(imageProxy: ImageProxy) {
        if (handled) {
            imageProxy.close()
            return
        }
        val scanner = barcodeScanner
        val mediaImage = try {
            imageProxy.image
        } catch (t: Throwable) {
            Log.w(TAG, "read camera image failed", t)
            null
        }
        if (scanner == null || mediaImage == null) {
            imageProxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            scanner.process(input)
                .addOnSuccessListener { barcodes -> onBarcodes(barcodes) }
                .addOnFailureListener { error ->
                    if (!handled) {
                        Log.d(TAG, "barcode scan failed: ${error.javaClass.simpleName}")
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        } catch (t: Throwable) {
            Log.w(TAG, "process camera frame failed", t)
            imageProxy.close()
        }
    }

    private fun onBarcodes(barcodes: List<Barcode>) {
        if (handled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAcceptedAt < DEBOUNCE_MS) return

        for (barcode in barcodes) {
            val raw = barcode.rawValue ?: continue
            val payload = QrPairPayload.parse(raw) ?: continue
            lastAcceptedAt = now
            runOnUiThread { deliver(payload, raw) }
            return
        }
    }

    private fun deliver(payload: QrPairPayload, raw: String) {
        if (handled || isFinishing || isDestroyed) return
        handled = true

        vibrateOnce()
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbind camera failed", t)
        }

        val data = Intent()
            .putExtra(EXTRA_PAYLOAD, payload)
            .putExtra(EXTRA_RAW_PAYLOAD, raw)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun cancelScan() {
        if (isFinishing) return
        handled = true
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun vibrateOnce() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed", t)
        }
    }

    // ---------------------------------------------------------------- 工具 / 生命周期

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        handled = true
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.w(TAG, "unbind camera on destroy failed", t)
        }
        cameraProvider = null
        try {
            barcodeScanner?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close barcode scanner failed", t)
        }
        barcodeScanner = null
        try {
            analysisExecutor?.shutdownNow()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown scan executor failed", t)
        }
        analysisExecutor = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DSHQrScan"
        private const val DEBOUNCE_MS = 500L
        private const val VIBRATE_MS = 80L

        /** 解析成功的 [QrPairPayload]（Serializable）。 */
        const val EXTRA_PAYLOAD = "ai.deepseek.dsh.mobile.extra.PAIR_PAYLOAD"

        /** 原始二维码文本，作为 EXTRA_PAYLOAD 的反序列化兜底。 */
        const val EXTRA_RAW_PAYLOAD = "ai.deepseek.dsh.mobile.extra.PAIR_RAW_PAYLOAD"

        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
