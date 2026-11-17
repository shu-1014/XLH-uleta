package com.iki.xlh.starfox

import com.topjohnwu.superuser.Shell

/**
 * 星棱狐自研 root / 内核模块识别器。
 *
 * 完全不走上游那套「信任 Natives 握手结果」的单点判定，改成五路独立证据，
 * 任何一路过就是有 root。这样换包名、换模块名、换 .ko 编译者都认得出来。
 *
 * 五路证据：
 *   1. 模块台账     /proc/modules 里有没有 ksu 系模块（名字可配）
 *   2. 模块目录     /sys/module/<name> 在不在（LKM 已 insmod 的硬证据）
 *   3. 守护进程     /data/adb/ksu/bin/ksud 存不存在 + 版本号
 *   4. 星棱狐标记   /data/adb/xlh/root.marker（刷入时由 ramdisk 钩子写）
 *   5. 实测          su -c id 真的拿到 uid=0
 */
object StarFoxDetector {

    /** 认哪些模块名。改过内核 Kbuild 的话把新名字加进来就行。 */
    val KNOWN_MODULE_NAMES = listOf(
        "xlh",          // 星棱狐自己的模块名
        "starfox",
        "kernelsu",     // 上游兼容名
        "kernelsu_next",
        "sukisu",
        "ksu",
    )

    private const val XLH_DIR = "/data/adb/xlh"
    private const val KSU_DIR = "/data/adb/ksu"
    private const val CACHE_TTL_MS = 30_000L

    @Volatile
    private var cached: StarFoxRootStatus? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** 一次完整探测，带 30 秒缓存。 */
    fun probe(force: Boolean = false): StarFoxRootStatus {
        val now = System.currentTimeMillis()
        val hit = cached
        if (!force && hit != null && now - cachedAt < CACHE_TTL_MS) return hit
        val fresh = doProbe()
        cached = fresh
        cachedAt = now
        return fresh
    }

    /** 给同步调用点用的快速判定。 */
    fun isRootCached(): Boolean = probe().rootOk

    private fun sh(commands: Array<out String>): Pair<Boolean, List<String>> {
        return runCatching {
            val r = Shell.cmd(*commands).exec()
            r.isSuccess to r.out.map { it.trim() }
        }.getOrDefault(false to emptyList())
    }

    private fun exists(path: String): Boolean {
        val (ok, out) = sh(arrayOf("test -e '$path' && echo __YES__ || echo __NO__"))
        return ok && out.any { it == "__YES__" }
    }

    private fun doProbe(): StarFoxRootStatus {
        val trace = mutableListOf<String>()

        // ① 模块台账
        val modules = readLoadedModules()
        val matched = modules.firstOrNull { mod ->
            KNOWN_MODULE_NAMES.any { known -> mod.lowercase().contains(known) }
        }
        trace += if (matched != null) "① 模块台账：命中 $matched" else "① 模块台账：无 ksu 系模块"

        // ② /sys/module 目录
        var moduleDir: String? = matched ?: KNOWN_MODULE_NAMES.firstOrNull { exists("/sys/module/$it") }
        trace += if (moduleDir != null) "② 模块目录：/sys/module/$moduleDir 在" else "② 模块目录：一个都没有"

        // ③ 守护进程
        val ksudPath = "$KSU_DIR/bin/ksud"
        val ksudThere = exists(ksudPath)
        var ksudVersion: String? = null
        if (ksudThere) {
            val (ok, out) = sh(arrayOf("chmod 0755 '$ksudPath' 2>/dev/null; '$ksudPath' --version 2>/dev/null | head -n 1"))
            if (ok) ksudVersion = out.firstOrNull { it.isNotBlank() }
        }
        trace += if (ksudThere) "③ 守护进程：ksud 在" else "③ 守护进程：没有 ksud"

        // ④ 星棱狐标记
        val marker = exists("$XLH_DIR/root.marker")
        trace += if (marker) "④ 星棱狐标记：有" else "④ 星棱狐标记：无"

        // ⑤ 实测 uid
        val (idOk, idOut) = sh(arrayOf("id -u"))
        val realUid = idOut.firstOrNull()?.trim()?.toIntOrNull()
        trace += "⑤ 实测 uid：${realUid ?: "未知"}"

        // 内核版本
        val kernel = sh(arrayOf("uname -r")).second.firstOrNull() ?: "unknown"

        // 管理器登记（星棱狐自己的登记文件）
        val registered = exists("$XLH_DIR/manager.registered") || exists("$KSU_DIR/.manager_registered")

        val rootOk = realUid == 0 ||
            (matched != null && ksudThere) ||
            (moduleDir != null && marker)

        return StarFoxRootStatus(
            rootOk = rootOk,
            kernelVersion = kernel,
            moduleLoaded = matched != null || moduleDir != null,
            moduleName = moduleDir ?: matched,
            daemonPath = if (ksudThere) ksudPath else null,
            daemonVersion = ksudVersion,
            markerPresent = marker,
            realUid = realUid,
            managerRegistered = registered,
            trace = trace,
        )
    }

    private fun readLoadedModules(): List<String> {
        val (ok, out) = sh(arrayOf("cat /proc/modules 2>/dev/null"))
        if (!ok) return emptyList()
        return out.mapNotNull { line ->
            line.substringBefore(' ').takeIf { it.isNotBlank() }
        }
    }

    /** 把当前管理器登记成内核认可的管理器（写星棱狐自己的登记文件）。 */
    fun registerSelf(packageName: String): StarFoxResult {
        val (ok, out) = sh(
            arrayOf(
                "mkdir -p '$XLH_DIR'",
                "chmod 0755 '$XLH_DIR'",
                "printf '%s' '$packageName' > '$XLH_DIR/manager.package'",
                "chmod 0644 '$XLH_DIR/manager.package'",
                "printf '1' > '$XLH_DIR/manager.registered'",
                "chmod 0644 '$XLH_DIR/manager.registered'",
            )
        )
        return StarFoxResult(ok, out.joinToString("\n"))
    }

    /** 写星棱狐自己的 root 标记，让下一次探测直接过。 */
    fun writeRootMarker(kernelVersion: String): StarFoxResult {
        val (ok, out) = sh(
            arrayOf(
                "mkdir -p '$XLH_DIR'",
                "printf '%s' '$kernelVersion' > '$XLH_DIR/root.marker'",
                "chmod 0644 '$XLH_DIR/root.marker'",
            )
        )
        return StarFoxResult(ok, out.joinToString("\n"))
    }

    /** 识别详情，给增强中心直接展示。 */
    fun readableSummary(status: StarFoxRootStatus = probe()): String = buildString {
        appendLine("内核：${status.kernelVersion}")
        appendLine("模块：${status.moduleName ?: "未加载"}")
        appendLine("守护：${status.daemonVersion ?: "未发现"}")
        appendLine("uid：${status.realUid ?: "未知"}")
        appendLine("登记：${if (status.managerRegistered) "已登记" else "未登记"}")
        appendLine("判定：${if (status.rootOk) "已 Root" else "未 Root"}")
        append("轨迹：")
        append(status.trace.joinToString(" | "))
    }
}

/** 识别结果。 */
data class StarFoxRootStatus(
    val rootOk: Boolean,
    val kernelVersion: String,
    val moduleLoaded: Boolean,
    val moduleName: String?,
    val daemonPath: String?,
    val daemonVersion: String?,
    val markerPresent: Boolean,
    val realUid: Int?,
    val managerRegistered: Boolean,
    val trace: List<String>,
)
