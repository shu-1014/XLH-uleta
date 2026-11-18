package com.iki.xlh.ui.screen.starfox

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iki.xlh.BuildConfig
import com.iki.xlh.server.KvServerState

/**
 * 服务器统计条：在线人数 + 累计安装次数。
 *
 * 两个数字都来自服务端：online 走心跳去重后的在线设备数，
 * installs 是每台设备首次启动上报一次累加出来的总数。
 */
@Composable
fun ServerStatsRow(state: KvServerState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Rounded.Group, null, tint = MaterialTheme.colorScheme.primary) },
            label = "在线",
            value = state.onlineCount.toString(),
        )
        StatTile(
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.tertiary) },
            label = "累计安装",
            value = state.installCount.toString(),
        )
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 公告卡片。
 *
 * [dismissed] 为 true 时整块不渲染——用户点过「知道了」就不再打扰，
 * 只有服务端换了 notice_id（新公告）才会重新出现。
 */
@Composable
fun NoticeCard(
    state: KvServerState,
    onDismiss: () -> Unit,
) {
    val body = state.notice
    if (body.isBlank()) return
    if (state.noticeId.isNotBlank() && state.noticeId == state.noticeDismissedId) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "公告",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

/**
 * 云更新卡片。
 *
 * 只有服务端开启了云更新、且 version_code 比当前 APK 大时才渲染。
 * 点「立即更新」直接交给系统浏览器/下载器，避免在 App 内自建下载器
 * 又要处理断点续传、校验、安装 Intent 那一整套。
 */
@Composable
fun UpdateCard(state: KvServerState) {
    val latestCode = state.latestVersionCode
    val url = state.latestUrl
    if (latestCode <= BuildConfig.VERSION_CODE) return
    if (url.isBlank()) return

    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (state.latestForce) "发现新版本（必须更新）" else "发现新版本",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text = "当前 ${BuildConfig.VERSION_NAME} → 最新 ${state.latestVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            if (state.latestChangelog.isNotBlank()) {
                Text(
                    text = state.latestChangelog,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) {
                    Text("立即更新")
                }
            }
        }
    }
}
