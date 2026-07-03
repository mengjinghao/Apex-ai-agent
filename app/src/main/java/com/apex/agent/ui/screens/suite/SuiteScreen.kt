package com.apex.agent.ui.screens.suite

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * APK 套件管理界面 — 显示所有 APK 安装状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuiteScreen(modifier: Modifier = Modifier) {
    val apks = remember { getMockApks() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("套件管理", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 安装摘要
            item {
                SuiteSummaryCard(
                    totalApks = apks.size,
                    installedCount = apks.count { it.installed },
                    requiredCount = apks.count { it.necessity == "REQUIRED" },
                    optionalCount = apks.count { it.necessity == "OPTIONAL" }
                )
            }

            // 必须 APK
            item {
                Text(
                    "必须组件",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }
            items(apks.filter { it.necessity == "REQUIRED" }) { apk ->
                ApkCard(apk)
            }

            // 可选 APK
            item {
                Text(
                    "可选组件",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
            }
            items(apks.filter { it.necessity == "OPTIONAL" }) { apk ->
                ApkCard(apk)
            }
        }
    }
}

@Composable
private fun SuiteSummaryCard(
    totalApks: Int,
    installedCount: Int,
    requiredCount: Int,
    optionalCount: Int
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp)
        ) {
            Text(
                "Apex 套件",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "已安装 $installedCount / $totalApks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "必须 $requiredCount 个 · 可选 $optionalCount 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ApkCard(apk: ApkInfo) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (apk.installed) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (apk.installed) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (apk.installed) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "已安装",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Cancel,
                        contentDescription = "未安装",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // 信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    apk.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    apk.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (apk.approxSizeMb > 0) {
                    Text(
                        "约 ${apk.approxSizeMb}MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // 操作按钮
            if (apk.installed) {
                FilledTonalIconButton(
                    onClick = {},
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Launch, "打开")
                }
            } else {
                FilledIconButton(
                    onClick = {},
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Download, "下载")
                }
            }
        }
    }
}

/** APK 信息。 */
data class ApkInfo(
    val apkId: String,
    val displayName: String,
    val description: String,
    val necessity: String,  // REQUIRED / OPTIONAL
    val installed: Boolean,
    val approxSizeMb: Int = 0
)

/** 模拟数据（实际应从 ApkDependencyManager 获取）。 */
private fun getMockApks(): List<ApkInfo> = listOf(
    ApkInfo("main", "Apex 主应用", "普通 Agent + 设置 + 权限 + 诊断", "REQUIRED", true, 60),
    ApkInfo("engine", "Apex 引擎", "Shell + 工具 + 容器 + 无障碍 + Shizuku", "REQUIRED", true, 15),
    ApkInfo("terminal", "Apex 终端", "三块终端 + C++ PTY", "REQUIRED", true, 10),
    ApkInfo("market", "Apex 市场", "27 个市场 + LLM 调用 + 缓存 + 收藏", "REQUIRED", true, 8),
    ApkInfo("rage", "Apex 狂暴模式", "31 个内置技能 + 7 种预设 + 4 种执行", "REQUIRED", true, 20),
    ApkInfo("multi-agent", "Apex 多 Agent", "7 种协作 + 30 种角色模板 + 三省六部", "REQUIRED", true, 8),
    ApkInfo("working-files", "Apex 工作文件区", "VSCode 式代码浏览 + 快照 + 分支", "OPTIONAL", false, 5),
    ApkInfo("workflow", "Apex 工作流", "DAG 编排 + 8 种节点", "OPTIONAL", false, 6),
    ApkInfo("voice", "Apex 语音", "TTS + ASR 多语言", "OPTIONAL", false, 4)
)
