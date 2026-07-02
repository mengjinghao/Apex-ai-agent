package com.apex.api.chat.enhance

import android.content.Context
import com.apex.agent.R
import com.apex.util.AppLogger
import com.apex.core.tools.AIToolHandler
import com.apex.core.tools.StringResultData
import com.apex.core.tools.ToolExecutor
import com.apex.data.model.ToolInvocation
import com.apex.data.model.ToolResult
import com.apex.core.tools.packTool.PackageManager
import com.apex.util.stream.StreamCollector

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.apex.data.model.AITool
import com.apex.data.model.ToolParameter
import com.apex.ui.common.displays.MessageContentParser
import com.apex.util.ChatMarkupRegex
import com.apex.util.stream.plugins.StreamXmlPlugin
import com.apex.util.stream.splitBy
import com.apex.util.stream.stream
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import com.apex.core.tools.ToolAdapterManager

/** Utility class for managing tool executions */
object ToolExecutionManager {
    private const val TAG = "ToolExecutionManager"
    private const val PACKAGE_PROXY_TOOL_NAME = "package_proxy"
    private const val PACKAGE_CALLER_NAME_PARAM = "__Apex_package_caller_name"
    private const val PACKAGE_CHAT_ID_PARAM = "__Apex_package_chat_id"


    private data class ResolvedToolTarget(
        val tool: AITool,
        val displayName: String
    )

    private fun ensureEndsWithNewline(content: String): String {
        return if (content.endsWith("\n")) content else "${content}\n"
    }

    private fun resolveToolTarget(tool: AITool): ResolvedToolTarget {
        if (tool.name != PACKAGE_PROXY_TOOL_NAME) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        if (targetToolName.isBlank()) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val forwardedParameters = resolveProxyParameters(tool)
        return ResolvedToolTarget(
            tool = AITool(name = targetToolName, parameters = forwardedParameters),
            displayName = targetToolName
        )
    }

    private fun resolveDisplayToolName(tool: AITool): String {
        return resolveToolTarget(tool).displayName
    }

    private fun isJsPackageTool(toolName: String, jsPackageNames: Set<String>): Boolean {
        val toolNameParts = toolName.split(':', limit = 2)
        val packageName = toolNameParts.getOrNull(0)
        return toolNameParts.size == 2 &&
            packageName != null &&
            jsPackageNames.contains(packageName)
    }

    private fun addPackageContextParamIfMissing(
        params: MutableList<ToolParameter>,
        name: String,
        value: String?
    ) {
        if (value.isNullOrBlank()) {
            return
        }
        if (params.any { it.name == name }) {
            return
        }
        params.add(ToolParameter(name, value))
    }

