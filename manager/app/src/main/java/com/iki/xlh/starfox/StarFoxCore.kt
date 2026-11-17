package com.iki.xlh.starfox

import android.content.Context
import android.content.SharedPreferences
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 星棱狐底层双模式。
 *
 * KSU  : 保留完整的 KSU 模块生态（Zygisk / LSPosed 直接可用），
 *        内核补丁走常规 KSU 路径。
 * ULTRA: 内核层直接实现 SU 授权，0 挂载、不改 SELinux 策略，
 *        从根源上抹掉侧信道痕迹。
 */
enum class StarFoxMode(val id: String) {
    KSU("ksu"),
    ULTRA("ultra");

    companion object {
        fun fromId(value: String?): StarFoxMode =
            StarFoxMode.entries.firstOrNull { it.id == value } ?: KSU
    }
}

/** 管理器持久化的开关集合。所有开关都会落到 /data/adb/xlh/ 里，内核侧读取。 */
data class StarFoxState(
    val mode: StarFoxMode = StarFoxMode.KSU,
    val stealth: Boolean = false,
    val teeSim: Boolean = false,
    val zygiskCompat: Boolean = true,
    val panicHardening: Boolean = true,
    val webManager: Boolean = false,
)

/** SharedPreferences 存储层，包名换过也不影响：文件名是固定的。 */
object StarFoxStore {

    private const val PREF_NAME = "starfox_manager"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): StarFoxState {
        val p = prefs(context)
        return StarFoxState(
            mode = StarFoxMode.fromId(p.getString("mode", null)),
            stealth = p.getBoolean("stealth", false),
            teeSim = p.getBoolean("tee", false),
            zygiskCompat = p.getBoolean("zygisk", true),
            panicHardening = p.getBoolean("panic", true),
            webManager = p.getBoolean("webui", false),
        )
    }

    fun save(context: Context, state: StarFoxState) {
        prefs(context).edit()
            .putString("mode", state.mode.id)
            .putBoolean("stealth", state.stealth)
            .putBoolean("tee", state.teeSim)
            .putBoolean("zygisk", state.zygiskCompat)
            .putBoolean("panic", state.panicHardening)
            .putBoolean("webui", state.webManager)
            .apply()
    }
}

/** 一条 shell 执行结果，直接给 UI 用。 */
data class StarFoxResult(
    val ok: Boolean,
    val output: String,
)

/**
 * 星棱狐底层操作。
 *
 * 全部走 libsu，调用的是已授权的 su；路径统一收口到 /data/adb/xlh，
 * 内核补丁读这些文件决定运行行为，所以换管理器名不会打断底层。
 */
object StarFoxCore {

    private const val BASE_DIR = "/data/adb/xlh"

    private fun sh(vararg commands: String): StarFoxResult {
        val result = runCatching { Shell.cmd(*commands).exec() }
            .getOrElse { return StarFoxResult(false, it.message ?: "shell error") }
        val text = result.out.joinToString("\n").trim()
        return StarFoxResult(result.isSuccess, text)
    }

    private fun ensureDir(): StarFoxResult = sh(
        "mkdir -p $BASE_DIR",
        "chmod 0755 $BASE_DIR",
    )

    private fun writeFlag(name: String, value: String): StarFoxResult = sh(
        "mkdir -p $BASE_DIR",
        "printf '%s' '$value' > $BASE_DIR/$name",
        "chmod 0644 $BASE_DIR/$name",
    )

    /** 检测底层是否已驱动就绪。 */
    suspend fun isRootReady(): Boolean = withContext(Dispatchers.IO) {
        Shell.cmd("id -u").exec().out.firstOrNull()?.trim() == "0"
    }

    /** 读取内核里汇报的模式，读不到就退回本地记录。 */
    suspend fun readKernelMode(): String? = withContext(Dispatchers.IO) {
        val r = sh("cat $BASE_DIR/mode")
        r.output.takeIf { it.isNotBlank() }
    }

