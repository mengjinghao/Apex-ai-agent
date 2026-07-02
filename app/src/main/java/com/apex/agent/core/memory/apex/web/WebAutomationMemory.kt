package com.apex.agent.core.memory.apex.web

import com.apex.agent.core.memory.apex.core.*

/**
 * 网页自动化记忆模块。
 *
 * 记录网页操作的经验和收获：
 * - **操作经验**：网页操作步骤/表单填写/登录流程/导航路径
 * - **收获**：爬取的数据/发现的模式/页面结构信息
 *
 * # 操作经验
 *
 * 记录成功的网页操作流程：
 * - 登录流程（用户名→密码→验证码→提交）
 * - 表单填写（字段映射/选择器/值）
 * - 导航路径（从 A 页面到 B 页面的步骤）
 * - 数据提取（CSS 选择器/XPath/正则模式）
 *
 * # 收获
 *
 * 记录从网页中获得的有价值信息：
 * - 数据模式（价格趋势/库存变化/评论分布）
 * - 页面结构（DOM 结构/API 端点/隐藏字段）
 * - 反爬策略（验证码类型/频率限制/User-Agent 要求）
 */
class WebAutomationMemory {

    private val operationStore = BaseMemoryStore()
    private val harvestStore = BaseMemoryStore()

    /**
     * 存储操作经验。
     *
     * @param name 操作名（如"淘宝登录"/"京东搜索"）
     * @param steps 操作步骤列表
     * @param domain 网站域名
     * @param success 是否成功
     * @param durationMs 耗时
     * @param selectors 使用的选择器
     */
    fun storeOperation(
        name: String,
        steps: List<String>,
        domain: String = "",
        success: Boolean = true,
        durationMs: Long = 0,
        selectors: Map<String, String> = emptyMap(),
        tags: Set<String> = emptySet()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.OPERATION,
            content = "$name: ${steps.joinToString(" → ")}",
            importance = if (success) 0.7f else 0.85f,
            confidence = if (success) 0.9f else 0.6f,
            source = "web_automation",
            tags = tags + setOf("web", "operation", domain, if (success) "success" else "failure")
                .filter { it.isNotBlank() }.toSet(),
            metadata = mapOf(
                "name" to name,
                "steps" to steps,
                "domain" to domain,
                "success" to success,
                "durationMs" to durationMs,
                "selectors" to selectors
            )
        )
        operationStore.store(entry)
        return entry.id
    }

    /**
     * 获取操作经验。
     */
    fun getOperations(limit: Int = 20): List<MemoryEntry> {
        return operationStore.getByType(MemoryType.OPERATION).take(limit)
    }

    /**
     * 按域名获取操作。
     */
    fun getOperationsByDomain(domain: String, limit: Int = 20): List<MemoryEntry> {
        return operationStore.query(MemoryQuery(
            type = MemoryType.OPERATION,
            tags = setOf(domain),
            limit = limit
        ))
    }

    /**
     * 按名称搜索操作。
     */
    fun searchOperations(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return operationStore.search(keyword, limit)
    }

    /**
     * 获取成功操作。
     */
    fun getSuccessfulOperations(limit: Int = 20): List<MemoryEntry> {
        return operationStore.query(MemoryQuery(
            type = MemoryType.OPERATION,
            tags = setOf("success"),
            limit = limit
        ))
    }

    /**
     * 存储收获。
     *
     * @param category 收获类别（如"数据模式"/"页面结构"/"反爬策略"）
     * @param content 收获内容
     * @param domain 网站
     * @param importance 重要性
     * @param tags 标签
     */
    fun storeHarvest(
        category: String,
        content: String,
        domain: String = "",
        importance: Float = 0.6f,
        tags: Set<String> = emptySet()
    ): String {
        val entry = MemoryEntry(
            type = MemoryType.HARVEST,
            content = content,
            importance = importance,
            confidence = 0.8f,
            source = "web_automation",
            tags = tags + setOf("web", "harvest", category, domain).filter { it.isNotBlank() }.toSet(),
            metadata = mapOf(
                "category" to category,
                "domain" to domain
            )
        )
        harvestStore.store(entry)
        return entry.id
    }

    /**
     * 获取所有收获。
     */
    fun getHarvests(limit: Int = 20): List<MemoryEntry> {
        return harvestStore.getByType(MemoryType.HARVEST).take(limit)
    }

    /**
     * 按类别获取收获。
     */
    fun getHarvestsByCategory(category: String, limit: Int = 20): List<MemoryEntry> {
        return harvestStore.query(MemoryQuery(
            type = MemoryType.HARVEST,
            tags = setOf(category),
            limit = limit
        ))
    }

    /**
     * 按域名获取收获。
     */
    fun getHarvestsByDomain(domain: String, limit: Int = 20): List<MemoryEntry> {
        return harvestStore.query(MemoryQuery(
            type = MemoryType.HARVEST,
            tags = setOf(domain),
            limit = limit
        ))
    }

    /**
     * 搜索收获。
     */
    fun searchHarvests(keyword: String, limit: Int = 20): List<MemoryEntry> {
        return harvestStore.search(keyword, limit)
    }

    /**
     * 清空。
     */
    fun clear() {
        operationStore.clear()
        harvestStore.clear()
    }

    fun getStats(): MemoryModuleStats {
        val total = operationStore.count() + harvestStore.count()
        return MemoryModuleStats(
            totalEntries = total,
            conversationCount = operationStore.count(),
            experienceCount = harvestStore.count(),
            oldestTimestamp = operationStore.getAll().minOfOrNull { it.timestamp } ?: 0,
            newestTimestamp = operationStore.getAll().maxOfOrNull { it.timestamp } ?: 0
        )
    }
}
