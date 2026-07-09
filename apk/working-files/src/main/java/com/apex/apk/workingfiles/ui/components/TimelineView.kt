package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.snapshot.ChangeSource
import com.apex.lib.workingfiles.snapshot.SnapshotSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间线视图 — 文件快照历史（VSCode Timeline 风格）。
 *
 * 显示文件的所有快照，每个快照含：
 * - 时间
 * - 来源（Agent / 用户 / 外部 / 手动）
 * - 描述
 * - 行数变化
 *
 * 点击快照可查看内容；点击「回退」按钮可恢复文件到该快照。
 */
@Composable
fun TimelineView(
    snapshots: List<SnapshotSummary>,
    selectedSnapshotId: String?,
    onSnapshotClick: (SnapshotSummary) -> Unit,
    onRestoreClick: (SnapshotSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CodeColors.Surface)
    ) {
        // 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = CodeColors.Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "时间线",
                color = CodeColors.EditorForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${snapshots.size} 个版本",
                color = CodeColors.LineNumberForeground,
                fontSize = 11.sp
            )
        }
        Divider(color = CodeColors.TimelineLine, thickness = 0.5.dp)

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(snapshots.reversed()) { snapshot ->  // 最新在上
                SnapshotItem(
                    snapshot = snapshot,
                    isSelected = snapshot.id == selectedSnapshotId,
                    onClick = { onSnapshotClick(snapshot) },
                    onRestoreClick = { onRestoreClick(snapshot) }
                )
                Divider(color = CodeColors.TimelineLine.copy(alpha = 0.3f), thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun SnapshotItem(
    snapshot: SnapshotSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val bg = if (isSelected) CodeColors.TreeItemSelected else Color.Transparent
    val sourceColor = when (snapshot.source) {
        ChangeSource.AGENT -> CodeColors.Primary
        ChangeSource.USER -> CodeColors.Warning
        ChangeSource.EXTERNAL -> CodeColors.LineNumberForeground
        ChangeSource.MANUAL -> CodeColors.Success
    }
    val sourceLabel = when (snapshot.source) {
        ChangeSource.AGENT -> "🤖"
        ChangeSource.USER -> "👤"
        ChangeSource.EXTERNAL -> "🌐"
        ChangeSource.MANUAL -> "✏️"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 时间线圆点
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(sourceColor)
        )
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = sourceLabel, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = snapshot.description.ifEmpty { "(无描述)" },
                    color = CodeColors.EditorForeground,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.SansSerif,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row {
                Text(
                    text = dateFormat.format(Date(snapshot.timestamp)),
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${snapshot.lineCount}行",
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = snapshot.changeType.name,
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp
                )
            }
        }

        // 回退按钮
        IconButton(
            onClick = onRestoreClick,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Restore,
                contentDescription = "回退到此版本",
                tint = CodeColors.Warning,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
