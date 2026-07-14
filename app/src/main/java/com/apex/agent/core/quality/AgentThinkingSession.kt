package com.apex.agent.core.quality

import android.content.Context
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.core.evaluation.PassKReport
import com.apex.agent.core.evaluation.QualityGate
import com.apex.agent.core.evaluation.ValidationResult
import com.apex.agent.core.multiagent.Agent
import com.apex.agent.core.thinking.InferenceNode
import com.apex.agent.core.thinking.ObservationNode
import com.apex.agent.core.thinking.DecisionNode
import com.apex.agent.core.thinking.ActionNode
import com.apex.agent.core.thinking.SummaryNode
import com.apex.agent.core.thinking.ThinkingChain
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class AgentThinkingSession(
    private val context: Context,
    private val aiService: AIService? = null
) {
    companion object {
        private const val TAG = "AgentThinkingSession"
    }

    data class ThinkingOutput(
        val finalAnswer: String,
        val thinkingChain: ThinkingChain,
        val confidence: Float,
        val validationReport: PassKReport? = null,
        val passedQualityGate: Boolean = true
    )

    suspend fun thinkAndProduce(
        agent: Agent,
        task: String,
        background: String = "",
        instructions: String = "",
        enableQualityCheck: Boolean = true,
        enableCoT: Boolean = true
    ): ThinkingOutput {
        val chain = ThinkingChain()
        var finalAnswer: String
        var confidence = 0.0f

        if (enableCoT && aiService != null) {
            val cot = chainOfThought(agent, task, background, instructions, chain)
            finalAnswer = cot.answer
            confidence = cot.confidence
        } else {
            finalAnswer = directResponse(agent, task, background, instructions)
            confidence = 0.7f
        }

        chain.markComplete()

        var validationReport: PassKReport? = null
        var passedQuality = true

        if (enableQualityCheck && aiService != null) {
            validationReport = validateOutput(finalAnswer, task)
            val gate = QualityGate.evaluate(validationReport)
            passedQuality = gate.passed

            if (!gate.passed && gate.suggestions.isNotEmpty()) {
                val improved = reflectAndImprove(agent, task, finalAnswer, gate.suggestions)
                if (improved != null) {
                    finalAnswer = improved
                    validationReport = validateOutput(finalAnswer, task)
                    passedQuality = QualityGate.evaluate(validationReport).passed
                }
            }
        }

        return ThinkingOutput(
            finalAnswer = finalAnswer,
            thinkingChain = chain,
            confidence = confidence.coerceIn(0f, 1f),
            validationReport = validationReport,
            passedQualityGate = passedQuality
        )
    }

    suspend fun validateOutput(output: String, task: String): PassKReport = coroutineScope {
        val results = (1..3).map { iteration ->
            async(Dispatchers.Default) {
                val score = callAI(
                    "Rate this response for the task. Task: $task Response: $output Return only a number 0-1.",
                    "You are a strict quality evaluator. Return ONLY a decimal number between 0.0 and 1.0."
                ).trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

                ValidationResult(
                    pass = score >= 0.6f,
                    score = score,
                    details = "Iteration $iteration: score=$score",
                    duration = 0L
                )
            }
        }.awaitAll()

        val passAtK = results.count { it.pass }.toFloat() / 3f
        val avgScore = results.map { it.score }.average().toFloat()
        PassKReport(3, passAtK, passAtK * passAtK, results, avgScore)
    }

    suspend fun reflectAndImprove(
        agent: Agent,
        task: String,
        original: String,
        suggestions: List<String>
    ): String? {
        if (suggestions.isEmpty()) return null
        val prompt = buildString {
            appendLine("Task: $task")
            appendLine("Your previous response needed improvement.")
            appendLine("\nIssues to fix:")
            suggestions.forEach { appendLine("- $it") }
            appendLine("\nPlease provide an improved, thorough response.")
        }
        return callAI(prompt, "You are ${agent.name}. Improve your response quality.").ifBlank { null }
    }

    private suspend fun chainOfThought(
        agent: Agent,
        task: String,
        background: String,
        instructions: String,
        chain: ThinkingChain
    ): CoResult = withContext(Dispatchers.Default) {
        val obs = callAI(
            "Observe the task carefully:\nTask: $task\nRole: ${agent.role}\n${if (background.isNotBlank()) "Context: $background" else ""}",
            "You are ${agent.name}. What do you observe about this task?"
        ).ifBlank { "Task requires ${agent.role} analysis." }
        chain.addNode(ObservationNode(content = obs))

        val inf = callAI(
            "Analyze: $obs\n\nWhat can you infer? Consider requirements, constraints, and approach.",
            "You are ${agent.name}. Reason step by step."
        ).ifBlank { "Systematic analysis needed for this task." }
        chain.addNode(InferenceNode(content = inf, confidence = 0.8f))

        val dec = callAI(
            "Based on your analysis: $inf\n\nDecide on the best approach and justify it.",
            "You are ${agent.name}. Make a reasoned decision."
        ).ifBlank { "Proceed with standard approach." }
        chain.addNode(DecisionNode(content = dec))

        val actionPrompt = buildString {
            appendLine("Decision: $dec")
            appendLine("\nNow produce your comprehensive ${agent.role} output.")
            if (instructions.isNotBlank()) appendLine("Instructions: $instructions")
        }
        val act = callAI(
            actionPrompt,
            "You are ${agent.name}. Produce a thorough, high-quality response."
        ).ifBlank { directResponse(agent, task, background, instructions) }
        chain.addNode(ActionNode(content = act))

        val sum = callAI(
            "Summarize your key conclusions from: $act",
            "You are ${agent.name}. Summarize concisely."
        ).ifBlank { "Completed ${agent.role} analysis for task." }
        chain.addNode(SummaryNode(content = sum))

        val confidence = chain.nodes.size.toFloat() / 5f
        CoResult(act, confidence.coerceIn(0.3f, 0.95f))
    }

    private suspend fun directResponse(agent: Agent, task: String, background: String, instructions: String): String {
        return callAI(
            buildString {
                appendLine("Task: $task")
                if (background.isNotBlank()) appendLine("Context: $background")
                if (instructions.isNotBlank()) appendLine("Instructions: $instructions")
            },
            "You are ${agent.name}, a ${agent.role}. Provide a comprehensive response."
        )
    }

    private suspend fun callAI(prompt: String, systemPrompt: String): String {
        if (aiService == null) return ""
        return try {
            val result = StringBuilder()
            aiService.sendMessage(
                context = context,
                chatHistory = listOf(
                    com.apex.core.chat.hooks.PromptTurn(
                        kind = com.apex.core.chat.hooks.PromptTurnKind.SYSTEM, content = systemPrompt
                    ),
                    com.apex.core.chat.hooks.PromptTurn(
                        kind = com.apex.core.chat.hooks.PromptTurnKind.USER, content = prompt
                    )
                ),
                stream = false
            ).collect { chunk -> result.append(chunk) }
            result.toString().trim()
        } catch (e: Exception) {
            AppLogger.w(TAG, "AI call failed: ${e.message}")
            ""
        }
    }

    private data class CoResult(val answer: String, val confidence: Float)
}
