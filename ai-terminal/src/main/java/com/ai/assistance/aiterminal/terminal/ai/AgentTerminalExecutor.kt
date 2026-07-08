package com.ai.assistance.aiterminal.terminal.ai

import android.content.Context
import com.ai.assistance.aiterminal.terminal.TerminalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 增强的 Agent 终端工具执行器
 *
 * 在 [TerminalToolExecutor] 基础上增强,解决 Agent 调用终端的 4 大效率瓶颈:
 *
 * 1. **命令输出返回** — executeCommand 返回完整 stdout/stderr/exitCode
 * 2. **批量执行** — 一次调用执行多条命令,共享工作目录
 * 3. **流式输出** — 长命令实时返回输出(Agent 可中途决策)
 * 4. **会话保持** — Agent 持有一个 session,cd/pushd 等状态保持
 *
 * # 工具列表(供 Agent 调用)
 *
 * | 工具名 | 说明 |
 * |--------|------|
 * | `agent_exec` | 执行单条命令,返回 stdout+stderr+exitCode |
 * | `agent_exec_batch` | 批量执行多条命令,共享工作目录 |
 * | `agent_exec_stream` | 流式执行(长命令),返回 Flow |
 * | `agent_session_create` | 创建持久会话,返回 sessionId |
 * | `agent_session_exec` | 在会话中执行(保持工作目录/环境变量) |
 * | `agent_session_close` | 关闭会话 |
 * | `agent_file_tree` | 获取目录树(JSON 结构) |
 * | `agent_grep` | 高级搜索(支持正则/文件过滤/上下文) |
 * | `agent_pipeline` | 管道: cmd1 | cmd2 | cmd3 |
 * | `agent_bg_exec` | 后台执行,返回 taskId,可轮询状态 |
 *
 * # 使用示例
 *
 * ```
 * val executor = AgentTerminalExecutor(context)
 *
 * // 单条命令
 * val result = executor.exec("ls -la /data")
 * // result.stdout, result.stderr, result.exitCode
 *
 * // 批量执行
 * val batch = executor.execBatch(listOf("cd /tmp", "mkdir test", "echo done"))
 *
 * // 会话保持
 * val sid = executor.createSession("~")
 * executor.sessionExec(sid, "cd /project")
 * executor.sessionExec(sid, "git status")  // 在 /project 下执行
 * executor.closeSession(sid)
 *
 * // 流式
 * executor.execStream("npm install").collect { chunk ->
 *     // 实时处理输出
 * }
 * ```
 */
class AgentTerminalExecutor(private val context: Context) {

