package com.apex.core.model

/**
 * 工具元信息 — 描述工具的能力和参数。
 */
data class ToolMetadata(
    val id: String,
    val name: String,
    val description: String,
    val category: ToolCategory = ToolCategory.GENERAL,
    val parameters: String = "{}",  // JSON Schema
    val isReadOnly: Boolean = true
)

enum class ToolCategory {
    GENERAL,
    NETWORK,
    FILE_SYSTEM,
    SHELL,
    APP,
    SYSTEM,
    SEARCH,
    MEDIA
}

/** 工具调用请求 */
data class ToolCall(
    val id: String,
    val toolId: String,
    val name: String,
    val arguments: String   // JSON string
)

/** 工具执行结果 */
sealed class ToolResult {
    data class Success(val output: String, val data: Any? = null) : ToolResult()
    data class Error(val message: String) : ToolResult()
}

/** 工具接口 */
interface ApexTool {
    val metadata: ToolMetadata
    suspend fun execute(arguments: Map<String, Any>): ToolResult
}
