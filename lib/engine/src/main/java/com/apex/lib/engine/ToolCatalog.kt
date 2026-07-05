package com.apex.lib.engine

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite

/**
 * 工具元数据注册表。
 *
 * 职责：
 *   - 注册 / 注销 [ToolDescriptor]
 *   - 按 name 精确查询
 *   - 按 category 查询
 *   - 列出全部 / 全部分类
 *
 * 不负责工具的执行（执行走 [EngineGateway.rawExecuteTool]）。
 * 通常由 [EngineOrchestrator.listTools] 在调用底层后同步刷新。
 *
 * 线程安全：内部使用 [LinkedHashMap] + 同步块，简单可靠。
 */
class ToolCatalog {

    private val tools: MutableMap<String, ToolDescriptor> = linkedMapOf()

    /** 注册（覆盖同名）。 */
    fun register(tool: ToolDescriptor) {
        synchronized(tools) {
            tools[tool.name] = tool
        }
    }

    /** 批量注册。 */
    fun registerAll(items: List<ToolDescriptor>) {
        synchronized(tools) {
            items.forEach { tools[it.name] = it }
        }
    }

    /** 注销。 */
    fun unregister(name: String): ToolDescriptor? = synchronized(tools) { tools.remove(name) }

    /** 按 name 查询。 */
    fun get(name: String): ToolDescriptor? = synchronized(tools) { tools[name] }

    /** 是否存在。 */
    fun contains(name: String): Boolean = synchronized(tools) { name in tools }

    /** 列出全部。 */
    fun list(): List<ToolDescriptor> = synchronized(tools) { tools.values.toList() }

    /** 按 category 过滤。 */
    fun byCategory(category: String): List<ToolDescriptor> =
        synchronized(tools) { tools.values.filter { it.category == category } }

    /** 全部分类。 */
    fun categories(): Set<String> = synchronized(tools) { tools.values.map { it.category }.toSet() }

    /** 工具数量。 */
    fun size(): Int = synchronized(tools) { tools.size }

    /** 清空。 */
    fun clear() = synchronized(tools) { tools.clear() }

    /**
     * 用底层返回的工具列表覆盖当前注册表，并返回最新列表。
     * 内部会打印 diff 日志，便于排查工具集变更。
     */
    fun refresh(items: List<ToolDescriptor>): List<ToolDescriptor> {
        val before = size()
        clear()
        registerAll(items)
        val after = size()
        if (before != after) {
            ApexLog.i(ApexSuite.ApkId.ENGINE, "[Catalog] refreshed: $before -> $after tools")
        }
        return items
    }
}
