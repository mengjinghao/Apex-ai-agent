package com.apex.agent.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.apex.agent.update.HotUpdateManager
import com.apex.agent.update.MirrorSourceRegistry
import com.apex.agent.update.UpdateState
import com.apex.agent.update.ui.UpdateDialog
import com.apex.agent.update.ui.UpdateSettingsSection
import com.apex.sdk.common.ApkDependencyManager
import com.apex.utils.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面 — 接入真实权限 + Shizuku + 主题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onMenuClick: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeManager = remember { ThemeManager }
    var dynamicColor by remember { mutableStateOf(themeManager.getCurrentTheme() == ThemeManager.ThemeType.MATERIAL_YOU) }
    var darkMode by remember { mutableStateOf(themeManager.getCurrentDarkMode() == ThemeManager.DarkMode.DARK) }

    // 权限状态
    val storageGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val locationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val manageStorage = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager() else true

    // Shizuku 状态
    var shizukuAvailable by remember { mutableStateOf(false) }
    var shizukuVersion by remember { mutableStateOf(0) }
    var shizukuGranted by remember { mutableStateOf(false) }

    // 套件安装状态
    var suiteInstalled by remember { mutableStateOf(0) }
    var suiteTotal by remember { mutableStateOf(0) }

    // 热更新
    val hotUpdateManager = remember { HotUpdateManager.getInstance(context) }
    val updateState by hotUpdateManager.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showUpdateSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // Shizuku
            try {
                val sm = com.ai.assistance.apex.engine.shizuku.ShizukuManager.getInstance(context)
                shizukuAvailable = sm.isAvailable()
                shizukuVersion = sm.getVersion()
                shizukuGranted = sm.isPermissionGranted()
            } catch (_: Throwable) {}
            // 套件
            suiteTotal = com.apex.sdk.common.ApkDescriptors.ALL.size
            suiteInstalled = com.apex.sdk.common.ApkDescriptors.ALL.count { ApkDependencyManager.isApkInstalled(context, it.apkId) }
            // 镜像源
            MirrorSourceRegistry.getInstance(context).load()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onMenuClick) { Icon(Icons.Default.Menu, "菜单") } },
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 外观
            item { SectionHeader("外观") }
            item {
                SettingsCard(Icons.Default.Palette, "动态取色", "跟随系统壁纸自动配色（Android 12+）") {
                    Switch(checked = dynamicColor, onCheckedChange = {
                        dynamicColor = it
                        themeManager.setTheme(if (it) ThemeManager.ThemeType.MATERIAL_YOU else ThemeManager.ThemeType.DEFAULT)
                    })
                }
            }
            item {
                SettingsCard(Icons.Default.DarkMode, "深色模式", "跟随系统 / 浅色 / 深色") {
                    Switch(checked = darkMode, onCheckedChange = {
                        darkMode = it
                        themeManager.setDarkMode(if (it) ThemeManager.DarkMode.DARK else ThemeManager.DarkMode.LIGHT)
                    })
                }
            }

            // 权限管理
            item { SectionHeader("权限管理") }
            item { SettingsCard(Icons.Default.Storage, "存储权限", if (storageGranted) "已授予" else "未授予") { PermissionIndicator(storageGranted) } }
            item { SettingsCard(Icons.defaultStorageAccess(manageStorage), "所有文件访问", if (manageStorage) "已授予" else "未授予") { PermissionIndicator(manageStorage) } }
            item { SettingsCard(Icons.Default.Mic, "麦克风权限", if (micGranted) "已授予" else "未授予") { PermissionIndicator(micGranted) } }
            item { SettingsCard(Icons.Default.LocationOn, "位置权限", if (locationGranted) "已授予" else "未授予") { PermissionIndicator(locationGranted) } }
            item { SettingsCard(Icons.Default.Camera, "相机权限", if (cameraGranted) "已授予" else "未授予") { PermissionIndicator(cameraGranted) } }

            // 高级
            item { SectionHeader("高级") }
            item {
                SettingsCard(Icons.Default.Bolt, "Shizuku",
                    if (shizukuAvailable) "已连接（v$shizukuVersion${if (shizukuGranted) " · 已授权" else " · 未授权"}）" else "未连接") {
                    if (!shizukuAvailable) TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                    }) { Text("安装") }
                    else if (!shizukuGranted) TextButton(onClick = {
                        try { com.rikka.shizuku.Shizuku.requestPermission(0) } catch (_: Throwable) {}
                    }) { Text("授权") }
                    else Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            item { SettingsCard(Icons.Default.Accessibility, "无障碍服务", "在系统设置中启用") { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { SettingsCard(Icons.Default.Apps, "套件状态", "$suiteInstalled / $suiteTotal 个 APK 已安装") { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) } }

            // 软件更新（热更新）
            item { SectionHeader("软件更新") }
            item {
                val updateSubtitle = when (updateState) {
                    is UpdateState.UpdateAvailable ->
                        "发现新版本 ${(updateState as UpdateState.UpdateAvailable).latestVersion}，点击更新"
                    is UpdateState.Downloading -> {
                        val p = (updateState as UpdateState.Downloading).progress
                        "下载中 ${p.percent}%"
                    }
                    UpdateState.Checking -> "正在检查更新..."
                    else -> "当前版本 ${hotUpdateManager.currentVersionName()} · 来自 GitHub Releases"
                }
                SettingsCard(Icons.Default.SystemUpdate, "检查更新", updateSubtitle) {
                    TextButton(onClick = {
                        showUpdateDialog = true
                        scope.launch { hotUpdateManager.checkForUpdate(force = true, notifyOnAvailable = false) }
                    }) { Text("检查") }
                }
            }
            item {
                SettingsCard(
                    Icons.Default.Cloud,
                    "镜像源管理",
                    "免费 GitHub 加速镜像，可启用/禁用/添加自定义"
                ) {
                    TextButton(onClick = { showUpdateSettings = true }) { Text("管理") }
                }
            }

            // 关于
            item { SectionHeader("关于") }
            item { SettingsCard(Icons.Default.Info, "关于 Apex", "版本 1.0.0 · 开发者 MJH") { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { SettingsCard(Icons.Default.Person, "联系方式", "QQ: 2544240258 · 微信: meng4117222") { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }

    // 更新对话框
    if (showUpdateDialog) {
        UpdateDialog(
            state = updateState,
            onDismiss = { showUpdateDialog = false },
            onCheck = {
                scope.launch { hotUpdateManager.checkForUpdate(force = true) }
            },
            onDownload = {
                val s = updateState
                if (s is UpdateState.UpdateAvailable && s.release != null && s.asset != null) {
                    hotUpdateManager.startDownload()
                } else {
                    hotUpdateManager.notifyFailed("当前无可下载的更新，请先检查")
                }
            },
            onCancel = { hotUpdateManager.cancelDownload() },
            onIgnore = { v ->
                scope.launch { hotUpdateManager.ignoreVersion(v) }
                showUpdateDialog = false
            }
        )
    }

    // 镜像源管理弹层
    if (showUpdateSettings) {
        ModalBottomSheet(onDismissRequest = { showUpdateSettings = false }) {
            UpdateSettingsSection(
                onCheckNow = {
                    scope.launch {
                        hotUpdateManager.checkForUpdate(force = true, notifyOnAvailable = false)
                    }
                },
                onShowUpdateDialog = {
                    showUpdateSettings = false
                    showUpdateDialog = true
                }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

@Composable
private fun PermissionIndicator(granted: Boolean) {
    Text(if (granted) "✓" else "✗", color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
}

// 扩展：管理存储访问图标
private fun androidx.compose.material.icons.Icons.defaultStorageAccess(granted: Boolean) = Icons.Default.Storage
