package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger

class SkillDebugFacade private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillDebugFacade"

        @Volatile private var INSTANCE: SkillDebugFacade? = null

        fun getInstance(context: Context): SkillDebugFacade {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillDebugFacade(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
        private val debugger: SkillDebugger = SkillDebugger.getInstance(context)
        private val consoleUI: DebugConsoleUI = DebugConsoleUI.getInstance(context)
        private val executionTracer: ExecutionTracer = ExecutionTracer.getInstance(context)
        fun getDebugger(): SkillDebugger = debugger
    fun getConsoleUI(): DebugConsoleUI = consoleUI
    fun getExecutionTracer(): ExecutionTracer = executionTracer

    fun startDebugSession(skillName: String): SkillDebugger.DebugSession {
        consoleUI.logToolCallStart("debugger", mapOf("action" to "start_session", "skillName" to skillName))
        val session = debugger.startDebugSession(skillName)
        consoleUI.logSessionStart(session.id, skillName)
        return session
    }
        fun endDebugSession(sessionId: String): SkillDebugger.DebugSession? {
        val session = debugger.endDebugSession(sessionId)
        session?.let {
            val totalDuration = it.endTime?.minus(it.startTime) ?: 0
            val toolCallCount = it.toolCalls.size
            val errorCount = it.toolCalls.count { tc -> tc.error != null }
            consoleUI.logSessionEnd(it.id, it.skillName, totalDuration, toolCallCount, errorCount)
        }
        return session
    }
        fun addToolBreakpoint(toolName: String): SkillDebugger.Breakpoint {
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.TOOL_CALL,
            toolName
        )
        consoleUI.addBreakpoint(breakpoint)
        return breakpoint
    }
        fun addLineBreakpoint(lineNumber: Int): SkillDebugger.Breakpoint {
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.LINE_NUMBER,
            lineNumber.toString()
        )
        consoleUI.addBreakpoint(breakpoint)
        return breakpoint
    }
        fun addConditionBreakpoint(condition: String): SkillDebugger.Breakpoint {
        val breakpoint = debugger.addBreakpoint(
            SkillDebugger.BreakpointType.CONDITION,
            condition,
            condition
        )
        consoleUI.addBreakpoint(breakpoint)
        return breakpoint
    }
        fun removeBreakpoint(breakpointId: String): Boolean {
        debugger.removeBreakpoint(breakpointId)
        consoleUI.removeBreakpoint(breakpointId)
        return true
    }
        fun pauseExecution(reason: SkillDebugger.PauseReason = SkillDebugger.PauseReason.MANUAL) {
        debugger.pauseExecution(reason)
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.INFO,
            "Execution paused: ${reason.name}"
        )
    }
        fun resumeExecution() {
        debugger.resumeExecution()
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.INFO,
            "Execution resumed"
        )
    }
        fun stepOver() {
        debugger.stepOver()
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.DEBUG,
            "Step over"
        )
    }
        fun stepInto() {
        debugger.stepInto()
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.DEBUG,
            "Step into"
        )
    }
        fun stepOut() {
        debugger.stepOut()
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.DEBUG,
            "Step out"
        )
    }
        fun onToolCallStart(toolName: String, input: Map<String, Any?>) {
        debugger.onToolCallStart(toolName, input)
        consoleUI.logToolCallStart(toolName, input)
    }
        fun onToolCallEnd(toolName: String, output: Any?, error: String? = null) {
        debugger.onToolCallEnd(toolName, output, error)
        val duration = debugger.getActiveSession()?.toolCalls?.lastOrNull { it.toolName == toolName && it.endTime == null }?.durationMs
        consoleUI.logToolCallEnd(toolName, output, error, duration)
    }
        fun onLineReached(lineNumber: Int) {
        debugger.onLineReached(lineNumber)
    }
        fun onError(error: String) {
        debugger.onError(error)
        consoleUI.log(
            DebugConsoleUI.ConsoleLevel.ERROR,
            error
        )
    }
        fun getCurrentDebugState(): DebugConsoleUI.DebugState {
        val session = debugger.getActiveSession()
        return consoleUI.getCurrentState(session)
    }
        fun getDebugSummary(): String {
        val state = getCurrentDebugState()
        return consoleUI.buildStateSummary(state)
    }
        fun getToolCallTree(): String {
        val session = debugger.getActiveSession()
        return consoleUI.buildToolCallTree(session)
    }
        fun getVariableTable(): String {
        val variables = debugger.getAllVariables()
        return consoleUI.buildVariableTable(variables)
    }
        fun getBreakpointTable(): String {
        return consoleUI.buildBreakpointTable()
    }
        fun getExecutionFlowDiagram(): String {
        val session = debugger.getActiveSession()
        return consoleUI.getExecutionFlowDiagram(session)
    }
        fun getMermaidFlowChart(): String {
        val session = debugger.getActiveSession() ?: return "No active session"
        return executionTracer.generateMermaidFlowChart(session.id)
    }
        fun getTraceLog(): List<ExecutionTracer.TraceEntry> {
        return executionTracer.getTraceLog()
    }
        fun getSessionTrace(sessionId: String): List<ExecutionTracer.TraceEntry> {
        return executionTracer.getSessionTrace(sessionId)
    }
        fun clearAllDebugData() {
        debugger.clearAllSessions()
        consoleUI.clearConsole()
        executionTracer.clearTrace()
        AppLogger.d(TAG, "All debug data cleared")
    }
        fun exportDebugSession(sessionId: String): DebugSessionExport? {
        val session = debugger.getSession(sessionId) ?: return null
        val flow = executionTracer.buildExecutionFlow(sessionId)
        return DebugSessionExport(
            session = session,
            flow = flow,
            consoleOutput = consoleUI.exportConsole(),
            breakpoints = debugger.getAllBreakpoints()
        )
    }

    data class DebugSessionExport(
        val session: SkillDebugger.DebugSession,
        val flow: ExecutionTracer.ExecutionFlow?,
        val consoleOutput: String,
        val breakpoints: List<SkillDebugger.Breakpoint>
    )

    interface DebugSessionListener : SkillDebugger.DebugListener, DebugConsoleUI.ConsoleListener

    fun registerDebugListener(listener: DebugSessionListener) {
        debugger.addDebugListener(listener)
        consoleUI.addConsoleListener(listener)
    }
        fun unregisterDebugListener(listener: DebugSessionListener) {
        debugger.removeDebugListener(listener)
        consoleUI.removeConsoleListener(listener)
    }
}