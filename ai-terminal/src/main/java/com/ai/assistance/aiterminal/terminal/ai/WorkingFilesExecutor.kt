package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.apex.sdk.bridge.ApexClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Working Files 工具执行器 — 让 Agent 通过 Working Files APK 操作项目文件
 *
 * 封装 [ApexClient.workingFiles] 的全部能力,作为 Agent 工具暴露给 LLM:
 *
 * # 文件操作
 * - wf_bind_folder: 绑定项目文件夹
 * - wf_list_files: 列出文件
 * - wf_read_file: 读取文件
 * - wf_write_file: 写入文件
 * - wf_file_tree: 获取目录树
 *
 * # 快照与回退
 * - wf_take_snapshot: 文件快照(修改前保存)
 * - wf_write_with_snapshot: 写入并自动快照
 * - wf_list_snapshots: 列出快照
 * - wf_restore_snapshot: 回退到快照
 * - wf_diff: 计算差异
 *
 * # Agent 执行流程
 * - wf_start_agent_session: 开始 Agent 会话(记录执行流程)
 * - wf_record_step: 记录执行步骤(含受影响文件/快照)
 * - wf_finish_session: 结束会话
 * - wf_get_agent_flow: 获取执行流程
 *
 * # 使用方式
 *
 * ```
 * val executor = WorkingFilesExecutor(context)
 *
 * // Agent 绑定项目
 * executor.bindFolder("project1", "MyProject", "/sdcard/MyProject")
 *
 * // Agent 读文件
 * val content = executor.readFile("project1", "src/Main.kt")
 *
 * // Agent 写文件(自动快照,可回退)
 * executor.writeWithSnapshot("project1", "src/Main.kt", newContent, agentId, agentName, sessionId, "修改了 main 函数")
 *
 * // Agent 查看历史
 * val snapshots = executor.listSnapshots("project1", "src/Main.kt")
 * executor.restoreSnapshot(snapshots[0].id)
 * ```
 */
class WorkingFilesExecutor(private val context: Context) {

    /** 检查 Working Files APK 是否可用 */
    fun isAvailable(): Boolean = ApexClient.workingFiles.isAvailable(context)

    // ============ 文件操作 ============

    /** 绑定文件夹 */
    suspend fun bindFolder(folderId: String, displayName: String, path: String, mode: String = "ALL"): Result<String> =
        safeCall { ApexClient.workingFiles.bindFolder(folderId, displayName, path, mode) }

    /** 列出已绑定的文件夹 */
    suspend fun listFolders(): Result<String> =
        safeCall { ApexClient.workingFiles.listFolders() }

    /** 列出文件 */
    suspend fun listFiles(folderId: String, relativePath: String = ""): Result<String> =
        safeCall { ApexClient.workingFiles.listFiles(folderId, relativePath) }

    /** 读取文件 */
    suspend fun readFile(folderId: String, relativePath: String): Result<String> =
        safeCall { ApexClient.workingFiles.readFile(folderId, relativePath) }

    /** 写入文件 */
    suspend fun writeFile(folderId: String, relativePath: String, content: String, append: Boolean = false): Result<String> =
        safeCall { ApexClient.workingFiles.writeFile(folderId, relativePath, content, append) }

    /** 获取文件树 */
    suspend fun getFileTree(rootPath: String, maxDepth: Int = 10): Result<String> =
        safeCall { ApexClient.workingFiles.getFileTree(rootPath, maxDepth) }

    // ============ 快照与回退 ============

    /** 手动快照 */
    suspend fun takeSnapshot(filePath: String, rootPath: String, description: String = "", source: String = "AGENT"): Result<String> =
        safeCall { ApexClient.workingFiles.takeSnapshot(filePath, rootPath, description, source) }

    /** 写入并自动快照(Agent 修改文件时推荐用这个) */
    suspend fun writeWithSnapshot(
        filePath: String, rootPath: String, content: String,
        agentId: String, agentName: String, sessionId: String,
        description: String,
    ): Result<String> =
        safeCall { ApexClient.workingFiles.writeWithSnapshot(filePath, rootPath, content, agentId, agentName, sessionId, description) }

