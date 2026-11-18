package com.iki.xlh.server

import android.content.Context
import android.content.SharedPreferences

/**
 * 远程管理服务器的本地状态。
 *
 * 只存「用户填了什么」和「上次拿到的结果」；不存任何密钥。
 */
data class KvServerState(
    /** 用户填的卡密；免费模式留空即视为 FREE。 */
    val cardKey: String = "",
    /** 上次验证是否通过。 */
    val verified: Boolean = false,
    /** 应用名（服务端返回）。 */
    val appName: String = "",
    /** 卡密类型：free / paid。 */
    val cardType: String = "",
    /** 到期时间或剩余天数文案，直接给界面用。 */
    val expireText: String = "",
    /** 上次同步时间（本地）。 */
    val lastSyncAt: Long = 0L,
    /** 上次错误信息；成功后清空。 */
    val lastError: String = "",
    /** 在线设备数（online 接口）。 */
    val onlineCount: Int = 0,
    /** 公告正文（notice 接口），空表示没有公告。 */
    val notice: String = "",
    /** 远程开关：服务端下发，本地只读。 */
    val remoteEnabled: Boolean = true,
    /** 心跳是否开启（用户可关）。 */
    val heartbeatEnabled: Boolean = true,
)

object KvServerStore {

    private const val PREF = "starfox_server"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun load(context: Context): KvServerState {
        val p = prefs(context)
        return KvServerState(
            cardKey = p.getString("card_key", "").orEmpty(),
            verified = p.getBoolean("verified", false),
            appName = p.getString("app_name", "").orEmpty(),
            cardType = p.getString("card_type", "").orEmpty(),
            expireText = p.getString("expire_text", "").orEmpty(),
            lastSyncAt = p.getLong("last_sync", 0L),
            lastError = p.getString("last_error", "").orEmpty(),
            onlineCount = p.getInt("online", 0),
            notice = p.getString("notice", "").orEmpty(),
            remoteEnabled = p.getBoolean("remote_enabled", true),
            heartbeatEnabled = p.getBoolean("heartbeat", true),
        )
    }

    fun save(context: Context, state: KvServerState) {
        prefs(context).edit()
            .putString("card_key", state.cardKey)
            .putBoolean("verified", state.verified)
            .putString("app_name", state.appName)
            .putString("card_type", state.cardType)
            .putString("expire_text", state.expireText)
            .putLong("last_sync", state.lastSyncAt)
            .putString("last_error", state.lastError)
            .putInt("online", state.onlineCount)
            .putString("notice", state.notice)
            .putBoolean("remote_enabled", state.remoteEnabled)
            .putBoolean("heartbeat", state.heartbeatEnabled)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
