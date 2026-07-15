package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SkillDebugger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillDebugger"
        private const val MAX_TRACE_SIZE = 1000

        @Volatile private var INSTANCE: SkillDebugger? = null

        fun getInstance(context: Context): SkillDebugger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillDebugger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    enum class DebugState {
        IDLE,
        RUNNING,
        PAUSED,
        STEP_MODE,
        TERMINATED
    }

    enum class BreakpointType {
        TOOL_CALL,
        LINE_NUMBER,
        CONDITION
    }

    data class Breakpoint(
        val id: String,
        val type: BreakpointType,
        val target: String,
        val condition: String? = null,
        val enabled: Boolean = true,
        val hitCount: AtomicLong = AtomicLong(0),
        val createdAt: Long = System.currentTimeMillis()
    ) {
        fun isHit(context: ExecutionContext): Boolean {
            if (!enabled) return false
            return when (type) {
                BreakpointType.TOOL_CALL -> context.currentTool == target
                BreakpointType.LINE_NUMBER -> context.currentLine == target.toIntOrNull()
                BreakpointType.CONDITION -> evaluateCondition(condition, context)
            }
        }
        private fun evaluateCondition(condition: String?, ctx: ExecutionContext): Boolean {
            if (condition.isNullOrBlank()) return false
            return try {
                val result = evaluateExpression(condition, ctx)
                result as? Boolean ?: false
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to evaluate condition: ${condition}", e)
                false
            }
        }
        private fun evaluateExpression(expr: String, ctx: ExecutionContext): Any? {
            return when {
                expr.contains("==") -> {
                    val parts = expr.split("==").map { it.trim() }
        val left = resolveVariable(parts[0], ctx)
        val right = resolveVariable(parts[1], ctx)
                    left == right
                }
                expr.contains("!=") -> {
                    val parts = expr.split("!=").map { it.trim() }
        val left = resolveVariable(parts[0], ctx)
        val right = resolveVariable(parts[1], ctx)
                    left != right
                }
                expr.contains(">=") -> {
                    val parts = expr.split(">=").map { it.trim() }
        val left = (resolveVariable(parts[0], ctx) as? Number)?.toLong() ?: 0
                    val right = (resolveVariable(parts[1], ctx) as? Number)?.toLong() ?: 0
                    left >= right
                }
                expr.contains("<=") -> {
                    val parts = expr.split("<=").map { it.trim() }
        val left = (resolveVariable(parts[0], ctx) as? Number)?.toLong() ?: 0
                    val right = (resolveVariable(parts[1], ctx) as? Number)?.toLong() ?: 0
                    left <= right
                }
                expr.contains(">") -> {
                    val parts = expr.split(">").map { it.trim() }
        val left = (resolveVariable(parts[0], ctx) as? Number)?.toLong() ?: 0
                    val right = (resolveVariable(parts[1], ctx) as? Number)?.toLong() ?: 0
                    left > right
                }
                expr.contains("<") -> {
                    val parts = expr.split("<").map { it.trim() }
        val left = (resolveVariable(parts[0], ctx) as? Number)?.toLong() ?: 0
                    val right = (resolveVariable(parts[1], ctx) as? Number)?.toLong() ?: 0
                    left < right
                }
                else -> resolveVariable(expr, ctx) as? Boolean
            }
        }
        private fun resolveVariable(name: String, ctx: ExecutionContext): Any? {
            return when (name) {
                "toolCount" -> ctx.toolCallCount
                "currentTool" -> ctx.currentTool
                "errorCount" -> ctx.errorCount
                "elapsedTime" -> ctx.elapsedTimeMs
                else -> ctx.variables[name]
            }
        }
    }

    data class ExecutionContext(
        val skillName: String,
        var currentTool: String? = null,
        var currentLine: Int? = null,
        var toolCallCount: Long = 0,
        var errorCount: Long = 0,
        var elapsedTimeMs: Long = 0,
        val variables: MutableMap<String, Any> = mutableMapOf(),
        val callStack: MutableList<StackFrame> = mutableListOf()
    )

    data class StackFrame(
        val toolName: String,
        val lineNumber: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ToolCall(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val input: Map<String, Any?>,
        val output: Any? = null,
        val error: String? = null,
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long? = null,
        var durationMs: Long? = null
    ) {
        fun complete(output: Any?, error: String? = null) {
            endTime = System.currentTimeMillis()
            durationMs = endTime!! - startTime
            this.output = output
            this.error = error
        }
    }

    data class DebugSession(
        val id: String = java.util.UUID.randomUUID().toString(),
        val skillName: String,
        val startTime: Long = System.currentTimeMillis(),
        var endTime: Long? = null,
        var state: DebugState = DebugState.IDLE,
        var currentContext: ExecutionContext? = null,
        val toolCalls: CopyOnWriteArrayList<ToolCall> = CopyOnWriteArrayList(),
        val breakpoints: ConcurrentHashMap<String, Breakpoint> = ConcurrentHashMap(),
        val pauseReason: PauseReason? = null
    )

    enum class PauseReason {
        BREAKPOINT_HIT,
        STEP_COMPLETE,
        MANUAL,
        ERROR
    }
        private val breakpointManager = BreakpointManager()
        private val executionTracer = ExecutionTracer(context)
        private val sessions = ConcurrentHashMap<String, DebugSession>()
        private var activeSession: DebugSession? = null
    private val isDebugging = AtomicBoolean(false)
        private val isPaused = AtomicBoolean(false)
        private val stepMode = AtomicBoolean(false)
        private val debugListeners = CopyOnWriteArrayList<DebugListener>()

    interface DebugListener {
        fun onBreakpointHit(session: DebugSession, breakpoint: Breakpoint)
        fun onStepComplete(session: DebugSession)
        fun onToolCallStart(session: DebugSession, toolName: String, input: Map<String, Any?>)
        fun onToolCallEnd(session: DebugSession, toolCall: ToolCall)
        fun onError(session: DebugSession, error: String)
        fun onSessionStart(session: DebugSession)
        fun onSessionEnd(session: DebugSession)
        fun onStateChanged(session: DebugSession, oldState: DebugState, newState: DebugState)
    }
        fun addDebugListener(listener: DebugListener) {
        if (!debugListeners.contains(listener)) {
            debugListeners.add(listener)
        }
    }
        fun removeDebugListener(listener: DebugListener) {
        debugListeners.remove(listener)
    }
        fun startDebugSession(skillName: String): DebugSession {
        val session = DebugSession(skillName = skillName).apply {
            state = DebugState.RUNNING
            currentContext = ExecutionContext(skillName = skillName)
        }
        sessions[session.id] = session
        activeSession = session
        isDebugging.set(true)
        isPaused.set(false)
        stepMode.set(false)

        notifySessionStart(session)
        AppLogger.d(TAG, "Debug session started: ${session.id} for skill: ${skillName}")
        return session
    }
        fun endDebugSession(sessionId: String): DebugSession? {
        val session = sessions[sessionId] ?: return null
        session.endTime = System.currentTimeMillis()
        session.state = DebugState.TERMINATED

        if (activeSession?.id == sessionId) {
            activeSession = null
            isDebugging.set(false)
            isPaused.set(false)
            stepMode.set(false)
        }

        executionTracer.recordSession(session)
        notifySessionEnd(session)
        AppLogger.d(TAG, "Debug session ended: ${sessionId}")
        return session
    }
        fun addBreakpoint(type: BreakpointType, target: String, condition: String? = null): Breakpoint {
        val breakpoint = breakpointManager.addBreakpoint(type, target, condition)
        activeSession?.breakpoints?.put(breakpoint.id, breakpoint)
        AppLogger.d(TAG, "Breakpoint added: ${breakpoint.id} type=${type} target=${target}")
        return breakpoint
    }
        fun removeBreakpoint(breakpointId: String): Boolean {
        val result = breakpointManager.removeBreakpoint(breakpointId)
        activeSession?.breakpoints?.remove(breakpointId)
        return result
    }
        fun enableBreakpoint(breakpointId: String, enabled: Boolean) {
        breakpointManager.setBreakpointEnabled(breakpointId, enabled)
        activeSession?.breakpoints?.get(breakpointId)?.let {
            activeSession?.breakpoints?.put(breakpointId, it.copy(enabled = enabled))
        }
    }
        fun getAllBreakpoints(): List<Breakpoint> = breakpointManager.getAllBreakpoints()
        fun pauseExecution(reason: PauseReason = PauseReason.MANUAL) {
        isPaused.set(true)
        activeSession?.let { session ->
            session.state = DebugState.PAUSED
            session.pauseReason = reason
        }
    }
        fun resumeExecution() {
        isPaused.set(false)
        stepMode.set(false)
        activeSession?.let { session ->
            val oldState = session.state
            session.state = DebugState.RUNNING
            session.pauseReason = null
            notifyStateChanged(session, oldState, DebugState.RUNNING)
        }
    }
        fun stepOver() {
        stepMode.set(true)
        isPaused.set(false)
        activeSession?.let { session ->
            val oldState = session.state
            session.state = DebugState.STEP_MODE
            session.pauseReason = PauseReason.STEP_COMPLETE
            notifyStateChanged(session, oldState, DebugState.STEP_MODE)
        }
    }
        fun stepInto() {
        stepMode.set(true)
        isPaused.set(false)
        activeSession?.let { session ->
            val oldState = session.state
            session.state = DebugState.STEP_MODE
            session.pauseReason = PauseReason.STEP_COMPLETE
            notifyStateChanged(session, oldState, DebugState.STEP_MODE)
        }
    }
        fun stepOut() {
        stepMode.set(true)
        isPaused.set(false)
        activeSession?.let { session ->
            val oldState = session.state
            session.state = DebugState.STEP_MODE
            session.pauseReason = PauseReason.STEP_COMPLETE
            notifyStateChanged(session, oldState, DebugState.STEP_MODE)
        }
    }
        fun onToolCallStart(toolName: String, input: Map<String, Any?>) {
        val session = activeSession ?: return

        session.currentContext?.apply {
            currentTool = toolName
            toolCallCount++
        }
        val toolCall = ToolCall(toolName = toolName, input = input)
        session.toolCalls.add(toolCall)
        if (session.toolCalls.size > MAX_TRACE_SIZE) {
            session.toolCalls.removeAt(0)
        }

        notifyToolCallStart(session, toolName, input)
        AppLogger.d(TAG, "Tool call started: ${toolName} in session ${session.id}")
    }
        fun onToolCallEnd(toolName: String, output: Any?, error: String? = null) {
        val session = activeSession ?: return

        val toolCall = session.toolCalls.findLast {
            it.toolName == toolName && it.endTime == null
        }
        toolCall?.complete(output, error)

        session.currentContext?.apply {
            if (error != null) errorCount++
        }

        toolCall?.let { notifyToolCallEnd(session, it) }
    }
        fun onLineReached(lineNumber: Int) {
        val session = activeSession ?: return

        session.currentContext?.currentLine = lineNumber

        if (isPaused.get() || stepMode.get()) {
            checkBreakpoints(session)
        }
    }
        fun onError(error: String) {
        val session = activeSession ?: return
        session.currentContext?.errorCount?.let { it }
        session.pauseReason = PauseReason.ERROR
        isPaused.set(true)
        session.state = DebugState.PAUSED
        notifyError(session, error)
    }
        fun checkBreakpoints(session: DebugSession) {
        val context = session.currentContext ?: return

        for (breakpoint in session.breakpoints.values) {
            if (breakpoint.isHit(context)) {
                breakpoint.hitCount.incrementAndGet()
                session.pauseReason = PauseReason.BREAKPOINT_HIT
                isPaused.set(true)
        val oldState = session.state
                session.state = DebugState.PAUSED
                notifyStateChanged(session, oldState, DebugState.PAUSED)
                notifyBreakpointHit(session, breakpoint)
                AppLogger.d(TAG, "Breakpoint hit: ${breakpoint.id} in session ${session.id}")
        return
            }
        }
    }
        fun isCurrentlyDebugging(): Boolean = isDebugging.get()
        fun isCurrentlyPaused(): Boolean = isPaused.get()
        fun getActiveSession(): DebugSession? = activeSession

    fun getSession(sessionId: String): DebugSession? = sessions[sessionId]

    fun getAllSessions(): List<DebugSession> = sessions.values.toList()
        fun getExecutionTracer(): ExecutionTracer = executionTracer

    fun getBreakpointManager(): BreakpointManager = breakpointManager

    fun setVariable(name: String, value: Any) {
        activeSession?.currentContext?.variables?.put(name, value)
    }
        fun getVariable(name: String): Any? {
        return activeSession?.currentContext?.variables?.get(name)
    }
        fun getAllVariables(): Map<String, Any> {
        return activeSession?.currentContext?.variables?.toMap() ?: emptyMap()
    }
        fun getCallStack(): List<StackFrame> {
        return activeSession?.currentContext?.callStack?.toList() ?: emptyList()
    }
        fun pushStackFrame(toolName: String, lineNumber: Int) {
        activeSession?.currentContext?.callStack?.add(
            StackFrame(toolName = toolName, lineNumber = lineNumber)
        )
    }
        fun popStackFrame(): StackFrame? {
        return activeSession?.currentContext?.callStack?.removeLastOrNull()
    }
        private fun notifyBreakpointHit(session: DebugSession, breakpoint: Breakpoint) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onBreakpointHit(session, breakpoint)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying breakpoint hit", e)
            }
        }
    }
        private fun notifyStepComplete(session: DebugSession) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onStepComplete(session)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying step complete", e)
            }
        }
    }
        private fun notifyToolCallStart(session: DebugSession, toolName: String, input: Map<String, Any?>) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onToolCallStart(session, toolName, input)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying tool call start", e)
            }
        }
    }
        private fun notifyToolCallEnd(session: DebugSession, toolCall: ToolCall) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onToolCallEnd(session, toolCall)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying tool call end", e)
            }
        }
    }
        private fun notifyError(session: DebugSession, error: String) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onError(session, error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying error", e)
            }
        }
    }
        private fun notifySessionStart(session: DebugSession) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onSessionStart(session)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying session start", e)
            }
        }
    }
        private fun notifySessionEnd(session: DebugSession) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onSessionEnd(session)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying session end", e)
            }
        }
    }
        private fun notifyStateChanged(session: DebugSession, oldState: DebugState, newState: DebugState) {
        debugListeners.forEach { listener ->
            runCatching {
                listener.onStateChanged(session, oldState, newState)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying state changed", e)
            }
        }
    }
        fun clearAllSessions() {
        sessions.forEach { (_, session) ->
            session.state = DebugState.TERMINATED
            session.endTime = System.currentTimeMillis()
        }
        sessions.clear()
        activeSession = null
        isDebugging.set(false)
        isPaused.set(false)
        stepMode.set(false)
    }
}