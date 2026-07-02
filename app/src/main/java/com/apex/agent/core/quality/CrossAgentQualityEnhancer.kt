package com.apex.agent.core.quality

import android.content.Context
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.core.evaluation.QualityGate
import com.apex.agent.core.evaluation.ValidationResult
import com.apex.agent.core.multiagent.Agent
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class CrossAgentQualityEnhancer(
    private val context: Context,
    private val aiService: AIService?
) {
    companion object {
        private const val TAG = "CrossAgentQualityEnhancer"
    }

    data class AgentOutput(
        val agent: Agent,
        val output: String,
        val confidence: Float = 0.0f
    )

    data class ReviewResult(
        val agentOutput: AgentOutput,
        val score: Float,
        val issues: List<String>,
        val suggestions: List<String>,
        val passed: Boolean
    )

    suspend fun reviewAgentOutput(
        reviewer: Agent,
        agentOutput: AgentOutput,
        taskContext: String
    ): ReviewResult = withContext(Dispatchers.Default) {
        val session = AgentThinkingSession(context, aiService)
        val reviewPrompt = buildString {
            appendLine("Review the following agent output for quality, correctness, and completeness.")
            appendLine("Task/Context: $taskContext")
            appendLine("Agent: ${agentOutput.agent.name} (${agentOutput.agent.role})")
            appendLine("Agent's output: ${agentOutput.output}")
            appendLine("\nProvide structured feedback with scores.")
        }

        val review = aiService?.let {
            val result = session.thinkAndProduce(
                agent = reviewer,
                task = "Quality review of ${agentOutput.agent.name}'s work",
                background = taskContext,
                instructions = "Evaluate: correctness, completeness, clarity, relevance. Score 0-1.",
                enableValidation = false
            )
            result.finalAnswer
        } ?: ""

        val score = extractScore(review, 0.7f)
        val issues = extractIssues(review)
        val suggestions = extractSuggestions(review)
        val passed = score >= 0.6f

        ReviewResult(agentOutput, score, issues, suggestions, passed)
    }

    suspend fun crossReviewAll(
        agents: List<Agent>,
        outputs: Map<String, String>,
        taskContext: String
    ): List<ReviewResult> = coroutineScope {
        val session = AgentThinkingSession(context, aiService)
        agents.map { reviewer ->
            async {
                val reviewed = mutableListOf<ReviewResult>()
                outputs.forEach { (agentId, output) ->
                    if (agentId != reviewer.id) {
                        val agent = agents.find { it.id == agentId } ?: return@forEach
                        val reviewPrompt = buildString {
                            appendLine("Context: $taskContext")
                            appendLine("Review ${agent.name}'s output: $output")
                        }
                        val review = aiService?.let {
                            session.thinkAndProduce(
                                agent = reviewer,
                                task = "Cross-review ${agent.name}'s work",
                                background = taskContext,
                                instructions = "Provide constructive peer review. Score 0-1.",
                                enableQualityCheck = false
                            ).finalAnswer
                        } ?: ""

                        val score = extractScore(review, 0.7f)
                        reviewed.add(ReviewResult(
                            AgentOutput(agent, output),
                            score, extractIssues(review),
                            extractSuggestions(review),
                            score >= 0.6f
                        ))
                    }
                }
                reviewed
            }
        }.awaitAll().flatten()
    }

    suspend fun reachConsensus(
        agents: List<Agent>,
        outputs: Map<String, String>,
        taskContext: String,
        minAgreementRatio: Float = 0.66f
    ): ConsensusResult = withContext(Dispatchers.Default) {
        val reviews = crossReviewAll(agents, outputs, taskContext)
        val avgScore = reviews.map { it.score }.average().toFloat()
        val passCount = reviews.count { it.passed }
        val agreementRatio = passCount.toFloat() / reviews.size.coerceAtLeast(1)

        val commonIssues = reviews.flatMap { it.issues }
            .groupBy { it }
            .filter { it.value.size >= 2 }
            .keys.toList()

        val topSuggestions = reviews.flatMap { it.suggestions }
            .groupBy { it }
            .maxByOrNull { it.value.size }
            ?.key?.let { listOf(it) } ?: emptyList()

        val consensus = if (agreementRatio >= minAgreementRatio) {
            val topOutput = outputs.maxByOrNull { (id, _) ->
                reviews.filter { it.agentOutput.agent.id == id }.sumOf { it.score.toDouble() }
            }
            ConsensusResult(
                consensusReached = true,
                selectedOutput = topOutput?.value ?: outputs.values.firstOrNull() ?: "",
                averageScore = avgScore,
                agreementRatio = agreementRatio,
                commonIssues = commonIssues,
                suggestions = topSuggestions
            )
        } else {
            val session = AgentThinkingSession(context, aiService)
            val mediatorPrompt = buildString {
                appendLine("Task: $taskContext")
                appendLine("\nAgent outputs and their peer reviews show disagreement.")
                appendLine("Average quality score: $avgScore")
                appendLine("Common issues identified: ${commonIssues.take(3).joinToString("; ")}")
                appendLine("\nSynthesize a consensus solution.")
            }
            val mediated = aiService?.let {
                session.thinkAndProduce(
                    agent = agents.first(),
                    task = "Resolve disagreements and synthesize consensus",
                    background = mediatorPrompt,
                    instructions = "Combine the best elements from all outputs.",
                    enableQualityCheck = true
                ).finalAnswer
            } ?: outputs.values.firstOrNull() ?: ""

            ConsensusResult(
                consensusReached = false,
                selectedOutput = mediated,
                averageScore = avgScore,
                agreementRatio = agreementRatio,
                commonIssues = commonIssues,
                suggestions = topSuggestions
            )
        }

        consensus
    }

    data class ConsensusResult(
        val consensusReached: Boolean,
        val selectedOutput: String,
        val averageScore: Float,
        val agreementRatio: Float,
        val commonIssues: List<String>,
        val suggestions: List<String>
    )

    private fun extractScore(text: String, default: Float): Float {
        val scores = Regex("""\b(\d\.?\d*)\b""").findAll(text)
            .map { it.groupValues[1].toFloatOrNull() }
            .filterNotNull()
            .toList()
        return scores.average().toFloat().takeIf { it.isFinite() } ?: default
    }

    private fun extractIssues(text: String): List<String> {
        return text.lines()
            .filter { it.contains("issue", ignoreCase = true) || it.contains("problem", ignoreCase = true) || it.contains("missing", ignoreCase = true) }
            .map { it.trim().removePrefix("- ").removePrefix("* ") }
            .take(5)
    }

    private fun extractSuggestions(text: String): List<String> {
        return text.lines()
            .filter { it.contains("suggest", ignoreCase = true) || it.contains("improve", ignoreCase = true) || it.contains("recommend", ignoreCase = true) }
            .map { it.trim().removePrefix("- ").removePrefix("* ") }
            .take(5)
    }
}
