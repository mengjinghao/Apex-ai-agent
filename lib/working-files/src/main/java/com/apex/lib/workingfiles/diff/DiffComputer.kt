package com.apex.lib.workingfiles.diff

import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.github.difflib.patch.AbstractDelta
import com.github.difflib.patch.DeltaType

/**
 * Diff 计算器 — 基于 java-diff-utils 的 Myers 算法。
 *
 * **设计参考**：
 *   - git diff：unified format，上下文 3 行
 *   - VSCode diff editor：并排展示，行级 + 字符级 diff
 *   - GitHub PR diff：支持折叠上下文
 *
 * **能力**：
 *   - 行级 diff（Myers 算法）
 *   - 字符级 diff（用于单行内的字符差异，未实现，预留）
 *   - unified diff format
 *   - 自定义 [FileDiff] 结构，便于 UI 渲染
 */
object DiffComputer {

    /** 默认上下文行数（同 git diff）。 */
    const val DEFAULT_CONTEXT_LINES = 3

    /**
     * 计算两个文本之间的 diff。
     *
     * @param oldContent 旧文本
     * @param newContent 新文本
     * @param contextLines 上下文行数（默认 3，同 git）
     * @return [FileDiff]
     */
    fun compute(
        oldContent: String,
        newContent: String,
        contextLines: Int = DEFAULT_CONTEXT_LINES
    ): FileDiff {
        val oldLines = oldContent.split("\n")
        val newLines = newContent.split("\n")

        // 用 Myers 算法计算 patch
        val patch = DiffUtils.diff(oldLines, newLines)

        // 生成 unified diff
        val unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
            "old", "new",
            oldLines, patch, contextLines
        )

        // 解析 unified diff → 自定义结构
        val hunks = parseUnifiedDiff(unifiedDiff, oldLines, newLines)

        // 计算统计
        val summary = computeSummary(oldLines, newLines, hunks)

        return FileDiff(
            oldFilePath = "old",
            newFilePath = "new",
            oldHash = hash(oldContent),
            newHash = hash(newContent),
            hunks = hunks,
            summary = summary
        )
    }

    /**
     * 解析 unified diff 格式 → [DiffHunk] 列表。
     */
    private fun parseUnifiedDiff(
        unifiedDiff: List<String>,
        oldLines: List<String>,
        newLines: List<String>
    ): List<DiffHunk> {
        if (unifiedDiff.size <= 2) return emptyList()

        val hunks = mutableListOf<DiffHunk>()
        var i = 2  // 跳过前两行（--- old / +++ new）

        while (i < unifiedDiff.size) {
            val line = unifiedDiff[i]
            if (!line.startsWith("@@")) {
                i++
                continue
            }

            // 解析 hunk header: @@ -oldStart,oldLen +newStart,newLen @@
            val match = Regex("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@").find(line)
                ?: Regex("@@ -(\\d+),(\\d+) \\+(\\d+) \\+@").find(line)
                ?: Regex("@@ -(\\d+) \\+(\\d+),(\\d+) @@").find(line)
                ?: Regex("@@ -(\\d+) \\+(\\d+) @@").find(line)
            if (match == null) {
                i++
                continue
            }

            val (oldStartStr, _, newStartStr, _) = match.destructured
            var oldStart = oldStartStr.toIntOrNull() ?: 1
            var newStart = newStartStr.toIntOrNull() ?: 1
            if (oldStart == 0) oldStart = 1
            if (newStart == 0) newStart = 1

            i++
            val diffLines = mutableListOf<DiffLine>()
            var oldLineNum = oldStart
            var newLineNum = newStart

            while (i < unifiedDiff.size && !unifiedDiff[i].startsWith("@@")) {
                val diffLine = unifiedDiff[i]
                when {
                    diffLine.startsWith("+++") || diffLine.startsWith("---") -> {
                        // 跳过文件头
                    }
                    diffLine.startsWith("+") -> {
                        diffLines.add(DiffLine(
                            type = DiffLineType.ADDED,
                            content = diffLine.substring(1),
                            oldLineNumber = null,
                            newLineNumber = newLineNum++
                        ))
                    }
                    diffLine.startsWith("-") -> {
                        diffLines.add(DiffLine(
                            type = DiffLineType.REMOVED,
                            content = diffLine.substring(1),
                            oldLineNumber = oldLineNum++,
                            newLineNumber = null
                        ))
                    }
                    diffLine.startsWith(" ") -> {
                        diffLines.add(DiffLine(
                            type = DiffLineType.CONTEXT,
                            content = diffLine.substring(1),
                            oldLineNumber = oldLineNum++,
                            newLineNumber = newLineNum++
                        ))
                    }
                    diffLine.isEmpty() -> {
                        diffLines.add(DiffLine(
                            type = DiffLineType.CONTEXT,
                            content = "",
                            oldLineNumber = oldLineNum++,
                            newLineNumber = newLineNum++
                        ))
                    }
                }
                i++
            }

            val oldEnd = diffLines.filter { it.oldLineNumber != null }.maxOrNull()?.oldLineNumber ?: oldStart
            val newEnd = diffLines.filter { it.newLineNumber != null }.maxOrNull()?.newLineNumber ?: newStart
            hunks.add(DiffHunk(oldStart, oldEnd, newStart, newEnd, diffLines))
        }

        return hunks
    }

    /**
     * 计算 diff 统计。
     */
    private fun computeSummary(
        oldLines: List<String>,
        newLines: List<String>,
        hunks: List<DiffHunk>
    ): DiffSummary {
        var added = 0
        var removed = 0
        var modified = 0

        // 把 + 和 - 配对统计为 modified（保守估计：每个 hunk 内的 min(+/-) 为 modified）
        for (hunk in hunks) {
            val hunkAdded = hunk.lines.count { it.type == DiffLineType.ADDED }
            val hunkRemoved = hunk.lines.count { it.type == DiffLineType.REMOVED }
            added += hunkAdded
            removed += hunkRemoved
            modified += minOf(hunkAdded, hunkRemoved)
        }

        val unchanged = oldLines.size - removed
        return DiffSummary(
            addedLines = added,
            removedLines = removed,
            modifiedLines = modified,
            unchangedLines = if (unchanged < 0) 0 else unchanged,
            totalOldLines = oldLines.size,
            totalNewLines = newLines.size
        )
    }

    /**
     * 生成 unified diff 文本（同 git diff 格式）。
     */
    fun toUnifiedDiffText(diff: FileDiff): String {
        val sb = StringBuilder()
        sb.append("--- ${diff.oldFilePath}\n")
        sb.append("+++ ${diff.newFilePath}\n")
        for (hunk in diff.hunks) {
            sb.append(hunk.header + "\n")
            for (line in hunk.lines) {
                sb.append(line.type.prefix)
                sb.append(line.content)
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /**
     * 简单字符串 hash（用于 diff 内部标识）。
     */
    private fun hash(text: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
}
