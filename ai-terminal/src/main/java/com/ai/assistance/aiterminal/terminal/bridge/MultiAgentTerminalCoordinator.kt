package com.ai.assistance.aiterminal.terminal.bridge

import com.ai.assistance.aiterminal.terminal.ui.*
import com.apex.agent.presentation.multiagent.data.*
import com.apex.agent.presentation.multiagent.state.MultiAgentPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 多 Agent 终端协调器。
 *
 * 将多 Agent 模式页面与终端桥接器联动。
 *
 * 5 种协作执行模式:
 * - agentExecute: 单 Agent 执行
 * - chainExecute: 链式（A 输出→B 输入）
 * - batchExecute: 批量并行
 * - burstExecute: 狂暴模式（所有 Agent 并行）
 * - pipelineExecute: 流水线
 * - debateExecute: 辩论（执行+审查）
 */
class MultiAgentTerminalCoordinator(
    private val bridge: TerminalBridge,
    private val pageState: MultiAgentPageState,
    private val terminal: ApexTerminal
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Agent 执行终端命令。
     */
    suspend fun agentExecute(agentId: String, command: String): TerminalExecutionResult {
        pageState.updateAgentStatus(agentId, AgentStatus.EXECUTING)
        pageState.updateAgentProgress(agentId, 0.1f, command)

        val agent = pageState.agents.value.find { it.id == agentId }
        if (agent != null) {
            pageState.sendAgentMessage(agentId, null, "Executing: $command", AgentMessageType.TASK_ASSIGNMENT)
        }

        val result = bridge.execute(command, agentId, agent?.role?.name?.lowercase() ?: "worker")

        if (result.success) {
            pageState.updateAgentStatus(agentId, AgentStatus.COMPLETED)
            pageState.updateAgentProgress(agentId, 1f)
            val summary = OutputSummarizer.summarize(result.output)
            pageState.sendAgentMessage(agentId, null, "Done (" + result.executionTimeMs + "ms)\n" + summary.summary, AgentMessageType.TASK_RESULT)
        } else {
            pageState.updateAgentStatus(agentId, AgentStatus.FAILED)
            pageState.sendAgentMessage(agentId, null, "Failed: " + result.error, AgentMessageType.TASK_RESULT)
        }

        return result
    }

    /**
     * 链式执行 — Agent A 的输出作为 Agent B 的输入。
     */
    suspend fun chainExecute(fromAgentId: String, toAgentId: String, command: String): TerminalExecutionResult {
        pageState.sendAgentMessage(fromAgentId, toAgentId, "Chain: $command", AgentMessageType.TASK_ASSIGNMENT)
        val result = agentExecute(fromAgentId, command)
        if (result.success) {
            pageState.sendAgentMessage(fromAgentId, toAgentId, "Output: " + result.output.take(200), AgentMessageType.TASK_RESULT)
        }
        return result
    }

    /**
     * 批量执行 — 多个 Agent 执行不同命令。
     */
    suspend fun batchExecute(assignments: Map<String, String>): Map<String, TerminalExecutionResult> {
        val results = mutableMapOf<String, TerminalExecutionResult>()
        val jobs = assignments.map { (agentId, command) ->
            scope.launch { results[agentId] = agentExecute(agentId, command) }
        }
        jobs.forEach { it.join() }
        return results
    }

    /**
     * 狂暴模式 — 所有 Agent 并行执行。
     */
    suspend fun burstExecute(taskDescription: String, commandGenerator: (String) -> String): Map<String, TerminalExecutionResult> {
        val agents = pageState.agents.value.filter { it.isActive }
        if (agents.isEmpty()) return emptyMap()

        pageState.sendAgentMessage(agents.first().id, null, "Burst start — " + agents.size + " agents: $taskDescription", AgentMessageType.SYSTEM)

        val assignments = agents.associate { it.id to commandGenerator(taskDescription) }
        val results = batchExecute(assignments)

        val successCount = results.count { it.value.success }
        pageState.sendAgentMessage(agents.first().id, null, "Burst done — $successCount/" + agents.size + " success", AgentMessageType.SYSTEM)

        return results
    }

    /**
     * 流水线执行 — Agent 按顺序执行，前一个输出作为后一个输入。
     */
    suspend fun pipelineExecute(
        agentIds: List<String>,
        initialCommand: String,
        commandTransformer: (String, String) -> String
    ): TerminalExecutionResult {
        if (agentIds.isEmpty()) return TerminalExecutionResult(success = false, error = "No agents")

        var currentOutput = ""
        var lastResult: TerminalExecutionResult? = null

        for ((index, agentId) in agentIds.withIndex()) {
            val command = if (index == 0) initialCommand else commandTransformer(agentId, currentOutput)
            pageState.sendAgentMessage(agentId, null, "Pipeline " + (index + 1) + "/" + agentIds.size + ": $command", AgentMessageType.TASK_ASSIGNMENT)

            lastResult = agentExecute(agentId, command)
            currentOutput = lastResult.output

            if (!lastResult.success) {
                pageState.sendAgentMessage(agentId, null, "Pipeline broke at stage " + (index + 1), AgentMessageType.SYSTEM)
                return lastResult
            }
        }

        return lastResult ?: TerminalExecutionResult(success = false, error = "No result")
    }

    /**
     * 辩论执行 — 多 Agent 对同一命令结果辩论。
     */
    suspend fun debateExecute(command: String, executorId: String, reviewerIds: List<String>): TerminalExecutionResult {
        pageState.sendAgentMessage(executorId, null, "Debate — executor starts", AgentMessageType.SYSTEM)
        val result = agentExecute(executorId, command)
        if (!result.success) return result

        val reviews = mutableListOf<String>()
        for (reviewerId in reviewerIds) {
            pageState.updateAgentStatus(reviewerId, AgentStatus.REVIEWING)
            val summary = OutputSummarizer.summarize(result.output)
            pageState.sendAgentMessage(reviewerId, executorId, "Review: " + summary.keyPoints.joinToString("; "), AgentMessageType.FEEDBACK)
            reviews.add("[$reviewerId] " + summary.keyPoints.joinToString("; "))
            pageState.updateAgentStatus(reviewerId, AgentStatus.COMPLETED)
        }

        return result.copy(output = result.output + "\n\n--- Reviews ---\n" + reviews.joinToString("\n"))
    }

    /**
     * 获取 Agent 终端使用统计。
     */
    fun getAgentTerminalStats(agentId: String): TerminalAgentStats {
        val history = bridge.getHistory(agentId)
        return TerminalAgentStats(
            agentId = agentId,
            totalCommands = history.size,
            successfulCommands = history.count { it.success },
            failedCommands = history.count { !it.success },
            averageExecutionTimeMs = if (history.isNotEmpty()) history.sumOf { it.executionTimeMs } / history.size else 0L
        )
    }

    fun shutdown() {
        for (agentId in bridge.getActiveAgentSessions().keys) {
            bridge.closeAgentSession(agentId)
        }
    }
}

data class TerminalAgentStats(
    val agentId: String,
    val totalCommands: Int,
    val successfulCommands: Int,
    val failedCommands: Int,
    val averageExecutionTimeMs: Long
) {
    val successRate: Float get() = if (totalCommands > 0) successfulCommands.toFloat() / totalCommands else 0f
}
