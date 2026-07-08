package com.apex.lib.multiagent

import com.apex.sdk.common.ApexLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 一次多 Agent 协作会话（增强版）。
 *
 * **实现 7 种协作模式**：
 *   1. PIPELINE — 顺序流水线（A→B→C）
 *   2. DEBATE — 辩论模式（多轮交锋 + 主持人裁决）
 *   3. ADVERSARIAL — 对抗模式（Generator vs Discriminator）
 *   4. PARALLEL_RACING — 并行竞速（取置信度最高）
 *   5. HIERARCHICAL — 层级模式（Supervisor 分派 + Reviewer 检查）
 *   6. VOTING — 投票表决（多数决）
 *   7. CONSENSUS — 共识达成（持续讨论直到同意）
 *
 * **增强点**：
 *   - 真实的并行执行（async/await）
 *   - 超时控制
 *   - 重试机制
 *   - 详细调用历史
 *   - Agent 间消息传递
 *   - 协作指标
 */
class CollaborationSession(
    val sessionId: String,
    val config: CollaborationConfig,
    val blackboard: Blackboard,
    val agents: Map<String, Agent>,
    private val eventSink: MutableSharedFlow<MultiAgentEvent>
) {
    private val invocations = mutableListOf<AgentInvocation>()
    private val messages = mutableListOf<AgentMessage>()
    private var invocationOrder = 0

    suspend fun execute(): SessionResult {
        val startMs = System.currentTimeMillis()
        eventSink.tryEmit(MultiAgentEvent.SessionStarted(sessionId, config.mode.name, config.agentIds.size))

        val result = withTimeoutOrNull(config.timeoutMs) {
            when (config.mode) {
                CollaborationMode.PIPELINE -> executePipeline()
                CollaborationMode.DEBATE -> executeDebate()
                CollaborationMode.ADVERSARIAL -> executeAdversarial()
                CollaborationMode.PARALLEL_RACING -> executeParallelRacing()
                CollaborationMode.HIERARCHICAL -> executeHierarchical()
                CollaborationMode.VOTING -> executeVoting()
                CollaborationMode.CONSENSUS -> executeConsensus()
            }
        } ?: run {
            eventSink.tryEmit(MultiAgentEvent.SessionFailed(sessionId, "timeout after ${config.timeoutMs}ms"))
            SessionResult(
                sessionId = sessionId,
                finalOutput = "",
                agentInvocations = invocations.size,
                durationMs = System.currentTimeMillis() - startMs,
                blackboardSnapshot = blackboard.snapshot(),
                rounds = 0,
                successRate = 0.0f,
                invocations = if (config.recordInvocations) invocations.toList() else emptyList()
            )
        }

        val durationMs = System.currentTimeMillis() - startMs
        val finalResult = result.copy(
            durationMs = durationMs,
            invocations = if (config.recordInvocations) invocations.toList() else emptyList(),
            successRate = if (invocations.isEmpty()) 1.0f else invocations.count { it.success }.toFloat() / invocations.size
        )

        eventSink.tryEmit(MultiAgentEvent.SessionCompleted(sessionId, finalResult))
        return finalResult
    }

    // ============================================================
    // 1. PIPELINE — 顺序流水线
    // ============================================================

    private suspend fun executePipeline(): SessionResult {
        var current: AgentInput = AgentInput(
            prompt = config.initialPrompt,
            sessionId = sessionId,
            round = 0
        )
        var round = 0

        for (agentId in config.agentIds) {
            val agent = agents[agentId] ?: continue
            val output = invokeAgent(agent, current, round)
            if (output == null) {
                if (!config.continueOnFailure) {
                    break
                }
                continue
            }
            blackboard.put("pipeline.$agentId", output.result, writer = agentId)
            blackboard.put("last.$agentId", output.result, writer = agentId)
            current = AgentInput(
                prompt = output.result,
                fromAgentId = agentId,
                sessionId = sessionId,
                round = round
            )
            if (output.shouldStop) break
            round++
        }

        val finalOutput = blackboard.get<String>("last.${config.agentIds.last()}") ?: current.prompt
        return SessionResult(
            sessionId = sessionId,
            finalOutput = finalOutput,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = round,
            agentResults = collectAgentResults()
        )
    }

    // ============================================================
    // 2. DEBATE — 辩论模式（多轮交锋 + 主持人裁决）
    // ============================================================

    private suspend fun executeDebate(): SessionResult {
        val moderatorId = config.moderatorId ?: config.agentIds.firstOrNull() ?: ""
        val debaters = config.agentIds.filter { it != moderatorId }
        var currentPrompt = config.initialPrompt
        var round = 0
        var finalOutput = currentPrompt

        for (roundIdx in 0 until config.maxRounds) {
            round = roundIdx
            // 每个辩手发表观点
            for (debaterId in debaters) {
                val agent = agents[debaterId] ?: continue
                val input = AgentInput(
                    prompt = currentPrompt,
                    fromAgentId = if (roundIdx > 0) moderatorId else null,
                    sessionId = sessionId,
                    round = roundIdx,
                    context = mapOf("role" to "debater", "round" to roundIdx)
                )
                val output = invokeAgent(agent, input, roundIdx) ?: continue
                blackboard.put("debate.round$roundIdx.$debaterId", output.result, writer = debaterId)
                currentPrompt = output.result
                finalOutput = output.result
            }
            eventSink.tryEmit(MultiAgentEvent.RoundCompleted(sessionId, roundIdx, debaters.size))

            // 主持人裁决
            val moderator = agents[moderatorId]
            if (moderator != null) {
                val modInput = AgentInput(
                    prompt = "请总结第 ${roundIdx + 1} 轮辩论并给出裁决:\n$currentPrompt",
                    fromAgentId = debaters.lastOrNull(),
                    sessionId = sessionId,
                    round = roundIdx,
                    context = mapOf("role" to "moderator")
                )
                val modOutput = invokeAgent(moderator, modInput, roundIdx)
                if (modOutput != null) {
                    blackboard.put("debate.round$roundIdx.moderator", modOutput.result, writer = moderatorId)
                    finalOutput = modOutput.result
                    // 主持人可以提前结束辩论
                    if (modOutput.shouldStop) break
                    currentPrompt = modOutput.result
                }
            }
        }

        return SessionResult(
            sessionId = sessionId,
            finalOutput = finalOutput,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = round + 1,
            agentResults = collectAgentResults()
        )
    }

    // ============================================================
    // 3. ADVERSARIAL — 对抗模式（Generator vs Discriminator）
    // ============================================================

    private suspend fun executeAdversarial(): SessionResult {
        val generatorId = config.agentIds.getOrNull(0) ?: ""
        val discriminatorId = config.agentIds.getOrNull(1) ?: generatorId
        val generator = agents[generatorId]
        val discriminator = agents[discriminatorId]
        if (generator == null || discriminator == null) {
            return emptyResult("adversarial requires 2 agents")
        }

        var currentOutput = config.initialPrompt
        var round = 0
        var bestOutput = currentOutput
        var bestScore = Float.MIN_VALUE

        for (roundIdx in 0 until config.maxRounds) {
            round = roundIdx
            // Generator 生成
            val genInput = AgentInput(
                prompt = currentOutput,
                fromAgentId = if (roundIdx > 0) discriminatorId else null,
                sessionId = sessionId,
                round = roundIdx,
                context = mapOf("role" to "generator")
            )
            val genOutput = invokeAgent(generator, genInput, roundIdx) ?: continue
            blackboard.put("adversarial.round$roundIdx.generator", genOutput.result, writer = generatorId)

            // Discriminator 评判
            val discInput = AgentInput(
                prompt = "评判以下输出的质量（0-1 分）:\n${genOutput.result}",
                fromAgentId = generatorId,
                sessionId = sessionId,
                round = roundIdx,
                context = mapOf("role" to "discriminator")
            )
            val discOutput = invokeAgent(discriminator, discInput, roundIdx) ?: continue
            blackboard.put("adversarial.round$roundIdx.discriminator", discOutput.result, writer = discriminatorId)

            // 更新最佳
            val score = discOutput.score ?: discOutput.confidence
            if (score > bestScore) {
                bestScore = score
                bestOutput = genOutput.result
            }

            eventSink.tryEmit(MultiAgentEvent.RoundCompleted(sessionId, roundIdx, 2))

            // 分数足够高则停止
            if (score >= 0.9f) break
            currentOutput = genOutput.result
        }

        return SessionResult(
            sessionId = sessionId,
            finalOutput = bestOutput,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = round + 1,
            agentResults = mapOf(
                generatorId to "score=$bestScore",
                discriminatorId to "final_score=$bestScore"
            )
        )
    }

    // ============================================================
    // 4. PARALLEL_RACING — 并行竞速（真实并行 + 取置信度最高）
    // ============================================================

    private suspend fun executeParallelRacing(): SessionResult = coroutineScope {
        val input = AgentInput(
            prompt = config.initialPrompt,
            fromAgentId = "race",
            sessionId = sessionId,
            round = 0
        )

        // 真实并行执行
        val deferredResults = config.agentIds.map { agentId ->
            val agent = agents[agentId] ?: return@map null
            async(Dispatchers.Default) {
                eventSink.tryEmit(MultiAgentEvent.AgentStarted(sessionId, agentId, agent.displayName, 0, invocationOrder++))
                val startMs = System.currentTimeMillis()
                try {
                    val output = agent.execute(input, blackboard)
                    val dur = System.currentTimeMillis() - startMs
                    recordInvocation(agent, input, output, 0, dur, true)
                    eventSink.tryEmit(MultiAgentEvent.AgentFinished(sessionId, agentId, output, dur))
                    blackboard.put("race.$agentId", output.result, writer = agentId)
                    Triple(agentId, output, dur)
                } catch (t: Throwable) {
                    val dur = System.currentTimeMillis() - startMs
                    recordInvocation(agent, input, AgentOutput(result = "", false), 0, dur, false, t.message)
                    eventSink.tryEmit(MultiAgentEvent.AgentFailed(sessionId, agentId, t.message ?: "unknown"))
                    null
                }
            }
        }.filterNotNull()

        val results = deferredResults.awaitAll().filterNotNull()
        // 取置信度最高（或分数最高）
        val best = results.maxByOrNull { it.second.confidence }
        val finalOutput = best?.second?.result ?: ""

        SessionResult(
            sessionId = sessionId,
            finalOutput = finalOutput,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = 1,
            agentResults = results.associate { it.first to "confidence=${it.second.confidence}" }
        )
    }

    // ============================================================
    // 5. HIERARCHICAL — 层级模式（Supervisor 分派 + Reviewer 检查）
    // ============================================================

    private suspend fun executeHierarchical(): SessionResult {
        val supervisorId = config.agentIds.firstOrNull { agents[it]?.role == AgentRole.SUPERVISOR }
            ?: config.agentIds.firstOrNull() ?: ""
        val supervisor = agents[supervisorId]
        val workers = config.agentIds.filter { it != supervisorId }
            .mapNotNull { agents[it] }
            .filter { it.role == AgentRole.WORKER }
        val reviewers = config.agentIds.mapNotNull { agents[it] }
            .filter { it.role == AgentRole.REVIEWER }

        if (supervisor == null) return emptyResult("hierarchical requires a supervisor")

        var currentPrompt = config.initialPrompt
        var round = 0

        for (roundIdx in 0 until config.maxRounds) {
            round = roundIdx
            // 1. Supervisor 分析并分派任务
            val supInput = AgentInput(
                prompt = currentPrompt,
                sessionId = sessionId,
                round = roundIdx,
                context = mapOf("role" to "supervisor", "workers" to workers.map { it.id })
            )
            val supOutput = invokeAgent(supervisor, supInput, roundIdx) ?: continue
            blackboard.put("hierarchical.round$roundIdx.supervisor", supOutput.result, writer = supervisorId)

            // 2. Workers 并行执行（按优先级排序）
            val sortedWorkers = workers.sortedBy { it.priority }
            val workerResults = coroutineScope {
                sortedWorkers.map { worker ->
                    async(Dispatchers.Default) {
                        val wInput = AgentInput(
                            prompt = supOutput.result,
                            fromAgentId = supervisorId,
                            sessionId = sessionId,
                            round = roundIdx,
                            context = mapOf("role" to "worker", "assignment" to worker.id)
                        )
                        val wOutput = invokeAgent(worker, wInput, roundIdx)
                        if (wOutput != null) {
                            blackboard.put("hierarchical.round$roundIdx.worker.${worker.id}", wOutput.result, writer = worker.id)
                        }
                        worker.id to wOutput
                    }
                }.awaitAll()
            }

            // 3. Reviewers 检查
            for (reviewer in reviewers) {
                val reviewInput = AgentInput(
                    prompt = "审查以下工作结果:\n${workerResults.mapNotNull { it.second?.result }.joinToString("\n---\n")}",
                    fromAgentId = workerResults.lastOrNull()?.first,
                    sessionId = sessionId,
                    round = roundIdx,
                    context = mapOf("role" to "reviewer")
                )
                val revOutput = invokeAgent(reviewer, reviewInput, roundIdx)
                if (revOutput != null) {
                    blackboard.put("hierarchical.round$roundIdx.reviewer.${reviewer.id}", revOutput.result, writer = reviewer.id)
                    // 审查通过则结束
                    if (revOutput.shouldStop) {
                        return SessionResult(
                            sessionId = sessionId,
                            finalOutput = revOutput.result,
                            agentInvocations = invocations.size,
                            durationMs = 0,
                            blackboardSnapshot = blackboard.snapshot(),
                            rounds = roundIdx + 1,
                            agentResults = collectAgentResults()
                        )
                    }
                    currentPrompt = revOutput.result
                }
            }

            eventSink.tryEmit(MultiAgentEvent.RoundCompleted(sessionId, roundIdx, workers.size + reviewers.size + 1))

            // Supervisor 认为完成
            if (supOutput.shouldStop) break
        }

        return SessionResult(
            sessionId = sessionId,
            finalOutput = blackboard.get<String>("hierarchical.round$round.supervisor") ?: currentPrompt,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = round + 1,
            agentResults = collectAgentResults()
        )
    }

    // ============================================================
    // 6. VOTING — 投票表决
    // ============================================================

    private suspend fun executeVoting(): SessionResult {
        val input = AgentInput(
            prompt = config.initialPrompt,
            sessionId = sessionId,
            round = 0
        )

        // 每个 Agent 独立执行 + 投票
        val votes = config.agentIds.mapNotNull { agentId ->
            val agent = agents[agentId] ?: return@mapNotNull null
            val output = invokeAgent(agent, input, 0) ?: return@mapNotNull null
            blackboard.put("vote.$agentId", output.result, writer = agentId)
            Triple(agentId, output.result, output.vote ?: (output.confidence >= 0.5f))
        }

        // 统计投票
        val yesCount = votes.count { it.third }
        val noCount = votes.size - yesCount
        val threshold = (votes.size * config.votingThreshold).toInt()
        val passed = yesCount >= threshold

        // 取得票最多的结果
        val resultByVotes = votes.groupBy { it.second }
            .maxByOrNull { it.value.size }?.value?.firstOrNull()?.second ?: ""

        val finalOutput = if (passed) {
            "✅ 投票通过 ($yesCount 赞成 / $noCount 反对)\n$resultByVotes"
        } else {
            "❌ 投票未通过 ($yesCount 赞成 / $noCount 反对，需 ${threshold + 1} 票)\n$resultByVotes"
        }

        return SessionResult(
            sessionId = sessionId,
            finalOutput = finalOutput,
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = 1,
            agentResults = votes.associate { it.first to if (it.third) "赞成" else "反对" }
        )
    }

    // ============================================================
    // 7. CONSENSUS — 共识达成
    // ============================================================

    private suspend fun executeConsensus(): SessionResult {
        var currentPrompt = config.initialPrompt
        var round = 0
        var agreedCount = 0
        val totalAgents = config.agentIds.size

        for (roundIdx in 0 until config.maxRounds) {
            round = roundIdx
            agreedCount = 0
            val roundOutputs = mutableListOf<Pair<String, AgentOutput>>()

            for (agentId in config.agentIds) {
                val agent = agents[agentId] ?: continue
                val input = AgentInput(
                    prompt = currentPrompt,
                    fromAgentId = if (roundIdx > 0) config.agentIds.last() else null,
                    sessionId = sessionId,
                    round = roundIdx,
                    context = mapOf("role" to "consensus", "round" to roundIdx)
                )
                val output = invokeAgent(agent, input, roundIdx) ?: continue
                roundOutputs.add(agentId to output)
                blackboard.put("consensus.round$roundIdx.$agentId", output.result, writer = agentId)
                // confidence >= 阈值视为同意
                if (output.confidence >= config.consensusThreshold) {
                    agreedCount++
                }
                currentPrompt = output.result
            }

            eventSink.tryEmit(MultiAgentEvent.RoundCompleted(sessionId, roundIdx, totalAgents))

            // 检查是否达成共识
            val agreement = if (totalAgents > 0) agreedCount.toFloat() / totalAgents else 0f
            if (agreement >= config.consensusThreshold) {
                return SessionResult(
                    sessionId = sessionId,
                    finalOutput = currentPrompt,
                    agentInvocations = invocations.size,
                    durationMs = 0,
                    blackboardSnapshot = blackboard.snapshot(),
                    rounds = roundIdx + 1,
                    agentResults = roundOutputs.associate { it.first to "confidence=${it.second.confidence}" }
                )
            }
        }

        // 未达成共识
        return SessionResult(
            sessionId = sessionId,
            finalOutput = "⚠️ 未达成共识（${agreedCount}/${totalAgents} 同意，需 ${config.consensusThreshold * totalAgents}）\n$currentPrompt",
            agentInvocations = invocations.size,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = round + 1,
            agentResults = collectAgentResults()
        )
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private suspend fun invokeAgent(
        agent: Agent,
        input: AgentInput,
        round: Int
    ): AgentOutput? {
        eventSink.tryEmit(MultiAgentEvent.AgentStarted(sessionId, agent.id, agent.displayName, round, invocationOrder))
        val startMs = System.currentTimeMillis()

        var lastError: String? = null
        repeat(agent.maxRetries + 1) { attempt ->
            try {
                val output = if (agent.timeoutMs > 0) {
                    withTimeoutOrNull(agent.timeoutMs) { agent.execute(input, blackboard) }
                } else {
                    agent.execute(input, blackboard)
                }
                if (output != null) {
                    val dur = System.currentTimeMillis() - startMs
                    recordInvocation(agent, input, output, round, dur, true)
                    eventSink.tryEmit(MultiAgentEvent.AgentFinished(sessionId, agent.id, output, dur))
                    return output
                }
            } catch (t: Throwable) {
                lastError = t.message
                ApexLog.w("multi-agent", "[Session] agent ${agent.id} attempt ${attempt + 1} failed: $lastError")
            }
        }

        val dur = System.currentTimeMillis() - startMs
        recordInvocation(agent, input, AgentOutput(result = ""), round, dur, false, lastError)
        eventSink.tryEmit(MultiAgentEvent.AgentFailed(sessionId, agent.id, lastError ?: "unknown"))
        return null
    }

    private fun recordInvocation(
        agent: Agent,
        input: AgentInput,
        output: AgentOutput,
        round: Int,
        durationMs: Long,
        success: Boolean,
        error: String? = null
    ) {
        invocations.add(AgentInvocation(
            agentId = agent.id,
            agentName = agent.displayName,
            role = agent.role.name,
            round = round,
            order = invocationOrder++,
            timestamp = System.currentTimeMillis(),
            durationMs = durationMs,
            inputPrompt = input.prompt.take(200),
            outputResult = output.result.take(500),
            confidence = output.confidence,
            success = success,
            errorMessage = error
        ))
    }

    /** Agent 间消息传递。 */
    fun sendMessage(message: AgentMessage) {
        messages.add(message)
        eventSink.tryEmit(MultiAgentEvent.MessageSent(sessionId, message.fromAgentId, message.toAgentId, message))
    }

    /** 获取所有消息。 */
    fun getMessages(): List<AgentMessage> = messages.toList()

    private fun collectAgentResults(): Map<String, String> {
        return invocations.groupBy { it.agentId }
            .mapValues { (id, invs) ->
                val last = invs.lastOrNull()
                "${last?.outputResult?.take(100) ?: ""} (${invs.size} 次调用)"
            }
    }

    private fun emptyResult(reason: String): SessionResult {
        eventSink.tryEmit(MultiAgentEvent.SessionFailed(sessionId, reason))
        return SessionResult(
            sessionId = sessionId,
            finalOutput = "",
            agentInvocations = 0,
            durationMs = 0,
            blackboardSnapshot = blackboard.snapshot(),
            rounds = 0,
            successRate = 0.0f
        )
    }
}
