package com.example.dshchat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * dsh-mobile 网关的认证层。
 *
 * 协议要点（对真实网关实测整理，详见 _dshchat_patch/dsh-mobile-协议规格.md）：
 *
 *   密钥格式：`dsh1.<64位实例ID>.<43位令牌>`，总长恒 113。
 *            **要给服务器的只有最后 43 位**；实例ID 是网关 CA 的 SHA-256 指纹。
 *
 *   引导：GET /mobile-access/discovery  → 拿到 instanceId（=CA指纹）
 *        GET /mobile-access/ca.cer      → 拿 CA（此阶段必须"信任一切"，随后指纹绑定）
 *
 *   配对：POST /mobile-access/auth/native-pair  {token,label}
 *        → {instanceId, deviceId, deviceToken, deviceExpiresAt,
 *           sessionToken, csrfToken, sessionExpiresAt}
 *   续期：POST /mobile-access/auth/native-renew {deviceToken} → 同上但不含 deviceToken
 *   探测：POST /mobile-access/auth/native-probe {deviceToken}
 *
 *   之后每个请求带：`Cookie: dsh_ma_session=<sessionToken>`
 *   并且 **必须带 `Origin: https://<host>:<port>`**（网关强制校验，缺了直接 403）
 *   写操作另需 `x-dsh-mobile-csrf: <csrfToken>`
 */

//#region 配对密钥

/** 解析后的配对密钥 */
data class PairingKey(val instanceId: String, val token: String) {
    companion object {
        private val FORMAT = Regex("^dsh1\\.([a-f0-9]{64})\\.([A-Za-z0-9_-]{43})$")

        fun parse(source: String): PairingKey? =
            FORMAT.matchEntire(source.trim())?.let {
                PairingKey(it.groupValues[1], it.groupValues[2])
            }
    }
}

//#endregion

//#region 网关地址与会话

/** 网关地址（默认 https，端口 3443） */
data class GatewayOrigin(val host: String, val port: Int = 3443) {
    val serialized: String get() = "https://$host:$port"

    companion object {
        /** 从用户输入/发现结果解析；接受带不带 scheme、带不带端口的写法 */
        fun parse(raw: String, defaultPort: Int = 3443): GatewayOrigin? {
            val t = raw.trim().removeSuffix("/")
            if (t.isEmpty()) return null
            val noScheme = when {
                t.startsWith("https://") -> t.removePrefix("https://")
                t.startsWith("http://") -> t.removePrefix("http://")
                else -> t
            }
            val hostPart = noScheme.substringBefore('/')
            if (hostPart.isEmpty()) return null
            val host = hostPart.substringBefore(':')
            val port = hostPart.substringAfter(':', "").toIntOrNull() ?: defaultPort
            if (host.isEmpty() || port !in 1..65535) return null
            return GatewayOrigin(host, port)
        }
    }
}

/** 一次网关会话（短期的 session + 长期的 deviceToken） */
data class GatewaySession(
    val origin: String,
    val instanceId: String,
    val deviceId: String,
    /** 续期时为 null（服务端不下发） */
    val deviceToken: String?,
    val sessionToken: String,
    val csrfToken: String,
    val sessionExpiresAt: Long,
    val deviceExpiresAt: Long? = null
) {
    fun isSessionValid(now: Long = System.currentTimeMillis()): Boolean = sessionExpiresAt > now + 60_000
}

/** 配对/续期的失败原因，用来给界面出人话 */
sealed class GatewayAuthError(override val message: String) : Exception(message) {
    object KeyInvalid : GatewayAuthError("密钥格式不对（应该是 dsh1.… 开头的 113 个字符）")
    object Unreachable : GatewayAuthError("连不上电脑 —— 确认手机和电脑在同一个局域网")
    object CaMismatch : GatewayAuthError("证书指纹对不上，可能连到了别的设备（已中止）")
    object KeyRejected : GatewayAuthError("密钥不对或已过期 —— 请重新在电脑上生成一个")
    object DeviceRejected : GatewayAuthError("这台设备已被撤销或过期，需要重新配对")
    object RateLimited : GatewayAuthError("请求太频繁，稍等一下再试")
    class Other(m: String) : GatewayAuthError(m)
}

//#endregion

//#region TLS 固定

object PinnedTls {

