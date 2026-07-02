package com.ai.assistance.aiterminal.terminal.agent.task

import android.util.Log
import com.ai.assistance.aiterminal.terminal.agent.SystemProbeData
import com.ai.assistance.aiterminal.terminal.ai.LLMAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "TaskPlanner"

/**
 * 任务规划器 - 将自然语言需求转换为结构化 TaskStep 数组
 */
class TaskPlanner(
    private val llmApi: LLMAPI
) {
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * 规划任务
     */
    suspend fun planTask(
        userRequest: String,
        systemProbeData: SystemProbeData? = null,
        useRoot: Boolean = false
    ): TaskPlan = withContext(Dispatchers.IO) {
        Log.i(TAG, "Planning task for request: $userRequest")
        
        // 构建提示词
        val prompt = buildTaskPlannerPrompt(userRequest, systemProbeData, useRoot)
        
        try {
            // 调用 LLM 生成任务计划
            val llmResponse = llmApi.generate(prompt)
            
            // 解析为结构化任务计划
            val taskPlan = parseTaskPlanResponse(llmResponse, userRequest, useRoot)
            
            Log.i(TAG, "Task planned with ${taskPlan.steps.size} steps")
            return@withContext taskPlan
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to plan task", e)
            // 生成备用计划
            return@withContext generateFallbackPlan(userRequest, useRoot)
        }
    }
    
    /**
     * 构建任务规划提示词（针对 DeepSeek V4 深度优化）
     *
     * DeepSeek V4 优化点：
     * - 利用 1M 超长上下文携带完整系统探测数据
     * - 利用强推理能力进行 step-by-step 安全分析
     * - 利用 128 并行函数调用支持生成超长多步骤计划
     * - 利用 reasoning_effort=max 实现深度推理
     */
    private fun buildTaskPlannerPrompt(
        userRequest: String,
        systemProbeData: SystemProbeData?,
        useRoot: Boolean
    ): String = buildString {
        appendLine("你是一个专业的 Android 系统任务规划专家，请逐步推理后输出结构化任务计划。")
        appendLine()
        appendLine("【核心原则】")
        appendLine("1. 先分析用户需求的安全性和可行性，再规划步骤")
        appendLine("2. 每一步都必须有明确的验证规则和失败处理")
        appendLine("3. 危险操作（刷写、删除、格式化）必须标记 requiresConfirmation=true")
        appendLine("4. 尽可能提供回滚/撤销方案")
        appendLine("5. 考虑 Android 版本差异和 root 权限限制")
        appendLine()
        
        appendLine("【用户需求】")
        appendLine(userRequest)
        appendLine()
        
        appendLine("【系统环境信息】")
        if (systemProbeData != null) {
            appendLine(systemProbeData.toPromptString())
        } else {
            appendLine("未知")
        }
        appendLine()
        
        appendLine("【Root 权限】")
        appendLine(if (useRoot) "可用" else "不可用")
        appendLine()
        
        appendLine("【输出格式要求】")
        appendLine("请严格按照以下 JSON 格式输出（不要包含任何其他文本，不要使用 markdown 代码块标记）：")
        appendLine("""
{
  "taskName": "任务名称",
  "taskDescription": "任务描述（包含安全分析摘要）",
  "estimatedDuration": 预计总耗时（秒）,
  "safetyWarning": "安全警告（如果需要用户特别注意的危险操作说明）",
  "reasoning": "你的分析推理过程（如何得出该执行计划，考虑了哪些风险）",
  "steps": [
    {
      "name": "步骤名称",
      "description": "步骤描述",
      "command": "具体执行的 Shell 命令",
      "requiresRoot": true/false,
      "validationRules": [
        {
          "type": "验证类型",
          "value": "验证值",
          "description": "验证描述"
        }
      ],
      "failureHandler": {
        "strategy": "失败策略",
        "maxRetries": 3,
        "rollbackCommand": "回滚命令（可选）",
        "alternativeCommands": ["备选命令1"],
        "description": "处理说明"
      },
      "requiresConfirmation": true/false,
      "estimatedDuration": 预计耗时（秒）,
      "tags": ["标签1"]
    }
  ],
  "tags": ["任务标签1"]
}
        """.trimIndent())
        appendLine()
        
        appendLine("【验证类型说明】")
        appendLine("- EXIT_CODE_ZERO: 检查命令退出码为 0")
        appendLine("- OUTPUT_CONTAINS: 检查输出包含特定字符串")
        appendLine("- OUTPUT_NOT_CONTAINS: 检查输出不包含特定字符串")
        appendLine("- FILE_EXISTS: 检查特定文件是否存在")
        appendLine("- CUSTOM: 自定义验证")
        appendLine()
        
        appendLine("【失败策略说明】")
        appendLine("- RETRY: 重试（最多 maxRetries 次）")
        appendLine("- ROLLBACK: 执行回滚命令")
        appendLine("- SKIP: 跳过此步骤继续")
        appendLine("- ABORT: 终止整个任务")
        appendLine("- ASK_USER: 询问用户")
        appendLine()
        
        appendLine("【最佳实践示例】")
        appendLine("""
{
  "taskName": "安全备份 boot 分区并刷入 Magisk 模块",
  "taskDescription": "三步完成：备份→校验→刷入，每步均含回滚机制",
  "estimatedDuration": 120,
  "safetyWarning": "刷入 boot 分区存在变砖风险，请确保备份完整",
  "steps": [
    {
      "name": "备份 boot 分区",
      "description": "使用 dd 将 boot 分区备份到 /sdcard",
      "command": "dd if=/dev/block/by-name/boot of=/sdcard/boot_backup.img bs=1M",
      "requiresRoot": true,
      "validationRules": [
        { "type": "EXIT_CODE_ZERO", "value": "", "description": "dd 命令成功" },
        { "type": "FILE_EXISTS", "value": "/sdcard/boot_backup.img", "description": "备份文件已创建" }
      ],
      "failureHandler": { "strategy": "ABORT", "description": "备份失败，终止避免数据丢失" },
      "requiresConfirmation": false,
      "estimatedDuration": 30,
      "tags": ["备份", "boot"]
    },
    {
      "name": "校验备份完整性",
      "description": "检查备份文件大小是否合理（>32MB）",
      "command": "stat -c%s /sdcard/boot_backup.img",
      "requiresRoot": false,
      "validationRules": [
        { "type": "CUSTOM", "value": "size>33554432", "description": "备份文件大于32MB" }
      ],
      "failureHandler": { "strategy": "ASK_USER", "description": "备份大小异常，询问用户是否继续" },
      "requiresConfirmation": false,
      "estimatedDuration": 5,
      "tags": ["校验"]
    },
    {
      "name": "刷入 Magisk 模块",
      "command": "magisk --install-module /sdcard/magisk_module.zip",
      "requiresRoot": true,
      "validationRules": [
        { "type": "EXIT_CODE_ZERO", "value": "", "description": "刷入命令成功" },
        { "type": "OUTPUT_CONTAINS", "value": "Done!", "description": "输出完成标记" }
      ],
      "failureHandler": {
        "strategy": "ROLLBACK",
        "maxRetries": 1,
        "rollbackCommand": "dd if=/sdcard/boot_backup.img of=/dev/block/by-name/boot",
        "description": "刷入失败，从备份恢复"
      },
      "requiresConfirmation": true,
      "estimatedDuration": 60,
      "tags": ["Magisk", "刷入"]
    }
  ],
  "tags": ["Magisk", "boot", "备份"]
}
        """.trimIndent())
        appendLine()
        
        appendLine("【安全与兼容性提醒】")
        appendLine("1. 每步必须包含至少一条 validationRules（EXIT_CODE_ZERO 为最低要求）")
        appendLine("2. 危险操作（rm -rf、dd、format、wipe）requiresConfirmation 必须为 true")
        appendLine("3. 有变砖风险的操作必须提供 rollbackCommand")
        appendLine("4. Android 14+ 的 /data 分区权限变化需考虑")
        appendLine("5. 动态分区设备（super分区）避免直接操作分区表")
        appendLine("6. 预估耗时包含命令执行和等待时间")
    }
    
    /**
     * 解析 LLM 响应为 TaskPlan
     */
    private fun parseTaskPlanResponse(
        llmResponse: String,
        userRequest: String,
        useRoot: Boolean
    ): TaskPlan {
        // 清理响应，提取 JSON
        val cleanResponse = extractJsonFromResponse(llmResponse)
        
        return try {
            // 先解析为临时数据结构
            val tempPlan = json.decodeFromString<TempTaskPlan>(cleanResponse)
            
            // 转换为正式数据结构
            val steps = tempPlan.steps.mapIndexed { index, tempStep ->
                TaskStep(
                    order = index + 1,
                    name = tempStep.name,
                    description = tempStep.description,
                    command = tempStep.command,
                    requiresRoot = tempStep.requiresRoot,
                    validationRules = tempStep.validationRules.map { rule ->
                        ValidationRule(
                            type = ValidationType.valueOf(rule.type.uppercase()),
                            value = rule.value,
                            description = rule.description
                        )
                    },
                    failureHandler = tempStep.failureHandler?.let { handler ->
                        FailureHandler(
                            strategy = FailureStrategy.valueOf(handler.strategy.uppercase()),
                            maxRetries = handler.maxRetries,
                            rollbackCommand = handler.rollbackCommand,
                            alternativeCommands = handler.alternativeCommands,
                            description = handler.description
                        )
                    },
                    requiresConfirmation = tempStep.requiresConfirmation,
                    estimatedDuration = tempStep.estimatedDuration,
                    tags = tempStep.tags
                )
            }
            
            TaskPlan(
                name = tempPlan.taskName,
                description = tempPlan.taskDescription,
                steps = steps,
                estimatedTotalDuration = tempPlan.estimatedDuration,
                safetyWarning = tempPlan.safetyWarning,
                reasoning = tempPlan.reasoning,
                tags = tempPlan.tags
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response", e)
            throw e
        }
    }
    
    /**
     * 从响应中提取 JSON
     */
    private fun extractJsonFromResponse(response: String): String {
        // 查找 JSON 开始位置
        val startIndex = response.indexOf('{')
        if (startIndex == -1) return response
        
        // 查找匹配的结束括号
        var braceCount = 0
        var endIndex = -1
        for (i in startIndex until response.length) {
            when (response[i]) {
                '{' -> braceCount++
                '}' -> {
                    braceCount--
                    if (braceCount == 0) {
                        endIndex = i + 1
                        break
                    }
                }
            }
        }
        
        return if (endIndex != -1) {
            response.substring(startIndex, endIndex)
        } else {
            response.substring(startIndex)
        }
    }
    
    /**
     * 生成备用计划（LLM 失败时）
     */
    private fun generateFallbackPlan(userRequest: String, useRoot: Boolean): TaskPlan {
        return TaskPlan(
            id = UUID.randomUUID().toString(),
            name = "简单执行任务",
            description = "简单执行用户请求",
            steps = listOf(
                TaskStep(
                    order = 1,
                    name = "执行请求",
                    description = "简单执行用户的请求",
                    command = "echo \"$userRequest\"",
                    requiresRoot = useRoot,
                    validationRules = listOf(
                        ValidationRule(ValidationType.EXIT_CODE_ZERO, "")
                    ),
                    failureHandler = FailureHandler(
                        strategy = FailureStrategy.RETRY,
                        maxRetries = 2
                    ),
                    requiresConfirmation = false
                )
            )
        )
    }
}

// ==================== 临时解析数据结构 ====================

@Serializable
private data class TempTaskPlan(
    val taskName: String,
    val taskDescription: String,
    val estimatedDuration: Long? = null,
    val safetyWarning: String? = null,
    val reasoning: String? = null,
    val steps: List<TempTaskStep>,
    val tags: List<String> = emptyList()
)

@Serializable
private data class TempTaskStep(
    val name: String,
    val description: String,
    val command: String,
    val requiresRoot: Boolean = false,
    val validationRules: List<TempValidationRule> = emptyList(),
    val failureHandler: TempFailureHandler? = null,
    val requiresConfirmation: Boolean = false,
    val estimatedDuration: Long? = null,
    val tags: List<String> = emptyList()
)

@Serializable
private data class TempValidationRule(
    val type: String,
    val value: String,
    val description: String? = null
)

@Serializable
private data class TempFailureHandler(
    val strategy: String,
    val maxRetries: Int = 3,
    val rollbackCommand: String? = null,
    val alternativeCommands: List<String> = emptyList(),
    val description: String? = null
)
