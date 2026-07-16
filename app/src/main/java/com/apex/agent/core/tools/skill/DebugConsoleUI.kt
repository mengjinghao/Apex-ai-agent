package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

class DebugConsoleUI private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DebugConsoleUI"
        private const val MAX_CONSOLE_LINES = 500
        private const val MAX_WATCHED_VARIABLES = 20

        @Volatile private var INSTANCE: DebugConsoleUI? = null

        fun getInstance(context: Context): DebugConsoleUI {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DebugConsoleUI(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    enum class ConsoleLevel {
        DEBUG,
        INFO,
        WARNING,
        ERROR,
        SUCCESS
    }

    data class ConsoleLine(
        val id: String = java.util.UUID.randomUUID().toString(),
        val level: ConsoleLevel,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String? = null,
        val toolName: String? = null,
        val details: Map<String, Any>? = null
    ) {
        fun toFormattedString(): String {
            val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            val levelStr = when (level) {
                ConsoleLevel.DEBUG -> "DBG"
                ConsoleLevel.INFO -> "INF"
                ConsoleLevel.WARNING -> "WRN"
                ConsoleLevel.ERROR -> "ERR"
                ConsoleLevel.SUCCESS -> "OK "
            }
            val toolPrefix = toolName?.let { "[${it}] " } ?: ""
            val sourceSuffix = source?.let { " (${it})" } ?: ""
            return "${timeStr} ${levelStr}${toolPrefix}${message}${sourceSuffix}"
        }
    }

    data class DebugState(
        val sessionId: String?,
        val skillName: String?,
        val state: SkillDebugger.DebugState,
        val isPaused: Boolean,
        val currentTool: String?,
        val currentLine: Int?,
        val toolCallCount: Long,
        val errorCount: Long,
        val elapsedTimeMs: Long,
        val hitBreakpoint: SkillDebugger.Breakpoint?,
        val pauseReason: SkillDebugger.PauseReason?
    )

    data class WatchVariable(
        val name: String,
        val expression: String,
        val lastValue: Any? = null,
        val lastUpdated: Long = System.currentTimeMillis(),
        val watchCount: Int = 0
    )

    private val consoleLines = CopyOnWriteArrayList<ConsoleLine>()
    private val watchedVariables = CopyOnWriteArrayList<WatchVariable>()
    private val breakpoints = CopyOnWriteArrayList<SkillDebugger.Breakpoint>()

    private val consoleListeners = CopyOnWriteArrayList<ConsoleListener>()

    interface ConsoleListener {
        fun onConsoleUpdated(lines: List<ConsoleLine>)
        fun onStateChanged(state: DebugState)
        fun onBreakpointHit(breakpoint: SkillDebugger.Breakpoint)
        fun onToolCallRecorded(toolCall: SkillDebugger.ToolCall)
        fun onVariablesChanged(variables: Map<String, Any>)
    }

    fun addConsoleListener(listener: ConsoleListener) {
        if (!consoleListeners.contains(listener)) {
            consoleListeners.add(listener)
        }
    }

    fun removeConsoleListener(listener: ConsoleListener) {
        consoleListeners.remove(listener)
    }

    fun log(level: ConsoleLevel, message: String, source: String? = null, toolName: String? = null, details: Map<String, Any>? = null) {
        val line = ConsoleLine(
            level = level,
            message = message,
            source = source,
            toolName = toolName,
            details = details
        )
        addConsoleLine(line)
    }

    fun debug(message: String, source: String? = null) = log(ConsoleLevel.DEBUG, message, source)
    fun info(message: String, source: String? = null) = log(ConsoleLevel.INFO, message, source)
    fun warning(message: String, source: String? = null) = log(ConsoleLevel.WARNING, message, source)
    fun error(message: String, source: String? = null) = log(ConsoleLevel.ERROR, message, source)
    fun success(message: String, source: String? = null) = log(ConsoleLevel.SUCCESS, message, source)

    fun logToolCallStart(toolName: String, input: Map<String, Any?>) {
        val inputStr = input.entries.joinToString(", ") { "${it.key}=${it.value}" }
        log(
            ConsoleLevel.INFO,
            "Tool call START: ${toolName}",
            toolName = toolName,
            details = input
        )
        log(ConsoleLevel.DEBUG, "  Input: ${inputStr}", toolName = toolName)
    }

    fun logToolCallEnd(toolName: String, output: Any?, error: String?, durationMs: Long) {
        if (error != null) {
            log(
                ConsoleLevel.ERROR,
                "Tool call END (ERROR): ${toolName} - ${error}",
                toolName = toolName,
                details = mapOf("durationMs" to (durationMs ?: 0))
            )
        } else {
            val outputStr = output?.toString()?.take(200) ?: "null"
            log(
                ConsoleLevel.SUCCESS,
                "Tool call END: ${toolName} (${durationMs ?: 0}ms)",
                toolName = toolName,
                details = mapOf("output" to outputStr, "durationMs" to (durationMs ?: 0))
            )
        }
    }

    fun logBreakpointHit(breakpoint: SkillDebugger.Breakpoint) {
        val conditionStr = breakpoint.condition?.let { " [${it}]" } ?: ""
        log(
            ConsoleLevel.WARNING,
            "BREAKPOINT HIT: ${breakpoint.type.name} - ${breakpoint.target}${conditionStr} (hit count: ${breakpoint.hitCount.get()})",
            details = mapOf(
                "breakpointId" to breakpoint.id,
                "breakpointType" to breakpoint.type.name,
                "target" to breakpoint.target
            )
        )
        notifyBreakpointHit(breakpoint)
    }

    fun logSessionStart(sessionId: String, skillName: String) {
        log(
            ConsoleLevel.INFO,
            "=== Debug Session Started: ${skillName} ===",
            details = mapOf("sessionId" to sessionId, "skillName" to skillName)
        )
    }

    fun logSessionEnd(sessionId: String, skillName: String, totalDurationMs: Long, toolCallCount: Int, errorCount: Int) {
        val status = if (errorCount == 0) ConsoleLevel.SUCCESS else ConsoleLevel.ERROR
        log(
            status,
            "=== Debug Session Ended: ${skillName} === (Duration: ${totalDurationMs}ms, Tools: ${toolCallCount}, Errors: ${errorCount})",
            details = mapOf(
                "sessionId" to sessionId,
                "skillName" to skillName,
                "totalDurationMs" to totalDurationMs,
                "toolCallCount" to toolCallCount,
                "errorCount" to errorCount
            )
        )
    }

    private fun addConsoleLine(line: ConsoleLine) {
        consoleLines.add(line)
        while (consoleLines.size > MAX_CONSOLE_LINES) {
            consoleLines.removeAt(0)
        }
        notifyConsoleUpdated()
    }

    fun getConsoleLines(): List<ConsoleLine> = consoleLines.toList()

    fun getConsoleLines(level: ConsoleLevel): List<ConsoleLine> {
        return consoleLines.filter { it.level == level }
    }

    fun getConsoleLines(toolName: String): List<ConsoleLine> {
        return consoleLines.filter { it.toolName == toolName }
    }

    fun clearConsole() {
        consoleLines.clear()
        notifyConsoleUpdated()
    }

    fun exportConsole(): String {
        return buildString {
            consoleLines.forEach { line ->
                appendLine(line.toFormattedString())
                line.details?.forEach { (key, value) ->
                    appendLine("    ${key}: ${value}")
                }
            }
        }
    }

    fun addWatchVariable(name: String, expression: String) {
        if (watchedVariables.size >= MAX_WATCHED_VARIABLES) {
            warning("Maximum watched variables reached (${MAX_WATCHED_VARIABLES})")
            return
        }
        val watch = WatchVariable(name = name, expression = expression)
        watchedVariables.add(watch)
        info("Added watch: ${name} = ${expression}")
    }

    fun removeWatchVariable(name: String) {
        watchedVariables.removeIf { it.name == name }
    }

    fun getWatchedVariables(): List<WatchVariable> = watchedVariables.toList()

    fun updateWatchedVariables(variables: Map<String, Any>) {
        watchedVariables.forEach { watch ->
            val newValue = variables[watch.expression] ?: evaluateExpression(watch.expression, variables)
            if (newValue != watch.lastValue) {
                watch.lastValue = newValue
                watch.lastUpdated = System.currentTimeMillis()
                watch.watchCount++
                log(
                    ConsoleLevel.DEBUG,
                    "Watch [${watch}.name]: ${watch.lastValue}",
                    details = mapOf("expression" to watch.expression, "newValue" to (newValue ?: "null"))
                )
            }
        }
        notifyVariablesChanged(variables)
    }

    private fun evaluateExpression(expression: String, variables: Map<String, Any>): Any? {
        return variables[expression]
    }

    fun addBreakpoint(breakpoint: SkillDebugger.Breakpoint) {
        breakpoints.add(breakpoint)
        debug("Breakpoint added: ${breakpoint.id} (${breakpoint.type.name}: ${breakpoint.target})")
    }

    fun removeBreakpoint(breakpointId: String) {
        breakpoints.removeIf { it.id == breakpointId }
    }

    fun getBreakpoints(): List<SkillDebugger.Breakpoint> = breakpoints.toList()

    fun updateBreakpoints(breakpointList: List<SkillDebugger.Breakpoint>) {
        breakpoints.clear()
        breakpoints.addAll(breakpointList)
    }

    fun getCurrentState(session: SkillDebugger.DebugSession): DebugState {
        return DebugState(
            sessionId = session?.id,
            skillName = session?.skillName,
            state = session?.state ?: SkillDebugger.DebugState.IDLE,
            isPaused = session?.state == SkillDebugger.DebugState.PAUSED,
            currentTool = session?.currentContext?.currentTool,
            currentLine = session?.currentContext?.currentLine,
            toolCallCount = session?.currentContext?.toolCallCount ?: 0,
            errorCount = session?.currentContext?.errorCount ?: 0,
            elapsedTimeMs = session?.currentContext?.elapsedTimeMs ?: 0,
            hitBreakpoint = session?.breakpoints?.values?.find { it.hitCount.get() > 0 && it.enabled },
            pauseReason = session?.pauseReason
        )
    }

    fun buildStateSummary(state: DebugState): String {
        val sb = StringBuilder()
        sb.appendLine("┌─────────────────────────────────────────────────────────────�?)
        sb.appendLine("�?Skill Debug Console                           ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())} �?)
        sb.appendLine("├─────────────────────────────────────────────────────────────�?)

        state.sessionId?.let { sessionId ->
            sb.appendLine("�?Session: ${sessionId.take(20).padEnd(48)}�?)
        }
        state.skillName?.let { skillName ->
            sb.appendLine("�?Skill: ${skillName.padEnd(51)}�?)
        }

        val statusStr = when (state.state) {
            SkillDebugger.DebugState.IDLE -> "IDLE"
            SkillDebugger.DebugState.RUNNING -> "RUNNING"
            SkillDebugger.DebugState.PAUSED -> "PAUSED"
            SkillDebugger.DebugState.STEP_MODE -> "STEP_MODE"
            SkillDebugger.DebugState.TERMINATED -> "TERMINATED"
        }
        val statusColor = when (state.state) {
            SkillDebugger.DebugState.RUNNING -> "�?
            SkillDebugger.DebugState.PAUSED -> "�?
            SkillDebugger.DebugState.STEP_MODE -> "�?
            else -> "�?
        }
        sb.appendLine("�?Status: ${statusColor} ${statusStr}${" ".repeat(42 - statusStr.length - 3)}�?)

        if (state.isPaused) {
            state.currentTool?.let {
                sb.appendLine("�?Current Tool: ${it.padEnd(42)}�?)
            }
            state.currentLine?.let {
                sb.appendLine("�?Current Line: ${it.toString().padEnd(43)}�?)
            }
            state.pauseReason?.let {
                sb.appendLine("�?Pause Reason: ${it.name.padEnd(39)}�?)
            }
        }

        sb.appendLine("├─────────────────────────────────────────────────────────────�?)
        sb.appendLine("�?Execution Stats                                              �?)
        sb.appendLine("�?  Tool Calls: ${state.toolCallCount.toString().padEnd(44)}�?)
        sb.appendLine("�?  Errors: ${state.errorCount.toString().padEnd(47)}�?)
        sb.appendLine("�?  Elapsed: ${state.elapsedTimeMs.toString().padEnd(45)}�?)
        sb.appendLine("�?  Breakpoints: ${breakpoints.size.toString().padEnd(43)}�?)

        if (watchedVariables.isNotEmpty()) {
            sb.appendLine("├─────────────────────────────────────────────────────────────�?)
            sb.appendLine("�?Watched Variables                                            �?)
            watchedVariables.take(5).forEach { watch ->
                val valueStr = (watch.lastValue?.toString() ?: "null").take(30)
                sb.appendLine("�?  ${watch.name}: ${valueStr}${" ".padEnd(45 - valueStr.length - watch.name.length)}�?)
            }
        }

        sb.appendLine("└─────────────────────────────────────────────────────────────�?)
        return sb.toString()
    }

    fun buildToolCallTree(session: SkillDebugger.DebugSession): String {
        if (session == null) return "No active session"

        val sb = StringBuilder()
        sb.appendLine("Tool Call Tree (Session: ${session.id})")
        sb.appendLine("�?.repeat(60))

        session.toolCalls.forEachIndexed { index, toolCall ->
            val indent = "  ".repeat(toolCall.sequenceNumber)
            val statusIcon = when {
                toolCall.error != null -> "�?
                toolCall.durationMs != null -> "�?
                else -> "�?
            }
            val duration = toolCall.durationMs?.let { "${it}ms" } ?: "..."
            val startTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(toolCall.startTime))

            sb.appendLine("${indent}${index + 1}. ${statusIcon} [${startTime}] ${toolCall.toolName} (${duration})")

            if (toolCall.error != null) {
                sb.appendLine("${indent}   Error: ${toolCall.error}")
            }
        }

        return sb.toString()
    }

    fun buildVariableTable(variables: Map<String, Any>): String {
        if (variables.isEmpty()) return "No variables"

        val sb = StringBuilder()
        sb.appendLine("Variables")
        sb.appendLine("─".repeat(60))
        sb.appendLine(String.format("%-25s %s", "Name", "Value"))
        sb.appendLine("─".repeat(60))

        variables.entries.sortedBy { it.key }.forEach { (name, value) ->
            val valueStr = value?.toString() ?: "null"
            val displayValue = if (valueStr.length > 35) valueStr.take(32) + "..." else valueStr
            sb.appendLine(String.format("%-25s %s", name, displayValue))
        }

        return sb.toString()
    }

    fun buildBreakpointTable(): String {
        if (breakpoints.isEmpty()) return "No breakpoints set"

        val sb = StringBuilder()
        sb.appendLine("Breakpoints")
        sb.appendLine("─".repeat(60))
        sb.appendLine(String.format("%-5s %-12s %-20s %s", "ID", "Type", "Target", "Hit Count"))
        sb.appendLine("─".repeat(60))

        breakpoints.forEach { bp ->
            val id = bp.id.take(5)
            val type = bp.type.name.take(12)
            val target = bp.target.take(20)
            val hitCount = bp.hitCount.get()
            val enabledStr = if (bp.enabled) "�? else "�?
            sb.appendLine(String.format("%-5s %-12s %-20s %d %s", id, type, target, hitCount, enabledStr))
        }

        return sb.toString()
    }

    fun getExecutionFlowDiagram(session: SkillDebugger.DebugSession): String {
        if (session == null) return "No active session"

        val sb = StringBuilder()
        sb.appendLine("Execution Flow")
        sb.appendLine("�?.repeat(60))

        val tracer = SkillDebugger.getInstance(context).getExecutionTracer()
        return tracer.generateFlowDiagram(session.id)
    }

    private fun notifyConsoleUpdated() {
        consoleListeners.forEach { listener ->
            runCatching {
                listener.onConsoleUpdated(consoleLines.toList())
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying console updated", e)
            }
        }
    }

    private fun notifyBreakpointHit(breakpoint: SkillDebugger.Breakpoint) {
        consoleListeners.forEach { listener ->
            runCatching {
                listener.onBreakpointHit(breakpoint)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying breakpoint hit", e)
            }
        }
    }

    private fun notifyToolCallRecorded(toolCall: SkillDebugger.ToolCall) {
        consoleListeners.forEach { listener ->
            runCatching {
                listener.onToolCallRecorded(toolCall)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying tool call recorded", e)
            }
        }
    }

    private fun notifyVariablesChanged(variables: Map<String, Any>) {
        consoleListeners.forEach { listener ->
            runCatching {
                listener.onVariablesChanged(variables)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying variables changed", e)
            }
        }
    }

    fun notifyStateChanged(state: DebugState) {
        consoleListeners.forEach { listener ->
            runCatching {
                listener.onStateChanged(state)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying state changed", e)
            }
        }
    }
}