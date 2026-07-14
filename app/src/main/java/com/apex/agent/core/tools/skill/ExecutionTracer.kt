package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class ExecutionTracer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ExecutionTracer"
        private const val MAX_TRACED_SESSIONS = 100
        private const val MAX_TOOL_CALLS_PER_SESSION = 1000

        @Volatile private var INSTANCE: ExecutionTracer? = null

        fun getInstance(context: Context): ExecutionTracer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExecutionTracer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class TraceEntry(
        val id: String = java.util.UUID.randomUUID().toString(),
        val sessionId: String,
        val skillName: String,
        val eventType: EventType,
        val toolName: String? = null,
        val lineNumber: Int? = null,
        val input: Map<String, Any?>? = null,
        val output: Any? = null,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val durationMs: Long? = null
    )

    enum class EventType {
        SESSION_START,
        SESSION_END,
        TOOL_CALL_START,
        TOOL_CALL_END,
        TOOL_CALL_ERROR,
        BREAKPOINT_HIT,
        VARIABLE_CHANGE,
        ERROR,
        WARNING,
        INFO,
        STEP_START,
        STEP_END
    }

    data class ExecutionFlow(
        val sessionId: String,
        val skillName: String,
        val startTime: Long,
        val endTime: Long? = null,
        val totalDurationMs: Long? = null,
        val toolCalls: List<ToolCallInfo>,
        val errors: List<ErrorInfo>,
        val flowGraph: List<FlowNode>
    )

    data class ToolCallInfo(
        val id: String,
        val toolName: String,
        val input: Map<String, Any?>,
        val output: Any?,
        val error: String?,
        val startTime: Long,
        val endTime: Long?,
        val durationMs: Long?,
        val sequenceNumber: Int
    )

    data class ErrorInfo(
        val toolName: String?,
        val error: String,
        val timestamp: Long,
        val lineNumber: Int?
    )

    data class FlowNode(
        val id: String,
        val type: FlowNodeType,
        val toolName: String? = null,
        val label: String,
        val depth: Int,
        val startTime: Long,
        val endTime: Long? = null,
        val durationMs: Long? = null,
        val children: List<FlowNode> = emptyList()
    )

    enum class FlowNodeType {
        ROOT,
        SESSION,
        TOOL_CALL,
        SEQUENCE,
        PARALLEL,
        CONDITIONAL,
        LOOP,
        ERROR,
        END
    }

    private val traceLog = CopyOnWriteArrayList<TraceEntry>()
    private val sessionTraces = ConcurrentHashMap<String, CopyOnWriteArrayList<TraceEntry>>()
    private val completedFlows = CopyOnWriteArrayList<ExecutionFlow>()

    private val traceListeners = CopyOnWriteArrayList<TraceListener>()

    interface TraceListener {
        fun onTraceEntry(entry: TraceEntry)
        fun onFlowCompleted(flow: ExecutionFlow)
    }

    fun addTraceListener(listener: TraceListener) {
        if (!traceListeners.contains(listener)) {
            traceListeners.add(listener)
        }
    }

    fun removeTraceListener(listener: TraceListener) {
        traceListeners.remove(listener)
    }

    fun recordEntry(entry: TraceEntry) {
        if (traceLog.size >= MAX_TRACED_SESSIONS * 100) {
            traceLog.removeAt(0)
        }
        traceLog.add(entry)

        sessionTraces.getOrPut(entry.sessionId) { CopyOnWriteArrayList() }.add(entry)

        notifyTraceEntry(entry)
    }

    fun recordSessionStart(sessionId: String, skillName: String) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.SESSION_START,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordSessionEnd(sessionId: String, skillName: String) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.SESSION_END,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)

        val flow = buildExecutionFlow(sessionId)
        if (flow != null) {
            completedFlows.add(flow)
            if (completedFlows.size > MAX_TRACED_SESSIONS) {
                completedFlows.removeAt(0)
            }
            notifyFlowCompleted(flow)
        }
    }

    fun recordToolCallStart(sessionId: String, skillName: String, toolName: String, input: Map<String, Any?>) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.TOOL_CALL_START,
            toolName = toolName,
            input = input,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordToolCallEnd(sessionId: String, skillName: String, toolName: String, output: Any?, error: String?, durationMs: Long) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = if (error != null) EventType.TOOL_CALL_ERROR else EventType.TOOL_CALL_END,
            toolName = toolName,
            output = output,
            error = error,
            durationMs = durationMs,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordBreakpointHit(sessionId: String, skillName: String, toolName: String?, lineNumber: Int) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.BREAKPOINT_HIT,
            toolName = toolName,
            lineNumber = lineNumber,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordVariableChange(sessionId: String, skillName: String, varName: String, oldValue: Any?, newValue: Any) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.VARIABLE_CHANGE,
            input = mapOf("name" to varName, "oldValue" to oldValue, "newValue" to newValue),
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordError(sessionId: String, skillName: String, error: String, toolName: String? = null, lineNumber: Int? = null) {
        val entry = TraceEntry(
            sessionId = sessionId,
            skillName = skillName,
            eventType = EventType.ERROR,
            toolName = toolName,
            lineNumber = lineNumber,
            error = error,
            timestamp = System.currentTimeMillis()
        )
        recordEntry(entry)
    }

    fun recordSession(session: SkillDebugger.DebugSession) {
        session.toolCalls.forEach { toolCall ->
            val eventType = if (toolCall.error != null) EventType.TOOL_CALL_ERROR else EventType.TOOL_CALL_END
            val entry = TraceEntry(
                sessionId = session.id,
                skillName = session.skillName,
                eventType = eventType,
                toolName = toolCall.toolName,
                input = toolCall.input,
                output = toolCall.output,
                error = toolCall.error,
                timestamp = toolCall.startTime,
                durationMs = toolCall.durationMs
            )
            recordEntry(entry)
        }
    }

    fun getTraceLog(): List<TraceEntry> = traceLog.toList()

    fun getSessionTrace(sessionId: String): List<TraceEntry> {
        return sessionTraces[sessionId]?.toList() ?: emptyList()
    }

    fun getCompletedFlows(): List<ExecutionFlow> = completedFlows.toList()

    fun getTraceStats(): TraceStats {
        val toolCallEvents = traceLog.filter {
            it.eventType == EventType.TOOL_CALL_START ||
            it.eventType == EventType.TOOL_CALL_END ||
            it.eventType == EventType.TOOL_CALL_ERROR
        }
        val errorEvents = traceLog.filter { it.eventType == EventType.ERROR || it.eventType == EventType.TOOL_CALL_ERROR }

        return TraceStats(
            totalEntries = traceLog.size,
            totalSessions = sessionTraces.size,
            totalToolCalls = toolCallEvents.size / 2,
            totalErrors = errorEvents.size,
            completedFlows = completedFlows.size
        )
    }

    fun buildExecutionFlow(sessionId: String): ExecutionFlow? {
        val entries = sessionTraces[sessionId] ?: return null
        if (entries.isEmpty()) return null

        val sessionStart = entries.firstOrNull { it.eventType == EventType.SESSION_START }
        val sessionEnd = entries.lastOrNull { it.eventType == EventType.SESSION_END }

        if (sessionStart == null) return null

        val toolCallEntries = entries.filter {
            it.eventType == EventType.TOOL_CALL_START ||
            it.eventType == EventType.TOOL_CALL_END ||
            it.eventType == EventType.TOOL_CALL_ERROR
        }

        val toolCalls = mutableListOf<ToolCallInfo>()
        val errors = mutableListOf<ErrorInfo>()
        var sequenceNumber = 0

        var i = 0
        while (i < toolCallEntries.size) {
            val entry = toolCallEntries[i]
            if (entry.eventType == EventType.TOOL_CALL_START) {
                val endEntry = toolCallEntries.getOrNull(i + 1)
                val toolCallInfo = ToolCallInfo(
                    id = java.util.UUID.randomUUID().toString(),
                    toolName = entry.toolName ?: "unknown",
                    input = entry.input ?: emptyMap(),
                    output = endEntry?.output,
                    error = endEntry?.error ?: entry.error,
                    startTime = entry.timestamp,
                    endTime = endEntry?.timestamp,
                    durationMs = endEntry?.durationMs,
                    sequenceNumber = sequenceNumber++
                )
                toolCalls.add(toolCallInfo)
                if (endEntry?.error != null) {
                    errors.add(ErrorInfo(
                        toolName = entry.toolName,
                        error = endEntry.error,
                        timestamp = endEntry.timestamp,
                        lineNumber = endEntry.lineNumber
                    ))
                }
            }
            i++
        }

        val flowGraph = buildFlowGraph(toolCalls)

        val totalDuration = if (sessionEnd != null && sessionStart != null) {
            sessionEnd.timestamp - sessionStart.timestamp
        } else null

        return ExecutionFlow(
            sessionId = sessionId,
            skillName = sessionStart.skillName,
            startTime = sessionStart.timestamp,
            endTime = sessionEnd?.timestamp,
            totalDurationMs = totalDuration,
            toolCalls = toolCalls,
            errors = errors,
            flowGraph = flowGraph
        )
    }

    private fun buildFlowGraph(toolCalls: List<ToolCallInfo>): List<FlowNode> {
        if (toolCalls.isEmpty()) return emptyList()

        val nodes = mutableListOf<FlowNode>()

        toolCalls.forEachIndexed { index, toolCall ->
            val node = FlowNode(
                id = "node_${index}",
                type = FlowNodeType.TOOL_CALL,
                toolName = toolCall.toolName,
                label = "${toolCall.toolName} (${toolCall.durationMs ?: 0}ms)",
                depth = 1,
                startTime = toolCall.startTime,
                endTime = toolCall.endTime,
                durationMs = toolCall.durationMs
            )
            nodes.add(node)
        }

        return nodes
    }

    fun generateFlowDiagram(sessionId: String): String {
        val flow = buildExecutionFlow(sessionId) ?: return "No flow data available"

        val sb = StringBuilder()
        sb.appendLine("Execution Flow for Session: ${flow.sessionId}")
        sb.appendLine("Skill: ${flow.skillName}")
        sb.appendLine("Start Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date(flow.startTime))}")
        flow.endTime?.let {
            sb.appendLine("End Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(java.util.Date(it))}")
        }
        flow.totalDurationMs?.let {
            sb.appendLine("Total Duration: ${it}ms")
        }
        sb.appendLine()
        sb.appendLine("Tool Call Sequence:")
        sb.appendLine("─".repeat(60))

        flow.toolCalls.forEachIndexed { index, toolCall ->
            val statusIcon = when {
                toolCall.error != null -> "�?
                toolCall.durationMs != null -> "�?
                else -> "�?
            }
            val duration = toolCall.durationMs?.let { "${it}ms" } ?: "N/A"
            sb.appendLine("${index + 1}. ${statusIcon} ${toolCall.toolName} [${duration}]")
            if (toolCall.error != null) {
                sb.appendLine("   Error: ${toolCall.error}")
            }
        }

        if (flow.errors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Errors (${flow.errors.size}):")
            sb.appendLine("─".repeat(60))
            flow.errors.forEach { error ->
                sb.appendLine("�?${error.toolName ?: "Unknown"}: ${error.error}")
            }
        }

        return sb.toString()
    }

    fun generateMermaidFlowChart(sessionId: String): String {
        val flow = buildExecutionFlow(sessionId) ?: return "No flow data available"

        val sb = StringBuilder()
        sb.appendLine("graph TD")
        sb.appendLine("    Start((Session Start))")
        sb.appendLine("    Skill_${flow.skillName}[${flow.skillName}]")

        var prevNodeId = "Start"
        var nodeIndex = 0

        flow.toolCalls.forEach { toolCall ->
            nodeIndex++
            val nodeId = "Tool${nodeIndex}"
            val statusClass = if (toolCall.error != null) "error" else "success"
            sb.appendLine("    ${nodeId}(${nodeId}: ${toolCall.toolName}):::${statusClass}")
            sb.appendLine("    ${prevNodeId} --> ${nodeId}")
            prevNodeId = nodeId
        }

        sb.appendLine("    End${nodeIndex}((Session End))")
        sb.appendLine("    ${prevNodeId} --> End${nodeIndex}")
        sb.appendLine()
        sb.appendLine("    classDef success fill:#90EE90")
        sb.appendLine("    classDef error fill:#FFB6C1")

        return sb.toString()
    }

    fun clearTrace() {
        traceLog.clear()
        sessionTraces.clear()
        AppLogger.d(TAG, "Trace cleared")
    }

    fun clearSessionTrace(sessionId: String) {
        sessionTraces.remove(sessionId)
        AppLogger.d(TAG, "Session trace cleared: ${sessionId}")
    }

    private fun notifyTraceEntry(entry: TraceEntry) {
        traceListeners.forEach { listener ->
            runCatching {
                listener.onTraceEntry(entry)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying trace entry", e)
            }
        }
    }

    private fun notifyFlowCompleted(flow: ExecutionFlow) {
        traceListeners.forEach { listener ->
            runCatching {
                listener.onFlowCompleted(flow)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying flow completed", e)
            }
        }
    }

    data class TraceStats(
        val totalEntries: Int,
        val totalSessions: Int,
        val totalToolCalls: Int,
        val totalErrors: Int,
        val completedFlows: Int
    )
}