package com.apex.agent.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设置界面 — Material You 3 风格。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var darkMode by remember { mutableStateOf(true) }
    var dynamicColor by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("设置", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 外观
            item { SectionHeader("外观") }
            item {
                SettingsCard(
                    icon = Icons.Default.Palette,
                    title = "动态取色",
                    subtitle = "跟随系统壁纸自动配色（Android 12+）",
                    trailing = { Switch(checked = dynamicColor, onCheckedChange = { dynamicColor = it }) }
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.DarkMode,
                    title = "深色模式",
                    subtitle = "跟随系统 / 浅色 / 深色",
                    trailing = { Switch(checked = darkMode, onCheckedChange = { darkMode = it }) }
                )
            }

            // Agent
            item { SectionHeader("Agent 配置") }
            item {
                SettingsCard(
                    icon = Icons.Default.Cloud,
                    title = "模型提供商",
                    subtitle = "DeepSeek（已配置 API Key）",
                    onClick = {}
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.Speed,
                    title = "温度",
                    subtitle = "0.7（控制输出随机性）",
                    onClick = {}
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.Token,
                    title = "最大 Token",
                    subtitle = "4096",
                    onClick = {}
                )
            }

            // 权限
            item { SectionHeader("权限管理") }
            item {
                SettingsCard(
                    icon = Icons.Default.Security,
                    title = "存储权限",
                    subtitle = "已授予",
                    trailing = { Text("✓", color = MaterialTheme.colorScheme.primary) }
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.Mic,
                    title = "麦克风权限",
                    subtitle = "未授予",
                    trailing = { Text("✗", color = MaterialTheme.colorScheme.error) }
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.LocationOn,
                    title = "位置权限",
                    subtitle = "未授予",
                    trailing = { Text("✗", color = MaterialTheme.colorScheme.error) }
                )
            }

            // 高级
            item { SectionHeader("高级") }
            item {
                SettingsCard(
                    icon = Icons.Default.Bolt,
                    title = "Shizuku",
                    subtitle = "已连接（v13）",
                    onClick = {}
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.Accessibility,
                    title = "无障碍服务",
                    subtitle = "已启用",
                    onClick = {}
                )
            }
            item {
                SettingsCard(
                    icon = Icons.Default.Info,
                    title = "关于 Apex",
                    subtitle = "版本 1.0.0",
                    onClick = {}
                )
            }
        }
    }
}

/**
 * 模式切换界面 — 普通 / 狂暴 / 多 Agent。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSwitchScreen(modifier: Modifier = Modifier) {
    var selectedMode by remember { mutableStateOf(AgentMode.NORMAL) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Agent 模式", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AgentMode.values().forEach { mode ->
                AgentModeCard(
                    mode = mode,
                    selected = selectedMode == mode,
                    onClick = { selectedMode = mode }
                )
            }
        }
    }
}

@Composable
private fun AgentModeCard(
    mode: AgentMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(mode.icon, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    mode.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    mode.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        onClick = onClick ?: {},
        enabled = onClick != null,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

enum class AgentMode(val displayName: String, val icon: String, val description: String) {
    NORMAL("普通 Agent", "💬", "标准对话模式，适合日常任务"),
    BURST("狂暴模式", "⚡", "31 个内置技能 + 多策略推理 + 并行执行"),
    MULTI_AGENT("多 Agent 协作", "🏛️", "7 种协作模式 + 30 种角色模板 + 三省六部制")
}
