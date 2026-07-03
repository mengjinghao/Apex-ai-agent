package com.apex.lib.workingfiles.semantic

import com.apex.lib.workingfiles.diff.DiffSummary
import com.apex.lib.workingfiles.diff.FileDiff
import kotlinx.serialization.Serializable

/**
 * 语义 Diff — Apex 独有的"AI 增强差异分析"。
 *
 * **创新点**（VSCode/GitHub 都只显示行级 diff）：
 *   - 用 LLM 总结变更："这个变更把 add() 改成了支持多参数"
 *   - 检测变更类型（FEATURE/BUGFIX/REFACTOR/...）
 *   - 评估风险等级（LOW/MEDIUM/HIGH/CRITICAL）
 *   - 列出影响范围（哪些函数/类被修改）
 *   - 检测 breaking change（API 签名变化）
 *
 * **简化实现**（不依赖 LLM，基于规则）：
 *   - 通过 diff 行模式识别变更类型
 *   - 通过关键词检测风险
 *   - 通过符号扫描识别影响函数
 *
 * **未来增强**：接入真实 LLM（通过 Market APK 的 invokeModel）做更准确的分析。
 */
@Serializable
data class SemanticDiff(
    val summary: String,              // 一句话总结
    val changeType: SemanticChangeType,
    val riskLevel: RiskLevel,
    val affectedSymbols: List<String>, // 影响的函数/类名
    val breakingChanges: List<String>, // 破坏性变更列表
    val suggestions: List<String>,     // 建议操作
    val lineStats: DiffSummary        // 行级统计
) {
    /** 简短描述："✨ 新功能 · 中等风险 · +12 -3" */
    val shortDescription: String
        get() = "${changeType.icon} ${changeType.displayName} · ${riskLevel.displayName}风险 · ${lineStats.shortStat}"
}

/** 语义变更类型。 */
@Serializable
enum class SemanticChangeType(val displayName: String, val icon: String) {
    FEATURE("新功能", "✨"),
    BUGFIX("修复", "🐛"),
    REFACTOR("重构", "♻️"),
    PERFORMANCE("性能优化", "⚡"),
    DOCS("文档", "📝"),
    TEST("测试", "✅"),
    STYLE("格式", "🎨"),
    CHORE("杂项", "🔧"),
    BREAKING("破坏性", "💥"),
    UNKNOWN("未知", "❓")
}

/** 风险等级。 */
@Serializable
enum class RiskLevel(val displayName: String, val score: Int) {
    NONE("无", 0),
    LOW("低", 1),
    MEDIUM("中等", 2),
    HIGH("高", 3),
    CRITICAL("严重", 4)
}
