package com.apex.agent.core.tools.integration.provider

import android.content.Context
import com.apex.agent.core.tools.integration.CapabilityType
import com.apex.agent.core.tools.integration.IntegrationCapability
import com.apex.agent.core.tools.integration.IntegrationInfo
import com.apex.agent.core.tools.integration.IntegrationProvider
import com.apex.agent.core.tools.integration.IntegrationSource
import com.apex.agent.core.tools.integration.ItemType
import com.apex.agent.core.tools.integration.UnifiedItem
import com.apex.data.api.mcpso.MCPSoApiClient
import com.apex.util.AppLogger
import com.apex.data.api.mcpso.MCPSoServer
import com.apex.data.mcp.MCPLocalServer
import com.apex.data.mcp.MCPRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.apex.agent.core.tools.defaultTool.standard.name

private const val TAG = "McpSoIntegration"

class McpSoIntegration(private val context: Context) : IntegrationProvider {

    private val apiClient = MCPSoApiClient(context)
        private val gson = Gson()
        private val source = IntegrationSource.MCP_SO

    override fun getInfo(): IntegrationInfo = IntegrationInfo(
        id = source.id,
        name = source.name,
        description = "MCP 服务器市场，浏览和安装来mcp.so 生态的 MCP Server",
        version = "1.0.0",
        author = "mcp.so",
        homepage = "https://mcp.so",
        logoUrl = null,
        enabled = true,
        capabilities = listOf(
            IntegrationCapability("browse", "浏览服务, "按分类浏MCP 服务, CapabilityType.BROWSE),
            IntegrationCapability("search", "搜索服务, "搜索 mcp.so 上的 MCP 服务, CapabilityType.SEARCH),
            IntegrationCapability("install", "安装服务, "?mcp.so 安装 MCP 服务, CapabilityType.INSTALL),
            IntegrationCapability("uninstall", "卸载服务, "卸载已安装的 MCP 服务, CapabilityType.UNINSTALL),
            IntegrationCapability("list_installed", "已安装列, "列出已从 mcp.so 安装的服务器", CapabilityType.BROWSE),
            IntegrationCapability("categories", "分类浏览", "按标签分类浏览服务器", CapabilityType.CATEGORIES)
        ),
        itemCount = 0,
        installedCount = getInstalledCount()
    )

    override fun isAvailable(): Boolean = true

    override suspend fun list(tag: String?, page: Int, pageSize: Int): Result<List<UnifiedItem>> {
        return runBlocking(Dispatchers.IO) {
            apiClient.getServers(tag).fold(
                onSuccess = { servers ->
                    val paged = servers.drop((page - 1) * pageSize).take(pageSize)
                    Result.success(paged.map { it.toUnifiedItem() })
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun search(query: String, filters: Map<String, String>): Result<List<UnifiedItem>> {
        return runBlocking(Dispatchers.IO) {
            apiClient.searchServers(query).fold(
                onSuccess = { servers -> Result.success(servers.map { it.toUnifiedItem() }) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun getDetail(id: String): Result<UnifiedItem> {
        return runBlocking(Dispatchers.IO) {
            // search by exact name/id first
            apiClient.searchServers(id).fold(
                onSuccess = { servers ->
                    val match = servers.firstOrNull { it.id == id || it.name == id }
        if (match != null) {
                        if (!match.canInstall) {
                            apiClient.getServerDetail(match).fold(
                                onSuccess = { Result.success(it.toUnifiedItem()) },
                                onFailure = { Result.success(match.toUnifiedItem()) }
                            )
                        } else {
                            Result.success(match.toUnifiedItem())
                        }
                    } else {
                        Result.failure(Exception("未找到服务器: $id"))
                    }
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun install(item: UnifiedItem): Result<String> {
        return runBlocking(Dispatchers.IO) {
            val searchResult = apiClient.searchServers(item.name)
            searchResult.getOrNull()?.firstOrNull()?.let { server ->
                val targetServer = if (!server.canInstall) {
                    apiClient.getServerDetail(server).getOrNull() ?: server
                } else server

                if (targetServer.canInstall && targetServer.configJson != null) {
                    val localServer = MCPLocalServer.getInstance(context)
                    localServer.mergeConfigFromJson(targetServer.configJson).map { count ->
                        val metadata = MCPLocalServer.PluginMetadata(
                            id = "mcpso_${targetServer.id}",
                            name = targetServer.name,
                            description = targetServer.description,
                            logoUrl = targetServer.logoUrl ?: "",
                            author = targetServer.author,
                            isInstalled = true,
                            version = "1.0.0",
                            updatedAt = "",
                            longDescription = targetServer.description,
                            repoUrl = targetServer.serverUrl,
                            type = "local",
                            marketConfig = targetServer.configJson
                        )
                        localServer.addOrUpdatePluginMetadata(metadata)
                        MCPRepository(context).refreshPluginList()
                        "成功安装 ${targetServer.name}（已添加 $count 个配置）"
                    }
                } else {
                    Result.failure(Exception("该服务器没有可用的安装配))
                }
            } ?: Result.failure(Exception("未找到服务器: ${item.name}"))
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun uninstall(installedId: String): Result<String> {
        return runBlocking(Dispatchers.IO) {
            val localServer = MCPLocalServer.getInstance(context)
        val allMetadata = localServer.getAllPluginMetadata()
        val target = allMetadata.values.find {
                it.id == installedId || it.name.equals(installedId, ignoreCase = true)
            }
            target?.let { meta ->
                if (meta.marketConfig != null) {
                    try {
                        val config = gson.fromJson(meta.marketConfig, McpServersConfig::class.java)
                        config.mcpServers.keys.forEach { localServer.removeMCPServer(it) }
                    } catch (e: Exception) { AppLogger.e(TAG, "Failed to remove MCP servers for ${meta.id}", e) }
                }
                localServer.removePluginMetadata(meta.id)
                MCPRepository(context).refreshPluginList()
                Result.success("已卸 ${meta.name}")
            } ?: Result.failure(Exception("未找到已安装的服务器: $installedId"))
        }
    }

    override suspend fun checkUpdate(item: UnifiedItem): Result<UnifiedItem?> {
        return Result.success(null)
    }

    override suspend fun listInstalled(): Result<List<UnifiedItem>> {
        return runBlocking(Dispatchers.IO) {
            val localServer = MCPLocalServer.getInstance(context)
        val allMetadata = localServer.getAllPluginMetadata()
        val items = allMetadata.values
                .filter { it.marketConfig != null || it.id.startsWith("mcpso_") }
                .map { meta ->
                    UnifiedItem(
                        source = source,
                        sourceId = meta.id.removePrefix("mcpso_"),
                        name = meta.name,
                        type = ItemType.MCP_SERVER,
                        description = meta.description,
                        author = meta.author,
                        version = meta.version,
                        installedId = meta.id,
                        isInstalled = true,
                        logoUrl = meta.logoUrl
                    )
                }
            Result.success(items)
        }
    }

    override suspend fun getCategories(): Result<List<String>> {
        return runBlocking(Dispatchers.IO) {
            apiClient.getServers(null).fold(
                onSuccess = { servers ->
                    val tags = servers.flatMap { it.tags }.distinct().sorted().take(20)
                    Result.success(tags)
                },
                onFailure = { Result.success(listOf("featured", "latest", "hosted", "official")) }
            )
        }
    }
        private fun MCPSoServer.toUnifiedItem() = UnifiedItem(
        source = source,
        sourceId = id,
        name = name,
        type = ItemType.MCP_SERVER,
        description = description,
        author = author,
        version = "1.0.0",
        tags = tags,
        installConfig = configJson,
        homepage = serverUrl,
        logoUrl = logoUrl
    )
        private fun getInstalledCount(): Int {
        return try {
            val localServer = MCPLocalServer.getInstance(context)
            localServer.getAllPluginMetadata().values.count {
                it.marketConfig != null || it.id.startsWith("mcpso_")
            }
        } catch (_: Exception) { 0 }
    }
        private data class McpServersConfig(val mcpServers: Map<String, Any?> = emptyMap())
}
