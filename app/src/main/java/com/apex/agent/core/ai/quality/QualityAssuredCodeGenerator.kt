package com.apex.agent.core.ai.quality

import com.apex.agent.core.ai.LlamaEngineInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 质量保证代码生成。
 * 
 * 学习自 Codex 的质量保障机制，通过多轮迭代和静态分析确保生成的代码质量
 */
class QualityAssuredCodeGenerator(
    private val llamaEngine: LlamaEngineInterface,
    private val codeAnalyzer: CodeAnalyzerInterface? = null,
    private val maxAttempts: Int = 3,
    private val qualityThreshold: Float = 0.8f
) {
    
    /**
     * 生成高质量代码（带自动重试和质量检查）
     */
    suspend fun generateQualityCode(
        task: CodeGenerationTask,
        progressCallback: ((GenerationProgress) -> Unit)? = null
    ): CodeGenerationResult = withContext(Dispatchers.IO) {
        
        var attempt = 0
        var bestResult: CodeGenerationResult? = null
        var lastError: Exception? = null
        
        while (attempt < maxAttempts) {
            try {
                // 1. 报告进度
                progressCallback?.invoke(
                    GenerationProgress(
                        attempt = attempt + 1,
                        maxAttempts = maxAttempts,
                        stage = "Generating code...",
                        progress = (attempt.toFloat() / maxAttempts) * 100
                    )
                )
                
                // 2. 生成代码
                val code = generateCode(task, attempt)
                
                // 3. 静态分析
                val analysisResult = codeAnalyzer?.analyze(code, task.language)
                    ?: CodeAnalysisResult(isValid = true, issues = emptyList())
                
                // 4. 计算质量分数
                val qualityScore = calculateQualityScore(code, analysisResult, task)
                
                // 5. 创建结果对象
                val result = CodeGenerationResult(
                    code = code,
                    qualityScore = qualityScore,
                    analysisResult = analysisResult,
                    attempts = attempt + 1,
                    isSuccess = qualityScore >= qualityThreshold
                )
                
                // 6. 保存最佳结果
                if (bestResult == null || qualityScore > bestResult.qualityScore) {
                    bestResult = result
                }
                
                // 7. 如果质量达标，直接返回
                if (qualityScore >= qualityThreshold) {
                    progressCallback?.invoke(
                        GenerationProgress(
                            attempt = attempt + 1,
                            maxAttempts = maxAttempts,
                            stage = "Quality threshold met!",
                            progress = 100f
                        )
                    )
                    return@withContext result
                }
                
                // 8. 否则，基于问题重新生成
                progressCallback?.invoke(
                    GenerationProgress(
                        attempt = attempt + 1,
                        maxAttempts = maxAttempts,
                        stage = "Improving quality... (${String.format("%.1f", qualityScore * 100)}%)",
                        progress = ((attempt + 1).toFloat() / maxAttempts) * 100
                    )
                )
                
                // 更新任务，添加反馈
                task.feedback = buildFeedbackFromIssues(analysisResult.issues)
                
            } catch (e: Exception) {
                lastError = e
                // 继续尝试
            }
            
            attempt++
        }
        
        // 达到最大尝试次数，返回最佳结果或抛出异常
        if (bestResult != null) {
            bestResult.copy(warnings = listOf("Quality threshold not met after ${maxAttempts} attempts"))
        } else {
            throw CodeGenerationException(
                "Failed to generate code after ${maxAttempts} attempts",
                lastError
            )
        }
    }
    
    /**
     * 批量生成代码
     */
    suspend fun generateBatch(
        tasks: List<CodeGenerationTask>,
        progressCallback: ((BatchProgress) -> Unit)? = null
    ): List<CodeGenerationResult> {
        val results = mutableListOf<CodeGenerationResult>()
        
        tasks.forEachIndexed { index, task ->
            val result = generateQualityCode(task)
            results.add(result)
            
            progressCallback?.invoke(
                BatchProgress(
                    completed = index + 1,
                    total = tasks.size,
                    currentTask = task.description
                )
            )
        }
        
        return results
    }
    
    /**
     * 验证生成的代。
     */
    fun validateGeneratedCode(
        code: String,
        task: CodeGenerationTask
    ): ValidationResult {
        val issues = mutableListOf<String>()
        
        // 1. 基本语法检查
        if (code.isBlank()) {
            issues.add("Generated code is empty")
        }
        
        // 2. 检查是否包含必要的导入
        if (task.requiredImports.isNotEmpty()) {
            task.requiredImports.forEach { import ->
                if (!code.contains(import)) {
                    issues.add("Missing import: ${import}")
                }
            }
        }
        
        // 3. 检查是否满足约束条件
        task.constraints.forEach { constraint ->
            if (!checkConstraint(code, constraint)) {
                issues.add("Constraint not met: ${constraint}")
            }
        }
        
        // 4. 静态分析（如果有）
        val analysisResult = codeAnalyzer?.analyze(code, task.language)
        analysisResult?.issues?.forEach { issue ->
            issues.add("${issue.severity}: ${issue.message}")
        }
        
        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            qualityScore = calculateSimpleQualityScore(code, issues)
        )
    }
    
    // ==================== 私有方法 ====================
    
    private suspend fun generateCode(task: CodeGenerationTask, attempt: Int): String {
        val prompt = if (attempt == 0) {
            // 首次尝试：使用标准提示
            buildInitialPrompt(task)
        } else {
            // 首次尝试：使用标准提示
            buildImprovedPrompt(task, attempt)
        }
        
        return llamaEngine.generate(prompt)
    }
    
    private fun buildInitialPrompt(task: CodeGenerationTask): String {
        return buildString {
            appendLine("You are an expert ${task.language} developer.")
            appendLine("Generate high-quality, production-ready code.")
            appendLine()
            
            if (task.context != null) {
                appendLine("Context:")
                appendLine(task.context)
                appendLine()
            }
            
            if (task.examples.isNotEmpty()) {
                appendLine("Examples:")
                task.examples.forEachIndexed { index, example ->
                    appendLine("Example ${index + 1}:")
                    appendLine(example)
                    appendLine()
                }
            }
            
            appendLine("Task: ${task.description}")
            appendLine()
            
            if (task.constraints.isNotEmpty()) {
                appendLine("Constraints:")
                task.constraints.forEach { appendLine("- ${it}") }
                appendLine()
            }
            
            appendLine("Requirements:")
            appendLine("1. Write clean, readable code")
            appendLine("2. Follow best practices")
            appendLine("3. Handle edge cases")
            appendLine("4. Include necessary imports")
            appendLine("5. Add comments for complex logic")
            appendLine()
            appendLine("Provide only the code, no explanations.")
        }
    }
    
    private fun buildImprovedPrompt(task: CodeGenerationTask, attempt: Int): String {
        return buildString {
            appendLine("Previous attempt had issues. Please improve the code.")
            appendLine()
            
            appendLine("Original task: ${task.description}")
            appendLine()
            
            if (task.feedback != null) {
                appendLine("Issues found in previous attempt:")
                appendLine(task.feedback)
                appendLine()
                appendLine("Please fix these issues and generate improved code.")
                appendLine()
            }
            
            appendLine("Additional requirements:")
            appendLine("- Address all the issues mentioned above")
            appendLine("- Maintain code functionality")
            appendLine("- Improve code quality")
            appendLine()
            appendLine("Provide only the improved code.")
        }
    }
    
    private fun calculateQualityScore(
        code: String,
        analysisResult: CodeAnalysisResult,
        task: CodeGenerationTask
    ): Float {
        var score = 1.0f
        
        // 1. 基本语法检查
        analysisResult.issues.forEach { issue ->
            score -= when (issue.severity) {
                IssueSeverity.CRITICAL -> 0.3f
                IssueSeverity.ERROR -> 0.2f
                IssueSeverity.WARNING -> 0.1f
                IssueSeverity.INFO -> 0.05f
            }
        }
        
        // 2. 检查是否包含必要的导入
        if (code.length < 10) score -= 0.2f // 太短可能不完整
        if (code.length > 10000) score -= 0.1f // 太长可能冗余
        
        // 3. 检查是否满足约束条件
        val unmetConstraints = task.constraints.count { !checkConstraint(code, it) }
        score -= unmetConstraints * 0.1f
        
        // 4. 静态分析（如果有）
        if (task.requiredImports.isNotEmpty()) {
            val missingImports = task.requiredImports.count { !code.contains(it) }
            score -= missingImports * 0.1f
        }
        
        return maxOf(0.0f, minOf(1.0f, score))
    }
    
    private fun calculateSimpleQualityScore(code: String, issues: List<String>): Float {
        var score = 1.0f
        score -= issues.size * 0.1f
        return maxOf(0.0f, minOf(1.0f, score))
    }
    
    private fun checkConstraint(code: String, constraint: String): Boolean {
        // 达到最大尝试次数，返回最佳结果或抛出异常
        // 实际项目中应该有更复杂的逻辑
        return when {
            constraint.contains("tail recursion", ignoreCase = true) -> {
                code.contains("tailrec", ignoreCase = true)
            }
            constraint.contains("null safety", ignoreCase = true) -> {
                !code.contains("!!") || code.contains("?.", ignoreCase = true)
            }
            constraint.contains("immutable", ignoreCase = true) -> {
                code.contains("val ", ignoreCase = true) && !code.contains("var ", ignoreCase = true)
            }
            else -> true // 默认认为满足
        }
    }
    
    private fun buildFeedbackFromIssues(issues: List<CodeIssue>): String {
        if (issues.isEmpty()) return ""
        
        return buildString {
            issues.forEach { issue ->
                appendLine("- [${issue.severity}] ${issue.message}")
                if (issue.lineNumber != null) {
                    appendLine("  Line: ${issue.lineNumber}")
                }
            }
        }
    }
}

