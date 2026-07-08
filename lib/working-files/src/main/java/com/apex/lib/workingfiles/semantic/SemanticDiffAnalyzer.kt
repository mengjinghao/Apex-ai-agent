package com.apex.lib.workingfiles.semantic

import com.apex.lib.workingfiles.diff.FileDiff
import com.apex.lib.workingfiles.diff.DiffLineType

/**
 * 语义 Diff 计算器 — 基于规则识别变更类型和风险。
 *
 * **不依赖 LLM**，纯规则分析。未来可加 LLM 增强版。
 *
 * **识别规则**：
 *   - FEATURE: 新增函数/类 + 包含 "add"/"new"/"create" 关键词
 *   - BUGFIX: 包含 "fix"/"bug"/"issue"/"null" 检查
 *   - REFACTOR: 函数签名变化但行数变化小
 *   - PERFORMANCE: 包含 "perf"/"cache"/"async" 关键词
 *   - DOCS: 仅注释/文档变化
 *   - TEST: 测试文件或 "test"/"assert" 关键词
 *   - BREAKING: 删除 public 函数 / 修改函数签名
 */
object SemanticDiffAnalyzer {

    /**
     * 分析 [FileDiff]，生成 [SemanticDiff]。
     */
    fun analyze(diff: FileDiff): SemanticDiff {
        val addedLines = diff.hunks.flatMap { it.lines }.filter { it.type == DiffLineType.ADDED }
        val removedLines = diff.hunks.flatMap { it.lines }.filter { it.type == DiffLineType.REMOVED }

        val changeType = detectChangeType(addedLines, removedLines)
        val riskLevel = detectRiskLevel(diff, addedLines, removedLines)
        val affectedSymbols = detectAffectedSymbols(addedLines, removedLines)
        val breakingChanges = detectBreakingChanges(addedLines, removedLines)
        val suggestions = generateSuggestions(changeType, riskLevel, breakingChanges)
        val summary = generateSummary(diff, changeType, affectedSymbols)

        return SemanticDiff(
            summary = summary,
            changeType = changeType,
            riskLevel = riskLevel,
            affectedSymbols = affectedSymbols,
            breakingChanges = breakingChanges,
            suggestions = suggestions,
            lineStats = diff.summary
        )
    }

    private fun detectChangeType(
        addedLines: List<com.apex.lib.workingfiles.diff.DiffLine>,
        removedLines: List<com.apex.lib.workingfiles.diff.DiffLine>
    ): SemanticChangeType {
        val addedText = addedLines.joinToString(" ") { it.content }.lowercase()
        val removedText = removedLines.joinToString(" ") { it.content }.lowercase()
        val allText = "$addedText $removedText"

        // 优先级：BREAKING > FEATURE > BUGFIX > PERFORMANCE > REFACTOR > DOCS > TEST > STYLE
        return when {
            // 破坏性变更
            removedLines.any { isFunctionDefinition(it.content) } &&
                addedLines.none { isFunctionDefinition(it.content) } -> SemanticChangeType.BREAKING

            // 新功能：新增函数/类 + add/new/create
            addedLines.any { isFunctionDefinition(it.content) || isClassDefinition(it.content) } -> SemanticChangeType.FEATURE

            // Bug 修复
            anyContains(allText, listOf("fix", "bug", "issue", "null", "npe", "crash", "error")) -> SemanticChangeType.BUGFIX

            // 性能优化
            anyContains(allText, listOf("perf", "cache", "async", "optimize", "speed")) -> SemanticChangeType.PERFORMANCE

            // 文档
            addedLines.all { isCommentOrBlank(it.content) } && removedLines.all { isCommentOrBlank(it.content) }
                && (addedLines.isNotEmpty() || removedLines.isNotEmpty()) -> SemanticChangeType.DOCS

            // 测试
            anyContains(allText, listOf("test", "assert", "@test", "should ")) -> SemanticChangeType.TEST

            // 重构：函数签名变化但行数变化小
            isRefactor(addedLines, removedLines) -> SemanticChangeType.REFACTOR

            // 格式
            addedLines.all { it.content.isBlank() || it.content.startsWith(" ") } &&
                removedLines.all { it.content.isBlank() || it.content.startsWith(" ") } -> SemanticChangeType.STYLE

            else -> SemanticChangeType.UNKNOWN
        }
    }

    private fun detectRiskLevel(
        diff: FileDiff,
        addedLines: List<com.apex.lib.workingfiles.diff.DiffLine>,
        removedLines: List<com.apex.lib.workingfiles.diff.DiffLine>
    ): RiskLevel {
        val removedCount = removedLines.size
        val addedCount = addedLines.size
        val totalChange = removedCount + addedCount

        // 删除 public 函数 → CRITICAL
        if (removedLines.any { it.content.contains("public ") || it.content.contains("fun ") }) {
            return RiskLevel.CRITICAL
        }

        // 修改函数签名 → HIGH
        if (removedLines.any { isFunctionDefinition(it.content) } && addedLines.any { isFunctionDefinition(it.content) }) {
            return RiskLevel.HIGH
        }

        // 大量删除 → HIGH
        if (removedCount > 20) return RiskLevel.HIGH

        // 中等变更 → MEDIUM
        if (totalChange > 10) return RiskLevel.MEDIUM

        // 少量变更 → LOW
        if (totalChange > 0) return RiskLevel.LOW

        return RiskLevel.NONE
    }

