package com.iki.xlh.ui.stealth

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标隐藏（纯 PackageManager，不碰内核）。
 *
 * 与 MapleSU 的差异：
 *  MapleSU 只有一个 `.Launcher` 别名，禁用它就等于隐藏。
 *  SukiSU 底座用 **8 个 activity-alias** 轮换图标（默认 / 备选 / Logo / 线稿 × 静态版），
 *  当前生效的是哪一个取决于用户选的主题，所以隐藏必须**全部**禁用，
 *  否则用户换了图标之后隐藏就失效了。
 *
 *  恢复时只恢复默认别名，和 SukiSU 自己的图标切换逻辑保持一致。
 */
object StealthController {

    /** SukiSU 底座的 8 个 launcher 别名。新增别名时必须同步这里。 */
    private val LAUNCHER_ALIASES = listOf(
        "com.iki.xlh.ui.MainActivityAliasDefault",
        "com.iki.xlh.ui.MainActivityAlias",
        "com.iki.xlh.ui.MainActivityAliasLogo",
        "com.iki.xlh.ui.MainActivityAliasZako",
        "com.iki.xlh.ui.MainActivityAliasDefaultStatic",
        "com.iki.xlh.ui.MainActivityAliasStatic",
        "com.iki.xlh.ui.MainActivityAliasLogoStatic",
        "com.iki.xlh.ui.MainActivityAliasZakoStatic",
    )

    /** 恢复时启用的默认别名。 */
    private const val DEFAULT_ALIAS = "com.iki.xlh.ui.MainActivityAliasDefault"

    fun dialCode(context: Context): String = DialerRecoveryCode.dialCode(context)

    fun recoveryCode(context: Context): String = DialerRecoveryCode.value(context)

    private fun component(context: Context, name: String) = ComponentName(context, name)

    /**
     * 是否处于隐藏状态。
     *
     * 判据取默认别名：只要默认别名被禁用，就认为用户已经隐藏
     * （其余别名在隐藏时也一定被一起禁用了）。
     */
    fun isHidden(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(
            component(context, DEFAULT_ALIAS),
        ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }.getOrDefault(false)

    /**
     * 隐藏或恢复桌面图标。
     *
     * 隐藏：禁用全部别名。
     * 恢复：只启用默认别名，其余保持禁用——这与 SukiSU 的图标切换语义一致，
     *       不会因为这里恢复而把用户选的图标覆盖掉。
     */
    fun setHidden(context: Context, hidden: Boolean) {
        val pm = context.packageManager
        if (hidden) {
            LAUNCHER_ALIASES.forEach { alias ->
                runCatching {
                    pm.setComponentEnabledSetting(
                        component(context, alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        } else {
            runCatching {
                pm.setComponentEnabledSetting(
                    component(context, DEFAULT_ALIAS),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
