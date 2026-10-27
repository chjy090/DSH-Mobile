package ai.deepseek.dsh.mobile

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 认证网关 deviceToken 的安全存储（v0.4.0）。
 *
 * 设计：
 * - 在 Android Keystore 生成/复用 AES-256-GCM 密钥（不要求用户认证，App 后台续期可用）；
 * - deviceToken 用该密钥加密后，以 `版本:Base64(iv):Base64(ciphertext)` 写入
 *   SharedPreferences；
 * - 每个服务器单独一条记录，pref key = `dsh_auth_dev_<sha256(host:port).take(16)>`，
 *   避免换服务器时把另一台设备的 token 发错 host；
 * - Keystore/加解密/SharedPreferences 任一环节异常时降级为“仅内存”：本次进程内
 *   续期仍可用，不崩溃、不把明文写盘。
 *
 * 任何日志都不包含 deviceToken 明文或密文。
 */
class DeviceTokenStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE)

    /** Keystore 不可用时的进程内降级存储；key 与 SharedPreferences 的 pref key 相同。 */
    private val memoryTokens = ConcurrentHashMap<String, String>()

    private val keyLock = Any()

    /** 加密并持久化；返回 true 表示已落盘，false 表示仅内存（仍可用）。 */
    fun save(baseUrl: String, token: String): Boolean {
        val prefKey = prefsKeyFor(baseUrl) ?: return false
        val value = token.trim()
        if (value.isEmpty()) {
            clear(baseUrl)
            return true
        }
        memoryTokens[prefKey] = value
        return try {
            val payload = encrypt(value)
            prefs.edit().putString(prefKey, payload).apply()
            true
        } catch (t: Throwable) {
            // 注意：异常信息可能包含 Keystore 细节，但不含 token 本身。
            Log.w(TAG, "persist device token failed, fallback to memory only", t)
            false
        }
    }

    /** 读取并解密 deviceToken；没有记录或解密失败返回 null。 */
    fun load(baseUrl: String): String? {
        val prefKey = prefsKeyFor(baseUrl) ?: return null
        memoryTokens[prefKey]?.let { return it }
        val stored = try {
            prefs.getString(prefKey, null)
        } catch (t: Throwable) {
            Log.w(TAG, "read device token failed", t)
            null
        } ?: return null
        return try {
            decrypt(stored).also { memoryTokens[prefKey] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "decrypt device token failed", t)
            null
        }
    }

    /** 删除该服务器的 deviceToken（内存 + 磁盘）。 */
    fun clear(baseUrl: String) {
        val prefKey = prefsKeyFor(baseUrl) ?: return
        memoryTokens.remove(prefKey)
        try {
            prefs.edit().remove(prefKey).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "remove device token failed", t)
        }
    }

    // ---------------------------------------------------------------- 加密实现

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return buildString {
            append(FORMAT_VERSION)
            append(':')
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    private fun decrypt(payload: String): String {
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == FORMAT_VERSION.toString()) {
            "unsupported device token payload"
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** 取/建 Android Keystore 中的 AES-256-GCM key（App 级，按服务器分记录）。 */
    private fun secretKey(): SecretKey = synchronized(keyLock) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) {
            existing
        } else {
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // 后台自动续期不能弹锁屏/生物识别，因此不绑定用户认证。
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKey()
        }
    }

    companion object {

        private const val TAG = "DSHDeviceToken"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "dsh_auth_device_token_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val FORMAT_VERSION = 1

        /** SharedPreferences 中每条 deviceToken 记录的前缀。 */
        private const val PREF_PREFIX = "dsh_auth_dev_"

        /**
         * 从服务器地址取 `host:port`（默认端口补全、host 小写），用于派生 pref key。
         * 非法地址返回 null。
         */
        fun hostPortOf(baseUrl: String): String? {
            val raw = baseUrl.trim()
            if (raw.isEmpty()) return null
            val uri = try {
                Uri.parse(raw)
            } catch (t: Throwable) {
                return null
            }
            val host = uri.host?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
                ?: return null
            val port = when {
                uri.port != -1 -> uri.port
                "https".equals(uri.scheme, ignoreCase = true) -> 443
                else -> 80
            }
            if (port !in 1..65535) return null
            return "$host:$port"
        }

        /** `dsh_auth_dev_<sha256(host:port).take(16)>`；非法地址返回 null。 */
        fun prefsKeyFor(baseUrl: String): String? {
            val hostPort = hostPortOf(baseUrl) ?: return null
            val digest = CertPin.sha256Hex(hostPort.toByteArray(Charsets.UTF_8)) ?: return null
            return PREF_PREFIX + digest.take(16)
        }
    }
}