    private fun injectPackageCallContext(
        invocation: ToolInvocation,
        jsPackageNames: Set<String>,
        callerName: String?,
        callerChatId: String?
    ): ToolInvocation {
        val resolvedTargetTool = resolveToolTarget(invocation.tool).tool
        if (!isJsPackageTool(resolvedTargetTool.name, jsPackageNames)) {
            return invocation
        }

        val updatedParams = invocation.tool.parameters.toMutableList()
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CALLER_NAME_PARAM, callerName)
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CHAT_ID_PARAM, callerChatId)

        if (updatedParams.size == invocation.tool.parameters.size) {
            return invocation
        }

        return invocation.copy(
            tool = invocation.tool.copy(parameters = updatedParams)
        )
    }

    private fun getParameterValue(tool: AITool, name: String): String? {
        return tool.parameters.firstOrNull { it.name == name }?.value?.trim()
    }



    private fun resolveProxyParameters(tool: AITool): List<ToolParameter> {
        val paramsRaw = tool.parameters
            .firstOrNull { it.name == "params" }
            ?.value
            ?.trim()
            .orEmpty()
        if (paramsRaw.isBlank()) {
            return emptyList()
        }

        val paramsObject = runCatching { JSONObject(paramsRaw) }.getOrNull() ?: return emptyList()
        val forwardedParameters = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = paramsObject.opt(key)
            val valueString = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> value
                else -> value.toString()
            }
            forwardedParameters.add(ToolParameter(name = key, value = valueString))
        }
        return forwardedParameters
    }

    /**
     * ，AI 响应中提取工具调用了     * @param response AI 的响应字符串�?    * @return 检测到的工具调用列表，     */
    suspend fun extractToolInvocations(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()
        val content = response

        val charStream = content.stream()
        val plugins = listOf(StreamXmlPlugin())

        charStream.splitBy(plugins).collect { group ->
            val chunkContent = StringBuilder()
            group.stream.collect { chunk -> chunkContent.append(chunk) }
            val chunkString = chunkContent.toString()

            if (chunkString.isEmpty()) return@collect

            if (group.tag is StreamXmlPlugin) {
                ChatMarkupRegex.toolCallPattern.findAll(chunkString).forEach { toolMatch ->
                    val toolName = toolMatch.groupValues.getOrNull(2) ?: return@forEach
                    val toolBody = toolMatch.groupValues.getOrNull(3).orEmpty()

                    val parameters = mutableListOf<ToolParameter>()
                    MessageContentParser.toolParamPattern.findAll(toolBody)
                        .forEach { paramMatch ->
                            val paramName = paramMatch.groupValues[1]
                            val paramValue = paramMatch.groupValues[2]
                            parameters.add(ToolParameter(paramName, unescapeXml(paramValue)))
                        }

                    val tool = AITool(name = toolName, parameters = parameters)
                    invocations.add(
                        ToolInvocation(
                            tool = tool,
                            rawText = toolMatch.value,
                            responseLocation = toolMatch.range
                        )
                    )
                }
            }
        }

        AppLogger.d(
            TAG,
            "Found ${invocations.size} tool invocations: ${invocations.map { resolveDisplayToolName(it.tool) }}"
        )
        return invocations
    }

    /**
     * Unescapes XML special characters
     * @param input The XML escaped string
     * @return Unescaped string
     */
    private fun unescapeXml(input: String): String {
        var result = input

        // 处理 CDATA 标记
        if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
            result = result.substring(9, result.length - 3)
        }

        // 即使没有完整，CDATA 标记，也尝试清理末尾，]]> 和开头的 <![CDATA[
        if (result.endsWith("]]>")) {
            result = result.substring(0, result.length - 3)
        }

        if (result.startsWith("<![CDATA[")) {
            result = result.substring(9)
        }

        return result.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * Execute a tool safely, with parameter validation
     *
     * @param invocation The tool invocation to execute
     * @param executor The tool executor to use
     * @return The result of the tool execution
     */
    fun executeToolSafely(
        invocation: ToolInvocation,
        executor: ToolExecutor,
        toolHandler: AIToolHandler? = null
    ): Flow<ToolResult> {
        val validationResult = executor.validateParameters(invocation.tool)
        if (!validationResult.valid) {
            return flow {
                emit(
                    ToolResult(
                        toolName = invocation.tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Invalid parameters: ${validationResult.errorMessage}"
                    )
                )
            }
        }

        return executor.invokeAndStream(invocation.tool).catch { e ->
            AppLogger.e(TAG, "Tool execution error: ${invocation.tool.name}", e)
            toolHandler?.notifyToolExecutionError(invocation.tool, e)
            emit(
                ToolResult(
                    toolName = invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool execution error: ${e.message}"
                )
            )
        }
    }

    /**
     * Check if a tool requires permission and verify if it has permission
     *
     * @param toolHandler The AIToolHandler instance to use for permission checks
     * @param invocation The tool invocation to check permissions for
     * @return A pair containing (has permission, error result if no permission)
     */
    suspend fun checkToolPermission(
        toolHandler: AIToolHandler,
        invocation: ToolInvocation
    ): Pair<Boolean, ToolResult?> {
        val resolvedTarget = resolveToolTarget(invocation.tool)
        val permissionTool = resolvedTarget.tool

        // 检查是否强制拒绝权限（deny_tool标记�?       val hasPromptForPermission = !invocation.rawText.contains("deny_tool")

        if (hasPromptForPermission) {
            // 检查权限，如果需要则弹出权限请求界面
            val toolPermissionSystem = toolHandler.getToolPermissionSystem()
            val hasPermission = toolPermissionSystem.checkToolPermission(permissionTool)

            // 如果权限被拒绝，创建错误结果
            if (!hasPermission) {
                val errorResult =
                    ToolResult(
                        toolName = resolvedTarget.displayName,
                        success = false,
                        result = StringResultData(""),
                        error = "User cancelled the tool execution."
                    )
                toolHandler.notifyToolPermissionChecked(
                    permissionTool,
                    granted = false,
                    reason = errorResult.error
                )
                return Pair(false, errorResult)
            }

            toolHandler.notifyToolPermissionChecked(permissionTool, granted = true)
            return Pair(true, null)
        }

        toolHandler.notifyToolPermissionChecked(
            permissionTool,
            granted = true,
            reason = "Permission check bypassed by deny_tool tag."
        )
        return Pair(true, null)
    }

    /**
     *
     * 执行工具调用，包括权限检查、并且串行执行和结果聚合�?    * @param invocations 要执行的工具调用列表�?    * @param toolHandler AIToolHandler 的实例，     * @param packageManager PackageManager 的实例，     * @param collector 用于实时输出结果，StreamCollector�?    * @return 所有工具执行结果的列表�?    */
    suspend fun executeInvocations(
        invocations: List<ToolInvocation>,
        context: Context,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        callerName: String? = null,
        callerChatId: String? = null
    ): List<ToolResult> = coroutineScope {
        // 默认工具注册现在可能在启动阶段被延后；这里确保在真正执行工具前已完成注册
        // registerDefaultTools() 是幂等且线程安全的，可安全重复调�?
        withContext(Dispatchers.Default) {
            toolHandler.registerDefaultTools()
        }

        // 1. 权限检�?
        val permittedInvocations = mutableListOf<ToolInvocation>()
        val permissionDeniedResults = mutableListOf<ToolResult>()
        for (invocation in invocations) {
            toolHandler.notifyToolCallRequested(invocation.tool)
            val (hasPermission, errorResult) = checkToolPermission(toolHandler, invocation)
            if (hasPermission) {
                permittedInvocations.add(invocation)
            } else {
                errorResult?.let {
                    permissionDeniedResults.add(it)
                    val toolResultStatusContent =
                        ConversationMarkupManager.formatToolResultForMessage(it)
                    collector.emit(ensureEndsWithNewline(toolResultStatusContent))
                }
            }
        }

        val injectedInvocations =
            if (callerName.isNullOrBlank() && callerChatId.isNullOrBlank()) {
                permittedInvocations
            } else {
                val jsPackageNames = packageManager.getAvailablePackages().keys
                permittedInvocations.map { invocation ->
                    injectPackageCallContext(
                        invocation = invocation,
                        jsPackageNames = jsPackageNames,
                        callerName = callerName,
                        callerChatId = callerChatId
                    )
                }
            }

        // 2. 按并�?串行对工具进行分�?
        val parallelizableToolNames = setOf(
            "list_files", "read_file", "read_file_part", "read_file_full", "file_exists",
            "find_files", "file_info", "grep_code", "calculate", "ffmpeg_info",
            "visit_web", "download_file"
        )
        val (parallelInvocations, serialInvocations) = injectedInvocations.partition {
            parallelizableToolNames.contains(
                it.tool.name
            )
        }

        // 3. 执行工具并收集聚合结�?
        val executionResults = ConcurrentHashMap<ToolInvocation, ToolResult>()

        // 启动并行工具
        val parallelJobs = parallelInvocations.map { invocation ->
            async {
                val result = executeAndEmitTool(invocation, toolHandler, packageManager, collector)
                executionResults[invocation] = result
            }
        }

        // 顺序执行串行工具
        for (invocation in serialInvocations) {
            val result = executeAndEmitTool(invocation, toolHandler, packageManager, collector)
            executionResults[invocation] = result
        }

        // 等待所有并行任务完�?
        parallelJobs.awaitAll()

        // 4. 按原始顺序重新排序结�?
        val orderedAggregated = injectedInvocations.mapNotNull { executionResults[it] }

        // 5. 组合所有结果并返回
        permissionDeniedResults + orderedAggregated
    }

    /**
     * 封装单个工具的执行、实时输出和结果聚合的辅助函�?    */
    private suspend fun executeAndEmitTool(
        invocation: ToolInvocation,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>
    ): ToolResult {
        val toolName = invocation.tool.name
        val displayToolName = resolveDisplayToolName(invocation.tool)

        return try {
            // 首先尝试使用传统工具执行�?           val executor = toolHandler.getToolExecutorOrActivate(toolName)
            
            if (executor != null) {
                // 使用传统工具执行器执�?               toolHandler.notifyToolExecutionStarted(invocation.tool)

                val collectedResults = mutableListOf<ToolResult>()
                executeToolSafely(invocation, executor, toolHandler).collect { result ->
                    collectedResults.add(result)
                    // 实时输出每个结果
                    val toolResultStatusContent =
                        ConversationMarkupManager.formatToolResultForMessage(result)
                    collector.emit(ensureEndsWithNewline(toolResultStatusContent))
                }

                // 为此调用聚合最终结�?               if (collectedResults.isEmpty()) {
                    val emptyResult =
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = "The tool execution returned no results."
                        )
                    toolHandler.notifyToolExecutionResult(invocation.tool, emptyResult)
                    return emptyResult
                }

                val lastResult = collectedResults.last()
                val combinedResultString = collectedResults.joinToString("\n") { res ->
                    (if (res.success) res.result.toString() else "Step error: ${res.error ?: "Unknown error"}").trim()
                }.trim()

                val finalResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = lastResult.success,
                        result = StringResultData(combinedResultString),
                        error = lastResult.error
                    )
                toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
                return finalResult
            } else {
                // 尝试使用新的工具适配�?               AppLogger.d(TAG, "尝试使用工具适配器执行工�?${toolName}")
                val toolResult = executeWithToolAdapter(invocation, displayToolName, collector)
                toolHandler.notifyToolExecutionResult(invocation.tool, toolResult)
                return toolResult
            }
        } finally {
            toolHandler.notifyToolExecutionFinished(invocation.tool)
        }
    }

    /**
     * 使用工具适配器执行工具调�?    */
    private suspend fun executeWithToolAdapter(
        invocation: ToolInvocation,
        displayToolName: String,
        collector: StreamCollector<String>
    ): ToolResult {
        try {
            // 构建工具参数
            val parameters = mutableMapOf<String, Any>()
            invocation.tool.parameters.forEach {
                parameters[it.name] = it.value
            }

            // 执行工具
            val resultData = ToolAdapterManager.executeTool(invocation.tool.name, parameters)
            
            // 构建工具结果
            val result = ToolResult(
                toolName = displayToolName,
                success = true,
                result = resultData,
                error = null
            )

            // 输出结果
            val toolResultStatusContent =
                ConversationMarkupManager.formatToolResultForMessage(result)
            collector.emit(ensureEndsWithNewline(toolResultStatusContent))
            
            return result
        } catch (e: Exception) {
            AppLogger.e(TAG, "工具适配器执行失�?${invocation.tool.name}", e)
            val errorMessage = "工具适配器执行失�?${e.message}"
            val errorResult = ToolResult(
                toolName = displayToolName,
                success = false,
                result = StringResultData(""),
                error = errorMessage
            )
            val errorContent = ConversationMarkupManager.formatToolResultForMessage(errorResult)
            collector.emit(ensureEndsWithNewline(errorContent))
            return errorResult
        }
    }

    /**
     * 构建工具不可用的错误信息，统一逻辑避免重复
     */
    private suspend fun buildToolNotAvailableErrorMessage(
        toolName: String,
        packageManager: PackageManager,
        toolHandler: AIToolHandler
    ): String {
        return when {
            toolName.contains('.') && !toolName.contains(':') -> {
                val parts = toolName.split('.', limit = 2)
                "Tool invocation syntax error: for tools inside a package, use the 'packName:toolName' format instead of '${toolName}'. You may want to call '${parts.getOrNull(0)}:${parts.getOrNull(1)}'."
            }

            toolName.contains(':') -> {
                val parts = toolName.split(':', limit = 2)
                val packName = parts[0]
                val toolNamePart = parts.getOrNull(1) ?: ""
                val isJsPackageAvailable = packageManager.getAvailablePackages().containsKey(packName)
                val isMcpServerAvailable = packageManager.getAvailableServerPackages().containsKey(packName)
                val isAvailable = isJsPackageAvailable || isMcpServerAvailable

                if (!isAvailable) {
                    "The tool package or MCP server '${packName}' does not exist."
                } else {
                    // 包存在，检查是否已激活（通过检查该包的任何工具是否已注册）
                    val packageTools =
                        packageManager.getPackageTools(packName)?.tools ?: emptyList()
                    val isAdviceTool = packageTools.any { it.advice && it.name == toolNamePart }
                    val isPackageActivated = packageTools
                        .filter { !it.advice }
                        .any { toolHandler.getToolExecutor("${packName}:${it.name}") != null }

                    if (isAdviceTool) {
                        "Tool '${toolNamePart}' is an advice-only entry in package '${packName}' and is not executable."
                    } else if (isPackageActivated) {
                        // 包已激活但工具不存�?                       "Tool '${toolNamePart}' does not exist in tool package '${packName}'. Please use the 'use_package' tool and specify package name '${packName}' to list all available tools in this package."
                    } else {
                        // 包未激�?                       "Tool package '${packName}' is not activated. Auto-activation was attempted but failed, or tool '${toolNamePart}' does not exist. Please use 'use_package' with package name '${packName}' to check available tools."
                    }
                }
            }

            else -> {
                // 检查是否直接把包名当作工具名调用了
                val isPackageName = packageManager.getAvailablePackages().containsKey(toolName)
                if (isPackageName) {
                    "Error: '${toolName}' is a tool package, not a tool. Please use the 'use_package' tool with package name '${toolName}' to activate this package before using its tools."
                } else {
                    "Tool '${toolName}' is unavailable or does not exist. If this is a tool inside a package, call it using the 'packName:toolName' format."
                }
            }
        }
    }

}
