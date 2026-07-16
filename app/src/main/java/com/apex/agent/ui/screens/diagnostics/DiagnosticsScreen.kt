package com.apex.agent.ui.screens.diagnostics

import android.os.Build
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.apex.agent.diagnostics.DiagnosticsServiceFacade
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApkDependencyManager
import com.apex.sdk.common.ApkDescriptors
import com.apex.sdk.watchdog.Watchdog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断界面 — 接入真实 DiagnosticsServiceFacade。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(modifier: Modifier = Modifier, onMenuClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usedMb by remember { mutableStateOf(0L) }
    var totalMb by remember { mutableStateOf(0L) }
    var maxMb by remember { mutableStateOf(0L) }
    var nativeTotal by remember { mutableStateOf(0L) }
    var nativeAllocated by remember { mutableStateOf(0L) }
    var apkHealth by remember { mutableStateOf<List<HealthItem>>(emptyList()) }
    var crashCount by remember { mutableStateOf(0) }
    var logCount by remember { mutableStateOf(0) }
    var systemInfo by remember { mutableStateOf<SystemInfo?>(null) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val facade = TypedServiceRegistry.get<DiagnosticsServiceFacade>()
                if (facade != null) {
                    val mem = facade.getMemoryStats()
                    usedMb = mem.usedMb
                    totalMb = mem.totalMb
                    maxMb = mem.maxMb
                    val native = facade.getNativeMemory()
                    nativeTotal = native.totalMb
                    nativeAllocated = native.allocatedMb
                    apkHealth = facade.getApkHealthList().map { HealthItem(it.apkId, it.healthy, it.lastHeartbeatAgoMs) }
                    val crashes = facade.listCrashReports()
                    crashCount = crashes.size.let { runCatching { it }.getOrDefault(0) }
                    val logs = facade.listLogFiles()
                    logCount = logs.size.let { runCatching { it }.getOrDefault(0) }
                }
                systemInfo = SystemInfo(
                    brand = Build.BRAND, model = Build.MODEL, manufacturer = Build.MANUFACTURER,
                    sdkInt = Build.VERSION.SDK_INT, release = Build.VERSION.RELEASE,
                    pid = Process.myPid(), uid = Process.myUid(),
                    processName = context.packageName
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
                title = { Text("诊断") },
                actions = { IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, "刷新") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // JVM 内存
            item {
                val pct = if (maxMb > 0) usedMb.toFloat() / maxMb else 0f
                StatCard("💾", "JVM 内存", "$usedMb / $maxMb MB", "已用 ${(pct * 100).toInt()}% · 峰值 ${totalMb} MB", pct)
            }
            // Native 内存
            item {
                val pct = if (nativeTotal > 0) nativeAllocated.toFloat() / nativeTotal else 0f
                StatCard("💾", "Native 内存", "$nativeAllocated MB", "已分配 · 总量 $nativeTotal MB", pct)
            }
            // APK 健康
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("APK 健康状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.weight(1f))
                            Text("${apkHealth.count { it.healthy }}/${apkHealth.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(12.dp))
                        apkHealth.forEach { h ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (h.healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
                                Spacer(Modifier.width(8.dp))
                                Text(ApkDescriptors.byId(h.apkId)?.displayName ?: h.apkId, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text("${h.lastHeartbeatAgoMs}ms", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // 系统信息
            item {
                systemInfo?.let { info ->
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("系统信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(12.dp))
                            InfoRow("品牌", "${info.brand} ${info.manufacturer}")
                            InfoRow("型号", info.model)
                            InfoRow("Android", "${info.release} (SDK ${info.sdkInt})")
                            InfoRow("进程", info.processName)
                            InfoRow("PID / UID", "${info.pid} / ${info.uid}")
                        }
                    }
                }
            }
            // 崩溃报告 + 日志
            item {
                Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = if (crashCount > 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, null, tint = if (crashCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("崩溃报告", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("$crashCount 个崩溃 · $logCount 个日志文件", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton(onClick = {}) { Text("查看") }
                    }
                }
            }
            // 操作
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { TypedServiceRegistry.get<DiagnosticsServiceFacade>()?.forceGc() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("强制 GC") }
                    FilledTonalButton(onClick = { TypedServiceRegistry.get<DiagnosticsServiceFacade>()?.dumpHeap() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Heap Dump") }
                    FilledTonalButton(onClick = { /* TODO: clearAllLogs */ }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("清除日志") }
                }
            }
        }
    }
}

@Composable
private fun StatCard(icon: String, title: String, value: String, subtitle: String, progress: Float) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

data class HealthItem(val apkId: String, val healthy: Boolean, val lastHeartbeatAgoMs: Long)
data class SystemInfo(val brand: String, val model: String, val manufacturer: String, val sdkInt: Int, val release: String, val pid: Int, val uid: Int, val processName: String)
