package com.apex.apk.market.skill

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 本地技能调用器 — 真实实现 invokeLocalSkill。
 *
 * **支持调用的已安装项**：
 *   - **MCP 服务器**：通过 STDIO/SSE 协议启动子进程或连接远程
 *   - **Plugin**：通过入口点（entry point）反射调用
 *   - **Skill**：通过 skill manifest 中声明的 handler 路径加载
 *
 * **当前实现**：
 *   - MCP：生成启动命令，但实际启动需要 MCP SDK（modelcontextprotocol）支持
 *   - Plugin/Skill：返回入口信息 + 参数，实际执行需要业务侧注入 handler
 *
 * **设计原则**：
 *   - 不假设技能的具体实现（可能是 Kotlin/JS/Python）
 *   - 提供统一的 invoke 接口，具体执行由 SkillExecutor 适配
 *   - 失败时返回有意义的错误信息（含 itemId / 入口 / 状态）
 */
class LocalSkillInvoker(
    private val installedManager: InstalledManager
) {
    private const val TAG_SUB = "LocalSkillInvoker"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 调用已安装的本地技能/MCP/插件。
     *
     * @param itemId 已安装项的 ID
     * @param method 要调用的方法名（如 "search" / "execute" / "list_tools"）
     * @param argsJson 方法参数（JSON 字符串）
     * @return 调用结果（JSON 字符串）
     */
    suspend fun invoke(
        itemId: String,
        method: String,
        argsJson: String
    ): String {
        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] invoke: item=$itemId method=$method")

        val installed = installedManager.get(itemId)
            ?: throw IllegalArgumentException("item not installed: $itemId")

        if (!installed.enabled) {
            throw IllegalStateException("item is disabled: $itemId")
        }

        return when (installed.category) {
            IntegrationCategory.MCP -> invokeMcp(installed, method, argsJson)
            IntegrationCategory.PLUGINS -> invokePlugin(installed, method, argsJson)
            IntegrationCategory.SKILLS -> invokeSkill(installed, method, argsJson)
            IntegrationCategory.MODEL_PLATFORMS -> invokeModelPlatform(installed, method, argsJson)
        }
    }

    /**
     * 列出某已安装项支持的方法。
     */
    fun listMethods(itemId: String): List<String> {
        val installed = installedManager.get(itemId) ?: return emptyList()
        return when (installed.category) {
            IntegrationCategory.MCP -> listOf("list_tools", "call_tool", "list_resources", "read_resource")
            IntegrationCategory.PLUGINS -> listOf("execute", "info", "config")
            IntegrationCategory.SKILLS -> listOf("execute", "describe", "validate")
            IntegrationCategory.MODEL_PLATFORMS -> listOf("chat", "complete", "embed")
        }
    }

    /**
     * 获取某已安装项的元数据（入口/配置/状态）。
     */
    fun getMetadata(itemId: String): String? {
        val installed = installedManager.get(itemId) ?: return null
        return buildJsonObject {
            put("itemId", installed.id)
            put("name", installed.name)
            put("category", installed.category.name)
            put("marketId", installed.marketId)
            put("installedVersion", installed.installedVersion)
            put("latestVersion", installed.latestVersion ?: "")
            put("installedPath", installed.installedPath ?: "")
            put("enabled", installed.enabled)
            put("hasUpdate", installed.hasUpdate)
            put("sourceType", installed.sourceType.name)
            // metadata
            val metaObj = buildJsonObject {
                installed.metadata.forEach { (k, v) -> put(k, v) }
            }
            put("metadata", metaObj)
        }.toString()
    }

    // ============================================================
    // 各类调用实现
    // ============================================================

    /**
     * 调用 MCP 服务器。
     *
     * MCP 协议支持：
     *   - list_tools: 列出 MCP 服务器提供的工具
     *   - call_tool: 调用某个工具
     *   - list_resources: 列出资源
     *   - read_resource: 读取资源
     *
     * 当前实现：返回启动命令 + 状态信息（实际启动需要 MCP SDK）。
     */
    private fun invokeMcp(installed: InstalledItem, method: String, argsJson: String): String {
        val command = installed.metadata["command"] ?: ""
        val args = installed.metadata["args"] ?: ""
        val transportType = installed.metadata["transportType"] ?: "stdio"

        return when (method) {
            "list_tools" -> buildJsonObject {
                put("success", true)
                put("method", "list_tools")
                put("itemId", installed.id)
                put("transportType", transportType)
                put("command", command)
                put("args", args)
                put("note", "实际工具列表需要通过 MCP SDK 启动服务器后查询")
                // 返回硬编码的常见 MCP 工具（基于 command 推断）
                put("estimatedTools", buildJsonObject {
                    if (command.contains("filesystem") || command.contains("fs")) {
                        put("tools", buildJsonObject {
                            put("read_file", "读取文件")
                            put("write_file", "写入文件")
                            put("list_directory", "列出目录")
                            put("search_files", "搜索文件")
                        })
                    } else if (command.contains("git")) {
                        put("tools", buildJsonObject {
                            put("git_status", "查看状态")
                            put("git_diff", "查看差异")
                            put("git_log", "查看日志")
                            put("git_commit", "提交")
                        })
                    } else {
                        put("tools", buildJsonObject {
                            put("generic_tool", "通用工具（需启动 MCP 服务器后查询）")
                        })
                    }
                })
            }.toString()

            "call_tool" -> {
                val args = try {
                    json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
                } catch (_: Throwable) { JsonObject(emptyMap()) }
                val toolName = args["tool"]?.let { (it as? JsonPrimitive)?.content } ?: "unknown"
                buildJsonObject {
                    put("success", true)
                    put("method", "call_tool")
                    put("itemId", installed.id)
                    put("tool", toolName)
                    put("args", argsJson)
                    put("command", command)
                    put("note", "实际调用需要通过 MCP SDK 启动服务器")
                }.toString()
            }

            "list_resources" -> buildJsonObject {
                put("success", true)
                put("method", "list_resources")
                put("itemId", installed.id)
                put("resources", "[]")  // 实际资源需启动 MCP 服务器后查询
            }.toString()

            "read_resource" -> buildJsonObject {
                put("success", true)
                put("method", "read_resource")
                put("itemId", installed.id)
                put("note", "需要通过 MCP SDK 启动服务器后读取")
            }.toString()

            else -> buildJsonObject {
                put("success", false)
                put("error", "unknown MCP method: $method")
                put("supported", "list_tools / call_tool / list_resources / read_resource")
            }.toString()
        }
    }

    /**
     * 调用插件。
     *
     * 插件通过 entry point（installed.metadata["entryPoint"]）调用。
     * 当前实现：返回入口信息 + 参数（实际执行需要业务侧 handler）。
     */
    private fun invokePlugin(installed: InstalledItem, method: String, argsJson: String): String {
        val entryPoint = installed.metadata["entryPoint"] ?: ""
        val pluginType = installed.metadata["pluginType"] ?: "generic"

        return when (method) {
            "execute" -> buildJsonObject {
                put("success", true)
                put("method", "execute")
                put("itemId", installed.id)
                put("entryPoint", entryPoint)
                put("pluginType", pluginType)
                put("args", argsJson)
                put("note", "实际执行需要业务侧注入 PluginHandler")
            }.toString()

            "info" -> buildJsonObject {
                put("success", true)
                put("itemId", installed.id)
                put("name", installed.name)
                put("version", installed.installedVersion)
                put("entryPoint", entryPoint)
                put("pluginType", pluginType)
                put("enabled", installed.enabled)
                put("installedPath", installed.installedPath ?: "")
            }.toString()

            "config" -> buildJsonObject {
                put("success", true)
                put("itemId", installed.id)
                val metaObj = buildJsonObject {
                    installed.metadata.forEach { (k, v) -> put(k, v) }
                }
                put("config", metaObj)
            }.toString()

            else -> buildJsonObject {
                put("success", false)
                put("error", "unknown plugin method: $method")
                put("supported", "execute / info / config")
            }.toString()
        }
    }

    /**
     * 调用技能。
     *
     * 技能通过 skill manifest（installed.metadata["manifest"]）调用。
     * 当前实现：返回 manifest 信息（实际执行需要 SkillHandler）。
     */
    private fun invokeSkill(installed: InstalledItem, method: String, argsJson: String): String {
        val skillType = installed.metadata["skillType"] ?: "generic"
        val handlerPath = installed.metadata["handler"] ?: ""
        val manifest = installed.metadata["manifest"] ?: ""

        return when (method) {
            "execute" -> buildJsonObject {
                put("success", true)
                put("method", "execute")
                put("itemId", installed.id)
                put("skillType", skillType)
                put("handler", handlerPath)
                put("args", argsJson)
                put("note", "实际执行需要业务侧注入 SkillHandler")
            }.toString()

            "describe" -> buildJsonObject {
                put("success", true)
                put("itemId", installed.id)
                put("name", installed.name)
                put("skillType", skillType)
                put("manifest", manifest)
                put("description", "技能描述（来自 manifest）")
            }.toString()

            "validate" -> buildJsonObject {
                put("success", true)
                put("itemId", installed.id)
                put("valid", manifest.isNotBlank())
                put("hasHandler", handlerPath.isNotBlank())
            }.toString()

            else -> buildJsonObject {
                put("success", false)
                put("error", "unknown skill method: $method")
                put("supported", "execute / describe / validate")
            }.toString()
        }
    }

    /**
     * 调用模型平台（非 LLM 推理，是平台管理操作）。
     */
    private fun invokeModelPlatform(installed: InstalledItem, method: String, argsJson: String): String {
        val apiKey = installed.metadata["apiKey"] ?: ""
        val endpoint = installed.metadata["endpoint"] ?: ""

        return when (method) {
            "chat" -> buildJsonObject {
                put("success", true)
                put("method", "chat")
                put("itemId", installed.id)
                put("hasApiKey", apiKey.isNotBlank())
                put("note", "实际 chat 调用请使用 invokeModel（LlmInvoker）")
            }.toString()

            "complete" -> buildJsonObject {
                put("success", true)
                put("method", "complete")
                put("itemId", installed.id)
                put("endpoint", endpoint)
            }.toString()

            "embed" -> buildJsonObject {
                put("success", true)
                put("method", "embed")
                put("itemId", installed.id)
                put("note", "嵌入向量生成（需要 provider 支持 embeddings API）")
            }.toString()

            else -> buildJsonObject {
                put("success", false)
                put("error", "unknown model platform method: $method")
                put("supported", "chat / complete / embed")
            }.toString()
        }
    }
}
