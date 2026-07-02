package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 代码质量分析技能
 * 实现代码质量问题检测、复杂度分析、代码样式检查
 */
class CodeQualityAnalyzerSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val codeStyleRules = listOf(
        CodeStyleRule(
            pattern = Pattern.compile("\\s{2,}"),
            message = "多余的空白字符",
            suggestion = "删除多余的空白字符"
        ),
        CodeStyleRule(
            pattern = Pattern.compile("\\t"),
            message = "使用了制表符",
            suggestion = "建议使用空格代替制表符"
        )
    )
    
    init {
        manifest = BurstSkillManifest(
            skillId = "code_quality_analyzer",
            skillName = "代码质量分析",
            version = "1.0.0",
            description = "代码质量问题检测，支持复杂度分析、代码样式检查和安全问题发现",
            author = "Apex Agent",
            tags = listOf("code-quality", "analyzer", "static-analysis"),
            priority = 82,
            capabilities = listOf(
                "code_quality_detection",
                "complexity_analysis",
                "code_style_check",
                "security_issue_detection"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val code = task.input.text ?: task.description
            val operation = task.metadata["operation"] ?: "analyze"
            
            when (operation) {
                "analyze" -> {
                    val analysis = analyzeCode(code)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Code quality analysis completed:
                            |- Issues found: ${analysis.totalIssues}
                            ${analysis.issuesBySeverity.entries.take(3).joinToString("\n") { "- ${it.key.name}: ${it.value}" }}
                            |- Overall score: ${analysis.overallScore}/100
                            |- Recommendations: ${analysis.recommendations.size}
                            ${analysis.recommendations.take(3).joinToString("\n") { "- $it" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = analysis.totalIssues
                        )
                    )
                }
                "check_style" -> {
                    val issues = checkCodeStyle(code)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Code style check completed:
                            |- Issues found: ${issues.size}
                            ${issues.take(5).joinToString("\n") { "- Line ${it.line}: ${it.message}" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = issues.size
                        )
                    )
                }
                "complexity" -> {
                    val complexity = calculateComplexity(code)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Complexity analysis completed:
                            |- Cyclomatic complexity: ${complexity.cyclomaticComplexity}
                            |- Lines of code: ${complexity.linesOfCode}
                            |- Method count: ${complexity.methodCount}
                            |- Class coupling: ${complexity.classCoupling}
                            |- Complexity grade: ${complexity.grade}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                else -> {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "Unknown operation: $operation"
                    )
                }
            }
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    fun analyzeCode(code: String): CodeQualityAnalysis {
        val issues = mutableListOf<CodeQualityIssue>()
        val lines = code.split("\n")
        
        // 代码样式检查
        lines.forEachIndexed { index, line ->
            codeStyleRules.forEach { rule ->
                if (rule.pattern.matcher(line).find()) {
                    issues.add(CodeQualityIssue(
                        id = "style_${index}",
                        file = "input",
                        line = index + 1,
                        column = 1,
                        severity = Severity.INFO,
                        type = IssueType.CODE_STYLE,
                        message = rule.message,
                        suggestion = rule.suggestion
                    ))
                }
            }
        }
        
        // 安全问题检查
        val securityPatterns = listOf(
            "eval(" to "使用eval()存在安全风险",
            "exec(" to "使用exec()存在安全风险",
            "system(" to "使用system()存在安全风险",
            "Runtime.getRuntime()" to "直接使用Runtime存在风险"
        )
        
        lines.forEachIndexed { index, line ->
            securityPatterns.forEach { (pattern, message) ->
                if (line.contains(pattern)) {
                    issues.add(CodeQualityIssue(
                        id = "security_${index}",
                        file = "input",
                        line = index + 1,
                        column = 1,
                        severity = Severity.WARNING,
                        type = IssueType.SECURITY,
                        message = message,
                        suggestion = "考虑使用更安全的替代方案"
                    ))
                }
            }
        }
        
        // 性能问题检查
        val performancePatterns = listOf(
            "Thread.sleep" to "Thread.sleep可能影响性能",
            "while(true)" to "无限循环可能影响性能",
            ".toString()" to "不必要的字符串转换"
        )
        
        lines.forEachIndexed { index, line ->
            performancePatterns.forEach { (pattern, message) ->
                if (line.contains(pattern)) {
                    issues.add(CodeQualityIssue(
                        id = "performance_${index}",
                        file = "input",
                        line = index + 1,
                        column = 1,
                        severity = Severity.INFO,
                        type = IssueType.PERFORMANCE,
                        message = message,
                        suggestion = "考虑优化"
                    ))
                }
            }
        }
        
        // 计算总体分数
        val errorCount = issues.count { it.severity == Severity.ERROR }
        val warningCount = issues.count { it.severity == Severity.WARNING }
        val infoCount = issues.count { it.severity == Severity.INFO }
        
        val issuesBySeverity = mapOf(
            Severity.ERROR to errorCount,
            Severity.WARNING to warningCount,
            Severity.INFO to infoCount
        )
        
        val issuesByType = issues.groupBy { it.type }.mapValues { it.value.size }
        
        val overallScore = maxOf(0, 100 - errorCount * 10 - warningCount * 5 - infoCount)
        
        val recommendations = mutableListOf<String>()
        if (errorCount > 0) recommendations.add("修复${errorCount}个错误")
        if (warningCount > 0) recommendations.add("检查${warningCount}个警告")
        if (infoCount > 0) recommendations.add("优化${infoCount}个代码风格问题")
        
        return CodeQualityAnalysis(
            files = listOf(FileAnalysis(
                file = "input",
                issues = issues,
                complexity = calculateComplexity(code).cyclomaticComplexity,
                codeStyleScore = maxOf(0, 100 - infoCount * 2),
                performanceScore = maxOf(0, 100 - issues.count { it.type == IssueType.PERFORMANCE } * 5),
                securityScore = maxOf(0, 100 - issues.count { it.type == IssueType.SECURITY } * 10)
            )),
            totalIssues = issues.size,
            issuesBySeverity = issuesBySeverity,
            issuesByType = issuesByType,
            overallScore = overallScore,
            recommendations = recommendations
        )
    }
    
    fun checkCodeStyle(code: String): List<CodeQualityIssue> {
        val issues = mutableListOf<CodeQualityIssue>()
        val lines = code.split("\n")
        
        lines.forEachIndexed { index, line ->
            codeStyleRules.forEach { rule ->
                if (rule.pattern.matcher(line).find()) {
                    issues.add(CodeQualityIssue(
                        id = "style_${index}",
                        file = "input",
                        line = index + 1,
                        column = 1,
                        severity = Severity.INFO,
                        type = IssueType.CODE_STYLE,
                        message = rule.message,
                        suggestion = rule.suggestion
                    ))
                }
            }
        }
        
        return issues
    }
    
    fun calculateComplexity(code: String): ComplexityResult {
        val lines = code.split("\n")
        val linesOfCode = lines.count { it.isNotBlank() }
        
        // 简化的圈复杂度计算
        val decisionPoints = listOf("if", "else", "for", "while", "when", "switch", "case", "catch")
        val cyclomaticComplexity = 1 + lines.sumOf { line ->
            decisionPoints.count { line.contains(it) }
        }
        
        val methodCount = lines.count { it.contains("fun ") }
        val classCount = lines.count { it.contains("class ") || it.contains("interface ") }
        val classCoupling = classCount * 2 + methodCount
        
        val grade = when {
            cyclomaticComplexity < 10 -> "A"
            cyclomaticComplexity < 20 -> "B"
            cyclomaticComplexity < 50 -> "C"
            else -> "D"
        }
        
        return ComplexityResult(
            cyclomaticComplexity = cyclomaticComplexity,
            linesOfCode = linesOfCode,
            methodCount = methodCount,
            classCoupling = classCoupling,
            grade = grade
        )
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.83f
    
    data class CodeQualityIssue(
        val id: String,
        val file: String,
        val line: Int,
        val column: Int,
        val severity: Severity,
        val type: IssueType,
        val message: String,
        val suggestion: String
    )
    
    enum class Severity {
        ERROR, WARNING, INFO
    }
    
    enum class IssueType {
        CODE_STYLE, PERFORMANCE, SECURITY, MAINTAINABILITY, BUG_RISK, COMPLEXITY
    }
    
    data class CodeQualityAnalysis(
        val files: List<FileAnalysis>,
        val totalIssues: Int,
        val issuesBySeverity: Map<Severity, Int>,
        val issuesByType: Map<IssueType, Int>,
        val overallScore: Int,
        val recommendations: List<String>
    )
    
    data class FileAnalysis(
        val file: String,
        val issues: List<CodeQualityIssue>,
        val complexity: Int,
        val codeStyleScore: Int,
        val performanceScore: Int,
        val securityScore: Int
    )
    
    data class ComplexityResult(
        val cyclomaticComplexity: Int,
        val linesOfCode: Int,
        val methodCount: Int,
        val classCoupling: Int,
        val grade: String
    )
    
    data class CodeStyleRule(
        val pattern: Pattern,
        val message: String,
        val suggestion: String
    )
}