package com.apex.apk.workingfiles.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apex.apk.workingfiles.ui.theme.CodeColors
import com.apex.lib.workingfiles.semantic.RiskLevel
import com.apex.lib.workingfiles.semantic.SemanticChangeType
import com.apex.lib.workingfiles.semantic.SemanticDiff

/**
 * 语义 Diff 卡片 — Apex 独有的"AI 增强差异分析"展示。
 *
 * 显示：
 *   - 变更类型（图标 + 颜色）
 *   - 风险等级（颜色条）
 *   - 一句话总结
 *   - 影响的符号列表
 *   - 破坏性变更警告
 *   - 建议操作
 */
@Composable
fun SemanticDiffCard(
    semantic: SemanticDiff,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CodeColors.Surface)
            .padding(12.dp)
    ) {
        // 头部：变更类型 + 风险
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = semantic.changeType.icon,
                fontSize = 20.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = semantic.changeType.displayName,
                    color = changeTypeColor(semantic.changeType),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SEMIBOLD
                )
                Text(
                    text = semantic.summary,
                    color = CodeColors.EditorForeground,
                    fontSize = 11.sp,
                    maxLines = 2
                )
            }
            // 风险徽章
            RiskBadge(riskLevel = semantic.riskLevel)
        }

        // 影响的符号
        if (semantic.affectedSymbols.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Divider(color = CodeColors.TimelineLine, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "影响范围",
                color = CodeColors.LineNumberForeground,
                fontSize = 10.sp,
                fontWeight = FontWeight.SEMIBOLD
            )
            Spacer(modifier = Modifier.height(4.dp))
            semantic.affectedSymbols.take(5).forEach { symbol ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        tint = CodeColors.Primary,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = symbol,
                        color = CodeColors.EditorForeground,
                        fontSize = 11.sp
                    )
                }
            }
            if (semantic.affectedSymbols.size > 5) {
                Text(
                    text = "... 共 ${semantic.affectedSymbols.size} 项",
                    color = CodeColors.LineNumberForeground,
                    fontSize = 10.sp
                )
            }
        }

        // 破坏性变更
        if (semantic.breakingChanges.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                color = CodeColors.Error.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = CodeColors.Error,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "破坏性变更 (${semantic.breakingChanges.size})",
                            color = CodeColors.Error,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SEMIBOLD
                        )
                    }
                    semantic.breakingChanges.forEach { change ->
                        Text(
                            text = "• $change",
                            color = CodeColors.Error,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                        )
                    }
                }
            }
        }

        // 建议
        if (semantic.suggestions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            semantic.suggestions.forEach { suggestion ->
                Row(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(text = "→", color = CodeColors.Warning, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = suggestion,
                        color = CodeColors.EditorForeground,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskBadge(riskLevel: RiskLevel) {
    val color = when (riskLevel) {
        RiskLevel.NONE -> CodeColors.Disabled
        RiskLevel.LOW -> CodeColors.Success
        RiskLevel.MEDIUM -> CodeColors.Warning
        RiskLevel.HIGH -> Color(0xFFFF9800)
        RiskLevel.CRITICAL -> CodeColors.Error
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = "${riskLevel.displayName}风险",
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.MEDIUM,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun changeTypeColor(type: SemanticChangeType) = when (type) {
    SemanticChangeType.FEATURE -> CodeColors.Success
    SemanticChangeType.BUGFIX -> CodeColors.Primary
    SemanticChangeType.REFACTOR -> CodeColors.Warning
    SemanticChangeType.PERFORMANCE -> Color(0xFF00BCD4)
    SemanticChangeType.DOCS -> CodeColors.LineNumberForeground
    SemanticChangeType.TEST -> Color(0xFF4CAF50)
    SemanticChangeType.STYLE -> CodeColors.LineNumberForeground
    SemanticChangeType.CHORE -> CodeColors.LineNumberForeground
    SemanticChangeType.BREAKING -> CodeColors.Error
    SemanticChangeType.UNKNOWN -> CodeColors.Disabled
}
