package com.apex.lib.multiagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一次多 Agent 协作会话。
 *
 * 根据配置的 [CollaborationMode] 执行不同的协作策略。
 * 会话状态记录在 [blackboard] 中，所有 Agent 共享。
 */
class CollaborationSession(
    val sessionId: String,
    val config: CollaborationConfig,
    val blackboard: Blackboard,
    val agents: Map<String, Agent>,
    private val eventSink: MutableSharedFlow<MultiAgentEvent>
) {

    suspend fun execute(): SessionResult {
        val startMs = System.currentTimeMillis()
        var invocations = 0

        when (config.mode) {
            CollaborationMode.PIPELINE -> {
                var current: AgentInput = AgentInput(config.initialPrompt)
                for (agentId in config.agentIds) {
                    val agent = agents[agentId] ?: continue
                    eventSink.tryEmit(MultiAgentEvent.AgentStarted(sessionId, agentId))
                    val output = agent.execute(current, blackboard)
                    invocations++
                    eventSink.tryEmit(MultiAgentEvent.AgentFinished(sessionId, agentId, output))
                    blackboard.put("last.$agentId", output.result)
                    current = AgentInput(output.result, fromAgentId = agentId)
                }
                val finalOutput = (blackboard.get<String>("last.${config.agentIds.last()}"))
                    ?: current.prompt
                return SessionResult(
                    sessionId = sessionId,
                    finalOutput = finalOutput,
                    agentInvocations = invocations,
                    durationMs = System.currentTimeMillis() - startMs,
                    blackboardSnapshot = blackboard.snapshot()
                )
            }

            CollaborationMode.PARALLEL_RACING -> {
                // 简化实现：所有 Agent 并行执行，取第一个完成的
                val results = config.agentIds.map { agentId ->
                    val agent = agents[agentId] ?: return@map null
                    eventSink.tryEmit(MultiAgentEvent.AgentStarted(sessionId, agentId))
                    val input = AgentInput(config.initialPrompt, fromAgentId = "race")
                    val output = agent.execute(input, blackboard)
                    invocations++
                    eventSink.tryEmit(MultiAgentEvent.AgentFinished(sessionId, agentId, output))
                    agentId to output
                }.filterNotNull()
                val best = results.maxByOrNull { it.second.confidence }
                val finalOutput = best?.second?.result ?: ""
                return SessionResult(
                    sessionId = sessionId,
                    finalOutput = finalOutput,
                    agentInvocations = invocations,
                    durationMs = System.currentTimeMillis() - startMs,
                    blackboardSnapshot = blackboard.snapshot()
                )
            }

            else -> {
                // 其他模式：简化为顺序执行
                var current: AgentInput = AgentInput(config.initialPrompt)
                for (round in 0 until config.maxRounds) {
                    var progressed = false
                    for (agentId in config.agentIds) {
                        val agent = agents[agentId] ?: continue
                        eventSink.tryEmit(MultiAgentEvent.AgentStarted(sessionId, agentId))
                        val output = agent.execute(current, blackboard)
                        invocations++
                        eventSink.tryEmit(MultiAgentEvent.AgentFinished(sessionId, agentId, output))
                        blackboard.put("round.$round.$agentId", output.result)
                        current = AgentInput(output.result, fromAgentId = agentId)
                        progressed = true
                    }
                    if (!progressed) break
                }
                return SessionResult(
                    sessionId = sessionId,
                    finalOutput = current.prompt,
                    agentInvocations = invocations,
                    durationMs = System.currentTimeMillis() - startMs,
                    blackboardSnapshot = blackboard.snapshot()
                )
            }
        }
    }
}
