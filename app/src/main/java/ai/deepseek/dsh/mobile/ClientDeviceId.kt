package ai.deepseek.dsh.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * 稳定的客户端设备标识（v0.4.6）。
 *
 * 服务端（v0.7.3+）按 `clientDeviceId` 去重：同一台设备重复扫码配对只会保留一条设备记录。
 * 因此该值在「忘记此设备 / 重新配对」后必须保持不变，只有清除应用数据或卸载才会丢失；
 * 丢失后尽量从 [Settings.Secure.ANDROID_ID] 确定性恢复（同一次安装内稳定），
 * 再退化为随机 UUID。
 *
 * 该标识**只是身份标识，不是认证秘密**：不能用于登录或换取凭据；
 * 不打印到 logcat/诊断日志，也不在界面展示（完整值与前 8 位均不输出）。
 * 不新增任何权限，也不读取硬件序列号。
 */
object ClientDeviceId {

    private const val TAG = "DSHClientDeviceId"

    /** 合法值：标准 UUID，或 8–64 位安全字符（字母/数字/`.`/`_`/`-`，与服务端 v0.7.3 校验一致）。 */
    private val UUID_PATTERN = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val SAFE_PATTERN = Regex("^[A-Za-z0-9._-]{8,64}$")

    /**
     * 已知不可用的 ANDROID_ID：空串/占位值，以及旧设备/山寨 ROM 上大量重复的
     * `9774d56d682e549c`。命中时不能用于派生（否则多台设备会得到同一个 ID）。
     */
    private val BAD_ANDROID_IDS = setOf(
        "9774d56d682e549c",
        "0000000000000000",
        "ffffffffffffffff",
        "0123456789abcdef",
        "null",
        "unknown",
        "android_id"
    )

    private val LOCK = Any()

    @Volatile
    private var cached: String? = null

    /**
     * 读取（必要时生成并持久化）本机稳定标识。任意线程可调用，内部串行化。
     *
     * 读取顺序：已存且格式合法 → 由 ANDROID_ID 确定性派生 → 随机 UUID。
     */
    fun get(context: Context): String {
        cached?.let { return it }
        val appContext = context.applicationContext ?: context
        synchronized(LOCK) {
            cached?.let { return it }
            val prefs = appContext.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(AppConfig.KEY_CLIENT_DEVICE_ID, null)?.trim()
            val resolved = if (isValid(stored)) {
                stored!!
            } else {
                generate(appContext)
            }
            if (resolved != stored) {
                prefs.edit()
                    .putString(AppConfig.KEY_CLIENT_DEVICE_ID, resolved)
                    .apply()
                // 不打印该标识（完整值或前 8 位均不输出）。
            }
            cached = resolved
            return resolved
        }
    }

    /** 格式校验：标准 UUID 或 8–64 位安全字符；空白/非法返回 false。 */
    fun isValid(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.length !in 8..64) return false
        if (UUID_PATTERN.matches(text)) return true
        return SAFE_PATTERN.matches(text)
    }

    private fun generate(context: Context): String {
        val androidId = readAndroidId(context)
        if (androidId != null) {
            return UUID.nameUUIDFromBytes(
                "dsh-mobile:$androidId:${BuildConfig.APPLICATION_ID}".toByteArray(Charsets.UTF_8)
            ).toString()
        }
        return UUID.randomUUID().toString()
    }

    /**
     * 读取可用的 ANDROID_ID；空值、已知坏值、读取异常时返回 null。
     *
     * 这里只把 ANDROID_ID 当作**去重标识的恢复种子**（单向散列派生的 UUID v3，不直接上报），
     * 不用于广告/分析，也不读取 IMEI 或硬件序列号；因此显式抑制 Lint 的 HardwareIds 提示。
     */
    @SuppressLint("HardwareIds")
    private fun readAndroidId(context: Context): String? {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "read ANDROID_ID failed: ${t.javaClass.simpleName}")
            null
        }?.trim()
        if (raw.isNullOrEmpty()) return null
        if (raw.lowercase() in BAD_ANDROID_IDS) return null
        return raw
    }
}
