package com.apex.lib.workingfiles.diff

import kotlinx.serialization.Serializable

/**
 * 文件 Diff 结果。
 *
 * 由 [DiffComputer] 计算得出，包含多个 [DiffHunk]。
 * 每个 hunk 是一段连续的差异区域 + 上下文。
 *
 * **设计参考**：git diff 的 unified format
 *   - 每个 hunk 头：`@@ -oldStart,oldLen +newStart,newLen @@`
 *   - 行前缀：` `（上下文）/ `-`（删除）/ `+`（新增）
 */
@Serializable
data class FileDiff(
    val oldFilePath: String,
    val newFilePath: String,
    val oldHash: String,
    val newHash: String,
    val hunks: List<DiffHunk>,
    val summary: DiffSummary
) {
    /** 是否有差异。 */
    val hasChanges: Boolean get() = hunks.isNotEmpty() && summary.addedLines + summary.removedLines > 0
}

/**
 * 一个 diff hunk — 一段连续的差异 + 上下文。
 */
@Serializable
data class DiffHunk(
    val oldStart: Int,    // 旧文件起始行号（1-based）
    val oldEnd: Int,      // 旧文件结束行号
    val newStart: Int,    // 新文件起始行号
    val newEnd: Int,      // 新文件结束行号
    val lines: List<DiffLine>
) {
    /** hunk 头：@@ -oldStart,oldLen +newStart,newLen @@ */
    val header: String
        get() {
            val oldLen = oldEnd - oldStart + 1
            val newLen = newEnd - newStart + 1
            return "@@ -$oldStart,$oldLen +$newStart,$newLen @@"
        }
}

/**
 * 单行 diff。
 */
@Serializable
data class DiffLine(
    val type: DiffLineType,
    val content: String,
    val oldLineNumber: Int?,   // 旧文件行号（1-based，null 表示新增行）
    val newLineNumber: Int?    // 新文件行号（1-based，null 表示删除行）
)

/** Diff 行类型。 */
@Serializable
enum class DiffLineType(val prefix: Char, val displayName: String) {
    CONTEXT(' ', "上下文"),
    ADDED('+', "新增"),
    REMOVED('-', "删除"),
    EMPTY(' ', "空行")
}

/**
 * Diff 统计摘要。
 */
@Serializable
data class DiffSummary(
    val addedLines: Int,
    val removedLines: Int,
    val modifiedLines: Int,  // 块修改（一对 -/+）
    val unchangedLines: Int,
    val totalOldLines: Int,
    val totalNewLines: Int
) {
    /** 净增减行数。 */
    val netChange: Int get() = addedLines - removedLines

    /** 简短描述：" +10 -3" */
    val shortStat: String get() = " +$addedLines -$removedLines"
}
