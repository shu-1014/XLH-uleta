package com.iki.xlh.server

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 星棱狐远程管理服务器客户端。
 *
 * 服务器只做「管理 root 管理器」这一件事：设备心跳、远程开关、公告下发。
 * 驱动 `.ko` 不走这里，仍然从 GitHub / 内置 libksud.so 取。
 *
 * 接口约定（服务端 api.php）：
 *   POST /api.php
 *     app_id  应用 ID
 *     key     卡密（免费模式传 FREE）
 *     mac     机器码
 *     action  verify | bind | query | unbind | heartbeat | remote | notice | online
 *   返回 { code, msg, data:{...}, t, sign }
 *
 * 证书是自签的，所以这里对**仅限本服务器域名**放宽校验；
 * 其他域名仍走系统 CA，避免把整个 App 的网络信任链打穿。
 */
object KvServerClient {

    const val BASE_URL = "https://kv.wxlh.asia"
    const val APP_ID = "APP6EG8X98H"

    /** 服务端若开启签名校验才会用到；当前 require_sign=0。 */
    private const val APP_SECRET = "Yb21f8U2bfDE2NUT0D8GeGIfC8Y61eFD"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 自签证书信任：只在无法用系统 CA 验证时回退。
     *
     * 做法是给 OkHttp 一个「宽松」的 SSLContext，但**只给本客户端用**，
     * 不要合并进全局 client —— 全局 client 还负责模块仓库、更新检查等 HTTPS 流量。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), java.security.SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 一次接口调用的结果。 */
    data class Result(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val data: JsonObject?,
        val raw: String,
    )

    /**
     * 机器码：Android ID 优先，取不到时退回 Build 指纹。
     *
     * 两端都拼上包名，避免同一台机器上不同 App 撞同一个设备号
     * （服务端按 mac 统计设备数，撞了就白占名额）。
     */
    @SuppressLint("HardwareIds")
    fun machineId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: (android.os.Build.FINGERPRINT + android.os.Build.MODEL)

        val seed = androidId + "|" + context.packageName
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** 计算请求签名（服务端 require_sign=1 时才需要）。 */
    private fun sign(vararg parts: String): String {
        val joined = (parts.toList() + APP_SECRET).joinToString("|")
        val digest = MessageDigest.getInstance("MD5").digest(joined.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    private suspend fun call(
        action: String,
        key: String,
        mac: String,
        withSign: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val body = FormBody.Builder()
            .add("app_id", APP_ID)
            .add("key", key)
            .add("mac", mac)
            .add("action", action)
            .apply {
                if (withSign) add("t", now.toString())
                if (withSign) add("sign", sign(APP_ID, key, mac, now.toString()))
            }
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/api.php")
            .post(body)
            .header("User-Agent", "StarFox-Manager")
            .build()

        runCatching {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (root == null) {
                    return@use Result(false, resp.code, "服务器返回非 JSON", null, text)
                }
                val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
                val msg = root["msg"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
                val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
                Result(code == 200, code, msg, data, text)
            }
        }.getOrElse {
            Result(false, -1, it.message ?: "网络错误", null, "")
        }
    }

    // ---------------------------------------------------------------- 业务动作

    suspend fun verify(key: String, mac: String) = call("verify", key, mac)

    suspend fun query(key: String, mac: String) = call("query", key, mac)

    suspend fun bind(key: String, mac: String) = call("bind", key, mac)

    suspend fun unbind(key: String, mac: String) = call("unbind", key, mac)

    suspend fun heartbeat(key: String, mac: String) = call("heartbeat", key, mac)

    suspend fun notice(key: String, mac: String) = call("notice", key, mac)

    suspend fun remote(key: String, mac: String) = call("remote", key, mac)

    suspend fun online() = call("online", "FREE", "")

    /** 上报一次安装（首次启动调用）；服务端返回累计安装次数。 */
    suspend fun reportInstall(mac: String) = call("install", "FREE", mac)

    /** 云更新：查最新版本。 */
    suspend fun checkVersion(mac: String) = call("version", "FREE", mac)

    // ---------------------------------------------------------------- data 解析

    /** 从 verify / query / heartbeat 的 data 里抽常用字段。 */
    data class CardInfo(
        val appName: String = "",
        val card: String = "",
        val type: String = "",
        val mode: String = "",
        val status: Int = 0,
        val expireAt: String? = null,
        val remainDays: Long? = null,
        val maxDevices: Int = 0,
        val firstBind: Boolean = false,
        val serverTime: String? = null,
    )

    fun parseCard(data: JsonObject?): CardInfo {
        if (data == null) return CardInfo()
        fun str(k: String) = data[k]?.let { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }
        fun num(k: String) = data[k]?.jsonPrimitive?.intOrNull
        fun lng(k: String) = data[k]?.jsonPrimitive?.longOrNull
        fun bool(k: String) = data[k]?.jsonPrimitive?.booleanOrNull
        return CardInfo(
            appName = str("app_name").orEmpty(),
            card = str("card").orEmpty(),
            type = str("type").orEmpty(),
            mode = str("mode").orEmpty(),
            status = num("status") ?: 0,
            expireAt = str("expire_at"),
            remainDays = lng("remain_days"),
            maxDevices = num("max_devices") ?: 0,
            firstBind = bool("first_bind") ?: false,
            serverTime = str("server_time"),
        )
    }

    /** online 接口的在线人数。 */
    fun parseOnline(data: JsonObject?): Int =
        data?.get("online")?.jsonPrimitive?.intOrNull ?: 0

    /** online / install 接口的累计安装次数。 */
    fun parseInstalls(data: JsonObject?): Int =
        data?.get("installs")?.jsonPrimitive?.intOrNull ?: 0

    /** notice 接口的公告正文；空串表示没有公告。 */
    fun parseNotice(data: JsonObject?): Pair<String, String> {
        if (data == null) return "" to ""
        val text = data["notice"]?.let { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }.orEmpty()
        val id = data["notice_id"]?.let { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }.orEmpty()
        return text.trim() to id.trim()
    }

    /** version 接口的云更新信息。 */
    data class VersionInfo(
        val latest: String = "",
        val versionCode: Int = 0,
        val url: String = "",
        val changelog: String = "",
        val force: Boolean = false,
        val size: Long = 0L,
    )

    fun parseVersion(data: JsonObject?): VersionInfo {
        if (data == null) return VersionInfo()
        fun str(k: String) = data[k]?.let { runCatching { it.jsonPrimitive.contentOrNullSafe() }.getOrNull() }.orEmpty()
        return VersionInfo(
            latest = str("latest").ifBlank { str("version_name") },
            versionCode = data["version_code"]?.jsonPrimitive?.intOrNull ?: 0,
            url = str("url").ifBlank { str("download") },
            changelog = str("changelog").ifBlank { str("note") },
            force = data["force"]?.jsonPrimitive?.booleanOrNull ?: false,
            size = data["size"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}

/** JSON 里值为 null 时 jsonPrimitive.content 会抛异常，这里统一兜一层。 */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