    private fun detectAffectedSymbols(
        addedLines: List<com.apex.lib.workingfiles.diff.DiffLine>,
        removedLines: List<com.apex.lib.workingfiles.diff.DiffLine>
    ): List<String> {
        val symbols = mutableSetOf<String>()
        val allLines = addedLines + removedLines

        // 检测函数名
        for (line in allLines) {
            extractFunctionName(line.content)?.let { symbols.add("fun $it()") }
            extractClassName(line.content)?.let { symbols.add("class $it") }
        }

        // 检测 import 变化
        for (line in allLines) {
            if (line.content.trimStart().startsWith("import ")) {
                symbols.add("import: ${line.content.trim().removePrefix("import ")}")
            }
        }

        return symbols.toList().take(10)  // 最多 10 个
    }

    private fun detectBreakingChanges(
        addedLines: List<com.apex.lib.workingfiles.diff.DiffLine>,
        removedLines: List<com.apex.lib.workingfiles.diff.DiffLine>
    ): List<String> {
        val breaking = mutableListOf<String>()

        // 删除 public 函数
        removedLines.filter { isFunctionDefinition(it.content) && it.content.contains("public ") }
            .forEach { breaking.add("删除了 public 函数: ${extractFunctionName(it.content) ?: "?"}") }

        // 修改函数签名（删除一个 fun 又新增一个同名 fun 但参数不同）
        val removedFunNames = removedLines.mapNotNull { extractFunctionName(it.content) }.toSet()
        val addedFunNames = addedLines.mapNotNull { extractFunctionName(it.content) }.toSet()
        val modifiedFuns = removedFunNames.intersect(addedFunNames)
        modifiedFuns.forEach { breaking.add("修改了函数签名: $it()") }

        return breaking
    }

    private fun generateSuggestions(
        changeType: SemanticChangeType,
        riskLevel: RiskLevel,
        breakingChanges: List<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()
        if (breakingChanges.isNotEmpty()) {
            suggestions.add("⚠️ 包含 ${breakingChanges.size} 个破坏性变更，需检查下游调用方")
        }
        when (riskLevel) {
            RiskLevel.CRITICAL -> suggestions.add("🔴 严重风险，建议在合并前充分测试")
            RiskLevel.HIGH -> suggestions.add("🟠 高风险，建议代码审查")
            RiskLevel.MEDIUM -> suggestions.add("🟡 中等风险，注意测试覆盖")
            else -> {}
        }
        when (changeType) {
            SemanticChangeType.BREAKING -> suggestions.add("考虑提供兼容层或迁移指南")
            SemanticChangeType.REFACTOR -> suggestions.add("确认测试通过后再合并")
            SemanticChangeType.BUGFIX -> suggestions.add("建议添加回归测试")
            else -> {}
        }
        return suggestions
    }

    private fun generateSummary(
        diff: FileDiff,
        changeType: SemanticChangeType,
        affectedSymbols: List<String>
    ): String {
        val symbolPart = if (affectedSymbols.isNotEmpty()) {
            "，影响：${affectedSymbols.take(3).joinToString("、")}" +
                if (affectedSymbols.size > 3) " 等 ${affectedSymbols.size} 项" else ""
        } else ""
        return "${changeType.icon} ${changeType.displayName}：${diff.summary.shortStat.trim()}$symbolPart"
    }

    // ===== 辅助检测函数 =====

    private fun isFunctionDefinition(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("fun ") || trimmed.startsWith("def ") ||
               trimmed.startsWith("function ") || trimmed.startsWith("void ") ||
               trimmed.startsWith("public ") || trimmed.startsWith("private ") ||
               trimmed.startsWith("protected ") || trimmed.startsWith("internal ")
    }

    private fun isClassDefinition(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("class ") || trimmed.startsWith("interface ") ||
               trimmed.startsWith("enum class ") || trimmed.startsWith("object ") ||
               trimmed.startsWith("data class ") || trimmed.startsWith("sealed class ")
    }

    private fun isCommentOrBlank(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") ||
               trimmed.startsWith("*") || trimmed.startsWith("#") || trimmed.startsWith("<!--")
    }

    private fun anyContains(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }

    private fun isRefactor(
        addedLines: List<com.apex.lib.workingfiles.diff.DiffLine>,
        removedLines: List<com.apex.lib.workingfiles.diff.DiffLine>
    ): Boolean {
        val total = addedLines.size + removedLines.size
        // 行数变化小但有结构变化（函数定义有增有删）
        return total in 5..30 &&
            addedLines.any { isFunctionDefinition(it.content) } &&
            removedLines.any { isFunctionDefinition(it.content) }
    }

    private fun extractFunctionName(line: String): String? {
        // 匹配 fun name( / def name( / function name( / void name( / int name(
        val patterns = listOf(
            Regex("\\bfun\\s+(\\w+)"),
            Regex("\\bdef\\s+(\\w+)"),
            Regex("\\bfunction\\s+(\\w+)"),
            Regex("\\b(?:void|int|String|Boolean|List|Map)\\s+(\\w+)\\s*\\(")
        )
        for (p in patterns) {
            val match = p.find(line)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractClassName(line: String): String? {
        val patterns = listOf(
            Regex("\\bclass\\s+(\\w+)"),
            Regex("\\binterface\\s+(\\w+)"),
            Regex("\\bobject\\s+(\\w+)"),
            Regex("\\benum\\s+class\\s+(\\w+)")
        )
        for (p in patterns) {
            val match = p.find(line)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
