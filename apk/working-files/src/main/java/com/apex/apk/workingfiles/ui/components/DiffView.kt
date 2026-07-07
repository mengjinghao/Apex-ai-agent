package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.diff.DiffHunk
import com.apex.lib.workingfiles.diff.DiffLine
import com.apex.lib.workingfiles.diff.DiffLineType
import com.apex.lib.workingfiles.diff.FileDiff

/**
 * Diff 视图 — 行级展示文件变更。
 *
 * - 新增行：绿色背景
 * - 删除行：红色背景
 * - 上下文行：默认背景
 * - 行号（旧/新）
 *
 * **设计参考**：GitHub PR diff view + VSCode diff editor
 */
@Composable
fun DiffView(
    diff: FileDiff,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CodeColors.EditorBackground)
            .verticalScroll(scrollState)
    ) {
        // 文件头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CodeColors.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = diff.oldFilePath,
                color = CodeColors.DiffRemovedFg,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = " → ",
                color = CodeColors.EditorForeground,
                fontSize = 12.sp
            )
            Text(
                text = diff.newFilePath,
                color = CodeColors.DiffAddedFg,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = diff.summary.shortStat,
                color = if (diff.summary.netChange >= 0) CodeColors.DiffAddedFg else CodeColors.DiffRemovedFg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (!diff.hasChanges) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "无差异",
                    color = CodeColors.Disabled,
                    fontSize = 13.sp
                )
            }
            return@Column
        }

        // Hunks
        diff.hunks.forEach { hunk ->
            DiffHunkView(hunk, horizontalScrollState)
        }
    }
}

@Composable
private fun DiffHunkView(
    hunk: DiffHunk,
    horizontalScrollState: androidx.compose.foundation.ScrollState
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(horizontalScrollState)
    ) {
        // Hunk header
        Text(
            text = hunk.header,
            color = CodeColors.DiffHeader,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0C2D6B))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        // Lines
        hunk.lines.forEach { line ->
            DiffLineView(line)
        }
    }
}

@Composable
private fun DiffLineView(line: DiffLine) {
    val bgColor = when (line.type) {
        DiffLineType.ADDED -> CodeColors.DiffAdded
        DiffLineType.REMOVED -> CodeColors.DiffRemoved
        DiffLineType.CONTEXT -> Color.Transparent
        DiffLineType.EMPTY -> Color.Transparent
    }
    val fgColor = when (line.type) {
        DiffLineType.ADDED -> CodeColors.DiffAddedFg
        DiffLineType.REMOVED -> CodeColors.DiffRemovedFg
        else -> CodeColors.DiffContext
    }
    val prefix = when (line.type) {
        DiffLineType.ADDED -> "+"
        DiffLineType.REMOVED -> "-"
        else -> " "
    }
    val oldNum = line.oldLineNumber?.toString() ?: ""
    val newNum = line.newLineNumber?.toString() ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
    ) {
        // 旧行号
        Text(
            text = oldNum.padStart(5, ' '),
            color = CodeColors.LineNumberForeground,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(50.dp)
                .padding(horizontal = 4.dp, vertical = 0.dp)
        )
        // 新行号
        Text(
            text = newNum.padStart(5, ' '),
            color = CodeColors.LineNumberForeground,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(50.dp)
                .padding(horizontal = 4.dp, vertical = 0.dp)
        )
        // 前缀
        Text(
            text = prefix,
            color = fgColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(12.dp)
        )
        // 内容
        Text(
            text = line.content,
            color = fgColor,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            softWrap = false
        )
    }
}
