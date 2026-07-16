package com.apex.agent.core.tools.integration

/**
 * 集成源提供者接�?—�每个市场/平台实现此接口接入统一集成系统
 */
interface IntegrationProvider {

    fun getInfo(): IntegrationInfo

    fun isAvailable(): Boolean

    suspend fun list(tag: String?, page: Int, pageSize: Int): Result<List<UnifiedItem>>

    suspend fun search(query: String, filters: Map<String, String>): Result<List<UnifiedItem>>

    suspend fun getDetail(id: String): Result<UnifiedItem>

    suspend fun install(item: UnifiedItem): Result<String>

    suspend fun uninstall(installedId: String): Result<String>

    suspend fun checkUpdate(item: UnifiedItem): Result<UnifiedItem?>

    suspend fun listInstalled(): Result<List<UnifiedItem>>

    suspend fun getCategories(): Result<List<String>>
}