    /** 应用双模式：写标记文件 + 记录版本，交给内核补丁在下次启动生效。 */
    suspend fun applyMode(mode: StarFoxMode): StarFoxResult = withContext(Dispatchers.IO) {
        val prepare = ensureDir()
        if (!prepare.ok) return@withContext prepare
        val flag = writeFlag("mode", mode.id)
        if (!flag.ok) return@withContext flag
        val extra = when (mode) {
            // Ultra 模式顺手把无痕默认打开，这是它的设计前提。
            StarFoxMode.ULTRA -> writeFlag("stealth", "1")
            StarFoxMode.KSU -> StarFoxResult(true, "ksu")
        }
        if (!extra.ok) return@withContext extra
        StarFoxResult(true, mode.id)
    }

    suspend fun setStealth(on: Boolean): StarFoxResult = withContext(Dispatchers.IO) {
        writeFlag("stealth", if (on) "1" else "0")
    }

    suspend fun setTeeSim(on: Boolean): StarFoxResult = withContext(Dispatchers.IO) {
        val flag = writeFlag("tee", if (on) "1" else "0")
        if (!flag.ok) return@withContext flag
        // TEESimulator 以模块形式挂在 /data/adb/modules 下，开关同时同步模块启用状态。
        val moduleDir = "/data/adb/modules/TEESimulator"
        if (on) {
            sh("rm -f $moduleDir/disable")
        } else {
            sh("mkdir -p $moduleDir", "touch $moduleDir/disable")
        }
        flag
    }

    suspend fun setZygiskCompat(on: Boolean): StarFoxResult = withContext(Dispatchers.IO) {
        writeFlag("zygisk", if (on) "1" else "0")
    }

    suspend fun setPanicHardening(on: Boolean): StarFoxResult = withContext(Dispatchers.IO) {
        writeFlag("panic", if (on) "1" else "0")
    }

    /**
     * 一键迁移：把其它管理器留下的修补镜像登记过来。
     * 返回识别到的管理器列表，空说明这台机器是干净的。
     */
    suspend fun detectOtherManagers(): List<String> = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "KernelSU" to "/data/adb/ksu",
            "Magisk" to "/data/adb/magisk",
            "APatch" to "/data/adb/ap",
            "SKRoot" to "/data/adb/skroot",
        )
        val found = mutableListOf<String>()
        for ((name, path) in candidates) {
            val r = sh("test -d $path && echo yes || echo no")
            if (r.ok && r.output.trim() == "yes") found += name
        }
        found
    }

    /** 执行迁移：备份旧目录里的授权记录，落到星棱狐自己的路径。 */
    suspend fun migrateFrom(managerPath: String): StarFoxResult = withContext(Dispatchers.IO) {
        ensureDir()
        val target = "/data/adb/$managerPath"
        val backup = "$BASE_DIR/migrate-$(System.currentTimeMillis())"
        val r = sh(
            "mkdir -p $backup",
            "cp -af $target/. $backup/ 2>/dev/null || true",
            "printf '%s' '$managerPath' > $BASE_DIR/migrated_from",
        )
        StarFoxResult(r.ok, r.output.ifBlank { backup })
    }

    /** 寄生 / WebUI 管理器：读取内核上报的监听端口。 */
    suspend fun readWebUiPort(): Int? = withContext(Dispatchers.IO) {
        val r = sh("cat $BASE_DIR/webui.port")
        r.output.trim().toIntOrNull()
    }

    suspend fun startWebUi(): StarFoxResult = withContext(Dispatchers.IO) {
        ensureDir()
        writeFlag("webui", "1")
    }

    suspend fun stopWebUi(): StarFoxResult = withContext(Dispatchers.IO) {
        writeFlag("webui", "0")
    }

    /** 拉一份底层状态摘要，主页直接展示。 */
    suspend fun kernelSummary(): String = withContext(Dispatchers.IO) {
        val r = sh("uname -r")
        r.output.trim().ifBlank { "unknown" }
    }
}
