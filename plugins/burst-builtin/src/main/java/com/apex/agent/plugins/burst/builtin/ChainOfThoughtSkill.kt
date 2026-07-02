package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class ChainOfThoughtSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var totalExecutions = 0
    private var successfulExecutions = 0
    private var totalExecutionTimeMs = 0L

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.chain-of-thought",
            skillName = "Chain of Thought",
            version = "1.0.0",
            description = "思维链推理策略，将复杂问题分解为逐步推理步骤，提高推理准确性",
            author = "Apex Agent",
            tags = listOf("reasoning", "chain-of-thought", "step-by-step"),
            priority = 85,
            capabilities = listOf(
                "task_decomposition",
                "step_execution",
                "logical_reasoning",
                "mathematical_reasoning"
            )
        )
    }

    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }

    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()

        try {
            if (isPaused) {
                return@runBlocking BurstSkillResult(success = false, errorMessage = "Skill paused")
            }

            val llm = context.llmService
            val steps = decomposeTask(task, llm)

            if (steps.isEmpty()) {
                return@runBlocking BurstSkillResult(success = false, errorMessage = "无法分解推理步骤")
            }

            val stepResults = mutableListOf<String>()
            var contextText = task.description

            steps.forEachIndexed { index, step ->
                if (isPaused) return@forEachIndexed

                val stepResult = executeStep(step, contextText, index + 1, steps.size, llm)
                stepResults.add(stepResult)
                contextText += "\n步骤${index + 1}结果：$stepResult"
            }

            val finalAnswer = synthesizeResults(task.description, stepResults, llm)

            val totalTime = System.currentTimeMillis() - startTime

            totalExecutions++
            successfulExecutions++
            totalExecutionTimeMs += totalTime

            val confidence = calculateConfidence(stepResults)

            BurstSkillResult(
                success = true,
                output = finalAnswer,
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = stepResults.size,
                    tokensProcessed = estimateTokens(finalAnswer)
                )
            )

        } catch (e: Exception) {
            totalExecutions++
            BurstSkillResult(success = false, errorMessage = "推理过程出错：${e.message}")
        }
    }

    private suspend fun decomposeTask(task: BurstTask, llm: ILLMService?): List<String> {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("将一个复杂问题分解为最多5个逐步推理步骤。")
                appendLine("每个步骤应当简洁明了，逐步推进推理。")
                appendLine()
                appendLine("问题：${task.description}")
                appendLine()
                appendLine("请以列表形式输出：")
                appendLine("1. 第一步")
                appendLine("2. 第二步")
                appendLine("...")
            }
            val response = llm.generate(prompt, maxTokens = 512)
            val steps = response.split("\n")
                .map { it.trim() }
                .filter { it.matches(Regex("""\d+\..*""")) }
                .map { it.replace(Regex("""^\d+\.\s*"""), "") }
                .filter { it.isNotBlank() }
            if (steps.isNotEmpty()) return steps
        }

        val sentences = task.description.split(Regex("[。；\n]"))
        return sentences
            .map { it.trim() }
            .filter { it.length > 10 }
            .take(5)
    }

    private suspend fun executeStep(
        step: String,
        context: String,
        currentStep: Int,
        totalSteps: Int,
        llm: ILLMService?
    ): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("你是Chain-of-Thought推理引擎，当前是步骤$currentStep/$totalSteps。")
                appendLine()
                appendLine("上下文：$context")
                appendLine()
                appendLine("当前步骤任务：$step")
                appendLine()
                appendLine("请逐步完成本步骤的推理，输出详尽的分析结果。")
            }
            return llm.generate(prompt, maxTokens = 512)
        }
        return "步骤$currentStep/$totalSteps 执行完成: $step"
    }

    private suspend fun synthesizeResults(originalGoal: String, stepResults: List<String>, llm: ILLMService?): String {
        val stepsSummary = stepResults.mapIndexed { index, result ->
            "步骤${index + 1}：$result"
        }.joinToString("\n\n")

        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("基于以下推理步骤，综合得出最终答案。")
                appendLine()
                appendLine("原始问题：$originalGoal")
                appendLine()
                appendLine("推理步骤：")
                appendLine(stepsSummary)
                appendLine()
                appendLine("请给出一个综合性的最终答案。")
            }
            return llm.generate(prompt, maxTokens = 512)
        }

        return buildString {
            appendLine("原始任务：$originalGoal")
            appendLine()
            appendLine("已完成的推理步骤：")
            appendLine(stepsSummary)
            appendLine()
            appendLine("最终答案：基于以上所有推理步骤的综合结论。")
        }
    }

    private fun calculateConfidence(stepResults: List<String>): Double {
        if (stepResults.isEmpty()) return 0.0
        val avgLength = stepResults.map { it.length }.average()
        val lengthScore = when {
            avgLength < 50 -> 0.5
            avgLength > 500 -> 0.7
            else -> 0.9
        }
        val stepCountScore = when (stepResults.size) {
            in 1..2 -> 0.6
            in 3..5 -> 0.9
            in 6..10 -> 0.8
            else -> 0.7
        }
        return (lengthScore + stepCountScore) / 2
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
        val timeEfficiency = (10000f / (avgTime + 1)).coerceIn(0f, 1f)
        return successRate * 0.7f + timeEfficiency * 0.3f
    }
}
