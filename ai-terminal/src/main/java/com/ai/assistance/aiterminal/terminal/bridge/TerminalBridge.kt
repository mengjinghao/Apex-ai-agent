package com.ai.assistance.aiterminal.terminal.bridge

import com.ai.assistance.aiterminal.terminal.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 终端执行结果。
 */
data class TerminalExecutionResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val exitCode: Int = 0,
    val executionTimeMs: Long = 0,
    val sessionId: String = ""
)

/**
 * 终端命令请求。
 */
data class TerminalCommandRequest(
    val command: String,
    val agentId: String,
    val agentRole: String = "worker",
    val requireRoot: Boolean = false,
    val timeoutMs: Long = 30_000,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap()
)

/**
 * 终端权限级别。
 */
enum class TerminalPermission(val displayName: String, val description: String) {
    FULL("完全权限", "可执行任何命令，包括危险命令"),
    SAFE_ONLY("仅安全命令", "不可执行高危命令"),
    READ_ONLY("仅读取", "只能执行读取类命令"),
    DENIED("拒绝", "无终端访问权限")
}

/**
 * 命令风险级别。
 */
enum class RiskLevel(val displayName: String, val color: Long) {
    LOW("低风险", 0xFF4ADE80),
    MEDIUM("中风险", 0xFFFBBF24),
    HIGH("高风险", 0xFFFB923C),
    CRITICAL("极高风险", 0xFFEF4444)
}

/**
 * 终端桥接器 — Agent 调用终端的统一入口。
 *
 * 让任何模式的任何 Agent 都能调用终端执行命令。
 *
 * 核心能力：
 * - 会话池：每个 Agent 拥有独立终端会话
 * - 权限控制：根据 Agent 角色限制可执行的命令
 * - 风险拦截：危险命令自动拦截
 * - 结果回传：Agent 获取命令输出和退出码
 * - 批量执行：支持脚本式批量命令
 */
