package com.apex.agent.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主导航 Tab（4 个）。
 *
 * 狂暴模式和多 Agent 协作在各自 APK 内使用，不出现在主应用。
 */
enum class ApexTab(
    val label: String,
    val icon: ImageVector,
    val description: String
) {
    CHAT(label = "Agent", icon = Icons.Default.Chat, description = "普通 Agent 对话"),
    SUITE(label = "套件", icon = Icons.Default.Apps, description = "APK 套件管理"),
    DIAGNOSTICS(label = "诊断", icon = Icons.Default.MonitorHeart, description = "日志 / 性能 / 崩溃"),
    SETTINGS(label = "设置", icon = Icons.Default.Settings, description = "应用设置");
}
