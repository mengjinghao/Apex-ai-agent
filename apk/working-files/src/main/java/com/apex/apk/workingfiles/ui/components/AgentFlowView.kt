package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.agent.AgentFlow
import com.apex.lib.workingfiles.agent.AgentSession
import com.apex.lib.workingfiles.agent.AgentSessionStatus
import com.apex.lib.workingfiles.agent.AgentStep
import com.apex.lib.workingfiles.agent.AgentStepType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Agent 执行流程视图 — 垂直时间线展示 Agent 在一次会话中执行的每一步。
 *
 * **设计参考**：
 *   - Cline (VSCode)：每个步骤含 say / tool / ask 等类型，垂直排列
 *   - Jupyter Notebook：单元格执行顺序，含状态
 *   - GitHub Actions：步骤状态（success/failure）
 *
 * 每个步骤展示：
 * - 类型图标 + 颜色
 * - 标题
 * - 时间 + 持续时间
 * - Agent 名称
 * - 影响的文件数
 * - 关联快照数
 * - 成功/失败状态
 *
 * 点击含快照的步骤可查看 diff。
 */
@Composable
fun AgentFlowView(
    flow: AgentFlow,
    onStepClick: (AgentStep) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(CodeColors.EditorBackground)
    ) {
        // 会话头
        item { SessionHeader(flow.session) }

        // 统计卡片
        item { SessionStatsCard(flow) }

        // 步骤列表
        items(flow.steps) { step ->
            StepCard(
                step = step,
                onClick = { onStepClick(step) }
            )
        }
    }
}

@Composable
private fun SessionHeader(session: AgentSession) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val statusColor = when (session.status) {
        AgentSessionStatus.RUNNING -> CodeColors.Primary
        AgentSessionStatus.COMPLETED -> CodeColors.Success
        AgentSessionStatus.FAILED -> CodeColors.Error
        AgentSessionStatus.CANCELLED -> CodeColors.Warning
        AgentSessionStatus.PAUSED -> CodeColors.Warning
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeColors.Surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when (session.mode) {
                    com.apex.lib.workingfiles.agent.AgentMode.NORMAL -> Icons.Default.SmartToy
                    com.apex.lib.workingfiles.agent.AgentMode.MULTI_AGENT -> Icons.Default.Group
                    com.apex.lib.workingfiles.agent.AgentMode.BURST -> Icons.Default.Bolt
                },
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = session.agentName,
                color = CodeColors.EditorForeground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = session.status.displayName,
                    color = statusColor,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "任务：${session.taskDescription}",
            color = CodeColors.EditorForeground,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "开始：${dateFormat.format(Date(session.startTime))}  ·  持续：${session.durationMs / 1000}秒",
            color = CodeColors.LineNumberForeground,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        if (session.finalResult != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "结果：${session.finalResult}",
                color = CodeColors.Success,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SessionStatsCard(flow: AgentFlow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeColors.SurfaceVariant)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(label = "步骤", value = flow.steps.size.toString(), color = CodeColors.Primary)
        StatItem(label = "文件变更", value = flow.totalFileChanges.toString(), color = CodeColors.Success)
        StatItem(label = "快照", value = flow.totalSnapshots.toString(), color = CodeColors.Warning)
        StatItem(label = "错误", value = flow.errorCount.toString(), color = if (flow.hasErrors) CodeColors.Error else CodeColors.Disabled)
        StatItem(label = "时长", value = "${flow.totalDurationMs / 1000}s", color = CodeColors.EditorForeground)
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = CodeColors.LineNumberForeground,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun StepCard(
    step: AgentStep,
    onClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val dotColor = when {
        !step.isSuccess -> CodeColors.TimelineDotError
        step.type == AgentStepType.ROLLBACK -> CodeColors.Warning
        step.type == AgentStepType.ERROR -> CodeColors.Error
        step.type == AgentStepType.CHECKPOINT -> CodeColors.Success
        step.affectedFiles.isNotEmpty() -> CodeColors.TimelineDot
        else -> CodeColors.LineNumberForeground
    }
    val iconVector = iconForStepType(step.type)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
    ) {
        // 时间线圆点 + 线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = dotColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            // 竖线（连接到下一个步骤）
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(40.dp)
                    .background(CodeColors.TimelineLine)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 内容
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${step.order}",
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = step.type.displayName,
                    color = dotColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = step.agentName,
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = step.title,
                color = CodeColors.EditorForeground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            )
            if (step.description.isNotBlank()) {
                Text(
                    text = step.description,
                    color = CodeColors.LineNumberForeground,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeFormat.format(Date(step.timestamp)),
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (step.durationMs > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${step.durationMs}ms",
                        color = CodeColors.LineNumberForeground,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (step.affectedFiles.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = CodeColors.LineNumberForeground,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "${step.affectedFiles.size}",
                        color = CodeColors.Success,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (step.snapshotIds.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = CodeColors.Warning,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "${step.snapshotIds.size}",
                        color = CodeColors.Warning,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (!step.isSuccess && step.errorMessage != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "❌ ${step.errorMessage?.let { e -> e.take(50)}",
                        color = CodeColors.Error,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun iconForStepType(type: AgentStepType): ImageVector = when (type) {
    AgentStepType.THOUGHT -> Icons.Default.Psychology
    AgentStepType.ACTION -> Icons.Default.PlayArrow
    AgentStepType.TOOL_CALL -> Icons.Default.Build
    AgentStepType.FILE_READ -> Icons.Default.FileOpen
    AgentStepType.FILE_WRITE -> Icons.Default.Edit
    AgentStepType.FILE_EDIT -> Icons.Default.Edit
    AgentStepType.FILE_DELETE -> Icons.Default.Delete
    AgentStepType.COMMAND -> Icons.Default.Terminal
    AgentStepType.LLM_CALL -> Icons.Default.Cloud
    AgentStepType.SEARCH -> Icons.Default.Search
    AgentStepType.WEB -> Icons.Default.Language
    AgentStepType.OBSERVATION -> Icons.Default.Visibility
    AgentStepType.REFLECTION -> Icons.Default.Refresh
    AgentStepType.DECISION -> Icons.Default.CheckCircle
    AgentStepType.ERROR -> Icons.Default.Error
    AgentStepType.CHECKPOINT -> Icons.Default.Bookmark
    AgentStepType.ROLLBACK -> Icons.Default.Restore
    AgentStepType.USER_INPUT -> Icons.Default.Person
    AgentStepType.CUSTOM -> Icons.Default.Star
}
