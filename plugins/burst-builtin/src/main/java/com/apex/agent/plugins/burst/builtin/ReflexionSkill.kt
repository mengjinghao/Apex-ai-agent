package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.*
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*

class ReflexionSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest

    private lateinit var context: BurstSkillContext
    // 修复 F5：isPaused 跨线程读写需 @Volatile
    @Volatile private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val maxIterations = 3

    // 修复 F2：reflectionHistory 原为声明但从未 add/read，是死代码。现在真正使用。
    private val reflectionHistory = java.util.concurrent.ConcurrentLinkedQueue<ReflectionRecord>()

    // 修复 X3：统计字段改为原子
    private val totalExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val successfulExecutions = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalExecutionTimeMs = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        manifest = BurstSkillManifest(
            skillId = "reasoning.reflexion",
            skillName = "Reflexion",
            version = "1.0.0",
            description = "自省推理策略，通过自我反思和纠错来改进推理质量",
            author = "Apex Agent",
            tags = listOf("reasoning", "reflexion", "self-correction", "self-improvement"),
            priority = 87,
            capabilities = listOf(
                "self_assessment",
                "error_identification",
                "corrective_reasoning",
                "iterative_refinement"
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
                return@runBlocking BurstSkillResult(
                    success = false,
                    errorMessage = "Skill paused"
                )
            }

            val llm = context.llmService
            reflectionHistory.clear()
            var iterations = 0

            // 修复 F1（critical）：旧版每次迭代都重新调用 initialAttempt(task, llm)，
            // 把上一轮 refineAnswer 的结果完全丢弃，Reflexion 算法核心“在精炼结果上继续反思”失效。
            // 新版：首轮 initialAttempt，后续轮在 currentResult 上 selfReflect + refineAnswer。
            var currentResult: String = initialAttempt(task, llm)
            var llmAvailable = llm != null && llm.isAvailable()

            while (iterations < maxIterations) {
                if (isPaused) break

                val reflections = selfReflect(currentResult, task, llm)

                // 修复 F9：LLM 不可用时原版返回长度 2 的固定 list，使循环必定跑满。
                // 新版 LLM 不可用时返回 emptyList，直接跳出。
                if (reflections.isEmpty()) {
                    break
                }

                val improved = refineAnswer(currentResult, reflections, task, llm)
                // 修复 F10：LLM 不可用时原版返回 attempt + "\n\n[Refined after reflection]"
                // 是字符串拼接伪装修饰。新版 refineAnswer 在 LLM 不可用时直接返回 attempt 不变。
                // 这里只有 improved != currentResult 才算真正精炼过，避免无意义迭代。
                val actuallyRefined = improved != currentResult
                currentResult = improved

                // 记录反思历史（F2 修复后的真实使用）
                reflectionHistory.add(ReflectionRecord(
                    result = currentResult,
                    accuracyScore = 0.0,  // 未做评分，留到未来接入 LLM 自评
                    issues = reflections,
                    suggestions = emptyList(),
                    confidence = if (actuallyRefined) 0.6 else 0.3
                ))

                iterations++

                // 如果 refine 未产生变化且 LLM 不可用，跳出避免空转
                if (!actuallyRefined && !llmAvailable) break
            }

            val totalTime = System.currentTimeMillis() - startTime

            totalExecutions.incrementAndGet()
            // 修复 F7：旧版无条件 successfulExecutions++。新版只在“有精炼过或 LLM 可用”时计成功
            val success = iterations > 0 && llmAvailable || llmAvailable
            if (success) successfulExecutions.incrementAndGet()
            totalExecutionTimeMs.addAndGet(totalTime)

            BurstSkillResult(
                success = success,
                output = buildString {
                    appendLine("Reflexion 推理完成：")
                    appendLine("- 迭代次数：$iterations")
                    appendLine("- LLM 可用：$llmAvailable")
                    appendLine("- 最终结果：$currentResult")
                },
                metrics = SkillMetrics(
                    executionTimeMs = totalTime,
                    stepsCompleted = iterations + 1,
                    tokensProcessed = estimateTokens(currentResult)
                )
            )

        } catch (e: Exception) {
            // 修复 X2：吞 CancellationException 会破坏结构化并发
            if (e is kotlinx.coroutines.CancellationException) throw e
            totalExecutions.incrementAndGet()
            BurstSkillResult(
                success = false,
                errorMessage = "Reflexion推理出错：${e.message}"
            )
        }
    }

    private suspend fun initialAttempt(task: BurstTask, llm: ILLMService?): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Solve the following problem. Provide your best initial answer:")
                appendLine(task.description)
            }
            return llm.generate(prompt, maxTokens = 512)
        }
        return "Initial attempt for: ${task.description.take(50)}"
    }

    private suspend fun selfReflect(attempt: String, task: BurstTask, llm: ILLMService?): List<String> {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Review your answer critically. Identify errors, gaps, and areas for improvement.")
                appendLine("Problem: ${task.description}")
                appendLine("Your answer: $attempt")
                appendLine("If you find issues, list them one per line.")
                appendLine("If no issues, output exactly: NO_ISSUES")
            }
            val response = llm.generate(prompt, maxTokens = 256)
            // 修复 F11：检测 NO_ISSUES 作为终止信号
            if (response.contains("NO_ISSUES", ignoreCase = true)) {
                return emptyList()
            }
            // 修复 F8：原版 filter { it.length > 10 } 会过滤掉“Wrong.”等短行
            return response.split("\n").filter { it.isNotBlank() }
        }
        // 修复 F9：LLM 不可用时返回 emptyList，让循环跳出，避免无意义迭代
        return emptyList()
    }

    private suspend fun refineAnswer(attempt: String, reflections: List<String>, task: BurstTask, llm: ILLMService?): String {
        if (llm != null && llm.isAvailable()) {
            val prompt = buildString {
                appendLine("Based on your reflection, produce an improved answer.")
                appendLine("Problem: ${task.description}")
                appendLine("Previous attempt: $attempt")
                appendLine("Issues identified:")
                reflections.forEach { appendLine("- $it") }
                appendLine("Provide your improved answer.")
            }
            return llm.generate(prompt, maxTokens = 512)
        }
        // 修复 F10：LLM 不可用时原版返回 attempt + "\n\n[Refined after reflection]"
        // 是字符串拼接伪装修饰，不产生任何真实精炼。新版直接返回 attempt，
        // 让调用方看到“未精炼”状态。调用方能根据 attempt == improved 判断是否真正精炼过。
        return attempt
    }

    private fun estimateTokens(text: String): Int {
        val chineseChars = text.count { it in '\u4e00'..'\u9fff' }
        val englishChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        val otherChars = text.length - chineseChars - englishChars

        return (chineseChars * 1.5 + englishChars * 0.25 + otherChars * 0.5).toInt()
    }

    override fun pause() {
        isPaused = true
    }

    override fun resume() {
        isPaused = false
    }

    override fun destroy() {
        scope.cancel()
    }

    override fun mutate(rate: Float): IBurstSkill {
        return this
    }

    override fun crossover(other: IBurstSkill): IBurstSkill {
        if (other is ReflexionSkill) {
            return this
        }
        return this
    }

    override fun evaluate(): Float {
        val total = totalExecutions.get()
        if (total == 0) return 0.5f

        val successRate = successfulExecutions.get().toFloat() / total
        val avgTime = totalExecutionTimeMs.get().toFloat() / total

        val timeEfficiency = (12000f / (avgTime + 1)).coerceIn(0f, 1f)

        return successRate * 0.8f + timeEfficiency * 0.2f
    }

    data class ReflectionRecord(
        val result: String,
        val accuracyScore: Double,
        val issues: List<String>,
        val suggestions: List<String>,
        val confidence: Double
    )
}