    /** 列出文件的所有快照 */
    suspend fun listSnapshots(filePath: String): Result<String> =
        safeCall { ApexClient.workingFiles.listSnapshots(filePath) }

    /** 获取最新快照 */
    suspend fun getLatestSnapshot(filePath: String): Result<String> =
        safeCall { ApexClient.workingFiles.getLatestSnapshot(filePath) }

    /** 回退到指定快照 */
    suspend fun restoreSnapshot(snapshotId: String, operator: String = "agent"): Result<String> =
        safeCall { ApexClient.workingFiles.restoreSnapshot(snapshotId, operator) }

    /** 计算两个内容的差异 */
    suspend fun computeDiff(oldContent: String, newContent: String): Result<String> =
        safeCall { ApexClient.workingFiles.computeDiff(oldContent, newContent) }

    /** 两个快照之间的差异 */
    suspend fun diffSnapshots(beforeId: String, afterId: String): Result<String> =
        safeCall { ApexClient.workingFiles.diffSnapshots(beforeId, afterId) }

    /** 快照与当前文件的差异 */
    suspend fun diffWithCurrent(snapshotId: String): Result<String> =
        safeCall { ApexClient.workingFiles.diffWithCurrent(snapshotId) }

    // ============ Agent 执行流程 ============

    /** 开始 Agent 会话(Working Files APK 会记录完整执行流程) */
    suspend fun startAgentSession(agentId: String, agentName: String, taskDescription: String, mode: String = "NORMAL"): Result<String> =
        safeCall { ApexClient.workingFiles.startAgentSession(agentId, agentName, taskDescription, mode) }

    /** 记录 Agent 执行步骤 */
    suspend fun recordAgentStep(
        sessionId: String, agentId: String, agentName: String,
        type: String, title: String, description: String = "",
        affectedFiles: List<String> = emptyList(), snapshotIds: List<String> = emptyList(),
        isSuccess: Boolean = true, errorMessage: String? = null,
        durationMs: Long = 0,
    ): Result<String> =
        safeCall { ApexClient.workingFiles.recordAgentStep(sessionId, agentId, agentName, type, title, description, affectedFiles, snapshotIds, isSuccess, errorMessage, durationMs) }

    /** 结束 Agent 会话 */
    suspend fun finishAgentSession(sessionId: String, finalResult: String? = null, status: String = "COMPLETED"): Result<String> =
        safeCall { ApexClient.workingFiles.finishAgentSession(sessionId, finalResult, status) }

    /** 获取 Agent 执行流程 */
    suspend fun getAgentFlow(sessionId: String): Result<String> =
        safeCall { ApexClient.workingFiles.getAgentFlow(sessionId) }

    /** 列出所有 Agent 会话 */
    suspend fun listAgentSessions(): Result<String> =
        safeCall { ApexClient.workingFiles.listAgentSessions() }

    /** 列出活跃 Agent 会话 */
    suspend fun listActiveAgentSessions(): Result<String> =
        safeCall { ApexClient.workingFiles.listActiveAgentSessions() }

    /** 列出会话的所有步骤 */
    suspend fun listAgentSteps(sessionId: String): Result<String> =
        safeCall { ApexClient.workingFiles.listAgentSteps(sessionId) }

    // ============ Agent 工具入口(JSON) ============

    /**
     * Agent 工具调用入口
     *
     * @param toolName 工具名(wf_*)
     * @param argumentsJson LLM 返回的参数 JSON
     * @return 执行结果 JSON
     */
    suspend fun executeAgentTool(toolName: String, argumentsJson: String): String {
        if (!isAvailable()) {
            return JSONObject().put("error", "Working Files APK not installed").toString()
        }

        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }

