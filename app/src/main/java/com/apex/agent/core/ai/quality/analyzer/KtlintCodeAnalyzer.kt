package com.apex.agent.core.ai.quality.analyzer

import com.apex.agent.core.ai.quality.CodeAnalysisResult
import com.apex.agent.core.ai.quality.CodeAnalyzerInterface
import com.apex.agent.core.ai.quality.CodeIssue
import com.apex.agent.core.ai.quality.IssueSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 简单代码分析器 - 提供基础的代码质量检查
 *
 * 注意: 这不是真正的 ktlint 集成，而是一个轻量级的代码风格检查工具。
 * 如需完整的 Kotlin 代码检查，请在 build.gradle.kts 中配置 ktlint 插件。
 */
class SimpleCodeAnalyzer : CodeAnalyzerInterface {

    override suspend fun analyze(code: String, language: String): CodeAnalysisResult =
        withContext(Dispatchers.IO) {

            if (language.lowercase() != "kotlin") {
                return@withContext CodeAnalysisResult(
                    isValid = true,
                    issues = emptyList()
                )
            }

            try {
                val issues = mutableListOf<CodeIssue>()

                issues.addAll(checkBasicSyntax(code))
                issues.addAll(checkNamingConventions(code))
                issues.addAll(checkCodeStyle(code))
                issues.addAll(checkPotentialIssues(code))
                issues.addAll(checkFileSize(code))
                issues.addAll(checkTrailingWhitespace(code))
                issues.addAll(checkTodoFixes(code))

                CodeAnalysisResult(
                    isValid = issues.none { it.severity == IssueSeverity.CRITICAL || it.severity == IssueSeverity.ERROR },
                    issues = issues
                )

            } catch (e: Exception) {
                CodeAnalysisResult(
                    isValid = false,
                    issues = listOf(
                        CodeIssue(
                            severity = IssueSeverity.ERROR,
                            message = "Analysis failed: ${e.message}"
                        )
                    )
                )
            }
        }
        private fun checkBasicSyntax(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val openBraces = code.count { it == '{' }
        val closeBraces = code.count { it == '}' }
        if (openBraces != closeBraces) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.ERROR,
                    message = "大括号不匹配: 有 $openBraces 个开括号, $closeBraces 个闭括号"
                )
            )
        }
        val quotes = code.count { it == '"' }
        if (quotes % 2 != 0) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.ERROR,
                    message = "引号不匹配"
                )
            )
        }
        return issues
    }
        private fun checkNamingConventions(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val classPattern = Regex("""class\s+([a-z]\w*)""")
        classPattern.findAll(code).forEach { match ->
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.WARNING,
                    message = "类名应使用 UpperCamelCase: ${match.groupValues[1]}",
                    lineNumber = getLineNumber(code, match.range.first)
                )
            )
        }
        val functionPattern = Regex("""fun\s+([A-Z]\w*)""")
        functionPattern.findAll(code).forEach { match ->
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.WARNING,
                    message = "函数名应使用 lowerCamelCase: ${match.groupValues[1]}",
                    lineNumber = getLineNumber(code, match.range.first)
                )
            )
        }
        return issues
    }
        private fun checkCodeStyle(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()

        code.lines().forEachIndexed { index, line ->
            if (line.length > 120) {
                issues.add(
                    CodeIssue(
                        severity = IssueSeverity.INFO,
                        message = "行过长 (${line.length} > 120 字符)",
                        lineNumber = index + 1
                    )
                )
            }
        }
        if (code.contains("  ") && !code.contains("    ")) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.INFO,
                    message = "建议使用单个空格而非双空格"
                )
            )
        }
        return issues
    }
        private fun checkPotentialIssues(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val forceUnwrapCount = code.split("!!").size - 1
        if (forceUnwrapCount > 0) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.WARNING,
                    message = "发现 $forceUnwrapCount 处强制解包 (!!); 建议使用安全调用 (?.)"
                )
            )
        }
        val magicNumberPattern = Regex("""[^a-zA-Z_](\d{2,})[^a-zA-Z_\d]""")
        magicNumberPattern.findAll(code).forEach { match ->
            val number = match.groupValues[1]
            if (number != "10" && number != "60" && number != "100") {
                issues.add(
                    CodeIssue(
                        severity = IssueSeverity.INFO,
                        message = "魔法数字: $number; 建议使用具名常量",
                        lineNumber = getLineNumber(code, match.range.first)
                    )
                )
            }
        }
        if (code.contains("catch") && code.contains("}")) {
            val catchPattern = Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")
            catchPattern.findAll(code).forEach {
                issues.add(
                    CodeIssue(
                        severity = IssueSeverity.WARNING,
                        message = "空的 catch 块; 建议记录异常日志"
                    )
                )
            }
        }
        return issues
    }
        private fun checkFileSize(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lineCount = code.lines().size
        if (lineCount > 800) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.WARNING,
                    message = "文件过长 ($lineCount 行); 建议拆分以提升可维护性"
                )
            )
        }
        return issues
    }
        private fun checkTrailingWhitespace(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val trailingPattern = Regex("""[ \t]+$""", RegexOption.MULTILINE)
        val matches = trailingPattern.findAll(code).toList()
        if (matches.isNotEmpty()) {
            issues.add(
                CodeIssue(
                    severity = IssueSeverity.INFO,
                    message = "发现 ${matches.size} 处行尾多余空格"
                )
            )
        }
        return issues
    }
        private fun checkTodoFixes(code: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val todoPattern = Regex("""//\s*(TODO|FIXME|HACK|XXX)\b""", RegexOption.IGNORE_CASE)
        code.lines().forEachIndexed { index, line ->
            if (todoPattern.containsMatchIn(line)) {
                val match = todoPattern.find(line)
                issues.add(
                    CodeIssue(
                        severity = IssueSeverity.INFO,
                        message = "未完成的标记: ${match?.groupValues?.get(1)}",
                        lineNumber = index + 1
                    )
                )
            }
        }
        return issues
    }
        private fun getLineNumber(code: String, charIndex: Int): Int {
        return code.substring(0, charIndex).count { it == '\n' } + 1
    }
}