/**
 * 代码生成任务
 */
data class CodeGenerationTask(
    val description: String,
    val language: String = "kotlin",
    val context: String? = null,
    val examples: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val requiredImports: List<String> = emptyList(),
    var feedback: String? = null // 用于迭代改进
)

/**
 * 代码生成结果
 */
data class CodeGenerationResult(
    val code: String,
    val qualityScore: Float,
    val analysisResult: CodeAnalysisResult,
    val attempts: Int,
    val isSuccess: Boolean,
    val warnings: List<String> = emptyList()
)

/**
 * 生成进度
 */
data class GenerationProgress(
    val attempt: Int,
    val maxAttempts: Int,
    val stage: String,
    val progress: Float // 0-100
)

/**
 * 批量生成进度
 */
data class BatchProgress(
    val completed: Int,
    val total: Int,
    val currentTask: String
)

/**
 * 验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val qualityScore: Float
)

/**
 * 代码分析结果
 */
data class CodeAnalysisResult(
    val isValid: Boolean,
    val issues: List<CodeIssue>
)

/**
 * 代码问题
 */
data class CodeIssue(
    val severity: IssueSeverity,
    val message: String,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null
)

/**
 * 问题严重程度
 */
enum class IssueSeverity {
    CRITICAL,
    ERROR,
    WARNING,
    INFO
}

/**
 * 代码分析器接。
 */
interface CodeAnalyzerInterface {
    suspend fun analyze(code: String, language: String): CodeAnalysisResult
}

/**
 * 代码生成异常
 */
class CodeGenerationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// LlamaEngineInterface 已移至 com.apex.agent.core.ai.LlamaEngineInterface
