package com.apex.agent.core.tools.result

/**
 * UI工具操作结果封装
 * 
 * 提供统一的错误码和结果类型，便于错误处理和调�? */
sealed class UIToolsResult {
    /**
     * 操作成功
     */
    data class Success(val data: Any? = null) : UIToolsResult()
    
    /**
     * 操作失败
     */
    data class Error(
        val errorCode: UIToolsErrorCode,
        val message: String = errorCode.message,
        val details: String? = null
    ) : UIToolsResult()
    
    /**
     * 操作超时
     */
    object Timeout : UIToolsResult()
    
    /**
     * 服务不可�?     */
    object ServiceNotAvailable : UIToolsResult()
    
    /**
     * 权限不足
     */
    object PermissionDenied : UIToolsResult()
    
    /**
     * 转换为ToolResult格式
     */
    fun toToolResult(toolName: String): com.apex.data.model.ToolResult {
        return when (this) {
            is Success -> com.apex.data.model.ToolResult(
                toolName = toolName,
                success = true,
                result = com.apex.agent.core.tools.StringResultData(data?.toString() ?: ""),
                error = ""
            )
            is Error -> com.apex.data.model.ToolResult(
                toolName = toolName,
                success = false,
                result = com.apex.agent.core.tools.StringResultData(""),
                error = "[${errorCode.code}] ${message}${if (details != null) "\n详情: ${details}" else ""}"
            )
            Timeout -> com.apex.data.model.ToolResult(
                toolName = toolName,
                success = false,
                result = com.apex.agent.core.tools.StringResultData(""),
                error = "[${UIToolsErrorCode.OPERATION_TIMEOUT.code}] ${UIToolsErrorCode.OPERATION_TIMEOUT.message}"
            )
            ServiceNotAvailable -> com.apex.data.model.ToolResult(
                toolName = toolName,
                success = false,
                result = com.apex.agent.core.tools.StringResultData(""),
                error = "[${UIToolsErrorCode.SERVICE_NOT_ENABLED.code}] ${UIToolsErrorCode.SERVICE_NOT_ENABLED.message}"
            )
            PermissionDenied -> com.apex.data.model.ToolResult(
                toolName = toolName,
                success = false,
                result = com.apex.agent.core.tools.StringResultData(""),
                error = "[${UIToolsErrorCode.PERMISSION_DENIED.code}] ${UIToolsErrorCode.PERMISSION_DENIED.message}"
            )
        }
    }
    
    companion object {
        /**
         * 创建成功结果
         */
        fun success(data: Any? = null): UIToolsResult {
            return Success(data)
        }
        
        /**
         * 创建错误结果
         */
        fun error(errorCode: UIToolsErrorCode, message: String? = null, details: String? = null): UIToolsResult {
            return Error(errorCode, message ?: errorCode.message, details)
        }
        
        /**
         * 创建超时结果
         */
        fun timeout(): UIToolsResult {
            return Timeout
        }
        
        /**
         * 创建服务不可用结�?         */
        fun serviceNotAvailable(): UIToolsResult {
            return ServiceNotAvailable
        }
        
        /**
         * 创建权限不足结果
         */
        fun permissionDenied(): UIToolsResult {
            return PermissionDenied
        }
    }
}

/**
 * UI工具错误码枚�? */
