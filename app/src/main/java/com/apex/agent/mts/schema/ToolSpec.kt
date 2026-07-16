package com.apex.agent.mts.schema

enum class ParameterType { STRING, INTEGER, FLOAT, BOOLEAN, FILE, JSON, ENUM, ARRAY, OBJECT }
enum class ExecutionMode { LOCAL, NETWORK, MCP, JS_SCRIPT, COMPOSITE }
enum class PermissionLevel { STANDARD, ACCESSIBILITY, DEBUGGER, ADMIN, ROOT }
enum class FailureStrategy { RETRY_ONCE, RETRY_WITH_BACKOFF, FAIL_FAST, FALLBACK_CHAIN, CIRCUIT_BREAKER }

/**
 * 三种运行模式：
 *   NORMAL       - 普通Agent模式，基础安全工具集 + 标准执行策略
 *   MULTI_AGENT  - 多Agent模式，支持Agent间通信/委派 + 协作工具
 *   BERSERK      - 狂暴模式，全部工具可用 + 激进执行策略（高并发/低超时/自动重试）
 */
enum class AgentMode { NORMAL, MULTI_AGENT, BERSERK }

data class AgentModeConfig(
    val allowedModes: Set<AgentMode> = setOf(AgentMode.NORMAL, AgentMode.MULTI_AGENT),
    val modeSpecificDescription: Map<AgentMode, String> = emptyMap()
)

data class ValidationRule(
    val pattern: String? = null,
    val min: Long? = null,
    val max: Long? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val custom: String? = null
)

data class ParameterSpec(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = false,
    val defaultValue: Any? = null,
    val enumValues: List<String>? = null,
    val validation: ValidationRule? = null,
    val examples: List<String>? = null
)

data class RateLimit(
    val maxCallsPerMinute: Int = 60,
    val maxConcurrentCalls: Int = 5
)

data class ToolConstraints(
    val requiresNetwork: Boolean = false,
    val requiresRoot: Boolean = false,
    val permissionLevel: PermissionLevel = PermissionLevel.STANDARD,
    val timeoutMs: Long = 30000,
    val maxConcurrency: Int = 1,
    val rateLimit: RateLimit? = null,
    val costToken: Int = 0,
    val allowedEnvironments: List<String>? = null
)

data class ToolMetadata(
    val version: String = "1.0.0",
    val author: String = "system",
    val description: String? = null,
    val deprecated: Boolean = false,
    val experimental: Boolean = false
)

data class ExecutionDependency(
    val toolId: String,
    val consumes: List<String> = emptyList(),
    val produces: List<String> = emptyList()
)

data class ToolCategory(
    val id: String,
    val displayName: String,
    val parentId: String? = null,
    val priority: Int = 0
)

object ToolCategories {
    val FILE_SYSTEM = ToolCategory("file_system", "File System", priority = 10)
    val NETWORK = ToolCategory("network", "Network", priority = 20)
    val UI = ToolCategory("ui", "UI Automation", priority = 30)
    val DEVICE = ToolCategory("device", "Device", priority = 40)
    val APP = ToolCategory("app", "Application", priority = 50)
    val MEMORY = ToolCategory("memory", "Memory", priority = 60)
    val WORKFLOW = ToolCategory("workflow", "Workflow", priority = 70)
    val CHAT = ToolCategory("chat", "Chat", priority = 80)
    val MEDIA = ToolCategory("media", "Media", priority = 90)
    val SYSTEM = ToolCategory("system", "System", priority = 100)
    val BROWSER = ToolCategory("browser", "Browser", priority = 25)
    val TERMINAL = ToolCategory("terminal", "Terminal", priority = 35)
    val MCP = ToolCategory("mcp", "MCP External", priority = 200)
    val USER = ToolCategory("user", "User Packages", priority = 300)
}

data class ToolSpec(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val detailedDescription: String = description,
    val category: ToolCategory,
    val tags: Set<String> = emptySet(),
    val parameters: List<ParameterSpec> = emptyList(),
    val execMode: ExecutionMode = ExecutionMode.LOCAL,
    val executor: ToolExecutorRef,
    val constraints: ToolConstraints = ToolConstraints(),
    val metadata: ToolMetadata = ToolMetadata(),
    val dependencies: List<ExecutionDependency> = emptyList(),
    val errorRecovery: FailureStrategy = FailureStrategy.FAIL_FAST,
    val parallelSafe: Boolean = true,
    val outputDescription: String? = null,
    /** 该工具在哪些模式下可用，默认三种模式都可用 */
    val modeConfig: AgentModeConfig = AgentModeConfig()
)

data class ToolExecutorRef(
    val type: ExecutionMode,
    val ref: String,
    val config: Map<String, Any> = emptyMap()
)

data class ScoredTool(
    val tool: ToolSpec,
    val score: Double,
    val matchReason: String = ""
)

data class ToolPromptDef(
    val name: String,
    val description: String,
    val parameters: List<ParameterSpec>,
    val details: String? = null
)

sealed interface ToolOutcome {
    data class Success(val data: String, val metadata: Map<String, Any?> = emptyMap()) : ToolOutcome
    data class Failure(val error: String, val code: String = "TOOL_ERROR", val recoverable: Boolean = true) : ToolOutcome
    data object Cancelled : ToolOutcome
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class ParsedToolCall(
    val id: String,
    val toolSpec: ToolSpec,
    val arguments: Map<String, Any?>,
    val rawName: String
)

data class ExecutionResult(
    val toolCallId: String,
    val toolName: String,
    val outcome: ToolOutcome,
    val durationMs: Long,
    val retryCount: Int = 0
)

typealias ToolExecutionFlow = kotlinx.coroutines.flow.Flow<ExecutionResult>

enum class OptimizationStrategy { MINIMAL, BALANCED, DETAILED }
