package com.apex.agent.integration.config

import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.api.IntegrationCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 配置生成器。
 *
 * 将已安装的 MCP 服务器一键生成标准配置 JSON，
 * 适配 Claude Desktop / Cursor / Apex-Agent 等客户端。
 *
 * # 支持的配置格式
 *
 * - **Claude Desktop**: `~/Library/Application Support/Claude/claude_desktop_config.json`
 * - **Cursor**: `.cursor/mcp.json`
 * - **Apex-Agent**: `apex_config.json` 中的 `mcpServers` 节点
 * - **通用**: 标准 `mcpServers` JSON 格式
 *
 * # 使用示例
 *
 * ```
 * val generator = McpConfigGenerator(installedManager)
 *
 * // 生成 Claude Desktop 配置
 * val claudeConfig = generator.generate(ConfigFormat.CLAUDE_DESKTOP)
 *
 * // 生成 Apex-Agent 配置
 * val apexConfig = generator.generate(ConfigFormat.APEX_AGENT)
 *
 * // 只含指定 MCP
 * val partial = generator.generate(ConfigFormat.GENERIC, itemIds = listOf("smithery:filesystem"))
 * ```
 */
class McpConfigGenerator(
    private val installedManager: InstalledManager
) {

    /**
     * 配置格式。
     */
    enum class ConfigFormat(val displayName: String, val fileHint: String) {
        /** Claude Desktop 配置 */
        CLAUDE_DESKTOP("Claude Desktop", "claude_desktop_config.json"),
        /** Cursor 配置 */
        CURSOR("Cursor", ".cursor/mcp.json"),
        /** Apex-Agent 配置 */
        APEX_AGENT("Apex-Agent", "apex_config.json"),
        /** 通用 mcpServers 格式 */
        GENERIC("通用", "mcp_servers.json")
    }

    /**
     * 生成配置 JSON。
     *
     * @param format 目标格式
     * @param itemIds 指定 MCP 项 ID 列表（空表示全部已安装的 MCP）
     * @return 配置 JSON 字符串
     */
    fun generate(format: ConfigFormat, itemIds: List<String> = emptyList()): String {
        val mcpItems = if (itemIds.isEmpty()) {
            installedManager.getByCategory(IntegrationCategory.MCP)
        } else {
            itemIds.mapNotNull { installedManager.get(it) }
        }.filter { it.enabled }

        val mcpServers = JSONObject()
        for (item in mcpItems) {
            val serverConfig = buildServerConfig(item)
            // 用 name 作为 key（去掉前缀）
            val serverName = item.name.lowercase().replace(" ", "_").replace(".", "_")
            mcpServers.put(serverName, serverConfig)
        }

        return when (format) {
            ConfigFormat.CLAUDE_DESKTOP -> {
                // Claude Desktop: { "mcpServers": { ... } }
                JSONObject().put("mcpServers", mcpServers).toString(2)
            }
            ConfigFormat.CURSOR -> {
                // Cursor: { "mcpServers": { ... } }
                JSONObject().put("mcpServers", mcpServers).toString(2)
            }
            ConfigFormat.APEX_AGENT -> {
                // Apex-Agent: { "mcpServers": { ... }, "autoStart": true }
                JSONObject().apply {
                    put("mcpServers", mcpServers)
                    put("autoStart", true)
                    put("version", "1.0.0")
                }.toString(2)
            }
            ConfigFormat.GENERIC -> {
                mcpServers.toString(2)
            }
        }
    }

    /**
     * 生成单个 MCP 服务器的配置。
     *
     * @param item 已安装的 MCP 项
     * @return 单个服务器配置 JSON
     */
    fun generateSingle(item: InstalledItem): String {
        return buildServerConfig(item).toString(2)
    }

    /**
     * 获取所有已安装 MCP 的配置预览（不生成 JSON，返回 Map 便于 UI 展示）。
     */
    fun getPreview(): Map<String, Map<String, Any>> {
        val mcpItems = installedManager.getByCategory(IntegrationCategory.MCP).filter { it.enabled }
        return mcpItems.associate { item ->
            val serverName = item.name.lowercase().replace(" ", "_").replace(".", "_")
            serverName to mapOf(
                "command" to (item.metadata["command"] ?: item.metadata["downloadUrl"] ?: ""),
                "transport" to (item.metadata["transport"] ?: "stdio"),
                "enabled" to item.enabled,
                "version" to item.installedVersion
            )
        }
    }

    /**
     * 检查配置是否完整（所有已安装 MCP 都有有效配置）。
     *
     * @return 不完整的项列表（itemId -> 缺失字段）
     */
    fun validateConfigs(): Map<String, List<String>> {
        val issues = mutableMapOf<String, List<String>>()
        val mcpItems = installedManager.getByCategory(IntegrationCategory.MCP).filter { it.enabled }

        for (item in mcpItems) {
            val missing = mutableListOf<String>()
            val command = item.metadata["command"] ?: item.metadata["downloadUrl"]
            if (command.isNullOrBlank()) missing.add("command")
            val transport = item.metadata["transport"]
            if (transport.isNullOrBlank()) missing.add("transport")

            // SSE/HTTP 类型需要 url
            if (transport == "sse" || transport == "streamable-http") {
                val url = item.metadata["url"] ?: item.metadata["endpoint"]
                if (url.isNullOrBlank()) missing.add("url")
            }

            if (missing.isNotEmpty()) {
                issues[item.id] = missing
            }
        }

        return issues
    }

    /**
     * 构建单个服务器配置对象。
     */
    private fun buildServerConfig(item: InstalledItem): JSONObject {
        val config = JSONObject()
        val transport = item.metadata["transport"] ?: "stdio"
        val command = item.metadata["command"] ?: item.metadata["downloadUrl"] ?: ""

        when (transport) {
            "stdio" -> {
                // STDIO 类型：command + args + env
                val parts = command.split(" ", limit = 2)
                config.put("command", parts[0])
                if (parts.size > 1) {
                    val args = JSONArray()
                    parts[1].split(" ").filter { it.isNotBlank() }.forEach { args.put(it) }
                    if (args.length() > 0) config.put("args", args)
                }

                // 环境变量
                val env = item.metadata["env"]
                if (!env.isNullOrBlank()) {
                    val envObj = JSONObject()
                    env.split(",").forEach { pair ->
                        val kv = pair.split("=", limit = 2)
                        if (kv.size == 2) envObj.put(kv[0].trim(), kv[1].trim())
                    }
                    if (envObj.length() > 0) config.put("env", envObj)
                }
            }
            "sse" -> {
                // SSE 类型：url
                config.put("url", item.metadata["url"] ?: item.metadata["endpoint"] ?: item.metadata["downloadUrl"] ?: "")
                config.put("transport", "sse")
            }
            "streamable-http" -> {
                // Streamable HTTP 类型：url
                config.put("url", item.metadata["url"] ?: item.metadata["endpoint"] ?: item.metadata["downloadUrl"] ?: "")
                config.put("transport", "streamable-http")
            }
            else -> {
                // 默认 STDIO
                config.put("command", command)
            }
        }

        return config
    }
}