        val result = when (toolName) {
            "wf_bind_folder" -> {
                val r = bindFolder(args.getString("folder_id"), args.getString("display_name"), args.getString("path"), args.optString("mode", "ALL"))
                resultToJson("bind_folder", r)
            }

            "wf_list_folders" -> {
                val r = listFolders()
                resultToJson("list_folders", r)
            }

            "wf_list_files" -> {
                val r = listFiles(args.getString("folder_id"), args.optString("relative_path", ""))
                resultToJson("list_files", r)
            }

            "wf_read_file" -> {
                val r = readFile(args.getString("folder_id"), args.getString("relative_path"))
                resultToJson("read_file", r)
            }

            "wf_write_file" -> {
                val r = writeFile(args.getString("folder_id"), args.getString("relative_path"), args.getString("content"), args.optBoolean("append", false))
                resultToJson("write_file", r)
            }

            "wf_file_tree" -> {
                val r = getFileTree(args.optString("root_path", "."), args.optInt("max_depth", 10))
                resultToJson("file_tree", r)
            }

            "wf_take_snapshot" -> {
                val r = takeSnapshot(args.getString("file_path"), args.getString("root_path"), args.optString("description", ""), args.optString("source", "AGENT"))
                resultToJson("take_snapshot", r)
            }

            "wf_write_with_snapshot" -> {
                val r = writeWithSnapshot(
                    args.getString("file_path"), args.getString("root_path"), args.getString("content"),
                    args.optString("agent_id", "unknown"), args.optString("agent_name", "Agent"),
                    args.optString("session_id", ""), args.optString("description", "Agent modification"),
                )
                resultToJson("write_with_snapshot", r)
            }

            "wf_list_snapshots" -> {
                val r = listSnapshots(args.getString("file_path"))
                resultToJson("list_snapshots", r)
            }

            "wf_restore_snapshot" -> {
                val r = restoreSnapshot(args.getString("snapshot_id"), args.optString("operator", "agent"))
                resultToJson("restore_snapshot", r)
            }

            "wf_diff" -> {
                val r = if (args.has("before_id") && args.has("after_id")) {
                    diffSnapshots(args.getString("before_id"), args.getString("after_id"))
                } else if (args.has("snapshot_id")) {
                    diffWithCurrent(args.getString("snapshot_id"))
                } else {
                    computeDiff(args.getString("old_content"), args.getString("new_content"))
                }
                resultToJson("diff", r)
            }

            "wf_start_agent_session" -> {
                val r = startAgentSession(args.optString("agent_id", "agent"), args.optString("agent_name", "Agent"), args.getString("task_description"), args.optString("mode", "NORMAL"))
                resultToJson("start_agent_session", r)
            }

            "wf_record_step" -> {
                val affectedFiles = args.optJSONArray("affected_files")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
                val r = recordAgentStep(
                    args.getString("session_id"), args.optString("agent_id", "agent"), args.optString("agent_name", "Agent"),
                    args.getString("type"), args.getString("title"), args.optString("description", ""),
                    affectedFiles, emptyList(), args.optBoolean("is_success", true),
                    args.optString("error_message", "").ifBlank { null },
                    args.optLong("duration_ms", 0),
                )
                resultToJson("record_step", r)
            }

            "wf_finish_session" -> {
                val r = finishAgentSession(args.getString("session_id"), args.optString("final_result"), args.optString("status", "COMPLETED"))
                resultToJson("finish_session", r)
            }

            "wf_get_agent_flow" -> {
                val r = getAgentFlow(args.getString("session_id"))
                resultToJson("agent_flow", r)
            }

            "wf_list_sessions" -> {
                val r = listAgentSessions()
                resultToJson("list_sessions", r)
            }

            "wf_list_steps" -> {
                val r = listAgentSteps(args.getString("session_id"))
                resultToJson("list_steps", r)
            }

            else -> JSONObject().put("error", "Unknown tool: $toolName")
        }

        return result.toString()
    }

    private fun resultToJson(toolName: String, result: Result<String>): JSONObject {
        val json = JSONObject()
        json.put("tool", toolName)
        return if (result.isSuccess) {
            json.put("success", true)
            json.put("result", result.getOrNull() ?: "")
        } else {
            json.put("success", false)
            json.put("error", result.exceptionOrNull()?.message ?: "Unknown error")
        }
    }

    private suspend fun safeCall(block: suspend () -> com.apex.sdk.common.BridgeResult<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            val r = block()
            if (r.isSuccess) Result.success(r.data ?: "")
            else Result.failure(Exception(r.errorMessage ?: "Unknown error"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
