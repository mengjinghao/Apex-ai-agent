package com.apex.agent.core.workflow.enhanced.replay

import com.apex.agent.core.workflow.enhanced.EnhancedWorkflowExecutor
import com.apex.agent.core.workflow.enhanced.observability.InMemoryTracer
import com.apex.agent.core.workflow.enhanced.observability.SpanRecord
import com.apex.agent.core.workflow.enhanced.observability.TracerHolder
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流历史回放器
 *
 * 参照 Temporal 的事件历史回放、LangGraph 的 checkpoint replay、
 * Airflow 的 task instance 历史
 *
 * 能力：
 * - 从历史 Span 记录重建执行过程
 * - 时间轴可视化
 * - 逐步单步调试
 * - 节点耗时分析
 * - 失败原因定位
 * - 比较两次执行差异
 */
class WorkflowReplayer {

    /**
     * 回放会话
     */
    data class ReplaySession(
        val threadId: String,
        val spans: List<SpanRecord>,
        val totalDurationMs: Long,
        val nodeCount: Int,
        val successCount: Int,
        val failureCount: Int,
        val timeline: List<TimelineEvent>
    )

    /**
     * 时间轴事件
     */
    data class TimelineEvent(
        val timestampMs: Long,
        val relativeMs: Long,
        val type: TimelineEventType,
        val spanId: String,
        val parentNodeId: String?,
        val nodeName: String,
        val durationMs: Long,
        val status: String,
        val attributes: Map<String, Any>,
        val exception: Throwable?
    )

    enum class TimelineEventType {
        WORKFLOW_START, NODE_START, NODE_END, BRANCH_START, BRANCH_END, WORKFLOW_END
    }

    /**
     * 回放分析报告
     */
    data class ReplayAnalysis(
        val threadId: String,
        val totalDurationMs: Long,
        val criticalPathMs: Long,
        val parallelismRatio: Float,
        val nodeCount: Int,
        val bottleneckNodeId: String?,
        val bottleneckDurationMs: Long,
        val slowestNodes: List<NodeTiming>,
        val failureChain: List<FailureRecord>
    )

    data class NodeTiming(
        val nodeId: String,
        val nodeName: String,
        val durationMs: Long,
        val percentage: Float
    )

    data class FailureRecord(
        val nodeId: String,
        val nodeName: String,
        val error: String,
        val timestampMs: Long
    )

    /**
     * 创建回放会话
     */
    fun createSession(threadId: String): ReplaySession? {
        val tracer = TracerHolder.get()
        val spans = tracer.snapshot(threadId)
        if (spans.isEmpty()) return null

        val totalDuration = spans.maxOf { it.endTimeMs } - spans.minOf { it.startTimeMs }
        val nodeSpans = spans.filter { it.nodeId != null }
        val successCount = nodeSpans.count { it.status.name == "OK" }
        val failureCount = nodeSpans.count { it.status.name == "ERROR" }

        val timeline = buildTimeline(spans)

        return ReplaySession(
            threadId = threadId,
            spans = spans,
            totalDurationMs = totalDuration,
            nodeCount = nodeSpans.size,
            successCount = successCount,
            failureCount = failureCount,
            timeline = timeline
        )
    }

    /**
     * 构建时间轴
     */
    private fun buildTimeline(spans: List<SpanRecord>): List<TimelineEvent> {
        val startTime = spans.minOf { it.startTimeMs }
        val events = mutableListOf<TimelineEvent>()

        // 工作流开始
        val rootSpan = spans.find { it.parentId == null }
        if (rootSpan != null) {
            events.add(TimelineEvent(
                timestampMs = rootSpan.startTimeMs,
                relativeMs = 0,
                type = TimelineEventType.WORKFLOW_START,
                spanId = rootSpan.spanId,
                parentNodeId = null,
                nodeName = rootSpan.name,
                durationMs = rootSpan.durationMs,
                status = rootSpan.status.name,
                attributes = rootSpan.attributes,
                exception = rootSpan.exception
            ))
        }

        // 节点事件
        spans.filter { it.nodeId != null }.forEach { span ->
            events.add(TimelineEvent(
                timestampMs = span.startTimeMs,
                relativeMs = span.startTimeMs - startTime,
                type = TimelineEventType.NODE_START,
                spanId = span.spanId,
                parentNodeId = span.parentId,
                nodeName = span.name,
                durationMs = span.durationMs,
                status = span.status.name,
                attributes = span.attributes,
                exception = span.exception
            ))
        }

        return events.sortedBy { it.timestampMs }
    }

    /**
     * 分析回放会话
     */
    fun analyze(session: ReplaySession): ReplayAnalysis {
        val nodeSpans = session.spans.filter { it.nodeId != null }

        // 找瓶颈节点
        val sorted = nodeSpans.sortedByDescending { it.durationMs }
        val bottleneck = sorted.firstOrNull()
        val slowestNodes = sorted.take(5).map {
            NodeTiming(
                nodeId = it.nodeId ?: "",
                nodeName = it.name,
                durationMs = it.durationMs,
                percentage = if (session.totalDurationMs > 0) it.durationMs.toFloat() / session.totalDurationMs else 0f
            )
        }

        // 失败链
        val failureChain = nodeSpans.filter { it.status.name == "ERROR" }.map {
            FailureRecord(
                nodeId = it.nodeId ?: "",
                nodeName = it.name,
                error = it.exception?.message ?: it.attributes["exception.message"]?.toString() ?: "unknown",
                timestampMs = it.startTimeMs
            )
        }

        // 关键路径（最长串行路径）
        val criticalPath = computeCriticalPath(nodeSpans)

        // 并行度
        val parallelism = if (session.totalDurationMs > 0) {
            nodeSpans.sumOf { it.durationMs }.toFloat() / session.totalDurationMs
        } else 1f

        return ReplayAnalysis(
            threadId = session.threadId,
            totalDurationMs = session.totalDurationMs,
            criticalPathMs = criticalPath,
            parallelismRatio = parallelism,
            nodeCount = nodeSpans.size,
            bottleneckNodeId = bottleneck?.nodeId,
            bottleneckDurationMs = bottleneck?.durationMs ?: 0,
            slowestNodes = slowestNodes,
            failureChain = failureChain
        )
    }