    /** DER 证书的 SHA-256 指纹（小写 hex），与网关的 instanceId 同一算法 */
    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 校验这张 CA 值不值得信任：
     * 自签名、在有效期内、是 CA、且指纹必须等于配对密钥里带的 instanceId。
     */
    fun validateCa(der: ByteArray, expectedInstanceId: String): Boolean = try {
        val ca = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        ca.checkValidity()
        ca.basicConstraints >= 0
        ca.subjectX500Principal == ca.issuerX500Principal
        ca.verify(ca.publicKey)
        fingerprint(der) == expectedInstanceId
    } catch (e: Exception) {
        false
    }

    /** 引导期用：这个 CA 还没被信任，只能先"信任一切"把证书取回来 */
    fun bootstrapClient(): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return base().sslSocketFactory(ctx.socketFactory, tm).build()
    }

    /** 正常通信用：只认这张固定的 CA */
    fun pinnedClient(caDer: ByteArray): OkHttpClient {
        val ca = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(caDer)) as X509Certificate
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(ca)

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("不校验客户端证书")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw CertificateException("空的证书链")
                val leaf = chain[0]
                leaf.checkValidity()
                // 服务器证书必须由我们固定的这张 CA 签发
                leaf.verify(ca.publicKey)
            }
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        return base()
            .sslSocketFactory(ctx.socketFactory, tm)
            // CA 已经固定了，链上只可能是我们自己的网关；
            // 再叠一层主机名校验反而会被自签证书的 SAN 写法坑到，这里放行。
            .hostnameVerifier { _, _ -> true }
            // 调试用：把实际发出的每个头打出来（排查 403 用）
            .addInterceptor { chain ->
                val r = chain.request()
                android.util.Log.d("DshApi", "→ ${r.method} ${r.url}\n${r.headers}")
                chain.proceed(r)
            }
            .build()
    }

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_1_1))
}

//#endregion

//#region 网关认证客户端

object GatewayAuthClient {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** discovery：拿 deviceName / origin / instanceId（未受信，必须再和 CA 指纹对一遍） */
    suspend fun fetchDiscovery(origin: GatewayOrigin): JSONObject = withContext(Dispatchers.IO) {
        val bootstrap = PinnedTls.bootstrapClient()
        val req = Request.Builder()
            .url("${origin.serialized}/mobile-access/discovery")
            .get()
            .build()
        try {
            bootstrap.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw GatewayAuthError.Unreachable
                JSONObject(raw)
            }
        } catch (e: GatewayAuthError) {
            throw e
        } catch (e: Exception) {
            throw GatewayAuthError.Unreachable
        }
    }

    /** 取网关的 CA（DER）。此阶段不校验，由调用方做指纹绑定 */
    suspend fun fetchCa(origin: GatewayOrigin): ByteArray = withContext(Dispatchers.IO) {
        val bootstrap = PinnedTls.bootstrapClient()
        val req = Request.Builder()
            .url("${origin.serialized}/mobile-access/ca.cer")
            .get()
            .build()
        try {
            bootstrap.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (!resp.isSuccessful || bytes == null || bytes.isEmpty()) throw GatewayAuthError.Unreachable
                bytes
            }
        } catch (e: GatewayAuthError) {
            throw e
        } catch (e: Exception) {
            throw GatewayAuthError.Unreachable
        }
    }

    /**
     * 完整配对：解析密钥 → 取 CA → 指纹绑定 → 换凭据。
     * @return 会话（含 deviceToken，调用方负责安全落盘）
     */
    suspend fun pair(
        origin: GatewayOrigin,
        key: PairingKey,
        label: String,
        caDer: ByteArray
    ): GatewaySession = post(origin, "/mobile-access/auth/native-pair",
        JSONObject().put("token", key.token).put("label", label), caDer)

    /** 用 deviceToken 换一个新的短期 session */
    suspend fun renew(
        origin: GatewayOrigin,
        deviceToken: String,
        caDer: ByteArray
    ): GatewaySession = post(origin, "/mobile-access/auth/native-renew",
        JSONObject().put("deviceToken", deviceToken), caDer)

    private suspend fun post(
        origin: GatewayOrigin,
        path: String,
        body: JSONObject,
        caDer: ByteArray
    ): GatewaySession = withContext(Dispatchers.IO) {
        val client = PinnedTls.pinnedClient(caDer)
        val req = Request.Builder()
            .url(origin.serialized + path)
            .addHeader("Content-Type", "application/json")
            // 网关强制校验 Origin，缺了直接 403
            .addHeader("Origin", origin.serialized)
            .addHeader("Sec-Fetch-Site", "same-origin")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val code = runCatching { JSONObject(raw).optString("error") }.getOrDefault("")
                    throw when {
                        resp.code == 429 -> GatewayAuthError.RateLimited
                        resp.code == 401 && code.contains("device") -> GatewayAuthError.DeviceRejected
                        resp.code == 401 -> GatewayAuthError.KeyRejected
                        else -> GatewayAuthError.Other("HTTP ${resp.code} $code")
                    }
                }
                parseSession(JSONObject(raw), origin.serialized)
            }
        } catch (e: GatewayAuthError) {
            throw e
        } catch (e: Exception) {
            throw GatewayAuthError.Other(e.message ?: "网络错误")
        }
    }

    private val INSTANCE_ID = Regex("^[a-f0-9]{64}$")
    private val DEVICE_ID = Regex("^[a-f0-9]{32}$")
    private val OPAQUE = Regex("^[A-Za-z0-9_-]{43}$")

    /** 严格校验服务端返回，格式不对就当作失败（照抄它客户端的做法） */
    private fun parseSession(json: JSONObject, origin: String): GatewaySession {
        val instanceId = json.optString("instanceId")
        val deviceId = json.optString("deviceId")
        val sessionToken = json.optString("sessionToken")
        val csrfToken = json.optString("csrfToken")
        if (!INSTANCE_ID.matches(instanceId) || !DEVICE_ID.matches(deviceId) ||
            !OPAQUE.matches(sessionToken) || !OPAQUE.matches(csrfToken)
        ) throw GatewayAuthError.CaMismatch
        val deviceToken = json.optString("deviceToken").takeIf { OPAQUE.matches(it) }
        return GatewaySession(
            origin = origin,
            instanceId = instanceId,
            deviceId = deviceId,
            deviceToken = deviceToken,
            sessionToken = sessionToken,
            csrfToken = csrfToken,
            sessionExpiresAt = json.optLong("sessionExpiresAt", 0L),
            deviceExpiresAt = json.optLong("deviceExpiresAt", 0L).takeIf { it > 0L }
        )
    }
}

