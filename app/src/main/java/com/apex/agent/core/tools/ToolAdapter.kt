package com.apex.core.tools

import kotlinx.serialization.Serializable

/**
 * 工具适配器接口，定义通用的工具调用方?*/
interface ToolAdapter {
    /**
     * 获取工具名称
     */
    fun getName(): String

    /**
     * 获取工具描述
     */
    fun getDescription(): String

    /**
     * 执行工具调用
     * @param parameters 工具参数
     * @return 工具执行结果
     */
    suspend fun execute(parameters: Map<String, Any>): ToolResultData

    /**
     * 获取工具参数描述
     */
    fun getParameters(): List<ToolParameter>

    /**
     * 检查工具是否可?    */
    fun isAvailable(): Boolean
}

/**
 * 工具参数描述
 */
@Serializable
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
    val defaultValue: Any? = null
)

/**
 * 工具注册?*/
object ToolRegistry {
    private val tools = mutableMapOf<String, ToolAdapter>()

    /**
     * 注册工具
     */
    fun register(tool: ToolAdapter) {
        tools[tool.getName()] = tool
    }

    /**
     * 获取工具
     */
    fun getTool(name: String): ToolAdapter? {
        return tools[name]
    }

    /**
     * 获取所有工?    */
    fun getAllTools(): List<ToolAdapter> {
        return tools.values.toList()
    }

    /**
     * 获取可用工具
     */
    fun getAvailableTools(): List<ToolAdapter> {
        return tools.values.filter { it.isAvailable() }
    }

    /**
     * 移除工具
     */
    fun remove(name: String) {
        tools.remove(name)
    }

    /**
     * 清空工具
     */
    fun clear() {
        tools.clear()
    }
}
