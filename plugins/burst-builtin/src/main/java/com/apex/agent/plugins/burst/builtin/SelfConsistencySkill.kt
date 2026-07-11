package com.apex.agent.plugins.burst.builtin

import kotlinx.coroutines.Dispatchers

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class SelfConsistencySkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val numSamplingPaths = 5

    private var totalExecutions = 0
    private var successfulExecutions = 0
    private var totalExecutionTimeMs = 0L

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.self-consistency",
            skillName = "Self-Consistency",
            version = "1.0.0",
            description = "自洽推理策略，通过多路径推理选择最一致的答案，提高推理可靠性",
            author = "Apex Agent",
            tags = listOf("reasoning", "self-consistency", "multi-path", "reliability"),
            priority = 86,
            capabilities = listOf(
                "multi_path_sampling",
                "answer_clustering",
                "consistency_voting",
                "confidence_estimation"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            if (isPaused) {
                return@runBlocking BurstSkillResult(success = false, errorMessage = "Skill paused")
            }

            val llm = context.llmService
            val paths = (1..numSamplingPaths).map { generatePath(it, task, llm) }
            val finalAnswer = majorityVote(paths, task, llm)

            val totalTime = System.currentTimeMillis() - startTime

            totalExecutions++
            successfulExecutions++
            totalExecutionTimeMs += totalTime

            BurstSkillResult(
                success = true,
                output = finalAnswer,
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = paths.size,
                    tokensProcessed = estimateTokens(finalAnswer)
                )
            )

        } catch (e: Exception) {
            totalExecutions++
            BurstSkillResult(success = false, errorMessage = "Self-Consistency推理出错：${e.message}")
        }
    }

    private suspend fun generatePath(pathId: Int, task: BurstTask, llm: ILLMService?): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Solve the following problem using step-by-step reasoning (path $pathId):")
                appendLine(task.description)
                appendLine()
                appendLine("Provide your complete reasoning.")
            }
            return llm.generate(prompt, maxTokens = 512)
        }
        delay(50)
        return "Solution path $pathId for: ${task.id}"
    }

    private suspend fun majorityVote(paths: List<String>, task: BurstTask, llm: ILLMService?): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Review the following reasoning paths and determine the most consistent answer.")
                appendLine("Problem: ${task.description}")
                appendLine()
                paths.forEachIndexed { i, p -> appendLine("Path $i: $p") }
                appendLine()
                appendLine("Output the most consistent and correct answer.")
            }
            return llm.generate(prompt, maxTokens = 512)
        }
        return paths.groupBy { it }.maxByOrNull { it.value.size }?.key ?: paths.firstOrNull() ?: ""
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it in '\u4e00'..'\u9fff' }
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val otherChars = text.length - chineseChars - englishChars
        return (chineseChars * 1.5 + englishChars * 0.25 + otherChars * 0.5).toInt()
    }

    override fun pause() { isPaused = true }
    override fun resume() { isPaused = false }
    override fun destroy() { scope.cancel() }
    override fun mutate(rate: Float): IBurstSkill = this
    override fun crossover(other: IBurstSkill): IBurstSkill = this

    override fun evaluate(): Float {
        if (totalExecutions == 0) return 0.5f
        val successRate = successfulExecutions.toFloat() / totalExecutions
        val avgTime = if (totalExecutions > 0) totalExecutionTimeMs.toFloat() / totalExecutions else 0f
        val timeEfficiency = (20000f / (avgTime + 1)).coerceIn(0f, 1f)
        return successRate * 0.8f + timeEfficiency * 0.2f
    }
}