    /**
     * 计算关键路径（最长串行耗时）
     */
    private fun computeCriticalPath(spans: List<SpanRecord>): Long {
        // 简化算法：找最长串行链
        val byParent = spans.groupBy { it.parentId }
        var maxPath = 0L

        fun dfs(spanId: String?, currentDuration: Long): Long {
            val children = byParent[spanId] ?: emptyList()
            if (children.isEmpty()) return currentDuration
            return children.maxOf { child -> dfs(child.spanId, currentDuration + child.durationMs) }
        }

        val roots = byParent[null] ?: emptyList()
        for (root in roots) {
            val path = dfs(root.spanId, root.durationMs)
            if (path > maxPath) maxPath = path
        }
        return maxPath
    }

    /**
     * 单步调试 - 返回某节点的执行详情
     */
    fun inspectNode(session: ReplaySession, nodeId: String): SpanRecord? {
        return session.spans.find { it.nodeId == nodeId }
    }

    /**
     * 比较两次执行
     */
    fun compare(session1: ReplaySession, session2: ReplaySession): ComparisonResult {
        val analysis1 = analyze(session1)
        val analysis2 = analyze(session2)

        val durationDiff = analysis2.totalDurationMs - analysis1.totalDurationMs
        val nodeDiff = analysis2.nodeCount - analysis1.nodeCount
        val bottleneckChanged = analysis1.bottleneckNodeId != analysis2.bottleneckNodeId

        val regressions = mutableListOf<String>()
        val improvements = mutableListOf<String>()

        if (durationDiff > 0) regressions.add("总耗时增加 ${durationDiff}ms")
        else if (durationDiff < 0) improvements.add("总耗时减少 ${-durationDiff}ms")

        if (analysis2.failureChain.size > analysis1.failureChain.size) {
            regressions.add("失败节点增加 ${analysis2.failureChain.size - analysis1.failureChain.size} 个")
        } else if (analysis2.failureChain.size < analysis1.failureChain.size) {
            improvements.add("失败节点减少 ${analysis1.failureChain.size - analysis2.failureChain.size} 个")
        }

        if (bottleneckChanged) {
            regressions.add("瓶颈节点变化: ${analysis1.bottleneckNodeId} -> ${analysis2.bottleneckNodeId}")
        }

        return ComparisonResult(
            session1 = analysis1,
            session2 = analysis2,
            durationDiffMs = durationDiff,
            nodeCountDiff = nodeDiff,
            bottleneckChanged = bottleneckChanged,
            regressions = regressions,
            improvements = improvements
        )
    }

    data class ComparisonResult(
        val session1: ReplayAnalysis,
        val session2: ReplayAnalysis,
        val durationDiffMs: Long,
        val nodeCountDiff: Int,
        val bottleneckChanged: Boolean,
        val regressions: List<String>,
        val improvements: List<String>
    ) {
        val isRegression: Boolean get() = regressions.isNotEmpty()
    }

    /**
     * 导出会话为可分享格式
     */
    fun exportSession(session: ReplaySession): String {
        val sb = StringBuilder()
        sb.appendLine("=== 工作流回放报告 ===")
        sb.appendLine("Thread ID: ${session.threadId}")
        sb.appendLine("总耗时: ${session.totalDurationMs}ms")
        sb.appendLine("节点数: ${session.nodeCount} (成功 ${session.successCount}, 失败 ${session.failureCount})")
        sb.appendLine()
        sb.appendLine("--- 时间轴 ---")
        session.timeline.forEach { e ->
            val indent = if (e.type == TimelineEventType.WORKFLOW_START) "" else "  "
            sb.appendLine("$indent+${e.relativeMs}ms [${e.durationMs}ms] ${e.nodeName} (${e.status})")
            if (e.exception != null) {
                sb.appendLine("$indent  ERROR: ${e.exception.message}")
            }
        }
        sb.appendLine()
        sb.appendLine("--- 分析 ---")
        val analysis = analyze(session)
        sb.appendLine("关键路径: ${analysis.criticalPathMs}ms")
        sb.appendLine("并行度: ${analysis.parallelismRatio}")
        sb.appendLine("瓶颈节点: ${analysis.bottleneckNodeId} (${analysis.bottleneckDurationMs}ms)")
        sb.appendLine("最慢节点:")
        analysis.slowestNodes.forEach { nt ->
            sb.appendLine("  ${nt.nodeName}: ${nt.durationMs}ms (${(nt.percentage * 100).toInt()}%)")
        }
        if (analysis.failureChain.isNotEmpty()) {
            sb.appendLine("失败链:")
            analysis.failureChain.forEach { fr ->
                sb.appendLine("  ${fr.nodeName}: ${fr.error}")
            }
        }
        return sb.toString()
    }
}
