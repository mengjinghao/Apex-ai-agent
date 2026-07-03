package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.branch.BranchStatus
import com.apex.lib.workingfiles.branch.VirtualBranch

/**
 * 虚拟分支管理视图 — Apex 独有功能。
 *
 * 显示文件的所有虚拟分支，支持：
 *   - 创建新分支
 *   - 切换到分支
 *   - 合并分支到 main
 *   - 丢弃分支
 *   - 查看分支 diff
 *
 * 移动端友好的"假设性"分支系统，无需 git。
 */
@Composable
fun BranchManagerView(
    branches: List<VirtualBranch>,
    activeBranchId: String?,
    onCreateBranch: () -> Unit,
    onSwitchToBranch: (VirtualBranch) -> Unit,
    onSwitchToMain: () -> Unit,
    onMergeBranch: (VirtualBranch) -> Unit,
    onDiscardBranch: (VirtualBranch) -> Unit,
    onViewDiff: (VirtualBranch) -> Unit,
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
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CallSplit,
                contentDescription = null,
                tint = CodeColors.Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "虚拟分支",
                color = CodeColors.EditorForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.SEMIBOLD
            )
            Spacer(modifier = Modifier.weight(1f))
            // main 状态
            if (activeBranchId == null) {
                Surface(
                    color = CodeColors.Success.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "main",
                        color = CodeColors.Success,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onCreateBranch, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "创建分支",
                    tint = CodeColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Divider(color = CodeColors.TimelineLine, thickness = 0.5.dp)

        // main 分支项
        BranchItem(
            name = "main",
            description = "主分支",
            color = "#4EC9B0",
            isActive = activeBranchId == null,
            status = BranchStatus.ACTIVE,
            onClick = onSwitchToMain
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(branches) { branch ->
                BranchItem(
                    name = branch.name,
                    description = branch.description.ifEmpty { "无描述" },
                    color = branch.color,
                    isActive = branch.id == activeBranchId,
                    status = branch.status,
                    onClick = { onSwitchToBranch(branch) },
                    onMerge = { onMergeBranch(branch) },
                    onDiscard = { onDiscardBranch(branch) },
                    onViewDiff = { onViewDiff(branch) }
                )
            }
        }
    }
}

@Composable
private fun BranchItem(
    name: String,
    description: String,
    color: String,
    isActive: Boolean,
    status: BranchStatus,
    onClick: () -> Unit = {},
    onMerge: (() -> Unit)? = null,
    onDiscard: (() -> Unit)? = null,
    onViewDiff: (() -> Unit)? = null
) {
    val branchColor = runCatching { Color(android.graphics.Color.parseColor(color)) }.getOrDefault(CodeColors.Primary)
    val bg = if (isActive) CodeColors.TreeItemSelected else Color.Transparent
    val statusColor = when (status) {
        BranchStatus.ACTIVE -> CodeColors.Success
        BranchStatus.MERGED -> CodeColors.LineNumberForeground
        BranchStatus.DISCARDED -> CodeColors.Error
        BranchStatus.LOCKED -> CodeColors.Warning
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 分支色块
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(branchColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                color = CodeColors.EditorForeground,
                fontSize = 13.sp,
                fontWeight = FontWeight.MEDIUM,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isActive) {
                Surface(
                    color = CodeColors.Primary.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "当前",
                        color = CodeColors.Primary,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                color = statusColor.copy(alpha = 0.2f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = status.displayName,
                    color = statusColor,
                    fontSize = 9.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
        if (description.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = CodeColors.LineNumberForeground,
                fontSize = 11.sp,
                maxLines = 1
            )
        }
        // 操作按钮（活跃分支显示合并/丢弃/diff）
        if (status == BranchStatus.ACTIVE && onMerge != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                if (onViewDiff != null) {
                    TextButton(onClick = onViewDiff, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("查看 diff", fontSize = 10.sp, color = CodeColors.Primary)
                    }
                }
                if (onMerge != null) {
                    TextButton(onClick = onMerge, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("合并", fontSize = 10.sp, color = CodeColors.Success)
                    }
                }
                if (onDiscard != null) {
                    TextButton(onClick = onDiscard, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("丢弃", fontSize = 10.sp, color = CodeColors.Error)
                    }
                }
            }
        }
    }
    Divider(color = CodeColors.TimelineLine.copy(alpha = 0.3f), thickness = 0.5.dp)
}
