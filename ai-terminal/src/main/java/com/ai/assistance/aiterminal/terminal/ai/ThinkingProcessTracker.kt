package com.ai.assistance.aiterminal.terminal.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Agent 思考过程追踪器
 *
 * 当 Agent 调用终端工具时,记录每一步思考(为什么调这个工具/期望什么结果/分析输出),
 * 实时暴露为 StateFlow,供 UI 展示"Agent 正在思考..."的可视化。
 *
 * # 思考步骤类型
 *
 * - TOOL_CALL: Agent 决定调用某工具(含理由)
 * - TOOL_RESULT: 工具返回后的分析(成功/失败/发现了什么)
 * - REASONING: Agent 的中间推理(不调工具,纯思考)
 * - DECISION: Agent 做出决策(如"需要再调一次"/"任务完成")
 * - ERROR: 出错时的分析
 *
 * # 使用方式
 *
 * ```
 * val tracker = ThinkingProcessTracker()
 *
 * // Agent 决定调用工具
 * tracker.logToolCall("agent_exec", "查看项目结构", "需要先了解项目布局再决定修改哪些文件")
 *
 * // 工具返回
 * tracker.logToolResult("agent_exec", true, "发现 src/main.kt 和 build.gradle.kts,是 Kotlin 项目")
 *
 * // Agent 推理
 * tracker.logReasoning("项目用的是 Gradle 构建,修改后需要 ./gradlew build 验证")
 *
 * // UI 观察
 * tracker.steps.collect { steps ->
 *     steps.forEach { step -> println("[${step.type}] ${step.content}") }
 * }
 * ```
 */
class ThinkingProcessTracker {

    /** 思考步骤类型 */
    enum class StepType(val icon: String, val label: String) {
        TOOL_CALL("🔧", "调用工具"),
        TOOL_RESULT("📥", "工具返回"),
        REASONING("💭", "推理"),
        DECISION("✅", "决策"),
        ERROR("❌", "错误"),
        PLAN("📋", "计划"),
    }

