package com.iki.xlh.ui.screen.starfox

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iki.xlh.R
import com.iki.xlh.starfox.StarFoxCore
import com.iki.xlh.starfox.StarFoxDetector
import com.iki.xlh.starfox.StarFoxMode
import com.iki.xlh.starfox.StarFoxResult
import com.iki.xlh.starfox.StarFoxRootStatus
import com.iki.xlh.starfox.StarFoxState
import com.iki.xlh.starfox.StarFoxStore
import com.iki.xlh.server.KvServerClient
import com.iki.xlh.server.KvServerState
import com.iki.xlh.server.KvServerStore
import com.iki.xlh.ui.stealth.Stealth
import com.iki.xlh.ui.stealth.StealthController
import com.iki.xlh.ui.navigation3.LocalNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

/**
 * 星棱狐增强中心。
 *
 * 把 MapleSU 形态的四件事收进一个页面：
 * 双底层模式、无痕与 TEE 兼容、一键迁移、寄生 WebUI。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarFoxScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(StarFoxStore.load(context)) }
    var busy by remember { mutableStateOf(false) }
    var rootReady by remember { mutableStateOf(false) }
    var kernel by remember { mutableStateOf("…") }
    var managers by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf<StarFoxRootStatus?>(null) }
    var iconHidden by remember { mutableStateOf(false) }
    var kernelStealth by remember { mutableStateOf(false) }
    var recoveryCode by remember { mutableStateOf("") }
    var stealthDetail by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
    // 远程管理服务器
    var serverState by remember { mutableStateOf(KvServerStore.load(context)) }
    var deviceId by remember { mutableStateOf("") }
    var serverBusy by remember { mutableStateOf(false) }

    fun persist(next: StarFoxState) {
        state = next
        StarFoxStore.save(context, next)
    }

    suspend fun report(result: StarFoxResult) {
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                if (result.ok) context.getString(R.string.starfox_action_done)
                else context.getString(R.string.starfox_action_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    suspend fun refreshStatus() {
        val s = withContext(Dispatchers.IO) { StarFoxDetector.probe(force = true) }
        withContext(Dispatchers.Main) {
            status = s
            rootReady = s.rootOk
            kernel = s.kernelVersion
        }
    }

    /**
     * 与云端服务（服务器上的 cloud.php）同步：
     * 安装上报 / 在线与装机统计 / 公告 / 云更新。
     *
     * 整个流程包在 runCatching 里——服务器连不上时界面照常用，
     * 这几块显示上一次成功的缓存值。
     */
    suspend fun syncServer(reportInstallOnce: Boolean = false) {
        val snapshot = serverState

        // ① 首次启动上报安装（服务端按 mac 去重，只有首装算新增）
        if (reportInstallOnce && !snapshot.installReported) {
            val r = runCatching { KvServerClient.install(context) }.getOrNull()
            if (r != null) {
                val st = KvServerClient.parseStats(r.data)
                withContext(Dispatchers.Main) {
                    serverState = serverState.copy(
                        installReported = r.ok,
                        installCount = if (st.installTotal > 0) st.installTotal else serverState.installCount,
                        onlineCount = if (st.online > 0) st.online else serverState.onlineCount,
                        lastError = if (r.ok) "" else r.msg,
                    )
                    KvServerStore.save(context, serverState)
                }
            }
        }

        // ② 心跳 + 拉统计
        val onlineRes = runCatching { KvServerClient.online(context) }.getOrNull()
        val stats = KvServerClient.parseStats(onlineRes?.data)

        // ③ 公告
        val noticeRes = runCatching { KvServerClient.notice() }.getOrNull()
        val (noticeOn, noticeText, noticeVer) = KvServerClient.parseNotice(noticeRes?.data)

        // ④ 云更新
        val verRes = runCatching { KvServerClient.update() }.getOrNull()
        val release = KvServerClient.parseRelease(verRes?.data)
        val serverNeed = KvServerClient.parseNeed(verRes?.data)

        withContext(Dispatchers.Main) {
            serverState = serverState.copy(
                onlineCount = stats.online,
                installCount = if (stats.installTotal > 0) stats.installTotal else serverState.installCount,
                notice = if (noticeOn) noticeText else "",
                noticeId = noticeVer,
                latestVersion = release.verName,
                latestVersionCode = if (serverNeed) release.verCode else release.verCode,
                latestUrl = release.url,
                latestChangelog = release.changelog,
                latestForce = release.force,
                lastSyncAt = System.currentTimeMillis(),
                lastError = when {
                    onlineRes == null -> "连不上服务器"
                    !onlineRes.ok -> onlineRes.msg.ifBlank { "同步失败" }
                    else -> ""
                },
            )
            KvServerStore.save(context, serverState)
        }
    }

    LaunchedEffect(Unit) {
        busy = true
        refreshStatus()
        managers = StarFoxCore.detectOtherManagers()
        // 隐身相关状态初始化（读失败一律当作关闭，不阻塞界面）
        iconHidden = runCatching { StealthController.isHidden(context) }.getOrDefault(false)
        kernelStealth = runCatching { Stealth.isEnabled() }.getOrDefault(false)
        stealthDetail = runCatching { Stealth.detail() }.getOrDefault(emptyMap())
        recoveryCode = runCatching { StealthController.dialCode(context) }.getOrDefault("")
        deviceId = runCatching { KvServerClient.machineId(context) }.getOrDefault("").take(12)
        busy = false
        // 服务器同步放到最后，失败不影响上面的本地状态
        runCatching { syncServer(reportInstallOnce = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            text = stringResource(R.string.starfox_title),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.starfox_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.starfox_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            // 云更新与公告置顶：有更新先看更新，其次看公告
            UpdateCard(state = serverState)
            NoticeCard(
                state = serverState,
                onDismiss = {
                    serverState = serverState.copy(noticeDismissedId = serverState.noticeId)
                    KvServerStore.save(context, serverState)
                },
            )

            // 状态卡：管理器身份 + 底层 + Root 状态
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.starfox_section_status),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_package),
                        "com.iki.xlh"
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_mode),
                        when (state.mode) {
                            StarFoxMode.KSU -> stringResource(R.string.starfox_mode_ksu)
                            StarFoxMode.ULTRA -> stringResource(R.string.starfox_mode_ultra)
                        }
                    )
                    StatusLine(stringResource(R.string.starfox_status_kernel), kernel)
                    StatusLine(
                        stringResource(R.string.starfox_status_root),
                        if (rootReady) stringResource(R.string.starfox_status_root_ok)
                        else stringResource(R.string.starfox_status_root_none)
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_module),
                        status?.moduleName ?: stringResource(R.string.starfox_status_module_none)
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_daemon),
                        status?.daemonVersion ?: stringResource(R.string.starfox_status_daemon_none)
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_uid),
                        status?.realUid?.toString() ?: "?"
                    )
                    StatusLine(
                        stringResource(R.string.starfox_status_registered),
                        if (status?.managerRegistered == true) stringResource(R.string.starfox_status_registered_yes)
                        else stringResource(R.string.starfox_status_registered_no)
                    )

                    status?.let { s ->
                        Text(
                            text = s.trace.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch { refreshStatus() }
                            }
                        ) {
                            Text(stringResource(R.string.starfox_detect_now))
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    StarFoxDetector.registerSelf("com.iki.xlh")
                                    StarFoxDetector.writeRootMarker(
                                        StarFoxDetector.probe(force = true).kernelVersion
                                    )
                                    val s = StarFoxDetector.probe(force = true)
                                    withContext(Dispatchers.Main) {
                                        status = s
                                        rootReady = s.rootOk
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.starfox_register_self))
                        }
                    }
                }
            }

            // 双模式
            SectionCard(title = stringResource(R.string.starfox_section_mode)) {
                ModeRow(
                    title = stringResource(R.string.starfox_mode_ksu),
                    summary = stringResource(R.string.starfox_mode_ksu_desc),
                    selected = state.mode == StarFoxMode.KSU,
                    enabled = !busy,
                    onSelect = {
                        persist(state.copy(mode = StarFoxMode.KSU))
                        scope.launch(Dispatchers.IO) {
                            report(StarFoxCore.applyMode(StarFoxMode.KSU))
                        }
                    }
                )
                ModeRow(
                    title = stringResource(R.string.starfox_mode_ultra),
                    summary = stringResource(R.string.starfox_mode_ultra_desc),
                    selected = state.mode == StarFoxMode.ULTRA,
                    enabled = !busy,
                    onSelect = {
                        persist(state.copy(mode = StarFoxMode.ULTRA, stealth = true))
                        scope.launch(Dispatchers.IO) {
                            report(StarFoxCore.applyMode(StarFoxMode.ULTRA))
                        }
                    }
                )
            }

            // 无痕与检测对抗
            SectionCard(title = stringResource(R.string.starfox_section_stealth)) {
                ToggleRow(
                    title = stringResource(R.string.starfox_stealth),
                    summary = stringResource(R.string.starfox_stealth_desc),
                    checked = state.stealth,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        persist(state.copy(stealth = on))
                        scope.launch(Dispatchers.IO) { report(StarFoxCore.setStealth(on)) }
                    }
                )
                ToggleRow(
                    title = stringResource(R.string.starfox_tee),
                    summary = stringResource(R.string.starfox_tee_desc),
                    checked = state.teeSim,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        persist(state.copy(teeSim = on))
                        scope.launch(Dispatchers.IO) { report(StarFoxCore.setTeeSim(on)) }
                    }
                )
                ToggleRow(
                    title = stringResource(R.string.starfox_zygisk),
                    summary = stringResource(R.string.starfox_zygisk_desc),
                    checked = state.zygiskCompat,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        persist(state.copy(zygiskCompat = on))
                        scope.launch(Dispatchers.IO) { report(StarFoxCore.setZygiskCompat(on)) }
                    }
                )
                ToggleRow(
                    title = stringResource(R.string.starfox_panic),
                    summary = stringResource(R.string.starfox_panic_desc),
                    checked = state.panicHardening,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        persist(state.copy(panicHardening = on))
                        scope.launch(Dispatchers.IO) { report(StarFoxCore.setPanicHardening(on)) }
                    }
                )
            }
