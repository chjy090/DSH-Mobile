package ai.deepseek.dsh.mobile

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 边到边（edge-to-edge）与安全区工具。
 *
 * 注意：[padByInsets] 会记录调用瞬间 view 的 padding 作为基准，因此同一个 view
 * 上只应调用一次；之后系统 inset 变化时会自动重新计算。
 */

@Suppress("DEPRECATION")
fun applyEdgeToEdge(window: Window) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    // API 35 起这两个属性已废弃，但在 26..34 上仍是透明系统栏的可靠做法。
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
}

/**
 * 把 system bars / 刘海 / IME 的 inset 叠加到 view 原有 padding 上。
 */
fun padByInsets(view: View, extraTop: Int = 0, extraBottom: Int = 0) {
    val baseLeft = view.paddingLeft
    val baseTop = view.paddingTop
    val baseRight = view.paddingRight
    val baseBottom = view.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top + extraTop,
            baseRight + bars.right,
            baseBottom + bars.bottom + extraBottom
        )
        insets
    }
    ViewCompat.requestApplyInsets(view)
}

/**
 * 根据背景深浅切换状态栏/导航栏图标明暗。
 */
fun setStatusBarAppearance(window: Window, lightBackground: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = lightBackground
    controller.isAppearanceLightNavigationBars = lightBackground
}