    /** 单个思考步骤 */
    data class ThinkingStep(
        val id: String,
        val type: StepType,
        val content: String,
        val toolName: String? = null,
        val toolArgs: String? = null,
        val success: Boolean? = null,
        val durationMs: Long? = null,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("icon", type.icon)
            put("label", type.label)
            put("content", content)
            toolName?.let { put("toolName", it) }
            toolArgs?.let { put("toolArgs", it.take(500)) }  // 截断长参数
            success?.let { put("success", it) }
            durationMs?.let { put("durationMs", it) }
            put("timestamp", timestamp)
            put("time", java.text.SimpleDateFormat("HH:mm:ss.SSS").format(java.util.Date(timestamp)))
        }
    }

    /** 一个完整的 Agent 任务会话 */
    data class ThinkingSession(
        val id: String,
        val taskDescription: String,
        val steps: List<ThinkingStep>,
        val startedAt: Long,
        var endedAt: Long? = null,
        val status: SessionStatus = SessionStatus.RUNNING,
    ) {
        enum class SessionStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

        val durationMs: Long get() = (endedAt ?: System.currentTimeMillis()) - startedAt
        val stepCount: Int get() = steps.size
        val toolCallCount: Int get() = steps.count { it.type == StepType.TOOL_CALL }
        val successRate: Float get() {
            val results = steps.filter { it.type == StepType.TOOL_RESULT }
            if (results.isEmpty()) return 1f
            return results.count { it.success == true }.toFloat() / results.size
        }
    }

    private val _currentSession = MutableStateFlow<ThinkingSession?>(null)
    val currentSession: StateFlow<ThinkingSession?> = _currentSession

    private val _sessions = MutableStateFlow<List<ThinkingSession>>(emptyList())
    val sessions: StateFlow<List<ThinkingSession>> = _sessions.asStateFlow()

    /** 当前会话的所有步骤(便捷访问) */
    val currentSteps: StateFlow<List<ThinkingStep>> = MutableStateFlow(emptyList())

    // ============ 会话管理 ============

    /** 开始新的思考会话 */
    fun startSession(taskDescription: String): String {
        val sessionId = "thinking_${System.currentTimeMillis()}"
        val session = ThinkingSession(
            id = sessionId,
            taskDescription = taskDescription,
            steps = emptyList(),
            startedAt = System.currentTimeMillis(),
        )
        _currentSession.value = session
        (currentSteps as MutableStateFlow).value = emptyList()

        logPlan("任务: $taskDescription")
        return sessionId
    }

    /** 结束当前会话 */
    fun endSession(status: ThinkingSession.SessionStatus = ThinkingSession.SessionStatus.COMPLETED) {
        val current = _currentSession.value ?: return
        val ended = current.copy(endedAt = System.currentTimeMillis(), status = status)
        _currentSession.value = ended
        _sessions.value = (listOf(ended) + _sessions.value).take(20)
    }

    // ============ 记录步骤 ============

    /** 记录计划 */
    fun logPlan(content: String) {
        addStep(ThinkingStep(generateId(), StepType.PLAN, content))
    }

    /** 记录推理 */
    fun logReasoning(content: String) {
        addStep(ThinkingStep(generateId(), StepType.REASONING, content))
    }

    /** 记录工具调用(Agent 决定调用某工具) */
    fun logToolCall(toolName: String, purpose: String, reasoning: String, args: String? = null) {
        val content = buildString {
            append("调用 $toolName — $purpose")
            if (reasoning.isNotBlank()) {
                append("\n理由: $reasoning")
            }
        }
        addStep(ThinkingStep(
            id = generateId(),
            type = StepType.TOOL_CALL,
            content = content,
            toolName = toolName,
            toolArgs = args,
        ))
    }

    /** 记录工具返回结果 */
    fun logToolResult(toolName: String, success: Boolean, analysis: String, durationMs: Long? = null) {
        val content = buildString {
            append(if (success) "✓ 成功" else "✗ 失败")
            append(" — $analysis")
        }
        addStep(ThinkingStep(
            id = generateId(),
            type = StepType.TOOL_RESULT,
            content = content,
            toolName = toolName,
            success = success,
            durationMs = durationMs,
        ))
    }

    /** 记录决策 */
    fun logDecision(content: String) {
        addStep(ThinkingStep(generateId(), StepType.DECISION, content))
    }

    /** 记录错误 */
    fun logError(content: String, toolName: String? = null) {
        addStep(ThinkingStep(
            id = generateId(),
            type = StepType.ERROR,
            content = content,
            toolName = toolName,
        ))
    }

    private fun addStep(step: ThinkingStep) {
        val current = _currentSession.value
        if (current != null) {
            val updated = current.copy(steps = current.steps + step)
            _currentSession.value = updated
            (currentSteps as MutableStateFlow).value = updated.steps
        }
    }

    /** 清空当前会话步骤 */
    fun clearCurrent() {
        _currentSession.value = null
        (currentSteps as MutableStateFlow).value = emptyList()
    }

    // ============ 查询 ============

    /** 获取当前会话的 JSON(供 UI/API) */
    fun getCurrentSessionJson(): JSONObject {
        val session = _currentSession.value ?: return JSONObject().put("error", "No active session")
        return sessionToJson(session)
    }

    /** 获取所有历史会话 JSON */
    fun getSessionsJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        _sessions.value.forEach { arr.put(sessionToJson(it)) }
        return arr
    }

    private fun sessionToJson(session: ThinkingSession): JSONObject {
        val stepsArr = org.json.JSONArray()
        session.steps.forEach { stepsArr.put(it.toJson()) }
        return JSONObject()
            .put("id", session.id)
            .put("task", session.taskDescription)
            .put("status", session.status.name)
            .put("startedAt", session.startedAt)
            .put("endedAt", session.endedAt)
            .put("durationMs", session.durationMs)
            .put("stepCount", session.stepCount)
            .put("toolCallCount", session.toolCallCount)
            .put("successRate", "%.1f%%".format(session.successRate * 100))
            .put("steps", stepsArr)
    }

    private fun generateId(): String = "step_${System.currentTimeMillis()}_${(1..999).random()}"
}
