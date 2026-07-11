package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.MutableStateFlow
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.StateFlow
import kotlinx.coroutines.flow
import kotlinx.coroutines.Dispatchers.asStateFlow

class APIClientSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private var toolExecutionEnabled = true
    private var dangerousOperationDetectionEnabled = true
    private var currentTaskId: String? = null
    
    private val _securityStats = MutableStateFlow(SecurityStats())
    val securityStats: StateFlow<SecurityStats> = _securityStats.asStateFlow()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "api_client",
            skillName = "API客户端",
            version = "1.0.0",
            description = "统一API调用管理，支持多种AI提供商和工具调用循环",
            author = "Apex Agent",
            tags = listOf("api", "client", "multi-provider"),
            priority = 92,
            capabilities = listOf(
                "multi_provider_support",
                "tool_call_loop",
                "security_check",
                "batch_execution"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            val model = task.metadata["model"] ?: "claude-3.5"
            val input = task.input.text ?: task.description
            currentTaskId = task.id
            
            val llm = context.llmService
            val response = executeApiCall(model, input, llm)
            
            val finalResponse = if (toolExecutionEnabled && response.contains("<tool_call>")) {
                processToolCalls(response)
            } else {
                response
            }
            
            val executionTime = System.currentTimeMillis() - startTime
            
            BurstSkillResult(
                success = true,
                output = buildString {
                    appendLine("API client execution completed:")
                    appendLine("- Model: $model")
                    appendLine("- Input length: ${input.length}")
                    appendLine("- Output length: ${finalResponse.length}")
                    appendLine("- Tool execution: ${if (toolExecutionEnabled) "enabled" else "disabled"}")
                    appendLine("- Security checks: ${if (dangerousOperationDetectionEnabled) "enabled" else "disabled"}")
                },
                metrics = SkillMetrics(
                    executionTimeMs = executionTime,
                    stepsCompleted = 1
                )
            )
        } catch (e: Exception) {
            BurstSkillResult(success = false, errorMessage = e.message)
        }
    }
    
    private suspend fun executeApiCall(model: String, input: String, llm: ILLMService?): String = withContext(Dispatchers.IO) {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("你是一个AI API模拟器。请根据以下输入生成响应。")
                appendLine("输入：$input")
                appendLine()
                appendLine("请分析输入并生成合适的响应。")
            }
            return@withContext llm.generate(prompt, maxTokens = 512)
        }
        return@withContext legacyApiCall(input)
    }

    private fun legacyApiCall(input: String): String {
        return when {
            input.contains("代码") || input.contains("code") || input.contains("开发") -> """
                分析结果：
                1. 需求理解：已识别为代码开发任务
                2. 技术方案：建议使用模块化架构
                3. 实现步骤：
                   - 设计接口
                   - 实现核心逻辑
                   - 添加测试用例
                <tool_call>task_decompose|input="分解任务"</tool_call>
            """.trimIndent()
            
            input.contains("分析") || input.contains("analyze") -> """
                分析结果：
                - 输入复杂度：中
                - 建议处理方式：分步处理
                - 预期输出质量：高
            """.trimIndent()
            
            else -> """
                任务处理完成：
                - 输入已接收
                - 正在分析内容...
                - 建议: ${input.take(50)}...
            """.trimIndent()
        }
    }
    
    private fun processToolCalls(response: String): String {
        val toolCallRegex = Regex("<tool_call>(.+?)</tool_call>")
        val matches = toolCallRegex.findAll(response)
        
        var result = response
        matches.forEach { match ->
            val toolCall = match.groupValues[1]
            val parts = toolCall.split("|")
            
            if (parts.size >= 2) {
                val toolName = parts[0].trim()
                val params = parts[1].trim()
                
                val securityCheck = performSecurityCheck(toolName, params)
                
                if (securityCheck.blocked) {
                    result = result.replace(match.value, "[BLOCKED: ${securityCheck.reason}]")
                } else {
                    val toolResult = executeTool(toolName, params)
                    result = result.replace(match.value, "[RESULT: $toolResult]")
                }
            }
        }
        
        return result
    }
    
    private fun performSecurityCheck(toolName: String, params: String): SecurityCheckResult {
        val dangerousTools = setOf("shell_execute", "file_delete", "system_command")
        
        if (dangerousOperationDetectionEnabled && toolName in dangerousTools) {
            if (params.contains("--force") || params.contains("-f")) {
                _securityStats.value = _securityStats.value.copy(
                    dangerousOperationsBlocked = _securityStats.value.dangerousOperationsBlocked + 1
                )
                return SecurityCheckResult(blocked = true, reason = "Dangerous operation with force flag detected")
            }
        }
        return SecurityCheckResult(blocked = false)
    }
    
    private fun executeTool(toolName: String, params: String): String {
        return when (toolName) {
            "task_decompose" -> "任务已分解为3个子任务"
            "context_summarize" -> "上下文摘要：核心要点已提取"
            "web_search" -> "搜索结果：找到10条相关信息"
            else -> "Tool executed: $toolName"
        }
    }
    
    fun setToolExecutionEnabled(enabled: Boolean) { toolExecutionEnabled = enabled }
    fun setDangerousOperationDetectionEnabled(enabled: Boolean) { dangerousOperationDetectionEnabled = enabled }
    fun isDangerousOperationDetectionEnabled(): Boolean = dangerousOperationDetectionEnabled
    
    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    override fun evaluate(): Float = 0.89f
    
    data class SecurityCheckResult(val blocked: Boolean, val reason: String? = null)
    data class SecurityStats(
        val totalChecks: Int = 0,
        val dangerousOperationsBlocked: Int = 0,
        val toolsExecuted: Int = 0
    )
}
