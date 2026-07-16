package com.apex.core.tools

/**
 * 工具帮助类，提供通用的工具功能和错误处理
 */
object ToolHelper {

    /**
     * 验证工具参数
     */
    fun validateParameters(
        parameters: Map<String, Any>,
        requiredParams: List<String>
    ): ValidationResult {
        val missingParams = mutableListOf<String>()
        
        requiredParams.forEach { paramName ->
            if (!parameters.containsKey(paramName) || parameters[paramName] == null) {
                missingParams.add(paramName)
            }
        }
        
        return if (missingParams.isEmpty()) {
            ValidationResult(success = true)
        } else {
            ValidationResult(
                success = false,
                errorMessage = "缺少必需的参数：${missingParams.joinToString(", ")}"
            )
        }
    }

    /**
     * 安全地获取参数，     */
    fun <T> getParameterSafely(
        parameters: Map<String, Any>,
        key: String,
        defaultValue: T,
        clazz: Class<T>
    ): T {
        val value = parameters[key] ?: return defaultValue
        
        @Suppress("UNCHECKED_CAST")
        return when (clazz) {
            String::class.java -> value.toString() as T
            Int::class.java -> value.toString().toIntOrNull() ?: defaultValue
            Long::class.java -> value.toString().toLongOrNull() ?: defaultValue
            Double::class.java -> value.toString().toDoubleOrNull() ?: defaultValue
            Boolean::class.java -> value.toString().toBoolean() as T
            else -> try {
                value as T
            } catch (e: Exception) {
                defaultValue
            }
        }
    }

    /**
     * 格式化错误信�?    */
    fun formatError(
        toolName: String,
        errorType: String,
        errorMessage: String,
        details: Map<String, Any>? = null
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[${toolName}] ${errorType}")
        sb.appendLine("错误信息，errorMessage")
        
        if (details != null && details.isNotEmpty()) {
            sb.appendLine("详细信息�?
            details.forEach { (key, value) ->
                sb.appendLine("  ${key}: ${value}")
            }
        }
        
        return sb.toString()
    }

    /**
     * 创建友好的工具使用说�?    */
    fun createUsageGuide(toolName: String, parameters: List<ToolParameter>): String {
        val sb = StringBuilder()
        sb.appendLine("=== ${toolName} 使用说明 ===")
        sb.appendLine()
        
        sb.appendLine("参数列表�?
        parameters.forEach { param ->
            val required = if (param.required) "[必需]" else "[可选]"
            val defaultValue = if (param.defaultValue != null) "(默认，{param.defaultValue})" else ""
            
            sb.appendLine("  ${required} ${param.name} (${param.type}): ${param.description} ${defaultValue}")
        }
        
        sb.appendLine()
        sb.appendLine("示例用法�?
        sb.appendLine("  请根据具体工具查看示�?
        
        return sb.toString()
    }

    /**
     * 验证结果
     */
    data class ValidationResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * 工具执行上下文（用于更复杂的工具状态管理）
     */
    data class ToolContext(
        val toolName: String,
        val sessionId: String? = null,
        val metadata: MutableMap<String, Any> = mutableMapOf()
    ) {
        fun put(key: String, value: Any) {
            metadata[key] = value
        }
        
        fun <T> get(key: String, clazz: Class<T>): T? {
            @Suppress("UNCHECKED_CAST")
            return metadata[key] as? T
        }
        
        fun has(key: String): Boolean {
            return metadata.containsKey(key)
        }
    }

    /**
     * 工具执行状�?    */
    enum class ExecutionStatus {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    /**
     * 工具执行进度回调接口（可选）
     */
    interface ExecutionProgressCallback {
        fun onProgress(progress: Int, message: String)
        fun onStatusChanged(status: ExecutionStatus)
    }
}
