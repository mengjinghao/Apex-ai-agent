package com.apex.agent.core.swarm

import android.content.Context
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.util.*

class SwarmIntelligenceEngine(
    private val context: Context,
    private val aiService: AIService
) {

    companion object {
        private const val TAG = "SwarmIntelligenceEngine"
        private const val MAX_DEBATE_ROUNDS = 3
        private const val CONSENSUS_THRESHOLD = 0.66
    }

    private val debates = mutableMapOf<String, Debate>()
    private val swarmTasks = mutableMapOf<String, SwarmTask>()

    /**
     * AI 驱动调用：通过 PromptTurn 发送消息给真实�?AI 服务并收集完整响�?
     */
    private suspend fun callAI(prompt: String, systemPrompt: String = "You are a helpful AI assistant."): String {
        return try {
            val turns = listOf(
                PromptTurn(kind = PromptTurnKind.SYSTEM, content = systemPrompt),
                PromptTurn(kind = PromptTurnKind.USER, content = prompt)
            )
            val result = StringBuilder()
            aiService.sendMessage(
                context = context,
                chatHistory = turns,
                stream = false
            ).collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    suspend fun startDebate(
        topic: String,
        participantIds: List<String>
    ): Debate = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Starting debate on topic: ${topic}")

        val debate = Debate(
            topic = topic,
            participants = participantIds,
            arguments = mutableListOf(),
            status = DebateStatus.IN_PROGRESS
        )

        debates[debate.id] = debate

        conductDebate(debate.id)

        debate
    }

    private suspend fun conductDebate(debateId: String) = withContext(Dispatchers.IO) {
        val debate = debates[debateId] ?: return@withContext

        for (round in 1..MAX_DEBATE_ROUNDS) {
            AppLogger.d(TAG, "Debate round ${round} for debate: ${debateId}")

            // 并行生成每个代理的论点（async/awaitAll�?
            val newArguments = debate.participants.map { agentId ->
                async {
                    generateArgument(agentId, debate.topic, debate.arguments)
                }
            }.awaitAll()

            debate.arguments.addAll(newArguments)

            if (round < MAX_DEBATE_ROUNDS) {
                addRebuttals(debateId)
            }
        }

        debate.status = DebateStatus.COMPLETED
        debate.updatedAt = System.currentTimeMillis()
    }

    private suspend fun generateArgument(
        agentId: String,
        topic: String,
        existingArguments: List<Argument>
    ): Argument {
        val stance = getRandomStance()

        // AI 驱动：如�?AI 失败则回退到模�?
        val aiPrompt = buildString {
            append("请为辩论主题'${topic}'生成一个论点。\n")
            append("你的立场�? ${stance.name}\n")
            append("其他参与者最近的论点:\n")
            existingArguments.takeLast(5).forEach { append("- ${it.content}\n") }
            append("请给出简短、有根据且不同于其他论点的观点（1-2句话）�?)
        }
        val aiResponse = callAI(
            prompt = aiPrompt,
            systemPrompt = "你是一名出色的辩论家。你的回复应该简短、具体，并基于事实进行论证�?
        )
        val content = aiResponse.ifBlank {
            generateArgumentContentFallback(topic, stance, existingArguments)
        }

        return Argument(
            agentId = agentId,
            content = content,
            stance = stance,
            confidence = 0.7f + Random().nextFloat() * 0.3f,
            evidence = generateEvidence(topic)
        )
    }

    private fun getRandomStance(): Stance {
        val rand = Random().nextInt(10)
        return when {
            rand < 4 -> Stance.FOR
            rand < 7 -> Stance.AGAINST
            else -> Stance.NEUTRAL
        }
    }

    private fun generateArgumentContentFallback(
        topic: String,
        stance: Stance,
        existingArguments: List<Argument>
    ): String {
        val stancePrefix = when (stance) {
            Stance.FOR -> "支持"
            Stance.AGAINST -> "反对"
            Stance.NEUTRAL -> "中立观点"
        }

        val points = listOf(
            "从技术角度分�?,
            "考虑用户体验",
            "评估实现难度",
            "分析潜在风险",
            "考虑长期影响"
        )

        val selectedPoint = points[Random().nextInt(points.size)]

        return "${stancePrefix} '${topic}' - ${selectedPoint}"
    }

    private fun generateEvidence(topic: String): List<String> {
        return listOf(
            "相关研究表明...",
            "过往经验显示...",
            "数据分析支持..."
        ).take(Random().nextInt(3) + 1)
    }

    private suspend fun addRebuttals(debateId: String) = withContext(Dispatchers.IO) {
        val debate = debates[debateId] ?: return@withContext
        val recentArguments = debate.arguments.takeLast(debate.participants.size)

        recentArguments.forEach { argument ->
            debate.participants.filter { it != argument.agentId }.forEach { rebutterId ->
                if (Random().nextBoolean()) {
                    val rebuttalId = UUID.randomUUID().toString()
                    argument.rebuttals.add(rebuttalId)
                }
            }
        }
    }

    suspend fun reachConsensus(debateId: String): Consensus? = withContext(Dispatchers.IO) {
        val debate = debates[debateId] ?: return@withContext null

        if (debate.status != DebateStatus.COMPLETED) {
            conductDebate(debateId)
        }

        // AI 驱动：让每个代理基于辩论历史生成真实意见
        val opinions = debate.participants.map { agentId ->
            val aiOpinion = callAI(
                prompt = "关于辩论主题'${debate.topic}'，请阅读以下论点后给出你的最终意见和投票:\n${debate.arguments.takeLast(10).map { "- [${it.stance.name}] ${it.content}" }.replace("\\n\"},
                systemPrompt = "你是一名辩论评估员，基于理性思考给出你的最终意见，请以明确表态�?
            )
            AgentOpinion(
                agentId = agentId,
                opinion = aiOpinion.ifBlank { "基于讨论，我认为..." },
                confidence = 0.7f + Random().nextFloat() * 0.3f,
                reasoning = aiOpinion.ifBlank { "综合考虑各方面因�?.." },
                vote = determineVoteFromAI(aiOpinion)
            )
        }

        val voteResults = calculateVoteResults(opinions)

        // AI 驱动：让 AI 作为辩论主席综合分析生成结论
        val aiConclusion = callAI(
            prompt = "请作为辩论主席，综合以下讨论，给出最终结论。\n\n辩论主题: ${debate.topic}\n参与者意�?\n${opinions.joinToString("\n") { "- ${it.opinion} (投票: ${it.vote}" },
            systemPrompt = "你是一名中立的辩论主席。你的回复应该客观、平衡，综合各方观点给出建设性结论�?
        )
        val conclusion = aiConclusion.ifBlank { synthesizeConclusionFallback(debate, opinions) }
        val confidence = calculateConsensusConfidence(opinions)

        Consensus(
            debateId = debateId,
            conclusion = conclusion,
            confidence = confidence,
            supportingArguments = debate.arguments.filter { it.stance == Stance.FOR }.map { it.id },
            dissentingArguments = debate.arguments.filter { it.stance == Stance.AGAINST }.map { it.id },
            voteResults = voteResults
        )
    }

    /**
     * 根据 AI 响应中的关键词自动判定投票类�?
     */
    private fun determineVoteFromAI(aiResponse: String): VoteType {
        val lower = aiResponse.lowercase(Locale.getDefault())
        return when {
            lower.contains("反对") || lower.contains("�?) || lower.contains("�?) || lower.contains("反对") ||
            lower.contains("disagree") || lower.contains("oppose") || lower.contains("no") || lower.contains("refuse") || lower.contains("reject") -> VoteType.DISAGREE
            lower.contains("支持") || lower.contains("同意") || lower.contains("�?) || lower.contains("同意") ||
            lower.contains("agree") || lower.contains("support") || lower.contains("yes") || lower.contains("endorse") || lower.contains("approve") -> VoteType.AGREE
            else -> VoteType.ABSTAIN
        }
    }

    private fun getRandomVote(): VoteType {
        val rand = Random().nextInt(10)
        return when {
            rand < 5 -> VoteType.AGREE
            rand < 8 -> VoteType.DISAGREE
            else -> VoteType.ABSTAIN
        }
    }

    private fun calculateVoteResults(opinions: List<AgentOpinion>): VoteResults {
        val agreeCount = opinions.count { it.vote == VoteType.AGREE }
        val disagreeCount = opinions.count { it.vote == VoteType.DISAGREE }
        val abstainCount = opinions.count { it.vote == VoteType.ABSTAIN }

        return VoteResults(
            totalVotes = opinions.size,
            agreeCount = agreeCount,
            disagreeCount = disagreeCount,
            abstainCount = abstainCount,
            voterIds = opinions.map { it.agentId }
        )
    }

    private fun synthesizeConclusionFallback(
        debate: Debate,
        opinions: List<AgentOpinion>
    ): String {
        val agreeOpinions = opinions.filter { it.vote == VoteType.AGREE }
        val disagreeOpinions = opinions.filter { it.vote == VoteType.DISAGREE }

        return buildString {
            appendLine("关于\"${debate.topic}\"的共识结�?")
            appendLine()
            if (agreeOpinions.isNotEmpty()) {
                appendLine("支持方观�?")
                agreeOpinions.take(3).forEach { appendLine("- ${it.opinion}") }
            }
            if (disagreeOpinions.isNotEmpty()) {
                appendLine("反对方观�?")
                disagreeOpinions.take(3).forEach { appendLine("- ${it.opinion}") }
            }
            appendLine()
            appendLine("综合结论: 根据讨论，建议采取平衡方案�?)
        }
    }

    private fun calculateConsensusConfidence(opinions: List<AgentOpinion>): Float {
        if (opinions.isEmpty()) return 0.1f
        val avgConfidence = opinions.map { it.confidence }.average().toFloat()
        val agreementRatio = opinions.count { it.vote == VoteType.AGREE }.toFloat() / opinions.size

        return (avgConfidence * 0.5f + agreementRatio * 0.5f).coerceIn(0.1f, 1.0f)
    }

    suspend fun executeSwarmTask(task: SwarmTask): SwarmResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Executing swarm task: ${task.description}")

        swarmTasks[task.id] = task

        val solutions = mutableListOf<Solution>()

        val agentSolutions = task.maxAgents.times { agentIndex ->
            async {
                generateSolution("agent_${agentIndex + 1}", task)
            }
        }.awaitAll()

        solutions.addAll(agentSolutions)

        val consensus = if (task.requireConsensus) {
            simulateConsensus(task, solutions)
        } else {
            null
        }

        val qualityScore = calculateQualityScore(solutions)
        val diversityScore = calculateDiversityScore(solutions)

        SwarmResult(
            taskId = task.id,
            solutions = solutions,
            consensus = consensus,
            qualityScore = qualityScore,
            diversityScore = diversityScore
        )
    }

    private suspend fun generateSolution(agentId: String, task: SwarmTask): Solution {
        val solutionContent = when (task.type) {
            TaskType.PROBLEM_SOLVING -> "针对问题\"${task.description}\"的解决方�?.."
            TaskType.DECISION_MAKING -> "关于\"${task.description}\"的决策建�?.."
            TaskType.CREATIVE_GENERATION -> "创意方案: ${task.description}..."
            TaskType.CRITICAL_ANALYSIS -> "分析报告: ${task.description}..."
            TaskType.PLANNING -> "行动计划: ${task.description}..."
        }

        return Solution(
            agentId = agentId,
            content = solutionContent,
            qualityScore = 0.6f + Random().nextFloat() * 0.4f,
            supportingEvidence = listOf("依据1", "依据2")
        )
    }

    private fun simulateConsensus(task: SwarmTask, solutions: List<Solution>): Consensus? {
        if (solutions.isEmpty()) return null

        val bestSolution = solutions.maxByOrNull { it.qualityScore }
        val agreeCount = (solutions.size * 0.7).toInt()

        return Consensus(
            debateId = task.id,
            conclusion = bestSolution?.content ?: "无共�?,
            confidence = bestSolution?.qualityScore ?: 0.5f,
            supportingArguments = solutions.filter { it.qualityScore > 0.7 }.map { it.id },
            dissentingArguments = solutions.filter { it.qualityScore <= 0.5 }.map { it.id },
            voteResults = VoteResults(
                totalVotes = solutions.size,
                agreeCount = agreeCount,
                disagreeCount = solutions.size - agreeCount,
                abstainCount = 0,
                voterIds = solutions.map { it.agentId }
            )
        )
    }

    private fun calculateQualityScore(solutions: List<Solution>): Float {
        if (solutions.isEmpty()) return 0.0f
        return solutions.map { it.qualityScore }.average().toFloat()
    }

    private fun calculateDiversityScore(solutions: List<Solution>): Float {
        if (solutions.size < 2) return 1.0f

        val scoreVariance = solutions.map { it.qualityScore }.let { scores ->
            val mean = scores.average()
            scores.map { (it - mean).let { it * it } }.average()
        }

        return (scoreVariance * 10).coerceIn(0.1f, 1.0f).toFloat()
    }

    suspend fun getDebate(debateId: String): Debate? = withContext(Dispatchers.IO) {
        debates[debateId]
    }

    suspend fun getDebateSummary(debateId: String): DebateSummary? = withContext(Dispatchers.IO) {
        val debate = debates[debateId] ?: return@withContext null

        val forCount = debate.arguments.count { it.stance == Stance.FOR }
        val againstCount = debate.arguments.count { it.stance == Stance.AGAINST }
        val neutralCount = debate.arguments.count { it.stance == Stance.NEUTRAL }

        DebateSummary(
            debateId = debate.id,
            topic = debate.topic,
            totalArguments = debate.arguments.size,
            forCount = forCount,
            againstCount = againstCount,
            neutralCount = neutralCount,
            consensus = reachConsensus(debateId),
            durationMs = debate.updatedAt - debate.createdAt
        )
    }

    suspend fun getAllDebates(): List<Debate> = withContext(Dispatchers.IO) {
        debates.values.toList()
    }

    suspend fun closeDebate(debateId: String) = withContext(Dispatchers.IO) {
        debates[debateId]?.let {
            it.status = DebateStatus.COMPLETED
            it.updatedAt = System.currentTimeMillis()
        }
    }

    fun getSwarmTask(taskId: String): SwarmTask? {
        return swarmTasks[taskId]
    }

    fun getAllSwarmTasks(): List<SwarmTask> {
        return swarmTasks.values.toList()
    }

    fun cleanupOldDebates(maxAgeMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        debates.entries.removeIf { it.value.createdAt < cutoff }
    }
}