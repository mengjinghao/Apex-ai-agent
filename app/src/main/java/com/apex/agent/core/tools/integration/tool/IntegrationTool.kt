package com.apex.agent.core.tools.integration.tool

import android.content.Context
import com.apex.agent.core.tools.integration.IntegrationManager
import com.apex.core.tools.ToolAdapter
import com.apex.core.tools.ToolParameter
import com.apex.core.tools.ToolResultData
import com.apex.core.tools.StringResultData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.apex.agent.core.tools.defaultTool.debugger.name

/**
 * 统一集成管理工具 —?AI Agent 通过此工具管理所有集成源
 *
 * 用法:
 *   integration action=list                             列出所有可用集? *   integration action=open provider=mcp_so             打开某个集成查看详情和功? *   integration action=search query=翻译 source=all     跨源搜索
 *   integration action=browse provider=mcp_so tag=featured  浏览某个源的内容
 *   integration action=install provider=mcp_so id=xxx   安装
 *   integration action=uninstall provider=mcp_so id=xxx 卸载
 *   integration action=list_installed provider=all      列出所有已安装
 */
class IntegrationTool(private val context: Context) : ToolAdapter {

    companion object {
        private const val TAG = "IntegrationTool"
        const val TOOL_NAME = "integration"
    }

    override fun getName(): String = TOOL_NAME

    override fun getDescription(): String =
        "统一集成管理中心。管理所有平台集成（mcp.so、LobeHub、技能仓库等? +
        "支持列出集成、打开查看详情、跨源搜索、安装卸载、浏览内容?

