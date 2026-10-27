package ai.deepseek.dsh.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * 注入到 WebView 的原生桥：`window.DSHNative`。
 *
 * 只暴露最少的剪贴板 / Toast / 日志能力，供 document-start 的
 * clipboard polyfill 使用。
 */
class NativeBridge(private val activity: Activity) {

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            try {
                val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (manager == null) {
                    Log.w(TAG, "ClipboardManager unavailable")
                    return@runOnUiThread
                }
                manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
                Toast.makeText(activity, R.string.copied, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "copyToClipboard failed", t)
            }
        }
    }

    @JavascriptInterface
    fun toast(msg: String) {
        activity.runOnUiThread {
            try {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "toast failed", t)
            }
        }
    }

    @JavascriptInterface
    fun log(msg: String) {
        // 页面传入的日志内容不可信，可能夹带带 token 的 URL，落 logcat / 诊断缓冲前先脱敏。
        val safe = AppConfig.redactForLog(msg)
        Diagnostics.add(source = "JSBridge", level = "D", message = safe)
        Log.d(TAG, safe)
    }

    private companion object {
        const val TAG = "DSHNative"
        const val CLIP_LABEL = "DSH Mobile"
    }
}