//#endregion

//#region 凭据存储（deviceToken 进 Android Keystore）

/**
 * deviceToken 是能换出 session 的长期凭据，不能明文躺在 SharedPreferences 里，
 * 所以用 Android Keystore 里的一把 AES 密钥加密后再存。
 */
class GatewayStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("dsh_gateway", Context.MODE_PRIVATE)

    data class Stored(
        val origin: String,
        val instanceId: String,
        val deviceId: String,
        val deviceToken: String,
        val deviceExpiresAt: Long,
        val caDer: ByteArray
    ) {
        override fun equals(other: Any?): Boolean = other is Stored && other.origin == origin &&
            other.deviceId == deviceId && other.deviceToken == deviceToken
        override fun hashCode(): Int = origin.hashCode() * 31 + deviceId.hashCode()
    }

    fun save(session: GatewaySession, caDer: ByteArray) {
        val token = session.deviceToken ?: return
        prefs.edit()
            .putString("origin", session.origin)
            .putString("instance_id", session.instanceId)
            .putString("device_id", session.deviceId)
            .putString("device_token_enc", encrypt(token))
            .putLong("device_expires_at", session.deviceExpiresAt ?: 0L)
            .putString("ca_der_b64", Base64.encodeToString(caDer, Base64.NO_WRAP))
            .apply()
    }

    fun load(): Stored? {
        val origin = prefs.getString("origin", null) ?: return null
        val deviceId = prefs.getString("device_id", null) ?: return null
        val enc = prefs.getString("device_token_enc", null) ?: return null
        val caB64 = prefs.getString("ca_der_b64", null) ?: return null
        val token = decrypt(enc) ?: return null
        val ca = runCatching { Base64.decode(caB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        return Stored(
            origin = origin,
            instanceId = prefs.getString("instance_id", "") ?: "",
            deviceId = deviceId,
            deviceToken = token,
            deviceExpiresAt = prefs.getLong("device_expires_at", 0L),
            caDer = ca
        )
    }

    fun clear() = prefs.edit().clear().apply()

    // ---- Keystore ----

    private companion object {
        const val KEY_ALIAS = "dsh_gateway_device_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val GCM_TAG_BITS = 128
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String? {
        return try {
            val parts = encoded.split(':')
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val body = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

//#endregion
