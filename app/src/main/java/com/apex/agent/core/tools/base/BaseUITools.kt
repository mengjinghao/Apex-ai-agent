package com.apex.agent.core.tools.base

import android.content.Context
import com.apex.agent.core.tools.config.UIToolsConfig
import com.apex.agent.core.tools.result.OperationLogger
import com.apex.agent.core.tools.result.UIToolsResult
import com.apex.data.model.AITool
import com.apex.core.tools.ToolResult
import com.apex.util.AppLogger
import kotlinx.coroutines.delay

/**
 * UI工具类基础抽象? * 
 * 定义所有UI工具类的通用接口和辅助方? * 提供统一的错误处理、重试机制和日志记录
 */
abstract class BaseUITools(protected val context: Context) : com.apex.agent.core.tools.agent.ToolImplementations {
    
    companion object {
        private const val TAG = "BaseUITools"
    }
    
    /** 操作日志管理?/
    protected val logger = OperationLogger(UIToolsConfig.MAX_LOG_ENTRIES)
    
    // ==================== 通用辅助方法 ====================
    
    /**
     * 带重试的执行方法
     * 
     * @param operation 要执行的操作
     * @param maxRetries 最大重试次?     * @param delayMs 重试延迟（毫秒）
     * @return 操作结果
     */
    protected suspend fun <T> executeWithRetry(
        operation: suspend () -> T,
        maxRetries: Int = UIToolsConfig.MAX_RETRY_COUNT,
        delayMs: Long = UIToolsConfig.RETRY_DELAY_MS
    ): T {
        var lastException: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val result = operation()
                if (attempt > 0) {
                    AppLogger.d(TAG, "重试成功，尝试次?${attempt + 1}")
                }
                return result
            } catch (e: Exception) {
                lastException = e
                AppLogger.w(TAG, "${{attempt + 1} 次尝试失?${e.message}")
                
                if (attempt < maxRetries - 1) {
                    delay(delayMs)
                }
            }
        }
        
        throw lastException ?: IllegalStateException("未知错误")
    }
    
    /**
     * 记录操作日志
     * 
     * @param toolName 工具名称
     * @param action 操作名称
     * @param success 是否成功
     * @param duration 执行时长（毫秒）
     * @param details 详细信息
     */
    protected fun logOperation(
        toolName: String,
        action: String,
        success: Boolean,
        duration: Long = 0,
        details: String? = null
    ) {
        if (success) {
            logger.logSuccess(toolName, action, duration, details)
        } else {
            logger.logError(toolName, action, com.apex.agent.core.tools.result.UIToolsErrorCode.OPERATION_FAILED, duration, details)
        }
        
        if (UIToolsConfig.ENABLE_DETAILED_LOGS) {
            AppLogger.d(TAG, "[${toolName}] ${action}: ${if (success) "成功" else "失败"}${details?.let { " - ${it}" } ?: ""}")
        }
    }
    
    /**
     * 验证工具参数
     * 
     * @param tool AITool对象
     * @param requiredParams 必需的参数名列表
     * @return 验证结果，如果失败返回错误Result
     */
    protected fun validateParameters(tool: AITool, vararg requiredParams: String): UIToolsResult? {
        val missingParams = requiredParams.filter { paramName ->
            tool.parameters.find { it.name == paramName }?.value.isNullOrBlank()
        }
        
        if (missingParams.isNotEmpty()) {
            return UIToolsResult.error(
                errorCode = com.apex.agent.core.tools.result.UIToolsErrorCode.INVALID_PARAMETERS,
                details = "缺少必需参数: ${missingParams.joinToString(", ")}"
            )
        }
        
        return null
    }
    
    /**
     * 获取工具参数?     * 
     * @param tool AITool对象
     * @param paramName 参数?     * @param defaultValue 默认?     * @return 参数?     */
    protected fun getParameter(tool: AITool, paramName: String, defaultValue: String? = null): String? {
        return tool.parameters.find { it.name == paramName }?.value ?: defaultValue
    }
    
    /**
     * 获取整数类型参数
     * 
     * @param tool AITool对象
     * @param paramName 参数?     * @param defaultValue 默认?     * @return 参数值，如果无效返回null
     */
    protected fun getIntParameter(tool: AITool, paramName: String, defaultValue: Int? = null): Int? {
        val value = getParameter(tool, paramName) ?: return defaultValue
        return value.toIntOrNull() ?: defaultValue
    }
    
    /**
     * 获取布尔类型参数
     * 
     * @param tool AITool对象
     * @param paramName 参数?     * @param defaultValue 默认?     * @return 参数?     */
    protected fun getBooleanParameter(tool: AITool, paramName: String, defaultValue: Boolean = false): Boolean {
        val value = getParameter(tool, paramName) ?: return defaultValue
        return value.toBoolean()
    }
    
    // ==================== 必须实现的方法（来自ToolImplementations接接?==================
    
    /**
     * 点击指定坐标
     * 
     * @param tool AITool对象
     * @return 操作结果
     */
    abstract override suspend fun tap(tool: AITool): ToolResult
    
    /**
     * 长按指定坐标
     * 
     * @param tool AITool对象
     * @return 操作结果
     */
    abstract override suspend fun longPress(tool: AITool): ToolResult
    
    /**
     * 设置输入文本
     * 
     * @param tool AITool对象
     * @return 操作结果
     */
    abstract override suspend fun setInputText(tool: AITool): ToolResult
    
    /**
     * 按下按键
     * 
     * @param tool AITool对象
     * @return 操作结果
     */
    abstract override suspend fun pressKey(tool: AITool): ToolResult
    
    /**
     * 执行滑动手势
     * 
     * @param tool AITool对象
     * @return 操作结果
     */
    abstract override suspend fun swipe(tool: AITool): ToolResult
    
    // ==================== 可选实现的方法 ====================
    
    /**
     * 截图到文?     * 
     * @param tool AITool对象
     * @return 文件路径和尺?     */
    open suspend fun captureScreenshotToFile(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return Pair(null, null)
    }
    
    /**
     * 截图
     * 
     * @param tool AITool对象
     * @return 文件路径和尺?     */
    open suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?> {
        return captureScreenshotToFile(tool)
    }
    
    /**
     * 截图为Bitmap
     * 
     * @param tool AITool对象
     * @return Bitmap和尺?     */
    open suspend fun captureScreenshotBitmap(tool: AITool): Pair<android.graphics.Bitmap?, Pair<Int, Int>?> {
        val (filePath, dimensions) = captureScreenshot(tool)
        if (filePath == null) {
            return Pair(null, dimensions)
        }
        
        val bitmap = android.graphics.BitmapFactory.decodeFile(filePath)
        val resolvedDimensions = dimensions ?: Pair(bitmap?.width ?: 0, bitmap?.height ?: 0)
        return Pair(bitmap, resolvedDimensions)
    }
    
    /**
     * 获取最近的操作日志
     * 
     * @param count 日志条数
     * @return 日志列表
     */
    fun getRecentLogs(count: Int = 10): List<com.apex.agent.core.tools.result.OperationLogEntry> {
        return logger.getRecentLogs(count)
    }
    
    /**
     * 导出所有操作日?     * 
     * @return 日志文本
     */
    fun exportLogs(): String {
        return logger.exportToText()
    }
    
    /**
     * 清空操作日志
     */
    fun clearLogs() {
        logger.clear()
    }
}