// 隐身（MapleSU 移植：图标隐藏 + 内核三开关 + 拨号密令恢复）
SectionCard(title = stringResource(R.string.starfox_section_disguise)) {
    ToggleRow(
        title = stringResource(R.string.stealth_hide_icon),
        summary = stringResource(R.string.stealth_hide_icon_summary),
        checked = iconHidden,
        enabled = !busy,
        onCheckedChange = { on ->
            StealthController.setHidden(context, on)
            iconHidden = on
        }
    )
    ToggleRow(
        title = stringResource(R.string.stealth_kernel),
        summary = stringResource(R.string.stealth_kernel_summary),
        checked = kernelStealth,
        enabled = !busy,
        onCheckedChange = { on ->
            kernelStealth = Stealth.setEnabled(on)
            stealthDetail = Stealth.detail()
        }
    )
    Text(
        text = stringResource(R.string.stealth_recovery_code_summary, recoveryCode),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


            // 云端服务：在线 / 装机统计、公告、云更新（服务端 cloud.php）
            SectionCard(title = stringResource(R.string.starfox_section_server)) {
                StatusLine(stringResource(R.string.server_host), KvServerClient.BASE_URL.removePrefix("https://"))
                StatusLine(stringResource(R.string.server_device), deviceId.ifBlank { "-" })
                StatusLine(
                    stringResource(R.string.server_last_sync),
                    if (serverState.lastSyncAt > 0) {
                        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(serverState.lastSyncAt))
                    } else {
                        stringResource(R.string.server_never)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !serverBusy,
                        onClick = {
                            serverBusy = true
                            scope.launch(Dispatchers.IO) {
                                runCatching { syncServer(reportInstallOnce = true) }
                                withContext(Dispatchers.Main) { serverBusy = false }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.server_sync))
                    }
                    OutlinedButton(
                        enabled = !serverBusy,
                        onClick = {
                            serverBusy = true
                            scope.launch(Dispatchers.IO) {
                                val r = runCatching { KvServerClient.install(context) }.getOrNull()
                                val st = KvServerClient.parseStats(r?.data)
                                withContext(Dispatchers.Main) {
                                    if (r != null) {
                                        serverState = serverState.copy(
                                            installReported = r.ok,
                                            installCount = if (st.installTotal > 0) st.installTotal else serverState.installCount,
                                            onlineCount = if (st.online > 0) st.online else serverState.onlineCount,
                                            lastError = if (r.ok) "" else r.msg,
                                        )
                                        KvServerStore.save(context, serverState)
                                    }
                                    serverBusy = false
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.server_report))
                    }
                }
                // 在线人数 / 累计安装次数（服务端统计）
                ServerStatsRow(state = serverState)
                if (serverState.lastError.isNotBlank()) {
                    Text(
                        text = serverState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // 一键操作
            SectionCard(title = stringResource(R.string.starfox_section_tools)) {
                Text(
                    text = if (managers.isEmpty())
                        stringResource(R.string.starfox_migrate_none)
                    else stringResource(R.string.starfox_migrate_found, managers.joinToString(" / ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && managers.isNotEmpty(),
                        onClick = {
                            val target = managers.firstOrNull()
                            if (target != null) {
                                val path = when (target) {
                                    "KernelSU" -> "ksu"
                                    "Magisk" -> "magisk"
                                    "APatch" -> "ap"
                                    else -> "skroot"
                                }
                                scope.launch(Dispatchers.IO) {
                                    report(StarFoxCore.migrateFrom(path))
                                    val found = StarFoxCore.detectOtherManagers()
                                    withContext(Dispatchers.Main) { managers = found }
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.starfox_migrate))
                    }
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val found = StarFoxCore.detectOtherManagers()
                                withContext(Dispatchers.Main) { managers = found }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.starfox_refresh))
                    }
                }

                ToggleRow(
                    title = stringResource(R.string.starfox_webui),
                    summary = stringResource(R.string.starfox_webui_desc),
                    checked = state.webManager,
                    enabled = !busy,
                    onCheckedChange = { on ->
                        persist(state.copy(webManager = on))
                        scope.launch(Dispatchers.IO) {
                            report(if (on) StarFoxCore.startWebUi() else StarFoxCore.stopWebUi())
                        }
                    }
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun ModeRow(
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (selected) "✓ $title" else title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
