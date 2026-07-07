package com.apex.agent.update.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.update.DownloadProgress
import com.apex.agent.update.UpdateState
import com.apex.agent.update.formatBytes

/**
 * 更新对话框 — 当发现新版本或用户主动触发"检查更新"时弹出。
 *
 * 状态：
 * - Idle / Checking → 显示进度条
 * - UpdateAvailable → 显示版本号 / 日志 / "立即更新" + "稍后"
 * - Downloading → 显示下载进度条
 * - Failed → 显示错误信息
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDismiss: () -> Unit,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onIgnore: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.NewReleases,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("软件更新", fontWeight = FontWeight.SemiBold)
            }
        },
        text = {
            when (state) {
                UpdateState.Idle, UpdateState.Checking -> {
                    Column(Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "正在检查 GitHub Releases...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is UpdateState.UpdateAvailable -> UpdateAvailableBody(state)
                is UpdateState.Downloading -> DownloadingBody(state.progress)
                UpdateState.Downloaded -> {
                    Text(
                        "下载完成，请在弹出的系统安装界面完成更新。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is UpdateState.Failed -> {
                    Text(
                        "更新失败：${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.UpdateAvailable -> {
                    Row {
                        TextButton(onClick = { onIgnore(state.latestVersion) }) {
                            Text("跳过该版本")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即更新")
                        }
                    }
                }
                is UpdateState.Downloading -> {
                    OutlinedButton(onClick = onCancel) { Text("取消下载") }
                }
                UpdateState.Downloaded, is UpdateState.Failed -> {
                    Button(onClick = onDismiss) { Text("关闭") }
                }
                UpdateState.Idle, UpdateState.Checking -> {
                    OutlinedButton(onClick = onDismiss) { Text("取消") }
                }
            }
        },
        dismissButton = {
            if (state is UpdateState.UpdateAvailable || state is UpdateState.Idle || state is UpdateState.Checking) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun UpdateAvailableBody(state: UpdateState.UpdateAvailable) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 版本号
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "发现新版本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    state.latestVersion,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "当前版本 ${state.currentVersion} · 大小 ${state.sizeText}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // SHA-256 校验状态徽章
        if (state.expectedSha256 != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "✓ 已附 SHA-256 校验",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        // 更新日志
        Text(
            "更新内容",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        // 简单 Markdown 渲染：按行展示，遇到 - 开头作为列表项
        val lines = state.changelog.lines().take(60)
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                Spacer(Modifier.height(6.dp))
            } else if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("•  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(trimmed.removePrefix("-").removePrefix("*").trim(), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(
                    trimmed,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
        if (state.changelog.lines().size > 60) {
            Spacer(Modifier.height(6.dp))
            Text(
                "... 完整日志见 GitHub Release 页面",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        }
    }
}

@Composable
private fun DownloadingBody(progress: DownloadProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "正在下载...",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (progress.percent.coerceIn(0, 100) / 100f) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (progress.percent >= 0) "${progress.percent}%" else "下载中",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${formatBytes(progress.bytesRead)} / ${formatBytes(progress.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "速度 ${formatBytes(progress.speedBytesPerSec)}/s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