class TerminalBridge(
    private val terminal: ApexTerminal
) {

    private val agentSessions = ConcurrentHashMap<String, String>()
    private val rolePermissions = ConcurrentHashMap<String, TerminalPermission>()
    private val executionHistory = ConcurrentHashMap<String, MutableList<TerminalExecutionResult>>()

    init {
        rolePermissions["supervisor"] = TerminalPermission.FULL
        rolePermissions["worker"] = TerminalPermission.SAFE_ONLY
        rolePermissions["reviewer"] = TerminalPermission.READ_ONLY
        rolePermissions["critic"] = TerminalPermission.READ_ONLY
        rolePermissions["observer"] = TerminalPermission.READ_ONLY
        rolePermissions["coordinator"] = TerminalPermission.SAFE_ONLY
        rolePermissions["red_team"] = TerminalPermission.FULL
        rolePermissions["blue_team"] = TerminalPermission.SAFE_ONLY
        rolePermissions["system"] = TerminalPermission.FULL
    }

    /**
     * 执行终端命令。
     */
    suspend fun execute(request: TerminalCommandRequest): TerminalExecutionResult {
        val startTime = System.currentTimeMillis()

        // 权限检查
        val permission = rolePermissions[request.agentRole] ?: TerminalPermission.DENIED
        if (permission == TerminalPermission.DENIED) {
            return TerminalExecutionResult(
                success = false,
                error = "Agent " + request.agentId + " (role: " + request.agentRole + ") has no terminal permission",
                exitCode = -1
            )
        }

        // 风险评估 (E-2: block CRITICAL and HIGH for non-privileged agents)
        val risk = assessRisk(request.command)
        if ((risk == RiskLevel.CRITICAL || risk == RiskLevel.HIGH) && permission != TerminalPermission.FULL) {
            return TerminalExecutionResult(
                success = false,
                error = "Command blocked (" + risk + " risk): " + request.command,
                exitCode = -1
            )
        }

        // READ_ONLY 检查
        if (permission == TerminalPermission.READ_ONLY && !isReadOnlyCommand(request.command)) {
            return TerminalExecutionResult(
                success = false,
                error = "Agent role '" + request.agentRole + "' has READ_ONLY permission",
                exitCode = -1
            )
        }

        // 获取或创建 Agent 会话
        val sessionId = getOrCreateSession(request.agentId)

        // 包装 root 命令
        val command = if (request.requireRoot && !request.command.startsWith("su")) {
            // Security (B-2): use single-quote escaping instead of double quotes
            // to prevent root shell injection via the command payload.
            "su -c '" + escapeShellSingleQuote(request.command) + "'"
        } else {
            request.command
        }

        // 在终端中显示 Agent 调用
        terminal.sessionManager.sendMessage(sessionId, TerminalMessage.agent(
            "Executing: $command",
            request.agentId,
            request.agentRole
        ))

        // 执行命令
        val result = withContext(Dispatchers.IO) {
            val output = simulateCommand(command)
            TerminalExecutionResult(
                success = output.isNotEmpty(),
                output = output,
                exitCode = 0,
                executionTimeMs = System.currentTimeMillis() - startTime,
                sessionId = sessionId
            )
        }

        // 记录历史
        recordHistory(request.agentId, result)

        // 发送结果到终端
        if (result.success) {
            terminal.sessionManager.sendMessage(sessionId, TerminalMessage.output(result.output))
        } else {
            terminal.sessionManager.sendMessage(sessionId, TerminalMessage.error(result.error))
        }

        return result
    }

    /**
     * 便捷执行方法。
     */
    suspend fun execute(
        command: String,
        agentId: String,
        agentRole: String = "worker",
        requireRoot: Boolean = false
    ): TerminalExecutionResult {
        return execute(TerminalCommandRequest(command, agentId, agentRole, requireRoot))
    }

    /**
     * 批量执行命令。
     */
    suspend fun executeBatch(agentId: String, agentRole: String, commands: List<String>): List<TerminalExecutionResult> {
        return commands.map { execute(it, agentId, agentRole) }
    }

    /**
     * 获取或创建 Agent 的专属会话。
     */
    fun getOrCreateSession(agentId: String): String {
        return agentSessions.computeIfAbsent(agentId) {
            val session = terminal.createSession("agent-" + agentId)
            terminal.sessionManager.sendMessage(session.id, TerminalMessage.system(
                "Agent " + agentId + " session created"
            ))
            session.id
        }
    }

    /**
     * 设置 Agent 角色权限。
     */
    fun setRolePermission(role: String, permission: TerminalPermission) {
        rolePermissions[role] = permission
    }

    /**
     * 获取 Agent 执行历史。
     */
    fun getHistory(agentId: String): List<TerminalExecutionResult> {
        return executionHistory[agentId]?.toList() ?: emptyList()
    }

    /**
     * 关闭 Agent 会话。
     */
    fun closeAgentSession(agentId: String) {
        val sessionId = agentSessions.remove(agentId) ?: return
        terminal.sessionManager.closeSession(sessionId)
        executionHistory.remove(agentId)
    }

    /**
     * 获取所有活跃 Agent 会话。
     */
    fun getActiveAgentSessions(): Map<String, String> = agentSessions.toMap()

    // ===== 内部方法 =====

    /**
     * Security (B-2): escape single quotes for safe interpolation inside a single-quoted
     * shell argument. Standard POSIX technique: replace ' with '\''.
     */
    private fun escapeShellSingleQuote(s: String): String = com.ai.assistance.aiterminal.terminal.ai.ShellEscape.singleQuote(s)

    private fun assessRisk(command: String): RiskLevel {
        // Security (E-2): delegate to the thorough ai.DangerousCommandPatterns library
        // (25+ regex patterns across 4 risk tiers) instead of the previous weak substring
        // check. Map the ai.RiskLevel enum to the local bridge.RiskLevel by name (both
        // enums define LOW/MEDIUM/HIGH/CRITICAL).
        val matched = com.ai.assistance.aiterminal.terminal.ai.DangerousCommandPatterns.matchPattern(command)
        return when (matched?.riskLevel?.name) {
            "CRITICAL" -> RiskLevel.CRITICAL
            "HIGH" -> RiskLevel.HIGH
            "MEDIUM" -> RiskLevel.MEDIUM
            "LOW" -> RiskLevel.LOW
            else -> RiskLevel.LOW
        }
    }

    private fun isReadOnlyCommand(command: String): Boolean {
        val lower = command.lowercase().trim()
        val readOnlyPrefixes = listOf("ls", "cat", "head", "tail", "grep", "find", "ps", "df", "free",
            "uname", "whoami", "pwd", "date", "echo", "which", "ifconfig", "ip addr", "getprop")
        return readOnlyPrefixes.any { lower.startsWith(it) }
    }

    private fun recordHistory(agentId: String, result: TerminalExecutionResult) {
        val history = executionHistory.computeIfAbsent(agentId) { mutableListOf() }
        history.add(result)
        while (history.size > 100) history.removeAt(0)
    }

    private fun simulateCommand(command: String): String {
        val lower = command.lowercase().trim()
        return when {
            lower.startsWith("ls") -> "total 42\ndrwxr-xr-x 3 root root 4096 app\n-rw-r--r-- 1 root root 1024 build.gradle.kts"
            lower.startsWith("pwd") -> "/home/apex"
            lower.startsWith("echo") -> command.removePrefix("echo ").trim().trim('"')
            lower.startsWith("whoami") -> "apex"
            lower.startsWith("date") -> java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", java.util.Locale.US).format(java.util.Date())
            lower.startsWith("cat ") -> "[file content]"
            lower.startsWith("ps") -> "PID USER COMMAND\n1 root init\n42 apex apex-agent"
            lower.startsWith("df") -> "Filesystem Size Used Free\n/ 128G 64G 64G"
            lower.startsWith("free") -> "Mem: 8G 4G 4G"
            lower.startsWith("uname") -> "Linux localhost 5.15.0 #1 SMP aarch64"
            lower.startsWith("ifconfig") || lower.startsWith("ip addr") -> "eth0: inet 192.168.1.100"
            lower.startsWith("which ") -> "/usr/bin/" + command.removePrefix("which ").trim()
            else -> "[executed: $command]"
        }
    }
}
