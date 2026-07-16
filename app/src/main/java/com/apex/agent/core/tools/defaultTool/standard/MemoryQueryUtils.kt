package com.apex.agent.core.tools.defaultTool.standard

import com.apex.data.model.AITool
import com.apex.core.tools.ToolResult
import com.apex.core.tools.StringResultData

/**
 * 内存查询工具的公共方?/
object MemoryQueryUtils {
    /**
     * 从工具参数中获取字符串参?   * @param tool 工具对象
     * @param name 参数名称
     * @return 参数值，如果不存在则返回null
     */
    fun getStringParameter(tool: AITool, name: String): String? {
        return tool.parameters.find { it.name == name }?.value
    }

    /**
     * 从工具参数中获取整数参数
     * @param tool 工具对象
     * @param name 参数名称
     * @param defaultValue 默认?    * @return 参数值，如果不存在或解析失败则返回默认，     */
    fun getIntParameter(tool: AITool, name: String, defaultValue: Int): Int {
        return getStringParameter(tool, name)?.toIntOrNull() ?: defaultValue
    }

    /**
     * 从工具参数中获取浮点数参?   * @param tool 工具对象
     * @param name 参数名称
     * @param defaultValue 默认?    * @return 参数值，如果不存在或解析失败则返回默认，     */
    fun getFloatParameter(tool: AITool, name: String, defaultValue: Float): Float {
        return getStringParameter(tool, name)?.toFloatOrNull() ?: defaultValue
    }

    /**
     * 从工具参数中获取布尔参数
     * @param tool 工具对象
     * @param name 参数名称
     * @param defaultValue 默认?    * @return 参数值，如果不存在或解析失败则返回默认，     */
    fun getBooleanParameter(tool: AITool, name: String, defaultValue: Boolean): Boolean {
        return getStringParameter(tool, name)?.toBooleanStrictOrNull() ?: defaultValue
    }

    /**
     * 检查必需参数是否存在
     * @param tool 工具对象
     * @param requiredParams 必需参数名称列表
     * @return 如果所有必需参数都存在则返回null，否则返回错误信?    */
    fun checkRequiredParameters(tool: AITool, vararg requiredParams: String): String? {
        for (param in requiredParams) {
            if (getStringParameter(tool, param).isNullOrBlank()) {
                return "${param} is a required parameter"
            }
        }
        return null
    }

    /**
     * 创建成功的工具结?    * @param toolName 工具名称
     * @param result 结果数据
     * @return 工具结果
     */
    fun createSuccessResult(toolName: String, result: Any): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = true,
            result = result
        )
    }

    /**
     * 创建失败的工具结?    * @param toolName 工具名称
     * @param error 错误信息
     * @return 工具结果
     */
    fun createErrorResult(toolName: String, error: String): ToolResult {
        return ToolResult(
            toolName = toolName,
            success = false,
            result = StringResultData(""),
            error = error
        )
    }

    /**
     * 解析标题列表参数
     * @param raw 原始字符?   * @return 标题列表
     */
    fun parseTitlesParam(raw: String): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw
            .split(',', '\n', '|')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
