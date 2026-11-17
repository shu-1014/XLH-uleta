package com.iki.xlh.ui.stealth

import com.iki.xlh.Natives

/**
 * 内核隐身开关（适配 SukiSU 底座）。
 *
 * 来源与差异：
 *  - MapleSU / 7kimisu 的内核只有一个 `stealth` 总开关（`Natives.stealthState/stealthSet`）。
 *  - SukiSU 内核没有这个 ioctl，但它把隐身拆成了更细的三项，实际覆盖更全：
 *      · 挂载隐藏   [Natives.isMountHideEnabled] / [Natives.setMountHideEnabled]
 *      · SELinux 隐藏 [Natives.isSelinuxHideEnabled] / [Natives.setSelinuxHideEnabled]
 *      · AVC 伪装   [Natives.isAvcSpoofEnabled]  / [Natives.setAvcSpoofEnabled]
 *
 * 这里做一层映射，对上层保持 MapleSU 那套「一个总开关 + 三态读数」的语义：
 *  - 三项全开 → [State.ON]
 *  - 三项全关 → [State.OFF]
 *  - 其余情况（部分开 / 读不到 / 内核不支持）→ [State.UNKNOWN]
 *
 * 为什么必须有 UNKNOWN：读失败不等于「关」。可能是内核没有这个接口、
 * 当前 APK 不是被内核认可的管理器、或者调用被拒绝。把它报成「关」会让
 * 用户以为自己已经不安全，而实际可能根本没查到。
 */
object Stealth {

    /** 三项隐身开关的统一读写口。任一项抛异常都当作读不到。 */
    private fun readStates(): List<Boolean?> = listOf(
        runCatching { Natives.isMountHideEnabled() }.getOrNull(),
        runCatching { Natives.isSelinuxHideEnabled() }.getOrNull(),
        runCatching { Natives.isAvcSpoofEnabled() }.getOrNull(),
    )

    enum class State {
        /** 三项都明确为开。 */
        ON,

        /** 三项都明确为关。 */
        OFF,

        /** 至少一项读不到，或三项不一致。 */
        UNKNOWN,
    }

    fun state(): State {
        val s = readStates()
        if (s.any { it == null }) return State.UNKNOWN
        return when {
            s.all { it == true } -> State.ON
            s.all { it == false } -> State.OFF
            else -> State.UNKNOWN
        }
    }

    /** 只有三项都被明确确认开着，才算开启。 */
    fun isEnabled(): Boolean = state() == State.ON

    /**
     * 统一切换三项隐身为 [enabled]。
     *
     * 返回 true 表示三项都写成功。部分成功也返回 false，由调用方决定是否提示；
     * 这里不回滚——回滚本身也可能失败，反而把状态搅得更乱。
     */
    fun setEnabled(enabled: Boolean): Boolean {
        val results = listOf(
            runCatching { Natives.setMountHideEnabled(enabled) }.getOrDefault(-1),
            runCatching { Natives.setSelinuxHideEnabled(enabled) }.getOrDefault(-1),
            runCatching { Natives.setAvcSpoofEnabled(enabled) }.getOrDefault(false),
        )
        // 两个 Int 接口 0 表示成功，Boolean 接口 true 表示成功
        val ok = results[0] == 0 && results[1] == 0 && results[2] == true
        return ok
    }

    /** 三项各自的当前值，给界面逐条展示用。读不到的项为 null。 */
    fun detail(): Map<String, Boolean?> = mapOf(
        "mount_hide" to runCatching { Natives.isMountHideEnabled() }.getOrNull(),
        "selinux_hide" to runCatching { Natives.isSelinuxHideEnabled() }.getOrNull(),
        "avc_spoof" to runCatching { Natives.isAvcSpoofEnabled() }.getOrNull(),
    )
}
