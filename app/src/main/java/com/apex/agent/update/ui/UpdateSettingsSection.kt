package com.apex.agent.update.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.agent.update.HotUpdateManager
import com.apex.agent.update.MirrorSource
import com.apex.agent.update.MirrorSourceRegistry
import com.apex.agent.update.MirrorTestResult
import com.apex.agent.update.UpdateSettings
import kotlinx.coroutines.launch

/**
 * 镜像源管理 + 更新偏好设置界面。
 *
 * 嵌入到设置页作为子页或底部弹层使用。功能：
 * 1. 显示所有镜像（内置 + 自定义），支持启用/禁用；
 * 2. 测试单个镜像连通性；
 * 3. 添加自定义镜像（输入 name + urlTemplate）；
 * 4. 删除自定义镜像；
 * 5. 切换自动检查、预发布、仅 Wi-Fi 下载；
 * 6. 修改 GitHub 仓库归属；
 * 7. 手动触发检查更新。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSettingsSection(
    modifier: Modifier = Modifier,
    onCheckNow: () -> Unit,
    onShowUpdateDialog: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { HotUpdateManager.getInstance(context) }
    val registry = remember { MirrorSourceRegistry.getInstance(context) }

    val mirrors by registry.mirrorsFlow.collectAsState()
    val state by manager.state.collectAsState()

    var autoCheck by remember { mutableStateOf(true) }
    var includePre by remember { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var repoOwner by remember { mutableStateOf(UpdateSettings.DEFAULT_REPO_OWNER) }
    var repoName by remember { mutableStateOf(UpdateSettings.DEFAULT_REPO_NAME) }

    var showAddMirror by remember { mutableStateOf(false) }
    var testResults by remember { mutableStateOf<Map<String, MirrorTestResult>>(emptyMap()) }

    // 加载偏好
    LaunchedEffect(Unit) {
        registry.load()
        autoCheck = UpdateSettings.isAutoCheckEnabled(context)
        includePre = UpdateSettings.isIncludePrerelease(context)
        wifiOnly = UpdateSettings.isDownloadWifiOnly(context)
        repoOwner = UpdateSettings.getRepoOwner(context)
        repoName = UpdateSettings.getRepoName(context)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { SectionLabel("更新检查") }
        item {
            SettingCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("立即检查更新", fontWeight = FontWeight.Medium)
                            Text(
                                "当前版本 ${manager.currentVersionName()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = onCheckNow, enabled = state !is com.apex.agent.update.UpdateState.Checking) {
                            Text("检查")
                        }
                    }
                    if (state is com.apex.agent.update.UpdateState.UpdateAvailable) {
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = onShowUpdateDialog,
                            label = { Text("发现新版本 ${(state as com.apex.agent.update.UpdateState.UpdateAvailable).latestVersion}") },
                            leadingIcon = { Icon(Icons.Default.NewReleases, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }
        }

        item {
            SwitchCard(
                icon = Icons.Default.Refresh,
                title = "启动时自动检查",
                subtitle = "每隔指定时间后台检查一次 GitHub Releases",
                checked = autoCheck
            ) {
                autoCheck = it
                scope.launch { UpdateSettings.setAutoCheck(context, it) }
            }
        }
        item {
            SwitchCard(
                icon = Icons.Default.Verified,
                title = "包含预发布版本",
                subtitle = "Pre-release 通常是 nightly / beta，可能不稳定",
                checked = includePre
            ) {
                includePre = it
                scope.launch { UpdateSettings.setIncludePrerelease(context, it) }
            }
        }
        item {
            SwitchCard(
                icon = Icons.Default.Cloud,
                title = "仅 Wi-Fi 下载",
                subtitle = "移动网络下不下载 APK，避免流量消耗",
                checked = wifiOnly
            ) {
                wifiOnly = it
                scope.launch { UpdateSettings.setDownloadWifiOnly(context, it) }
            }
        }

        item { SectionLabel("GitHub 仓库") }
        item {
            SettingCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("仓库地址", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoOwner,
                        onValueChange = { repoOwner = it },
                        label = { Text("owner") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = repoName,
                        onValueChange = { repoName = it },
                        label = { Text("repo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                UpdateSettings.setRepo(context, repoOwner.trim(), repoName.trim())
                            }
                        }
                    ) { Text("保存") }
                }
            }
        }

        item { SectionLabel("镜像源（按顺序回退）") }
        item {
            SettingCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "下载时按以下顺序尝试每个已启用的镜像，首个成功即用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = { showAddMirror = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加")
                    }
                }
            }
        }
        items(mirrors, key = { it.id }) { mirror ->
            MirrorCard(
                mirror = mirror,
                testResult = testResults[mirror.id],
                onToggle = { enabled ->
                    scope.launch { registry.setEnabled(mirror.id, enabled) }
                },
                onTest = {
                    scope.launch {
                        val r = manager.testMirror(mirror)
                        testResults = testResults + (mirror.id to r)
                    }
                },
                onDelete = if (mirror.builtin) null else {
                    { scope.launch { registry.remove(mirror.id) } }
                }
            )
        }
    }

    if (showAddMirror) {
        AddMirrorDialog(
            onDismiss = { showAddMirror = false },
            onConfirm = { name, urlTemplate, description ->
                scope.launch {
                    registry.addCustom(
                        MirrorSource(
                            id = "custom-${System.currentTimeMillis()}",
                            name = name,
                            urlTemplate = urlTemplate,
                            builtin = false,
                            description = description
                        )
                    )
                    showAddMirror = false
                }
            }
        )
    }
}

@Composable
private fun MirrorCard(
    mirror: MirrorSource,
    testResult: MirrorTestResult?,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: (() -> Unit)?
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (mirror.builtin) Icons.Default.Verified else Icons.Default.Cloud,
                    null,
                    tint = if (mirror.builtin) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.tertiary
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mirror.name, fontWeight = FontWeight.SemiBold)
                        if (mirror.builtin) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    "内置",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    if (mirror.description.isNotBlank()) {
                        Text(
                            mirror.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        mirror.urlTemplate.ifBlank { "(直连)" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = mirror.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest) {
                    Icon(Icons.Default.Speed, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("测试")
                }
                Spacer(Modifier.width(8.dp))
                testResult?.let { r ->
                    if (r.success) {
                        Text(
                            "✓ ${r.latencyMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            "✗ ${r.message}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddMirrorDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, urlTemplate: String, description: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("https://") }
    var description by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加自定义镜像") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "URL 模板中使用 {url} 作为 GitHub 原始下载地址占位符。" +
                        "例如 https://ghproxy.com/{url}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = false },
                    label = { Text("镜像名称") },
                    singleLine = true,
                    isError = nameError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = urlTemplate,
                    onValueChange = { urlTemplate = it; urlError = false },
                    label = { Text("URL 模板") },
                    singleLine = true,
                    isError = urlError,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("说明（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) { nameError = true; return@Button }
                if (urlTemplate.isBlank() || (!urlTemplate.startsWith("http") && urlTemplate != "{url}")) {
                    urlError = true; return@Button
                }
                onConfirm(name.trim(), urlTemplate.trim(), description.trim())
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun SwitchCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
