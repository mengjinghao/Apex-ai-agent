package com.apex.agent.integration.market

import com.apex.agent.integration.api.IntegrationCategory
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap

/**
 * 市场注册表。
 *
 * 管理所有已注册的 [IntegrationMarket] 实例，支持：
 * - 按分类查询市场
 * - 按 ID 查询市场
 * - 动态注册/注销
 * - ServiceLoader 自动发现
 *
 * # 高扩展性设计
 *
 * 业务侧通过 3 种方式注册市场：
 * 1. **手动注册**：`MarketRegistry.register(MyMarket())`
 * 2. **ServiceLoader 自动发现**：在 `META-INF/services/` 中声明实现类
 * 3. **运行时注入**：通过 DI 框架注入
 *
 * # 使用示例
 *
 * ```
 * // 注册市场
 * MarketRegistry.register(ApexSkillMarket())
 * MarketRegistry.register(McpSoMarket())
 * MarketRegistry.register(DeepSeekModelMarket())
 *
 * // 按分类查询
 * val skillMarkets = MarketRegistry.getByCategory(IntegrationCategory.SKILLS)
 *
 * // 按 ID 查询
 * val market = MarketRegistry.get("apex_skill_market")
 *
 * // 列出所有市场信息
 * val allMarkets = MarketRegistry.listAll()
 * ```
 */
object MarketRegistry {

    private val markets = ConcurrentHashMap<String, IntegrationMarket>()

    /**
     * 注册市场。
     *
     * @param market 市场实例
     * @return true 注册成功，false 已存在同 ID
     */
    fun register(market: IntegrationMarket): Boolean {
        return markets.putIfAbsent(market.marketId, market) == null
    }

    /**
     * 批量注册。
     *
     * @param markets 市场列表
     * @return 成功注册的数量
     */
    fun registerAll(markets: List<IntegrationMarket>): Int {
        var count = 0
        for (market in markets) {
            if (register(market)) count++
        }
        return count
    }

    /**
     * 注销市场。
     */
    fun unregister(marketId: String): Boolean {
        return markets.remove(marketId) != null
    }

    /**
     * 按 ID 获取市场。
     */
    fun get(marketId: String): IntegrationMarket? = markets[marketId]

    /**
     * 按分类获取所有市场。
     */
    fun getByCategory(category: IntegrationCategory): List<IntegrationMarket> {
        return markets.values.filter { it.category == category }
    }

    /**
     * 获取所有已注册市场。
     */
    fun getAll(): List<IntegrationMarket> = markets.values.toList()

    /**
     * 列出所有市场元信息（用于 UI 列表展示）。
     */
    suspend fun listAll(): List<MarketInfo> {
        return markets.values.map { market ->
            val available = try { market.isAvailable() } catch (_: Exception) { false }
            MarketInfo.fromMarket(market, available)
        }
    }

    /**
     * 列出指定分类的市场元信息。
     */
    suspend fun listByCategory(category: IntegrationCategory): List<MarketInfo> {
        return markets.values
            .filter { it.category == category }
            .map { market ->
                val available = try { market.isAvailable() } catch (_: Exception) { false }
                MarketInfo.fromMarket(market, available)
            }
    }

    /**
     * 检查市场是否已注册。
     */
    fun contains(marketId: String): Boolean = markets.containsKey(marketId)

    /**
     * 已注册市场数。
     */
    fun count(): Int = markets.size

    /**
     * 按分类统计市场数。
     */
    fun countByCategory(category: IntegrationCategory): Int {
        return markets.values.count { it.category == category }
    }

    /**
     * 清空所有注册。
     */
    fun clear() {
        markets.clear()
    }

    /**
     * 通过 Java ServiceLoader 自动发现市场。
     *
     * 业务侧在 `META-INF/services/com.apex.agent.integration.market.IntegrationMarket`
     * 文件中列出市场实现类，调用此方法后自动注册。
     */
    fun autoDiscover() {
        try {
            val loader = ServiceLoader.load(IntegrationMarket::class.java)
            for (market in loader) {
                register(market)
            }
        } catch (_: Exception) {
            // ServiceLoader 不可用时静默降级
        }
    }
}