    override fun isAvailable(): Boolean = true

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "action",
            type = "string",
            description = "操作类型：list（列出集成）、open（打开集成查看功能）? +
                    "search（搜索）、browse（浏览内容）? +
                    "install（安装）、uninstall（卸载）? +
                    "list_installed（已安装列表）、categories（分类列表）",
            required = true
        ),
        ToolParameter(
            name = "provider",
            type = "string",
            description = "集成源ID：mcp_so、lobehub、skill_repo、plugin、all（全部）",
            required = false
        ),
        ToolParameter(
            name = "query",
            type = "string",
            description = "搜索关键?,
            required = false
        ),
        ToolParameter(
            name = "tag",
            type = "string",
            description = "分类/标签过滤，如 featured、popular、latest",
            required = false
        ),
        ToolParameter(
            name = "id",
            type = "string",
            description = "项目ID（用于安卸载/详情?,
            required = false
        ),
        ToolParameter(
            name = "page",
            type = "integer",
            description = "页码，默?",
            required = false,
            defaultValue = 1
        ),
        ToolParameter(
            name = "page_size",
            type = "integer",
            description = "每页数量，默?0",
            required = false,
            defaultValue = 20
        )
    )

    override suspend fun execute(parameters: Map<String, Any>): ToolResultData {
        val action = (parameters["action"] as? String)?.trim()?.lowercase() ?: ""
        if (action.isBlank()) {
            return StringResultData("错误：缺?action 参数\n可用操作：list, open, search, browse, install, uninstall, list_installed, categories")
        }

        return when (action) {
            "list" -> executeList()
            "open" -> executeOpen(parameters)
            "search" -> executeSearch(parameters)
            "browse" -> executeBrowse(parameters)
            "install" -> executeInstall(parameters)
            "uninstall" -> executeUninstall(parameters)
            "list_installed" -> executeListInstalled(parameters)
            "categories" -> executeCategories(parameters)
            else -> StringResultData("未知操作: $action\n可用操作：list, open, search, browse, install, uninstall, list_installed, categories")
        }
    }

    private fun executeList(): ToolResultData {
        val integrations = IntegrationManager.getAllIntegrations()
        if (integrations.isEmpty()) {
            return StringResultData("暂无已注册的集成")
        }
        return StringResultData(buildString {
            appendLine("📦 集成管理中心 ??${integrations.size} 个集?)
            appendLine()
            integrations.forEachIndexed { i, info ->
                appendLine("${i + 1}. ${info.name} (${info.id})")
                appendLine("   描述: ${info.description}")
                appendLine("   功能: ${info.capabilities.size} ??${info.capabilities.joinToString(", ") { it.name }}")
                appendLine("   状? ${if (info.enabled) "已启? else "已禁?}")
                appendLine()
            }
            appendLine("使用 action=open provider=xxx 查看集成的详细功?)
            appendLine("使用 action=search query=xxx source=all 跨源搜索")
        })
    }

    private fun executeOpen(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim() ?: ""
        if (providerId.isBlank()) return StringResultData("错误：缺?provider 参数")

        val provider = IntegrationManager.getProvider(providerId)
            ?: return StringResultData("未找到集? $providerId\n可用集成: ${IntegrationManager.getAllIntegrations().joinToString(", ") { "${it.name}(${it.id})" }}")

        val info = provider.getInfo()
        return StringResultData(buildString {
            appendLine("📋 ${info.name} 集成详情")
            appendLine("─".repeat(40))
            appendLine("ID: ${info.id}")
            appendLine("描述: ${info.description}")
            appendLine("作? ${info.author}")
            appendLine("版本: ${info.version}")
            if (info.homepage != null) appendLine("主页: ${info.homepage}")
            appendLine("状? ${if (info.enabled) "已启? else "已禁?}")
            appendLine("项目? ${info.itemCount}")
            appendLine("已安? ${info.installedCount}")
            appendLine()
            appendLine("🔧 可用功能:")
            info.capabilities.forEach { cap ->
                appendLine("  ?${cap.name} ?${cap.description}")
            }
            appendLine()
            appendLine("使用示例:")
            appendLine("  integration action=browse provider=${info.id} tag=featured")
            appendLine("  integration action=search provider=${info.id} query=翻译")
            appendLine("  integration action=install provider=${info.id} id=xxx")
            appendLine("  integration action=categories provider=${info.id}")
        })
    }

    private suspend fun executeSearch(parameters: Map<String, Any>): ToolResultData {
        val query = (parameters["query"] as? String)?.trim() ?: ""
        if (query.isBlank()) return StringResultData("错误：缺?query 参数")

        val sourceFilter = (parameters["provider"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        return withContext(Dispatchers.IO) {
            val results = IntegrationManager.searchAll(query, sourceFilter)
            if (results.isEmpty()) {
                StringResultData("未找到匹?\"$query\" 的结?)
            } else {
                StringResultData(buildString {
                    appendLine("搜索结果: \"$query\"（共 ${results.size} 项）")
                    appendLine()
                    results.take(30).forEachIndexed { i, item ->
                        appendLine("${i + 1}. [${item.source.name}] ${item.name}")
                        appendLine("   类型: ${item.type.displayName} | 作? ${item.author}")
                        appendLine("   描述: ${item.description.take(80)}")
                        if (item.isInstalled) appendLine("   状? 已安?)
                        appendLine()
                    }
                    if (results.size > 30) {
                        appendLine("...以及 ${results.size - 30} 项更多结?)
                    }
                    appendLine("使用 action=install provider=${results.first().source.id} id=${results.first().sourceId} 安装")
                })
            }
        }
    }

    private suspend fun executeBrowse(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim() ?: ""
        if (providerId.isBlank()) return StringResultData("错误：缺?provider 参数")

        val provider = IntegrationManager.getProvider(providerId)
            ?: return StringResultData("未找到集? $providerId")

        val tag = (parameters["tag"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val page = (parameters["page"] as? Number)?.toInt() ?: 1
        val pageSize = (parameters["page_size"] as? Number)?.toInt() ?: 20

        return withContext(Dispatchers.IO) {
            provider.list(tag, page, pageSize).fold(
                onSuccess = { items ->
                    if (items.isEmpty()) {
                        StringResultData("${provider.getInfo().name} 暂无内容${tag?.let { "（分? $it? } ?: ""}")
                    } else {
                        StringResultData(buildString {
                            appendLine("${provider.getInfo().name} ?${tag?.let { "分类: $it" } ?: "全部"}（第 $page 页，?${items.size} 项）")
                            appendLine()
                            items.forEachIndexed { i, item ->
                                appendLine("${i + 1}. ${item.name}")
                                appendLine("   类型: ${item.type.displayName} | 作? ${item.author}")
                                appendLine("   描述: ${item.description.take(80)}")
                                if (item.tags.isNotEmpty()) {
                                    appendLine("   标签: ${item.tags.take(5).joinToString(", ")}")
                                }
                                if (item.isInstalled) appendLine("   状? 已安?)
                                appendLine()
                            }
                            appendLine("使用 action=search provider=$providerId query=关键搜索")
                            appendLine("使用 action=install provider=$providerId id=${items.first().sourceId} 安装")
                        })
                    }
                },
                onFailure = { e ->
                    StringResultData("浏览失败: ${e.message}")
                }
            )
        }
    }

    private suspend fun executeInstall(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim() ?: ""
        val itemId = (parameters["id"] as? String)?.trim() ?: ""

        if (providerId.isBlank()) return StringResultData("错误：缺?provider 参数")
        if (itemId.isBlank()) return StringResultData("错误：缺?id 参数")

        return withContext(Dispatchers.IO) {
            IntegrationManager.install(providerId, itemId).fold(
                onSuccess = { msg -> StringResultData("?$msg") },
                onFailure = { e -> StringResultData("安装失败: ${e.message}") }
            )
        }
    }

    private suspend fun executeUninstall(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim() ?: ""
        val itemId = (parameters["id"] as? String)?.trim() ?: ""

        if (providerId.isBlank()) return StringResultData("错误：缺?provider 参数")
        if (itemId.isBlank()) return StringResultData("错误：缺?id 参数")

        return withContext(Dispatchers.IO) {
            IntegrationManager.uninstall(providerId, itemId).fold(
                onSuccess = { msg -> StringResultData("?$msg") },
                onFailure = { e -> StringResultData("卸载失败: ${e.message}") }
            )
        }
    }

    private suspend fun executeListInstalled(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim()?.takeIf { it.isNotBlank() }

        val targets = if (providerId != null) {
            listOfNotNull(IntegrationManager.getProvider(providerId))
        } else {
            IntegrationManager.getAvailableProviders()
        }

        if (targets.isEmpty()) return StringResultData("暂无已安装的项目")

        return withContext(Dispatchers.IO) {
            val output = buildString {
                appendLine("📦 已安装项目列?)
                appendLine()
                for (provider in targets) {
                    provider.listInstalled().onSuccess { items ->
                        if (items.isNotEmpty()) {
                            appendLine("?{provider.getInfo().name}】?${items.size} ?)
                            items.forEachIndexed { i, item ->
                                appendLine("  ${i + 1}. ${item.name} (${item.type.displayName})")
                                if (item.author.isNotBlank()) appendLine("     作? ${item.author}")
                            }
                            appendLine()
                        }
                    }
                }
            }
            StringResultData(output.ifBlank { "暂无已安装的项目" })
        }
    }

    private suspend fun executeCategories(parameters: Map<String, Any>): ToolResultData {
        val providerId = (parameters["provider"] as? String)?.trim() ?: ""
        if (providerId.isBlank()) return StringResultData("错误：缺?provider 参数")

        val provider = IntegrationManager.getProvider(providerId)
            ?: return StringResultData("未找到集? $providerId")

        return withContext(Dispatchers.IO) {
            provider.getCategories().fold(
                onSuccess = { cats ->
                    StringResultData(buildString {
                        appendLine("${provider.getInfo().name} 分类列表")
                        appendLine()
                        cats.forEachIndexed { i, cat -> appendLine("${i + 1}. $cat") }
                        appendLine()
                        appendLine("使用 action=browse provider=$providerId tag=分类浏览")
                    })
                },
                onFailure = { e ->
                    StringResultData("获取分类失败: ${e.message}")
                }
            )
        }
    }
}
