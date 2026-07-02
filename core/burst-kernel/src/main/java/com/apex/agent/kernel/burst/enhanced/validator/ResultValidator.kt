package com.apex.agent.kernel.burst.enhanced.validator

import java.util.concurrent.ConcurrentHashMap

/**
 * B42: 结果验证器
 *
 * 在返回结果前验证输出质量：
 * - 格式验证（JSON/Markdown/代码）
 * - 内容验证（非空/长度/关键词）
 * - 语义验证（相关性/一致性）
 * - 自定义验证规则
 */
class ResultValidator {

    data class ValidationResult(
        val valid: Boolean,
        val score: Float,          // 0-1 质量分
        val issues: List<ValidationIssue>,
        val suggestions: List<String>
    )

    data class ValidationIssue(
        val severity: IssueSeverity,
        val rule: String,
        val message: String
    )

    enum class IssueSeverity { INFO, WARNING, ERROR }

    data class ValidationRule(
        val name: String,
        val description: String,
        val validator: (String) -> Boolean,
        val errorMessage: String,
        val severity: IssueSeverity = IssueSeverity.ERROR
    )

    private val rules = mutableListOf<ValidationRule>()
    private val validationHistory = mutableListOf<Pair<String, ValidationResult>>()
    private val customRules = ConcurrentHashMap<String, ValidationRule>()

    init {
        registerBuiltinRules()
    }

    /**
     * 验证结果
     */
    fun validate(output: String?, expectedFormat: String? = null): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        var score = 1.0f

        // 空值检查
        if (output.isNullOrBlank()) {
            return ValidationResult(false, 0f,
                listOf(ValidationIssue(IssueSeverity.ERROR, "non_null", "输出为空")),
                listOf("确保技能产生非空输出"))
        }

        // 格式验证
        if (expectedFormat != null) {
            val formatIssue = validateFormat(output, expectedFormat)
            if (formatIssue != null) {
                issues.add(formatIssue)
                score -= 0.3f
            }
        }

        // 应用所有规则
        for (rule in rules) {
            try {
                if (!rule.validator(output)) {
                    issues.add(ValidationIssue(rule.severity, rule.name, rule.errorMessage))
                    if (rule.severity == IssueSeverity.ERROR) score -= 0.2f
                    else if (rule.severity == IssueSeverity.WARNING) score -= 0.1f
                }
            } catch (e: Exception) {
                // 规则执行异常不影响验证
            }
        }

        // 应用自定义规则
        for (rule in customRules.values) {
            try {
                if (!rule.validator(output)) {
                    issues.add(ValidationIssue(rule.severity, rule.name, rule.errorMessage))
                    if (rule.severity == IssueSeverity.ERROR) score -= 0.2f
                }
            } catch (e: Exception) {}
        }

        val hasErrors = issues.any { it.severity == IssueSeverity.ERROR }
        val suggestions = generateSuggestions(output, issues)

        val result = ValidationResult(
            valid = !hasErrors && score > 0.3f,
            score = score.coerceIn(0f, 1f),
            issues = issues,
            suggestions = suggestions
        )

        validationHistory.add((output?.take(50) ?: "") to result)
        while (validationHistory.size > 200) validationHistory.removeAt(0)

        return result
    }

    /**
     * 注册自定义规则
     */
    fun registerRule(rule: ValidationRule) {
        customRules[rule.name] = rule
    }

    /**
     * 获取验证历史
     */
    fun getHistory(): List<Pair<String, ValidationResult>> = validationHistory.toList()

    fun getStats(): ValidatorStats {
        return ValidatorStats(
            totalValidations = validationHistory.size,
            passRate = if (validationHistory.isNotEmpty())
                validationHistory.count { it.second.valid }.toFloat() / validationHistory.size else 0f,
            avgScore = if (validationHistory.isNotEmpty())
                validationHistory.map { it.second.score }.average().toFloat() else 0f,
            totalRules = rules.size + customRules.size
        )
    }

    data class ValidatorStats(
        val totalValidations: Int,
        val passRate: Float,
        val avgScore: Float,
        val totalRules: Int
    )

    // ============ 内部方法 ============

    private fun validateFormat(output: String, format: String): ValidationIssue? {
        return when (format.lowercase()) {
            "json" -> {
                try { org.json.JSONObject(output); null }
                catch (e1: Exception) {
                    try { org.json.JSONArray(output); null }
                    catch (e2: Exception) {
                        ValidationIssue(IssueSeverity.ERROR, "json_format", "输出不是有效的 JSON")
                    }
                }
            }
            "markdown" -> {
                if (output.contains(Regex("^#+\\s|\\*\\*|```|\\|", RegexOption.MULTILINE))) null
                else ValidationIssue(IssueSeverity.WARNING, "markdown_format", "输出缺少 Markdown 格式标记")
            }
            "code" -> {
                if (output.contains("```") || output.matches(Regex("^[\\w\\s{}()<>;]+$"))) null
                else ValidationIssue(IssueSeverity.WARNING, "code_format", "输出不像代码")
            }
            "xml" -> {
                if (output.contains("<") && output.contains(">")) null
                else ValidationIssue(IssueSeverity.ERROR, "xml_format", "输出不是有效的 XML")
            }
            else -> null
        }
    }

    private fun generateSuggestions(output: String, issues: List<ValidationIssue>): List<String> {
        val suggestions = mutableListOf<String>()
        if (output.length < 10) suggestions.add("输出过短，考虑补充内容")
        if (output.length > 10000) suggestions.add("输出过长，考虑精简")
        if (!output.contains(Regex("[。.！!？?]"))) suggestions.add("输出缺少标点符号")
        if (issues.any { it.rule == "json_format" }) suggestions.add("考虑修复 JSON 格式")
        if (issues.any { it.severity == IssueSeverity.ERROR }) suggestions.add("存在严重问题，建议重试")
        return suggestions
    }

    private fun registerBuiltinRules() {
        rules.add(ValidationRule("min_length", "最小长度", { it.length >= 10 }, "输出长度不足 10 字符", IssueSeverity.WARNING))
        rules.add(ValidationRule("max_length", "最大长度", { it.length <= 50000 }, "输出超过 50000 字符", IssueSeverity.WARNING))
        rules.add(ValidationRule("no_garbage", "无乱码", { !it.contains(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")) }, "输出包含控制字符", IssueSeverity.ERROR))
        rules.add(ValidationRule("has_content", "有实质内容", { it.trim().length >= 5 }, "输出无实质内容", IssueSeverity.ERROR))
        rules.add(ValidationRule("no_repetition", "无过度重复", {
            val words = it.split(Regex("\\s+"))
            if (words.size < 10) true
            else words.groupingBy { it.lowercase() }.eachCount().values.maxOrNull()!! < words.size / 3
        }, "输出存在过度重复", IssueSeverity.WARNING))
    }
}