enum class UIToolsErrorCode(val code: Int, val message: String) {
    // 服务相关错误 (1000-1099)
    SERVICE_NOT_ENABLED(1001, "无障碍服务未启用，请在系统设置中启用"),
    SERVICE_DISCONNECTED(1002, "无障碍服务连接断开"),
    SERVICE_TIMEOUT(1003, "无障碍服务响应超的）,
    
    // 元素查找错误 (1100-1199)
    ELEMENT_NOT_FOUND(1101, "未找到匹配的UI元素"),
    ELEMENT_AMBIGUOUS(1102, "找到多个匹配元素，请提供更精确的选择条件"),
    ELEMENT_NOT_CLICKABLE(1103, "元素不可点击"),
    ELEMENT_NOT_VISIBLE(1104, "元素不可以）,
    ELEMENT_OUT_OF_BOUNDS(1105, "元素坐标超出屏幕范围"),
    
    // 参数错误 (1200-1299)
    INVALID_PARAMETERS(1201, "参数无效或缺的）,
    INVALID_COORDINATES(1202, "坐标值无的）,
    INVALID_SELECTOR(1203, "选择器无效，请至少提供一个选择条件"),
    INDEX_OUT_OF_RANGE(1204, "索引超出范围"),
    
    // 操作错误 (1300-1399)
    OPERATION_FAILED(1301, "操作执行失败"),
    OPERATION_TIMEOUT(1302, "操作超时"),
    OPERATION_CANCELLED(1303, "操作已取的）,
    SWIPE_FAILED(1304, "滑动操作失败"),
    INPUT_FAILED(1305, "文本输入失败"),
    
    // 权限错误 (1400-1499)
    PERMISSION_DENIED(1401, "权限不足，需要更高权限级的）,
    ROOT_REQUIRED(1402, "需要Root权限"),
    ADMIN_REQUIRED(1403, "需要管理员权限"),
    
    // 系统错误 (1500-1599)
    SYSTEM_ERROR(1501, "系统错误"),
    SCREENSHOT_FAILED(1502, "截图失败"),
    MEMORY_ERROR(1503, "内存不足"),
    UNKNOWN_ERROR(1599, "未知错误");
    
    /**
     * 根据错误码获取错误对�?     */
    companion object {
        fun fromCode(code: Int): UIToolsErrorCode {
            return values().find { it.code == code } ?: UNKNOWN_ERROR
        }
    }
}

/**
 * 操作日志条目
 */
data class OperationLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val toolName: String,
    val action: String,
    val success: Boolean,
    val duration: Long = 0,
    val details: String? = null,
    val errorCode: UIToolsErrorCode? = null
) {
    /**
     * 格式化为可读字符�?     */
    fun format(): String {
        val status = if (success) "�?else "�?
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
        return buildString {
            append("[${time}] ${status} ${toolName}.${action}")
        if (duration > 0) {
                append(" (${duration}ms)")
            }
        if (!success && errorCode != null) {
                append(" [${errorCode.code}]")
            }
        if (details != null) {
                append("\n  详情: ${details}")
            }
        }
    }
}

/**
 * 操作日志管理�? */
class OperationLogger(private val maxEntries: Int = 1000) {
    private val logEntries = mutableListOf<OperationLogEntry>()
    
    /**
     * 记录操作日志
     */
    @Synchronized
    fun log(entry: OperationLogEntry) {
        logEntries.add(entry)
        
        // 如果超过最大条目数，删除最旧的条目
    if (logEntries.size > maxEntries) {
            logEntries.removeAt(0)
        }
    }
    
    /**
     * 便捷方法：记录成功操�?     */
    fun logSuccess(toolName: String, action: String, duration: Long = 0, details: String? = null) {
        log(OperationLogEntry(
            toolName = toolName,
            action = action,
            success = true,
            duration = duration,
            details = details
        ))
    }
    
    /**
     * 便捷方法：记录失败操�?     */
    fun logError(toolName: String, action: String, errorCode: UIToolsErrorCode, duration: Long = 0, details: String? = null) {
        log(OperationLogEntry(
            toolName = toolName,
            action = action,
            success = false,
            duration = duration,
            details = details,
            errorCode = errorCode
        ))
    }
    
    /**
     * 获取最近的日志条目
     */
    @Synchronized
    fun getRecentLogs(count: Int = 10): List<OperationLogEntry> {
        return logEntries.takeLast(count).reversed()
    }
    
    /**
     * 获取所有日志条�?     */
    @Synchronized
    fun getAllLogs(): List<OperationLogEntry> {
        return logEntries.toList()
    }
    
    /**
     * 清空日志
     */
    @Synchronized
    fun clear() {
        logEntries.clear()
    }
    
    /**
     * 导出日志为文�?     */
    @Synchronized
    fun exportToText(): String {
        return logEntries.joinToString("\n") { it.format() }
    }
}
