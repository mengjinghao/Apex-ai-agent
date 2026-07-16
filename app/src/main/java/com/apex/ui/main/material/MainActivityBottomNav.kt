package com.apex.ui.main.material

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.agent.ui.theme.ApexTheme

class MainActivityBottomNav : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ApexTheme {
                ApexHomeScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexHomeScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        BottomTab("Agent", Icons.Filled.Chat, Icons.Outlined.Chat),
        BottomTab("套件", Icons.Filled.Extension, Icons.Outlined.Extension),
        BottomTab("协作", Icons.Filled.Group, Icons.Outlined.Group),
        BottomTab("诊断", Icons.Filled.BugReport, Icons.Outlined.BugReport),
        BottomTab("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apex AI Agent", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(if (selectedTab == index) tab.selectedIcon else tab.unselectedIcon, contentDescription = tab.label) },
                        label = { Text(tab.label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.fillMaxSize().padding(innerPadding), color = MaterialTheme.colorScheme.background) {
            when (selectedTab) {
                0 -> AgentTab()
                1 -> SuiteTab()
                2 -> CollaborateTab()
                3 -> DiagnosticsTab()
                4 -> SettingsTab()
            }
        }
    }
}

data class BottomTab(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector)

@Composable
private fun AgentTab() {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("欢迎使用 Apex AI Agent", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("智能AI助手 · 多Agent协作 · 爆发模式引擎", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                    Spacer(Modifier.size(12.dp))
                    Text("v1.0.1 — 多 APK 架构，9 个独立模块协同运行", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                }
            }
        }
        item { Text("快捷功能", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 8.dp)) }
        items(listOf(
            FeatureItem("AI 对话", "与 AI 助手对话，支持流式输出", Icons.Filled.Chat),
            FeatureItem("语音助手", "语音输入，支持唤醒词", Icons.Filled.Memory),
            FeatureItem("狂暴模式", "高性能模式，释放全部算力", Icons.Filled.Speed),
            FeatureItem("多 Agent 协作", "多个 Agent 协同完成复杂任务", Icons.Filled.Group)
        )) { FeatureCard(it) }
    }
}

@Composable
private fun SuiteTab() {
    val apks = listOf(
        ApkItem("app", "主 APK", "套件核心入口", true),
        ApkItem("engine", "Engine", "AI 引擎模块", true),
        ApkItem("rage", "Rage", "狂暴模式", true),
        ApkItem("multi-agent", "Multi-Agent", "多 Agent 协作", true),
        ApkItem("workflow", "Workflow", "工作流编排", true),
        ApkItem("market", "Market", "技能市场", true),
        ApkItem("working-files", "Working Files", "文件管理", true),
        ApkItem("voice", "Voice", "语音交互", true),
        ApkItem("terminal", "Terminal", "终端模拟器", true)
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("APK 套件", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("9 个独立 APK · 共享进程 · 零延迟调用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.size(8.dp))
        }
        items(apks) { apk ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(apk.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(apk.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = if (apk.installed) Color(0xFF34A853) else Color(0xFFEA4335)) {
                        Text(if (apk.installed) "已安装" else "未安装", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CollaborateTab() {
    val modes = listOf(
        ModeItem("普通模式", "标准 AI 对话，平衡性能与功耗", Icons.Filled.Home, Color(0xFF1A73E8)),
        ModeItem("狂暴模式", "释放全部算力，高速推理", Icons.Filled.Speed, Color(0xFFEA4335)),
        ModeItem("多 Agent", "多个 Agent 协同工作", Icons.Filled.Group, Color(0xFF34A853)),
        ModeItem("三星模式", "中书省/门下省/御史台协作", Icons.Filled.Build, Color(0xFFFBBC04))
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("协作模式", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Text("选择不同的 AI 协作模式以适应不同场景", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.size(8.dp))
        }
        items(modes) { mode ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = mode.color.copy(alpha = 0.15f), modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(mode.icon, contentDescription = null, tint = mode.color) }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(mode.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsTab() {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("系统诊断", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.size(8.dp)) }
        items(listOf(
            DiagItem("应用版本", "v1.0.1 (versionCode 2)"),
            DiagItem("目标 SDK", "Android 14 (API 34)"),
            DiagItem("最低 SDK", "Android 8.0 (API 26)"),
            DiagItem("架构", "arm64-v8a"),
            DiagItem("进程", "com.apex.agent.mainprocess"),
            DiagItem("模块数", "9 APK · 1139 源文件"),
            DiagItem("混淆", "已禁用"),
            DiagItem("签名", "Release keystore")
        )) { diag ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(diag.label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Text(diag.value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("设置", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground); Spacer(Modifier.size(8.dp)) }
        items(listOf(
            SettingItem("AI 模型配置", "配置 LLM API 密钥和模型"),
            SettingItem("权限管理", "管理应用权限"),
            SettingItem("外观", "主题、字体、语言"),
            SettingItem("通知", "通知和提醒设置"),
            SettingItem("存储", "缓存和数据管理"),
            SettingItem("关于", "版本信息和开源协议"),
            SettingItem("反馈", "提交反馈和报告问题")
        )) { setting ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(setting.title, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(setting.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                }
            }
        }
    }
}

data class FeatureItem(val title: String, val subtitle: String, val icon: ImageVector)
data class ApkItem(val id: String, val name: String, val description: String, val installed: Boolean)
data class ModeItem(val name: String, val description: String, val icon: ImageVector, val color: Color)
data class DiagItem(val label: String, val value: String)
data class SettingItem(val title: String, val subtitle: String)

@Composable
private fun FeatureCard(feature: FeatureItem) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(feature.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(feature.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(feature.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
