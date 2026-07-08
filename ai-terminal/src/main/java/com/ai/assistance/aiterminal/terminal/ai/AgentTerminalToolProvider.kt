package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.data.model.ToolParameterSchema
import com.ai.assistance.aiterminal.terminal.data.model.ToolPrompt

/**
 * Agent 终端工具提供者 — 把 [AgentTerminalExecutor] 的工具注册到 LLM Tool Call 系统
 *
 * # 使用方式
 *
 * 在配置 LLM 时,把这些工具加入 availableTools:
 *
 * ```
 * val provider = AgentTerminalToolProvider(context)
 * val tools = provider.getAllToolPrompts()
 * // tools 传给 AIService.sendMessage(availableTools = tools)
 * ```
 *
 * 当 LLM 返回 tool_call 时,执行:
 *
 * ```
 * val result = provider.executeToolCall(toolName, argumentsJson)
 * // result 是 JSON 字符串,返回给 LLM 作为 tool_result
 * ```
 */
class AgentTerminalToolProvider(private val context: Context) {

    private val executor = AgentTerminalExecutor(context)
    private val workingFilesExecutor = WorkingFilesExecutor(context)
    val thinkingTracker = ThinkingProcessTracker()

    /**
     * 获取所有终端工具的 ToolPrompt(供 LLM function calling)
     */
    fun getAllToolPrompts(): List<ToolPrompt> = listOf(
        // === 命令执行 ===
        ToolPrompt(
            name = "agent_exec",
            description = "执行单条 shell 命令,返回 stdout、stderr 和 exitCode。适用于 ls/cat/grep/git 等快速命令。",
            parametersStructured = listOf(
                ToolParameterSchema("command", "string", "要执行的 shell 命令", required = true),
                ToolParameterSchema("working_dir", "string", "工作目录,默认用户主目录", required = false),
                ToolParameterSchema("timeout_ms", "number", "超时毫秒,默认 30000", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_exec_batch",
            description = "批量执行多条命令,共享工作目录。前一条 cd 会影响后一条。适用于多步骤操作。",
            parametersStructured = listOf(
                ToolParameterSchema("commands", "array", "命令列表(JSON 数组)", required = true),
                ToolParameterSchema("working_dir", "string", "初始工作目录", required = false),
                ToolParameterSchema("stop_on_error", "boolean", "失败时是否停止后续命令,默认 true", required = false),
            ),
        ),

        // === 持久会话 ===
        ToolPrompt(
            name = "agent_session_create",
            description = "创建持久会话,后续命令在同一工作目录和环境变量下执行。适用于需要多次 cd 的复杂操作。",
            parametersStructured = listOf(
                ToolParameterSchema("working_dir", "string", "初始工作目录,默认 ~", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_session_exec",
            description = "在已创建的会话中执行命令,保持工作目录和 export 的环境变量。",
            parametersStructured = listOf(
                ToolParameterSchema("session_id", "string", "会话 ID(create_session 返回)", required = true),
                ToolParameterSchema("command", "string", "要执行的命令", required = true),
                ToolParameterSchema("timeout_ms", "number", "超时毫秒,默认 30000", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_session_close",
            description = "关闭持久会话,释放资源。",
            parametersStructured = listOf(
                ToolParameterSchema("session_id", "string", "要关闭的会话 ID", required = true),
            ),
        ),

        // === 文件操作 ===
        ToolPrompt(
            name = "agent_file_tree",
            description = "获取目录树 JSON 结构,包含文件名/路径/大小/子节点。适用于快速了解项目结构。",
            parametersStructured = listOf(
                ToolParameterSchema("path", "string", "根路径,默认当前目录", required = false),
                ToolParameterSchema("max_depth", "number", "最大递归深度,默认 3", required = false),
                ToolParameterSchema("include_hidden", "boolean", "是否包含隐藏文件(以.开头),默认 false", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_grep",
            description = "高级文本搜索,支持正则/文件名过滤/上下文行。比 grep 命令更结构化。",
            parametersStructured = listOf(
                ToolParameterSchema("pattern", "string", "搜索模式(正则表达式)", required = true),
                ToolParameterSchema("path", "string", "搜索路径,默认当前目录", required = false),
                ToolParameterSchema("file_pattern", "string", "文件名过滤,如 *.kt,默认 *", required = false),
                ToolParameterSchema("context_lines", "number", "上下文行数,默认 0", required = false),
                ToolParameterSchema("max_results", "number", "最大结果数,默认 50", required = false),
            ),
        ),

        // === 管道 ===
        ToolPrompt(
            name = "agent_pipeline",
            description = "管道执行多条命令(cmd1 | cmd2 | cmd3),自动处理管道连接。",
            parametersStructured = listOf(
                ToolParameterSchema("commands", "array", "命令列表(按管道顺序)", required = true),
                ToolParameterSchema("working_dir", "string", "工作目录", required = false),
            ),
        ),

        // === 后台执行 ===
        ToolPrompt(
            name = "agent_bg_exec",
            description = "后台执行长命令(如 npm install / gradle build),立即返回 taskId。适用于不阻塞的长时间操作。",
            parametersStructured = listOf(
                ToolParameterSchema("command", "string", "要后台执行的命令", required = true),
                ToolParameterSchema("working_dir", "string", "工作目录", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_bg_status",
            description = "查询后台任务的执行状态和已输出。Agent 可轮询此接口。",
            parametersStructured = listOf(
                ToolParameterSchema("task_id", "string", "后台任务 ID", required = true),
            ),
        ),
        ToolPrompt(
            name = "agent_bg_cancel",
            description = "取消正在运行的后台任务。",
            parametersStructured = listOf(
                ToolParameterSchema("task_id", "string", "要取消的任务 ID", required = true),
            ),
        ),

        // === 高级执行 ===
        ToolPrompt(
            name = "agent_exec_parallel",
            description = "并行执行多条独立命令(互不依赖)。适合同时检查多个文件、ping多个服务器、获取多种系统信息。每条命令独立工作目录。",
            parametersStructured = listOf(
                ToolParameterSchema("tasks", "array", "任务列表,每项含 id/command/working_dir", required = true),
                ToolParameterSchema("timeout_ms", "number", "总超时毫秒,默认 60000", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_exec_retry",
            description = "带自动重试的命令执行(指数退避)。适合网络命令/不稳定操作。默认重试3次,退避1s/2s/4s。",
            parametersStructured = listOf(
                ToolParameterSchema("command", "string", "要执行的命令", required = true),
                ToolParameterSchema("working_dir", "string", "工作目录", required = false),
                ToolParameterSchema("max_retries", "number", "最大重试次数,默认 3", required = false),
                ToolParameterSchema("backoff_ms", "number", "退避基数毫秒,默认 1000(指数增长)", required = false),
            ),
        ),

        // === 命令模板 ===
        ToolPrompt(
            name = "agent_template_list",
            description = "列出所有可用命令模板(git_status/git_commit_push/build_gradle/find_process/disk_usage/port_info/file_search/env_check)。",
            parametersStructured = emptyList(),
        ),
        ToolPrompt(
            name = "agent_template_exec",
            description = "执行命令模板(用模板名+参数代替记住复杂命令)。",
            parametersStructured = listOf(
                ToolParameterSchema("template_id", "string", "模板 ID(如 git_status/env_check)", required = true),
                ToolParameterSchema("parameters", "object", "模板参数(键值对)", required = false),
            ),
        ),

        // === 审计与统计 ===
        ToolPrompt(
            name = "agent_audit_logs",
            description = "获取最近的执行审计日志(工具名/命令/成功/耗时/输出大小)。",
            parametersStructured = listOf(
                ToolParameterSchema("limit", "number", "返回条数,默认 50", required = false),
            ),
        ),
        ToolPrompt(
            name = "agent_stats",
            description = "获取执行统计(总执行数/成功率/平均耗时/活跃会话/后台任务)。",
            parametersStructured = emptyList(),
        ),

        // === Working Files(项目文件操作) ===
        ToolPrompt(
            name = "wf_bind_folder",
            description = "绑定项目文件夹到 Working Files。Agent 后续所有文件操作都通过 folder_id 引用。首次操作项目时必须先绑定。",
            parametersStructured = listOf(
                ToolParameterSchema("folder_id", "string", "文件夹唯一 ID(如 project1)", required = true),
                ToolParameterSchema("display_name", "string", "显示名(如 MyProject)", required = true),
                ToolParameterSchema("path", "string", "文件系统绝对路径(如 /sdcard/MyProject)", required = true),
                ToolParameterSchema("mode", "string", "访问模式: ALL/READ_ONLY/WRITE_ONLY,默认 ALL", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_list_files",
            description = "列出已绑定文件夹的文件列表(支持子目录)。",
            parametersStructured = listOf(
                ToolParameterSchema("folder_id", "string", "已绑定的文件夹 ID", required = true),
                ToolParameterSchema("relative_path", "string", "相对子路径(如 src/main),默认根目录", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_read_file",
            description = "读取项目文件内容。Agent 查看代码/配置文件时用。",
            parametersStructured = listOf(
                ToolParameterSchema("folder_id", "string", "文件夹 ID", required = true),
                ToolParameterSchema("relative_path", "string", "文件相对路径(如 src/Main.kt)", required = true),
            ),
        ),
        ToolPrompt(
            name = "wf_write_file",
            description = "写入项目文件。Agent 创建/修改代码时用。支持追加模式。",
            parametersStructured = listOf(
                ToolParameterSchema("folder_id", "string", "文件夹 ID", required = true),
                ToolParameterSchema("relative_path", "string", "文件相对路径", required = true),
                ToolParameterSchema("content", "string", "文件内容", required = true),
                ToolParameterSchema("append", "boolean", "是否追加到末尾,默认 false(覆盖)", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_write_with_snapshot",
            description = "写入文件并自动创建快照(推荐)。修改前自动保存旧版本,出问题可回退。Agent 修改项目代码时优先用这个。",
            parametersStructured = listOf(
                ToolParameterSchema("file_path", "string", "文件绝对路径", required = true),
                ToolParameterSchema("root_path", "string", "项目根路径(用于快照关联)", required = true),
                ToolParameterSchema("content", "string", "新文件内容", required = true),
                ToolParameterSchema("agent_id", "string", "Agent ID", required = false),
                ToolParameterSchema("agent_name", "string", "Agent 名称", required = false),
                ToolParameterSchema("session_id", "string", "Agent 会话 ID", required = false),
                ToolParameterSchema("description", "string", "修改说明(如'修改了 main 函数')", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_file_tree",
            description = "获取目录树 JSON(含文件名/路径/大小/子节点)。Agent 快速了解项目结构。",
            parametersStructured = listOf(
                ToolParameterSchema("root_path", "string", "根路径", required = false),
                ToolParameterSchema("max_depth", "number", "最大深度,默认 10", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_list_snapshots",
            description = "列出文件的所有历史快照。Agent 查看修改历史。",
            parametersStructured = listOf(
                ToolParameterSchema("file_path", "string", "文件绝对路径", required = true),
            ),
        ),
        ToolPrompt(
            name = "wf_restore_snapshot",
            description = "回退文件到指定快照版本。Agent 修改出错时可用此工具回退。",
            parametersStructured = listOf(
                ToolParameterSchema("snapshot_id", "string", "快照 ID(list_snapshots 返回)", required = true),
            ),
        ),
        ToolPrompt(
            name = "wf_diff",
            description = "计算差异。支持:两个快照之间(before_id+after_id)、快照与当前(snapshot_id)、两段文本(old_content+new_content)。",
            parametersStructured = listOf(
                ToolParameterSchema("before_id", "string", "旧快照 ID(可选)", required = false),
                ToolParameterSchema("after_id", "string", "新快照 ID(可选)", required = false),
                ToolParameterSchema("snapshot_id", "string", "快照 ID(与当前文件对比,可选)", required = false),
                ToolParameterSchema("old_content", "string", "旧文本(可选)", required = false),
                ToolParameterSchema("new_content", "string", "新文本(可选)", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_start_agent_session",
            description = "在 Working Files 中开始 Agent 执行会话。会记录 Agent 的所有操作步骤,形成可回溯的执行流程。",
            parametersStructured = listOf(
                ToolParameterSchema("agent_id", "string", "Agent ID", required = false),
                ToolParameterSchema("agent_name", "string", "Agent 名称", required = false),
                ToolParameterSchema("task_description", "string", "任务描述", required = true),
            ),
        ),
        ToolPrompt(
            name = "wf_record_step",
            description = "记录 Agent 执行步骤到 Working Files(含受影响文件)。形成可回溯的项目修改历史。",
            parametersStructured = listOf(
                ToolParameterSchema("session_id", "string", "会话 ID", required = true),
                ToolParameterSchema("type", "string", "步骤类型: ANALYZE/MODIFY/TEST/DEPLOY", required = true),
                ToolParameterSchema("title", "string", "步骤标题", required = true),
                ToolParameterSchema("description", "string", "详细描述", required = false),
                ToolParameterSchema("affected_files", "array", "受影响文件列表", required = false),
                ToolParameterSchema("is_success", "boolean", "是否成功,默认 true", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_finish_session",
            description = "结束 Agent 执行会话。",
            parametersStructured = listOf(
                ToolParameterSchema("session_id", "string", "会话 ID", required = true),
                ToolParameterSchema("final_result", "string", "最终结果摘要", required = false),
                ToolParameterSchema("status", "string", "状态: COMPLETED/FAILED/CANCELLED", required = false),
            ),
        ),
        ToolPrompt(
            name = "wf_get_agent_flow",
            description = "获取 Agent 执行流程(所有步骤的可视化时间线)。",
            parametersStructured = listOf(
                ToolParameterSchema("session_id", "string", "会话 ID", required = true),
            ),
        ),

        // === 思考过程 ===
        ToolPrompt(
            name = "agent_think",
            description = "记录 Agent 的思考过程。Agent 在调用工具前/分析结果后/做决策时,用此工具记录推理步骤。UI 会实时展示。",
            parametersStructured = listOf(
                ToolParameterSchema("type", "string", "思考类型: PLAN(计划)/REASONING(推理)/DECISION(决策)", required = true),
                ToolParameterSchema("content", "string", "思考内容(为什么这样做/发现了什么/决定什么)", required = true),
            ),
        ),
        ToolPrompt(
            name = "agent_get_thinking",
            description = "获取当前思考过程的所有步骤(JSON)。Agent 可回顾自己的推理历史。",
            parametersStructured = emptyList(),
        ),
    )

    /**
     * 执行 LLM 返回的 tool_call
     *
     * @param toolName 工具名(agent_exec / agent_grep / ...)
     * @param argumentsJson LLM 返回的参数 JSON
     * @return 执行结果 JSON(返回给 LLM 作为 tool_result)
     */
    suspend fun executeToolCall(toolName: String, argumentsJson: String): String {
        // 思考过程工具
        if (toolName == "agent_think") {
            val args = org.json.JSONObject(argumentsJson)
            val type = args.optString("type", "REASONING")
            val content = args.optString("content", "")
            when (type.uppercase()) {
                "PLAN" -> thinkingTracker.logPlan(content)
                "DECISION" -> thinkingTracker.logDecision(content)
                else -> thinkingTracker.logReasoning(content)
            }
            return org.json.JSONObject().put("success", true).put("recorded", true).toString()
        }
        if (toolName == "agent_get_thinking") {
            return thinkingTracker.getCurrentSessionJson().toString()
        }

        // Working Files 工具(wf_*)
        if (toolName.startsWith("wf_")) {
            // 记录思考: Agent 调用了 Working Files 工具
            val args = try { org.json.JSONObject(argumentsJson) } catch (e: Exception) { org.json.JSONObject() }
            thinkingTracker.logToolCall(toolName, "操作项目文件", "通过 Working Files APK 操作", argumentsJson)

            val startedAt = System.currentTimeMillis()
            val result = workingFilesExecutor.executeAgentTool(toolName, argumentsJson)
            val durationMs = System.currentTimeMillis() - startedAt

            // 解析结果,记录分析
            val resultJson = try { org.json.JSONObject(result) } catch (e: Exception) { org.json.JSONObject() }
            val success = resultJson.optBoolean("success", false)
            val analysis = if (success) "Working Files 操作成功" else "操作失败: ${resultJson.optString("error", "unknown")}"
            thinkingTracker.logToolResult(toolName, success, analysis, durationMs)

            return result
        }

        // 终端工具(agent_*)
        // 记录思考: Agent 调用了终端工具
        val args = try { org.json.JSONObject(argumentsJson) } catch (e: Exception) { org.json.JSONObject() }
        val command = args.optString("command", args.optString("template_id", toolName))
        thinkingTracker.logToolCall(toolName, "执行: $command".take(100), "Agent 通过终端工具操作", argumentsJson.take(200))

        val startedAt = System.currentTimeMillis()
        val result = executor.executeAgentTool(toolName, argumentsJson)
        val durationMs = System.currentTimeMillis() - startedAt

        // 解析结果,记录分析
        val resultJson = try { org.json.JSONObject(result) } catch (e: Exception) { org.json.JSONObject() }
        val success = resultJson.optBoolean("success", resultJson.optInt("exitCode", 0) == 0)
        val analysis = if (success) {
            val outputLen = resultJson.optString("stdout", "").length + resultJson.optString("result", "").length
            "执行成功,输出 ${outputLen} 字符"
        } else {
            "执行失败: ${resultJson.optString("error", resultJson.optString("stderr", "exit ${resultJson.optInt("exitCode", -1)}"))}"
        }
        thinkingTracker.logToolResult(toolName, success, analysis, durationMs)

        return result
    }

    /** 获取底层 executor(高级用法) */
    fun getExecutor(): AgentTerminalExecutor = executor

    /** 释放资源 */
    fun shutdown() {
        executor.shutdown()
        thinkingTracker.clearCurrent()
    }

    /** 开始思考会话(Agent 执行任务前调用) */
    fun startThinkingSession(taskDescription: String) {
        thinkingTracker.startSession(taskDescription)
    }

    /** 结束思考会话 */
    fun endThinkingSession(status: ThinkingProcessTracker.ThinkingSession.SessionStatus = ThinkingProcessTracker.ThinkingSession.SessionStatus.COMPLETED) {
        thinkingTracker.endSession(status)
    }
}
