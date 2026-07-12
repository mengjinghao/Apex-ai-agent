package com.apex.agent.kernel.burst.enhanced.context

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * B41: 执行上下文与追踪ID传播
 *
 * 跨步骤传播执行上下文：
 * - Trace ID（全链路追踪）
 * - Span ID（步骤级追踪）
 * - 用户 ID / 会话 ID
 * - 自定义 baggage（键值对）
 * - 上下文继承（子任务继承父上下文）
 */
class ExecutionContextManager {

    data class ExecutionContext(
        val traceId: String,            // 全链路追踪 ID
        val spanId: String,             // 当前步骤 ID
        val parentSpanId: String?,      // 父步骤 ID
        val userId: String = "default",
        val sessionId: String,
        val pipelineId: String?,
        val taskId: String,
        val baggage: Map<String, String> = emptyMap(),  // 自定义键值对
        val createdAt: Long = System.currentTimeMillis(),
        val depth: Int = 0              // 嵌套深度
    ) {
        fun withBaggage(key: String, value: String): ExecutionContext =
            copy(baggage = baggage + (key to value))

        fun childSpan(spanId: String, taskId: String): ExecutionContext =
            copy(
                spanId = spanId,
                parentSpanId = this.spanId,
                taskId = taskId,
                depth = depth + 1
            )
    }

    data class SpanRecord(
        val spanId: String,
        val traceId: String,
        val parentSpanId: String?,
        val taskId: String,
        val skillId: String?,
        val startedAt: Long,
        val completedAt: Long?,
        val durationMs: Long?,
        val success: Boolean?,
        val tags: Map<String, String>,
        val depth: Int
    )

    private val activeContexts = ConcurrentHashMap<String, ExecutionContext>()
    private val spanHistory = mutableListOf<SpanRecord>()
    private val _currentTraceId = ThreadLocal<String?>()

    /**
     * 创建根上下文
     */
    fun createRootContext(taskId: String, userId: String = "default", sessionId: String? = null, pipelineId: String? = null): ExecutionContext {
        val ctx = ExecutionContext(
            traceId = UUID.randomUUID().toString().take(16),
            spanId = "span_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            parentSpanId = null,
            userId = userId,
            sessionId = sessionId ?: "sess_${System.currentTimeMillis()}",
            pipelineId = pipelineId,
            taskId = taskId
        )
        activeContexts[taskId] = ctx
        _currentTraceId.set(ctx.traceId)
        return ctx
    }

    /**
     * 创建子上下文
     */
    fun createChildContext(parentTaskId: String, childTaskId: String, spanId: String): ExecutionContext? {
        val parent = activeContexts[parentTaskId] ?: return null
        val child = parent.childSpan(spanId, childTaskId)
        activeContexts[childTaskId] = child
        return child
    }

    /**
     * 获取上下文
     */
    fun getContext(taskId: String): ExecutionContext? = activeContexts[taskId]

    /**
     * 获取当前 trace ID
     */
    fun currentTraceId(): String? = _currentTraceId.get()

    /**
     * 记录 span 开始
     */
    fun startSpan(ctx: ExecutionContext, skillId: String? = null, tags: Map<String, String> = emptyMap()) {
        val record = SpanRecord(
            spanId = ctx.spanId, traceId = ctx.traceId,
            parentSpanId = ctx.parentSpanId, taskId = ctx.taskId,
            skillId = skillId, startedAt = System.currentTimeMillis(),
            completedAt = null, durationMs = null, success = null,
            tags = tags + mapOf("userId" to ctx.userId, "sessionId" to ctx.sessionId),
            depth = ctx.depth
        )
        spanHistory.add(record)
        while (spanHistory.size > 2000) spanHistory.removeAt(0)
    }

    /**
     * 记录 span 完成
     */
    fun completeSpan(spanId: String, success: Boolean) {
        val idx = spanHistory.indexOfLast { it.spanId == spanId }
        if (idx >= 0) {
            val record = spanHistory[idx]
            val now = System.currentTimeMillis()
            spanHistory[idx] = record.copy(
                completedAt = now,
                durationMs = now - record.startedAt,
                success = success
            )
        }
    }

    /**
     * 获取 trace 的所有 span
     */
    fun getTraceSpans(traceId: String): List<SpanRecord> =
        spanHistory.filter { it.traceId == traceId }.sortedBy { it.startedAt }

    /**
     * 获取任务的上下文链（从根到当前）
     */
    fun getContextChain(taskId: String): List<ExecutionContext> {
        val chain = mutableListOf<ExecutionContext>()
        var current = activeContexts[taskId]
        while (current != null) {
            chain.add(0, current)
            current = current.parentSpanId?.let { pSpanId ->
                activeContexts.values.find { it.spanId == pSpanId }
            }
        }
        return chain
    }

    /**
     * 清理已完成的上下文
     */
    fun cleanup(taskId: String) {
        activeContexts.remove(taskId)
    }

    /**
     * 生成追踪报告
     */
    fun generateTraceReport(traceId: String): String {
        val spans = getTraceSpans(traceId)
        if (spans.isEmpty()) return "无追踪记录"
        val sb = StringBuilder()
        sb.appendLine("═══ 追踪报告: $traceId ═══")
        sb.appendLine("Span 数: ${spans.size}")
        val totalDuration = (spans.maxOfOrNull { it.completedAt ?: 0 } ?: 0L) - (spans.minOfOrNull { it.startedAt ?: 0 } ?: 0L)
        sb.appendLine("总耗时: ${totalDuration}ms")
        sb.appendLine()
        sb.appendLine("Span 链:")
        spans.forEach { span ->
            val indent = "  ".repeat(span.depth)
            val duration = span.durationMs?.let { "${it}ms" } ?: "进行中"
            val status = when (span.success) {
                true -> "✓"; false -> "✗"; null -> "○"
            }
            sb.appendLine("$indent$status ${span.spanId} (${span.skillId ?: "N/A"}) $duration")
        }
        sb.appendLine("═══════════════════")
        return sb.toString()
    }
}