    private val terminalManager by lazy { TerminalManager.getInstance(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // === 持久会话 ===
    data class AgentSession(
        val id: String,
        var workingDir: String,
        val env: MutableMap<String, String> = mutableMapOf(),
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val sessions = ConcurrentHashMap<String, AgentSession>()

    // === 执行结果 ===
    data class ExecResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val durationMs: Long,
        val workingDir: String,
    ) {
        val success: Boolean get() = exitCode == 0
        val output: String get() = if (stderr.isEmpty()) stdout else "$stdout\n[stderr]\n$stderr"

        fun toJson(): JSONObject = JSONObject().apply {
            put("stdout", stdout)
            put("stderr", stderr)
            put("exitCode", exitCode)
            put("success", success)
            put("durationMs", durationMs)
            put("workingDir", workingDir)
        }
    }

    data class BatchResult(
        val results: List<ExecResult>,
        val allSuccess: Boolean,
        val totalDurationMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            val arr = JSONArray()
            results.forEach { arr.put(it.toJson()) }
            put("results", arr)
            put("allSuccess", allSuccess)
            put("totalDurationMs", totalDurationMs)
        }
    }

    // ============ 1. 单条命令执行(返回完整输出) ============

    /**
     * 执行单条命令,返回 stdout + stderr + exitCode
     *
     * @param command shell 命令
     * @param workingDir 工作目录(默认 ~)
     * @param timeoutMs 超时(默认 30s)
     * @param env 额外环境变量
     */
    suspend fun exec(
        command: String,
        workingDir: String = System.getProperty("user.home") ?: "/",
        timeoutMs: Long = 30_000,
        env: Map<String, String> = emptyMap(),
    ): ExecResult = withTimeoutOrNull(timeoutMs) {
        val startedAt = System.currentTimeMillis()
        val dir = File(workingDir).takeIf { it.exists() } ?: File("/")

        val processBuilder = ProcessBuilder("sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(false)

        // 设置环境变量
        env.forEach { (k, v) -> processBuilder.environment()[k] = v }

        val process = processBuilder.start()

        val stdoutDeferred = scope.async { process.inputStream.bufferedReader().readText() }
        val stderrDeferred = scope.async { process.errorStream.bufferedReader().readText() }

        val exitCode = process.waitFor()
        val stdout = stdoutDeferred.await()
        val stderr = stderrDeferred.await()
        val durationMs = System.currentTimeMillis() - startedAt

        ExecResult(stdout, stderr, exitCode, durationMs, workingDir)
    } ?: ExecResult("", "Timeout after ${timeoutMs}ms", -1, timeoutMs, workingDir)

    // ============ 2. 批量执行 ============

    /**
     * 批量执行多条命令,共享工作目录(前一条 cd 影响后一条)
     *
     * @param commands 命令列表
     * @param workingDir 初始工作目录
     * @param stopOnError 失败时是否停止后续
     */
    suspend fun execBatch(
        commands: List<String>,
        workingDir: String = System.getProperty("user.home") ?: "/",
        stopOnError: Boolean = true,
    ): BatchResult = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val results = mutableListOf<ExecResult>()
        var currentDir = workingDir

        for (cmd in commands) {
            val result = exec(cmd, currentDir)
            results.add(result)

            // 如果是 cd 命令且成功,更新工作目录
            if (result.success && cmd.trim().startsWith("cd ")) {
                val target = cmd.trim().removePrefix("cd ").trim()
                currentDir = resolvePath(target, currentDir)
            }

            if (stopOnError && !result.success) break
        }

        BatchResult(results, results.all { it.success }, System.currentTimeMillis() - startedAt)
    }

    // ============ 3. 流式执行 ============

    /**
     * 流式执行命令,实时返回输出块
     *
     * 适合长时间命令(npm install / gradle build / pip install)
     *
     * @return Flow 输出块(每行一个 String)
     */
    fun execStream(
        command: String,
        workingDir: String = System.getProperty("user.home") ?: "/",
    ): Flow<String> = flow {
        val dir = File(workingDir).takeIf { it.exists() } ?: File("/")
        val process = ProcessBuilder("sh", "-c", command)
            .directory(dir)
            .redirectErrorStream(true)
            .start()

        val reader = process.inputStream.bufferedReader()
        var line = reader.readLine()
        while (line != null && process.isAlive) {
            emit(line)
            line = reader.readLine()
        }

        // 等待结束
        val exitCode = process.waitFor()
        emit("[exit: $exitCode]")
    }.flowOn(Dispatchers.IO)

    // ============ 4. 持久会话 ============

    /**
     * 创建持久会话(Agent 可在多次调用间保持工作目录/环境变量)
     *
     * @param workingDir 初始工作目录
     * @return sessionId
     */
    fun createSession(workingDir: String = System.getProperty("user.home") ?: "/"): String {
        val id = "agent_session_${System.currentTimeMillis()}"
        sessions[id] = AgentSession(id, workingDir)
        return id
    }

    /**
     * 在会话中执行命令(保持工作目录和环境变量)
     */
    suspend fun sessionExec(sessionId: String, command: String, timeoutMs: Long = 30_000): ExecResult {
        val session = sessions[sessionId] ?: return ExecResult("", "Session not found: $sessionId", -1, 0, "")

        val result = exec(command, session.workingDir, timeoutMs, session.env)

        // 如果是 cd 命令且成功,更新会话工作目录
        if (result.success && command.trim().startsWith("cd ")) {
            val target = command.trim().removePrefix("cd ").trim()
            session.workingDir = resolvePath(target, session.workingDir)
        }

        // 如果是 export 命令,解析环境变量
        if (result.success && command.trim().startsWith("export ")) {
            parseExport(command.trim())?.let { (k, v) -> session.env[k] = v }
        }

        return result
    }

    /** 关闭会话 */
    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** 列出所有活跃会话 */
    fun listSessions(): List<AgentSession> = sessions.values.toList()

    // ============ 5. 高级工具 ============

    /**
     * 获取目录树(JSON 结构)
     *
     * @param path 根路径
     * @param maxDepth 最大深度
     * @param includeHidden 是否包含隐藏文件
     */
    suspend fun fileTree(path: String = ".", maxDepth: Int = 3, includeHidden: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val root = File(resolvePath(path, System.getProperty("user.home") ?: "/"))
        buildFileTree(root, maxDepth, includeHidden, 0)
    }

    private fun buildFileTree(file: File, maxDepth: Int, includeHidden: Boolean, currentDepth: Int): JSONObject {
        val node = JSONObject()
        node.put("name", file.name)
        node.put("path", file.absolutePath)
        node.put("isDir", file.isDirectory)
        node.put("size", file.length())
        node.put("lastModified", file.lastModified())

        if (file.isDirectory && currentDepth < maxDepth) {
            val children = JSONArray()
            file.listFiles()
                ?.filter { includeHidden || !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { child ->
                    children.put(buildFileTree(child, maxDepth, includeHidden, currentDepth + 1))
                }
            node.put("children", children)
        }

        return node
    }

    /**
     * 高级 grep 搜索
     *
     * @param pattern 搜索模式(正则)
     * @param path 搜索路径
     * @param filePattern 文件名过滤(如 "*.kt")
     * @param contextLines 上下文行数
     * @param maxResults 最大结果数
     */
    suspend fun grep(
        pattern: String,
        path: String = ".",
        filePattern: String = "*",
        contextLines: Int = 0,
        maxResults: Int = 50,
    ): JSONArray = withContext(Dispatchers.IO) {
        val results = JSONArray()
        val regex = try { Regex(pattern) } catch (e: Exception) { return@withContext results }
        val fileRegex = try { Regex(filePattern.replace("*", ".*").replace("?", ".")) } catch (e: Exception) { return@withContext results }

        val root = File(resolvePath(path, System.getProperty("user.home") ?: "/"))
        var count = 0

        root.walkTopDown().forEach { file ->
            if (count >= maxResults) return@forEach
            if (!file.isFile) return@forEach
            if (!fileRegex.matches(file.name)) return@forEach

            try {
                val lines = file.readLines()
                lines.forEachIndexed { idx, line ->
                    if (regex.containsMatchIn(line)) {
                        val result = JSONObject()
                        result.put("file", file.absolutePath)
                        result.put("line", idx + 1)
                        result.put("content", line.trim())

                        // 上下文
                        if (contextLines > 0) {
                            val contextStart = (idx - contextLines).coerceAtLeast(0)
                            val contextEnd = (idx + contextLines).coerceAtMost(lines.lastIndex)
                            val context = JSONArray()
                            for (i in contextStart..contextEnd) {
                                context.put(lines[i].trim())
                            }
                            result.put("context", context)
                        }

                        results.put(result)
                        count++
                        if (count >= maxResults) return@forEachIndexed
                    }
                }
            } catch (e: Exception) {
                // 跳过无法读取的文件
            }
        }

        results
    }

    /**
     * 管道执行: cmd1 | cmd2 | cmd3
     *
     * 在一条 sh -c 中执行,自动处理管道
     */
    suspend fun pipeline(commands: List<String>, workingDir: String = System.getProperty("user.home") ?: "/"): ExecResult {
        val pipeline = commands.joinToString(" | ")
        return exec(pipeline, workingDir)
    }

    // ============ 6. 后台执行 ============

    data class BgTask(
        val taskId: String,
        val command: String,
        var status: BgTaskStatus,
        var output: StringBuilder = StringBuilder(),
        var startedAt: Long = System.currentTimeMillis(),
        var endedAt: Long? = null,
    )

    enum class BgTaskStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

    private val bgTasks = ConcurrentHashMap<String, BgTask>()
    private val bgJobs = ConcurrentHashMap<String, Job>()

    /**
     * 后台执行命令,立即返回 taskId
     *
     * Agent 可轮询 [getBgTaskStatus] 检查状态
     */
    fun execBackground(command: String, workingDir: String = System.getProperty("user.home") ?: "/"): String {
        val taskId = "bg_${System.currentTimeMillis()}"
        val task = BgTask(taskId, command, BgTaskStatus.RUNNING)
        bgTasks[taskId] = task

        val job = scope.launch {
            try {
                val dir = File(workingDir).takeIf { it.exists() } ?: File("/")
                val process = ProcessBuilder("sh", "-c", command).directory(dir).redirectErrorStream(true).start()
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    task.output.appendLine(line)
                    line = reader.readLine()
                }
                val exit = process.waitFor()
                task.status = if (exit == 0) BgTaskStatus.COMPLETED else BgTaskStatus.FAILED
            } catch (e: Exception) {
                task.output.appendLine("Error: ${e.message}")
                task.status = BgTaskStatus.FAILED
            } finally {
                task.endedAt = System.currentTimeMillis()
            }
        }
        bgJobs[taskId] = job

        return taskId
    }

    /** 获取后台任务状态 */
    fun getBgTaskStatus(taskId: String): BgTask? = bgTasks[taskId]

    /** 取消后台任务 */
    fun cancelBgTask(taskId: String) {
        bgJobs[taskId]?.cancel()
        bgTasks[taskId]?.status = BgTaskStatus.CANCELLED
        bgTasks[taskId]?.endedAt = System.currentTimeMillis()
    }

    /** 列出所有后台任务 */
    fun listBgTasks(): List<BgTask> = bgTasks.values.toList()

    // ============ 7. Agent 工具入口(JSON 接口) ============

    /**
     * Agent 工具调用入口(从 JSON 解析参数,返回 JSON 结果)
     *
     * 供 LLM function call 使用。
     */
    suspend fun executeAgentTool(toolName: String, argumentsJson: String): String {
        val args = try { JSONObject(argumentsJson) } catch (e: Exception) { JSONObject() }

        val result = when (toolName) {
            "agent_exec" -> {
                val cmd = args.getString("command")
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                val timeout = args.optLong("timeout_ms", 30_000)
                exec(cmd, dir, timeout).toJson()
            }

            "agent_exec_batch" -> {
                val cmds = args.getJSONArray("commands").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                val stopOnError = args.optBoolean("stop_on_error", true)
                execBatch(cmds, dir, stopOnError).toJson()
            }

            "agent_session_create" -> {
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                val sid = createSession(dir)
                JSONObject().put("sessionId", sid).put("workingDir", dir)
            }

            "agent_session_exec" -> {
                val sid = args.getString("session_id")
                val cmd = args.getString("command")
                val timeout = args.optLong("timeout_ms", 30_000)
                sessionExec(sid, cmd, timeout).toJson()
            }

            "agent_session_close" -> {
                val sid = args.getString("session_id")
                closeSession(sid)
                JSONObject().put("closed", true).put("sessionId", sid)
            }

            "agent_file_tree" -> {
                val path = args.optString("path", ".")
                val depth = args.optInt("max_depth", 3)
                val hidden = args.optBoolean("include_hidden", false)
                fileTree(path, depth, hidden)
            }

            "agent_grep" -> {
                val pattern = args.getString("pattern")
                val path = args.optString("path", ".")
                val filePattern = args.optString("file_pattern", "*")
                val context = args.optInt("context_lines", 0)
                val max = args.optInt("max_results", 50)
                JSONObject().put("results", grep(pattern, path, filePattern, context, max))
            }

            "agent_pipeline" -> {
                val cmds = args.getJSONArray("commands").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                pipeline(cmds, dir).toJson()
            }

            "agent_bg_exec" -> {
                val cmd = args.getString("command")
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                val taskId = execBackground(cmd, dir)
                JSONObject().put("taskId", taskId).put("status", "RUNNING")
            }

            "agent_bg_status" -> {
                val taskId = args.getString("task_id")
                val task = getBgTaskStatus(taskId)
                if (task != null) {
                    JSONObject().put("taskId", task.taskId).put("status", task.status.name)
                        .put("output", task.output.toString()).put("durationMs", (task.endedAt ?: System.currentTimeMillis()) - task.startedAt)
                } else {
                    JSONObject().put("error", "Task not found: $taskId")
                }
            }

            "agent_bg_cancel" -> {
                val taskId = args.getString("task_id")
                cancelBgTask(taskId)
                JSONObject().put("cancelled", true).put("taskId", taskId)
            }

            "agent_exec_parallel" -> {
                val tasksArr = args.getJSONArray("tasks")
                val timeout = args.optLong("timeout_ms", 60_000)
                execParallelJson(tasksArr, timeout)
            }

            "agent_exec_retry" -> {
                val cmd = args.getString("command")
                val dir = args.optString("working_dir", System.getProperty("user.home") ?: "/")
                val retries = args.optInt("max_retries", 3)
                val backoff = args.optLong("backoff_ms", 1000)
                execWithRetry(cmd, dir, retries, backoff).toJson()
            }

            "agent_template_list" -> {
                listTemplates()
            }

            "agent_template_exec" -> {
                val templateId = args.getString("template_id")
                val params = mutableMapOf<String, String>()
                val paramsObj = args.optJSONObject("parameters")
                if (paramsObj != null) {
                    val keys = paramsObj.keys()
                    while (keys.hasNext()) { val k = keys.next(); params[k] = paramsObj.getString(k) }
                }
                execTemplate(templateId, params).toJson()
            }

            "agent_audit_logs" -> {
                val limit = args.optInt("limit", 50)
                getAuditLogs(limit)
            }

            "agent_stats" -> {
                getStats()
            }

            else -> JSONObject().put("error", "Unknown tool: $toolName")
        }

        return result.toString()
    }

    /** 获取所有可用工具的 schema(供 LLM function calling) */
    fun getToolSchemas(): JSONArray {
        val tools = JSONArray()
        listOf(
            """{"name":"agent_exec","description":"执行单条 shell 命令,返回 stdout/stderr/exitCode","parameters":{"command":{"type":"string","description":"shell 命令"},"working_dir":{"type":"string","description":"工作目录,默认 ~"},"timeout_ms":{"type":"number","description":"超时毫秒,默认 30000"}}}""",
            """{"name":"agent_exec_batch","description":"批量执行多条命令,共享工作目录","parameters":{"commands":{"type":"array","items":{"type":"string"}},"working_dir":{"type":"string"},"stop_on_error":{"type":"boolean","default":true}}}""",
            """{"name":"agent_session_create","description":"创建持久会话(保持工作目录/环境变量)","parameters":{"working_dir":{"type":"string","default":"~"}}}""",
            """{"name":"agent_session_exec","description":"在会话中执行命令(保持状态)","parameters":{"session_id":{"type":"string"},"command":{"type":"string"},"timeout_ms":{"type":"number","default":30000}}}""",
            """{"name":"agent_session_close","description":"关闭会话","parameters":{"session_id":{"type":"string"}}}""",
            """{"name":"agent_file_tree","description":"获取目录树 JSON","parameters":{"path":{"type":"string","default":"."},"max_depth":{"type":"number","default":3},"include_hidden":{"type":"boolean","default":false}}}""",
            """{"name":"agent_grep","description":"高级搜索(正则+文件过滤+上下文)","parameters":{"pattern":{"type":"string"},"path":{"type":"string","default":"."},"file_pattern":{"type":"string","default":"*"},"context_lines":{"type":"number","default":0},"max_results":{"type":"number","default":50}}}""",
            """{"name":"agent_pipeline","description":"管道执行 cmd1|cmd2|cmd3","parameters":{"commands":{"type":"array","items":{"type":"string"}},"working_dir":{"type":"string"}}}""",
            """{"name":"agent_bg_exec","description":"后台执行,返回 taskId","parameters":{"command":{"type":"string"},"working_dir":{"type":"string"}}}""",
            """{"name":"agent_bg_status","description":"查询后台任务状态","parameters":{"task_id":{"type":"string"}}}""",
            """{"name":"agent_bg_cancel","description":"取消后台任务","parameters":{"task_id":{"type":"string"}}}""",
        ).forEach { tools.put(JSONObject(it)) }
        return tools
    }

    // ============ 辅助 ============

    private fun resolvePath(path: String, base: String): String {
        val expanded = path.replace("~", System.getProperty("user.home") ?: "/")
        return if (File(expanded).isAbsolute) expanded else File(base, expanded).canonicalPath
    }

    private fun parseExport(command: String): Pair<String, String>? {
        val match = Regex("""export\s+(\w+)=(.+)""").matchEntire(command) ?: return null
        val key = match.groupValues[1]
        var value = match.groupValues[2].trim()
        // 去引号
        if (value.startsWith("\"") && value.endsWith("\"")) value = value.removeSurrounding("\"")
        else if (value.startsWith("'") && value.endsWith("'")) value = value.removeSurrounding("'")
        return key to value
    }

    /** 清理所有资源 */
    fun shutdown() {
        bgJobs.values.forEach { it.cancel() }
        bgJobs.clear()
        bgTasks.clear()
        sessions.clear()
        scope.cancel()
    }

    // ============ 8. 输出截断策略 ============

    /**
     * 智能截断长输出,避免 LLM context window 爆炸
     *
     * 策略:
     * - 保留头部(开始几行,通常是命令本身+前几行输出)
     * - 保留尾部(最后几行,通常是结果/错误/提示)
     * - 中间用 [truncated N lines] 标记
     *
     * @param text 原始输出
     * @param maxChars 最大字符数(默认 4000,约 1000 token)
     */
    fun truncateOutput(text: String, maxChars: Int = 4000): String {
        if (text.length <= maxChars) return text

        val lines = text.lines()
        if (lines.isEmpty()) return text

        val headLines = 15
        val tailLines = 25
        val headChars = maxChars * 30 / 100  // 30% 给头部
        val tailChars = maxChars * 60 / 100  // 60% 给尾部

        val head = lines.take(headLines).joinToString("\n").take(headChars)
        val tail = lines.takeLast(tailLines).joinToString("\n").takeLast(tailChars)
        val truncatedLines = lines.size - headLines - tailLines

        return buildString {
            append(head)
            append("\n\n... [truncated $truncatedLines lines] ...\n\n")
            append(tail)
        }
    }

    /**
     * 执行命令并自动截断输出(适合直接返回给 LLM)
     */
    suspend fun execTruncated(
        command: String,
        workingDir: String = System.getProperty("user.home") ?: "/",
        timeoutMs: Long = 30_000,
        maxOutputChars: Int = 4000,
    ): ExecResult {
        val result = exec(command, workingDir, timeoutMs)
        return result.copy(
            stdout = truncateOutput(result.stdout, maxOutputChars),
            stderr = truncateOutput(result.stderr, maxOutputChars / 2),
        )
    }

    // ============ 9. 并行执行 ============

    data class ParallelTask(
        val id: String,
        val command: String,
        val workingDir: String = System.getProperty("user.home") ?: "/",
    )

    data class ParallelResult(
        val taskId: String,
        val result: ExecResult,
    )

    /**
     * 并行执行多条独立命令(互不依赖)
     *
     * 适合: 同时检查多个文件、同时 ping 多个服务器、同时获取多种系统信息
     *
     * @param tasks 任务列表
     * @param timeoutMs 总超时
     */
    suspend fun execParallel(
        tasks: List<ParallelTask>,
        timeoutMs: Long = 60_000,
    ): List<ParallelResult> = withTimeoutOrNull(timeoutMs) {
        coroutineScope {
            tasks.map { task ->
                async(Dispatchers.IO) {
                    ParallelResult(task.id, exec(task.command, task.workingDir, timeoutMs / tasks.size))
                }
            }.awaitAll()
        }
    } ?: emptyList()

    /**
     * 并行执行并返回 JSON
     */
    suspend fun execParallelJson(
        tasksJson: JSONArray,
        timeoutMs: Long = 60_000,
    ): JSONArray {
        val tasks = (0 until tasksJson.length()).map { i ->
            val o = tasksJson.getJSONObject(i)
            ParallelTask(
                id = o.getString("id"),
                command = o.getString("command"),
                workingDir = o.optString("working_dir", System.getProperty("user.home") ?: "/"),
            )
        }
        val results = execParallel(tasks, timeoutMs)
        val arr = JSONArray()
        results.forEach { pr ->
            arr.put(JSONObject().put("taskId", pr.taskId).put("result", pr.result.toJson()))
        }
        return arr
    }

    // ============ 10. 自动重试 ============

    /**
     * 带重试的命令执行
     *
     * @param command 命令
     * @param maxRetries 最大重试次数(默认 3)
     * @param backoffMs 退避基数(默认 1000ms,指数增长)
     * @param retryOnExitCodes 哪些 exitCode 触发重试(默认 -1=超时/1=一般错误)
     */
    suspend fun execWithRetry(
        command: String,
        workingDir: String = System.getProperty("user.home") ?: "/",
        maxRetries: Int = 3,
        backoffMs: Long = 1000,
        retryOnExitCodes: Set<Int> = setOf(-1, 1),
        timeoutMs: Long = 30_000,
    ): ExecResult {
        var lastResult: ExecResult? = null
        for (attempt in 0..maxRetries) {
            lastResult = exec(command, workingDir, timeoutMs)
            if (lastResult.success || lastResult.exitCode !in retryOnExitCodes) {
                return lastResult
            }
            if (attempt < maxRetries) {
                kotlinx.coroutines.delay(backoffMs * (1L shl attempt))  // 指数退避
            }
        }
        return lastResult ?: ExecResult("", "Max retries exceeded", -1, 0, workingDir)
    }

    // ============ 11. 命令模板 ============

    /**
     * 命令模板 — 常用操作一键执行
     *
     * Agent 可以用模板名代替记住复杂命令
     */
    data class CommandTemplate(
        val id: String,
        val name: String,
        val description: String,
        val commandTemplate: String,  // 含 {占位符}
        val parameters: List<TemplateParam>,
    )

    data class TemplateParam(
        val name: String,
        val description: String,
        val required: Boolean = true,
        val defaultValue: String? = null,
    )

    /** 内置命令模板 */
    val builtinTemplates: List<CommandTemplate> = listOf(
        CommandTemplate(
            "git_status", "Git 状态", "查看 git 仓库状态",
            "cd {repo} && git status -sb && echo '---' && git log --oneline -5",
            listOf(TemplateParam("repo", "仓库路径", false, ".")),
        ),
        CommandTemplate(
            "git_commit_push", "Git 提交推送", "提交所有修改并推送",
            "cd {repo} && git add -A && git commit -m \"{message}\" && git push",
            listOf(TemplateParam("repo", "仓库路径", false, "."), TemplateParam("message", "提交信息")),
        ),
        CommandTemplate(
            "build_gradle", "Gradle 构建", "执行 gradle build",
            "cd {project} && ./gradlew {task} --no-daemon 2>&1 | tail -30",
            listOf(TemplateParam("project", "项目路径", false, "."), TemplateParam("task", "gradle task", false, "build")),
        ),
        CommandTemplate(
            "find_process", "查找进程", "按名称查找进程",
            "ps aux | grep -i \"{name}\" | grep -v grep",
            listOf(TemplateParam("name", "进程名关键词")),
        ),
        CommandTemplate(
            "disk_usage", "磁盘分析", "分析目录磁盘使用",
            "cd {path} && du -sh * 2>/dev/null | sort -rh | head -20",
            listOf(TemplateParam("path", "分析路径", false, ".")),
        ),
        CommandTemplate(
            "port_info", "端口信息", "查看端口占用详情",
            "lsof -i:{port} 2>/dev/null || netstat -tlnp 2>/dev/null | grep {port} || ss -tlnp | grep {port}",
            listOf(TemplateParam("port", "端口号")),
        ),
        CommandTemplate(
            "file_search", "文件搜索", "按名称搜索文件",
            "find {path} -name \"{pattern}\" -type f 2>/dev/null | head -20",
            listOf(TemplateParam("path", "搜索路径", false, "."), TemplateParam("pattern", "文件名模式(支持*)", false, "*")),
        ),
        CommandTemplate(
            "env_check", "环境检查", "检查开发环境版本",
            "echo '=== Node ===' && node --version 2>/dev/null; echo '=== npm ===' && npm --version 2>/dev/null; echo '=== Java ===' && java -version 2>&1; echo '=== Python ===' && python3 --version 2>/dev/null; echo '=== Git ===' && git --version",
            emptyList(),
        ),
    )

    /** 列出所有可用模板 */
    fun listTemplates(): JSONArray {
        val arr = JSONArray()
        builtinTemplates.forEach { t ->
            val params = JSONArray()
            t.parameters.forEach { p ->
                params.put(JSONObject().put("name", p.name).put("description", p.description).put("required", p.required).put("default", p.defaultValue ?: ""))
            }
            arr.put(JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("description", t.description)
                .put("command", t.commandTemplate)
                .put("parameters", params))
        }
        return arr
    }

    /** 执行模板 */
    suspend fun execTemplate(templateId: String, params: Map<String, String>): ExecResult {
        val template = builtinTemplates.firstOrNull { it.id == templateId }
            ?: return ExecResult("", "Template not found: $templateId", -1, 0, "")

        var command = template.commandTemplate
        template.parameters.forEach { p ->
            val value = params[p.name] ?: p.defaultValue ?: if (p.required) {
                return ExecResult("", "Missing required parameter: ${p.name}", -1, 0, "")
            } else ""
            command = command.replace("{${p.name}}", value)
        }

        return execTruncated(command)
    }

    // ============ 12. 执行日志审计 ============

    data class AuditLog(
        val id: String,
        val timestamp: Long,
        val toolName: String,
        val command: String,
        val success: Boolean,
        val exitCode: Int,
        val durationMs: Long,
        val outputLength: Int,
        val agentId: String?,
    )

    private val _auditLogs = kotlinx.coroutines.flow.MutableStateFlow<List<AuditLog>>(emptyList())
    val auditLogs: kotlinx.coroutines.flow.StateFlow<List<AuditLog>> = _auditLogs

    private fun logAudit(toolName: String, command: String, result: ExecResult, agentId: String? = null) {
        val log = AuditLog(
            id = "audit_${System.currentTimeMillis()}_${(1..999).random()}",
            timestamp = System.currentTimeMillis(),
            toolName = toolName,
            command = command.take(200),
            success = result.success,
            exitCode = result.exitCode,
            durationMs = result.durationMs,
            outputLength = result.stdout.length + result.stderr.length,
            agentId = agentId,
        )
        _auditLogs.value = (_auditLogs.value + log).takeLast(200)
    }

    /** 获取审计日志(JSON) */
    fun getAuditLogs(limit: Int = 50): JSONArray {
        val arr = JSONArray()
        _auditLogs.value.takeLast(limit).reversed().forEach { log ->
            arr.put(JSONObject()
                .put("id", log.id)
                .put("timestamp", log.timestamp)
                .put("time", java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(log.timestamp)))
                .put("tool", log.toolName)
                .put("command", log.command)
                .put("success", log.success)
                .put("exitCode", log.exitCode)
                .put("durationMs", log.durationMs)
                .put("outputLength", log.outputLength))
        }
        return arr
    }

    /** 清空审计日志 */
    fun clearAuditLogs() {
        _auditLogs.value = emptyList()
    }

    /** 获取统计信息 */
    fun getStats(): JSONObject {
        val logs = _auditLogs.value
        val total = logs.size
        val success = logs.count { it.success }
        val failed = total - success
        val avgDuration = if (total > 0) logs.sumOf { it.durationMs } / total else 0
        val totalOutput = logs.sumOf { it.outputLength }

        return JSONObject()
            .put("totalExecutions", total)
            .put("successCount", success)
            .put("failedCount", failed)
            .put("successRate", if (total > 0) "%.1f%%".format(success * 100.0 / total) else "N/A")
            .put("avgDurationMs", avgDuration)
            .put("totalOutputChars", totalOutput)
            .put("activeSessions", sessions.size)
            .put("activeBgTasks", bgTasks.count { it.status == BgTaskStatus.RUNNING })
    }
}
