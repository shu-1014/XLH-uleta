package com.iki.xlh.server

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.iki.xlh.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 星棱狐云端服务客户端 —— 对接服务器上的 `cloud.php`。
 *
 * 服务器端已经实现了完整能力，这里只按它的契约调用，不重复造轮子：
 *
 *   action=install   mac ver os channel          → {new, stats}
 *   action=online    mac ver os channel          → {stats, notice_ver, layout_ver}
 *   action=update    ver_code                    → {update, client_ver, need, force}
 *   action=notice                                → {enabled, title, content, ver, updated}
 *   action=ping                                  → {api_ver}
 *
 * 统一响应：`{code:200, msg, data:{...}, server_time, ts}`
 *
 * 云更新判定完全交给服务端：它比较 `update.ver_code > client_ver` 后给出 `need`，
 * 客户端再兜一层 `ver_code > BuildConfig.VERSION_CODE`，两边一致才会提示，
 * 避免服务端漏配时误弹。
 */
object KvServerClient {

    /** 业务站点根；cloud.php 在根目录。 */
    const val BASE_URL = "https://kv.wxlh.asia"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 证书是自签的，这里对本客户端放宽校验。
     *
     * 只作用于这个 OkHttpClient，不碰全局 client —— 全局还要处理
     * 模块仓库、更新检查这些正常 HTTPS 流量，不能一起降级。
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 一次调用结果。 */
    data class Result(
        val ok: Boolean,
        val code: Int,
        val msg: String,
        val data: JsonObject?,
    )

    /**
     * 机器码：Android ID 优先，取不到退回 Build 指纹。
     * 拼上包名，避免同机不同 App 撞同一个设备号。
     */
    @SuppressLint("HardwareIds")
    fun machineId(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
            ?: (android.os.Build.FINGERPRINT + android.os.Build.MODEL)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((androidId + "|" + context.packageName).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun osTag(): String = "android" + android.os.Build.VERSION.SDK_INT

    private suspend fun call(action: String, params: Map<String, String>): Result =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("action", action)
                .apply { params.forEach { (k, v) -> add(k, v) } }
                .build()

            val request = Request.Builder()
                .url("$BASE_URL/cloud.php")
                .post(body)
                .header("User-Agent", "StarFox/${BuildConfig.VERSION_NAME}")
                .build()

            runCatching {
                http.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: return@use Result(false, resp.code, "服务器返回非 JSON", null)
                    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
                    val msg = root["msg"]?.contentSafe().orEmpty()
                    val data = root["data"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    Result(code == 200, code, msg, data)
                }
            }.getOrElse {
                Result(false, -1, it.message ?: "网络错误", null)
            }
        }

    // ---------------------------------------------------------------- 接口

    suspend fun ping(): Result = call("ping", emptyMap())

    /** 上报安装（服务端按 mac 去重，只有首次算新增）。 */
    suspend fun install(context: Context): Result = call(
        "install",
        mapOf(
            "mac" to machineId(context),
            "ver" to BuildConfig.VERSION_NAME,
            "os" to osTag(),
            "channel" to "apk",
        ),
    )

    /** 心跳 + 拉统计。服务端会顺手刷新在线表。 */
    suspend fun online(context: Context): Result = call(
        "online",
        mapOf(
            "mac" to machineId(context),
            "ver" to BuildConfig.VERSION_NAME,
            "os" to osTag(),
            "channel" to "apk",
        ),
    )

    /** 云更新检查。 */
    suspend fun update(): Result = call(
        "update",
        mapOf("ver_code" to BuildConfig.VERSION_CODE.toString()),
    )

    suspend fun notice(): Result = call("notice", emptyMap())

    // ---------------------------------------------------------------- 解析

    /** `stats` 子对象：在线 / 装机 / 今日活跃。 */
    data class Stats(
        val online: Int = 0,
        val installTotal: Int = 0,
        val installToday: Int = 0,
        val activeToday: Int = 0,
        val onlineWindow: Int = 180,
    )

    fun parseStats(data: JsonObject?): Stats {
        val s = data?.get("stats")?.let { runCatching { it.jsonObject }.getOrNull() } ?: return Stats()
        fun i(k: String) = s[k]?.jsonPrimitive?.intOrNull ?: 0
        return Stats(
            online = i("online"),
            installTotal = i("install_total"),
            installToday = i("install_today"),
            activeToday = i("active_today"),
            onlineWindow = if (i("online_window") > 0) i("online_window") else 180,
        )
    }

    /** `update.update` 子对象。 */
    data class Release(
        val enabled: Boolean = false,
        val verCode: Int = 0,
        val verName: String = "",
        val url: String = "",
        val size: Long = 0,
        val changelog: String = "",
        val force: Boolean = false,
    )

    fun parseRelease(data: JsonObject?): Release {
        val u = data?.get("update")?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return Release()
        fun s(k: String) = u[k]?.contentSafe().orEmpty()
        fun i(k: String) = u[k]?.jsonPrimitive?.intOrNull ?: 0
        val forceField = u["force"]?.jsonPrimitive?.booleanOrNull
            ?: (i("force") != 0)
        return Release(
            enabled = (i("enabled") != 0),
            verCode = i("ver_code"),
            verName = s("ver_name"),
            url = s("url"),
            size = u["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            changelog = s("changelog"),
            force = forceField,
        )
    }

    /** 服务端给的 need 标记（它自己已经做过版本比较）。 */
    fun parseNeed(data: JsonObject?): Boolean =
        (data?.get("need")?.jsonPrimitive?.intOrNull ?: 0) == 1

    /** 公告：正文 + 版本号（版本变了就算新公告）。 */
    fun parseNotice(data: JsonObject?): Triple<Boolean, String, String> {
        if (data == null) return Triple(false, "", "")
        val enabled = (data["enabled"]?.jsonPrimitive?.intOrNull ?: 0) != 0
        val title = data["title"]?.contentSafe().orEmpty()
        val content = data["content"]?.contentSafe().orEmpty()
        val ver = data["ver"]?.jsonPrimitive?.intOrNull?.toString().orEmpty()
        val text = if (title.isNotBlank() && content.isNotBlank()) "$title\n$content" else content
        return Triple(enabled && text.isNotBlank(), text, ver)
    }

    /** 服务端 API 版本，用于排查「服务端没更新」。 */
    fun parseApiVer(data: JsonObject?): String =
        data?.get("api_ver")?.contentSafe().orEmpty()
}

/** `JsonNull.content` 会抛异常，统一兜一层。 */
private fun kotlinx.serialization.json.JsonElement.contentSafe(): String? =
    if (this is JsonNull) null else runCatching { (this as JsonPrimitive).content }.getOrNull()
