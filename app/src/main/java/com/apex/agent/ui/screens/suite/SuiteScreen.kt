package com.apex.agent.ui.screens.suite

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apex.sdk.common.ApkDependencyManager
import com.apex.sdk.common.ApkDescriptors
import com.apex.sdk.common.ApkNecessity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * APK 套件管理界面 — 接入真实 ApkDependencyManager。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuiteScreen(modifier: Modifier = Modifier, onMenuClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apkStates by remember { mutableStateOf<List<ApkStateItem>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }

    // 加载 APK 状态
    fun refresh() {
        scope.launch {
            refreshing = true
            val states = withContext(Dispatchers.IO) {
                ApkDescriptors.ALL.map { desc ->
                    val installed = ApkDependencyManager.isApkInstalled(context, desc.apkId)
                    val version = if (installed) ApkDependencyManager.getInstalledVersion(context, desc.apkId) else null
                    val missingDeps = ApkDependencyManager.checkDependencies(context, desc.apkId)
                    ApkStateItem(
                        apkId = desc.apkId,
                        displayName = desc.displayName,
                        description = desc.description,
                        necessity = desc.necessity,
                        installed = installed,
                        version = version,
                        approxSizeMb = desc.approxSizeMb,
                        missingDeps = missingDeps.map { it.apkId },
                        downloadUrl = desc.downloadUrl,
                        capabilities = desc.capabilities
                    )
                }
            }
            apkStates = states
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
                title = { Text("套件管理") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        if (refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val installedCount = apkStates.count { it.installed }
            val requiredCount = apkStates.count { it.necessity == ApkNecessity.REQUIRED }
            val optionalCount = apkStates.count { it.necessity == ApkNecessity.OPTIONAL }

            // 摘要
            item {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("Apex 套件", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text("已安装 $installedCount / ${apkStates.size}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("必须 $requiredCount 个 · 可选 $optionalCount 个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                }
            }

            // 必须
            item { SectionHeader("必须组件", MaterialTheme.colorScheme.primary) }
            items(apkStates.filter { it.necessity == ApkNecessity.REQUIRED }) { apk -> ApkCard(apk, context) }

            // 可选
            item { SectionHeader("可选组件", MaterialTheme.colorScheme.tertiary) }
            items(apkStates.filter { it.necessity == ApkNecessity.OPTIONAL }) { apk -> ApkCard(apk, context) }
        }
    }
}

@Composable
private fun SectionHeader(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun ApkCard(apk: ApkStateItem, context: android.content.Context) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (apk.installed) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // 状态图标
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (apk.installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (apk.installed) Icons.Default.CheckCircle else Icons.Default.Cancel, if (apk.installed) "已安装" else "未安装", tint = if (apk.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(16.dp))
            // 信息
            Column(Modifier.weight(1f)) {
                Text(apk.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(apk.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("约 ${apk.approxSizeMb}MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    if (apk.installed && apk.version != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("v${apk.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    if (!apk.installed && apk.missingDeps.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("依赖: ${apk.missingDeps.joinToString(",")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            // 操作
            FilledIconButton(
                onClick = {
                    if (apk.installed) {
                        // 已安装 → 启动
                        com.apex.sdk.common.ApkIdentityRegistry.launchApk(context, apk.apkId)
                    } else {
                        // 未安装 → 打开下载页
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apk.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(intent) } catch (_: Throwable) {}
                    }
                },
                shape = RoundedCornerShape(50)
            ) {
                Icon(if (apk.installed) Icons.Default.Launch else Icons.Default.Download, if (apk.installed) "打开" else "下载")
            }
        }
    }
}

/** APK 状态项。 */
data class ApkStateItem(
    val apkId: String,
    val displayName: String,
    val description: String,
    val necessity: ApkNecessity,
    val installed: Boolean,
    val version: String? = null,
    val approxSizeMb: Int = 0,
    val missingDeps: List<String> = emptyList(),
    val downloadUrl: String = "",
    val capabilities: List<String> = emptyList()
)
