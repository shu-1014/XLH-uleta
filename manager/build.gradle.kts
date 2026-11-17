plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.agp.library) apply false
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.compose.compiler) apply false
}

extra["androidMinSdkVersion"] = 24
extra["androidTargetSdkVersion"] = 37
extra["androidCompileSdkVersion"] = 37
extra["androidCompileSdkVersionMinor"] = 0
extra["androidBuildToolsVersion"] = "37.0.0"
extra["androidCompileNdkVersion"] = libs.versions.ndk.get()
extra["androidSourceCompatibility"] = JavaVersion.VERSION_21
extra["androidTargetCompatibility"] = JavaVersion.VERSION_21
extra["managerVersionCode"] = getVersionCode()
extra["managerVersionName"] = getVersionName()

fun getGitCommitCount(): Int {
    // 没有 git 仓库（比如直接解压源码）时不再炸构建，退回本地计数。
    val process = runCatching {
        Runtime.getRuntime().exec(arrayOf("git", "rev-list", "--count", "HEAD"))
    }.getOrNull() ?: return 4001
    return runCatching { process.inputStream.bufferedReader().use { it.readText().trim().toInt() } }
        .getOrDefault(4001)
}

fun getGitDescribe(): String {
    val process = runCatching {
        Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--always", "--abbrev=0"))
    }.getOrNull() ?: return "1.0.0-xinglengfu"
    return runCatching { process.inputStream.bufferedReader().use { it.readText().trim() } }
        .getOrDefault("1.0.0-xinglengfu")
        .ifBlank { "1.0.0-xinglengfu" }
}

fun getVersionCode(): Int {
    val commitCount = getGitCommitCount()
    val major = 3
    val end = 4001
    return major * 10000 + commitCount - end
}

fun getVersionName(): String {
    return getGitDescribe()
}