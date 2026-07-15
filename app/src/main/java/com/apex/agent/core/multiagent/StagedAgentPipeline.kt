package com.apex.agent.core.multiagent

import com.apex.util.AppLogger
import java.util.UUID
import com.apex.agent.orchestration.pipeline.ImplementerAgent
import com.apex.agent.orchestration.pipeline.PlannerAgent
import com.apex.agent.orchestration.pipeline.ResearchAgent
import com.apex.agent.orchestration.pipeline.ReviewerAgent
import com.apex.agent.orchestration.pipeline.ValidatorAgent

/**
 * 管道阶段枚举
 */
enum class PipelineStage(val displayName: String, val description: String) {
    RESEARCH("研究阶段", "信息收集和探�?,
    PLAN("规划阶段", "任务分解和计划制定）,
    IMPLEMENT("实现阶段", "代码实现"),
    REVIEW("审查阶段", "代码审查和质量检�?,
    VALIDATE("验证阶段", "验证和测�?
}

/**
 * 阶段执行结果
 */
data class StageResult(
    val stage: PipelineStage,
    val output: String,
    val summary: String,
    val duration: Long,
    val tokenCost: Int,
    val success: Boolean,
    val error: String? = null
)

/**
 * 管道执行上下�?*/
data class PipelineContext(
    val taskId: String = UUID.randomUUID().toString(),
    val originalGoal: String,
    val currentStage: PipelineStage = PipelineStage.RESEARCH,
    val stageResults: MutableList<StageResult> = mutableListOf(),
    val loopCount: Int = 0,
    val maxLoops: Int = 3,
    val constraints: List<String> = emptyList(),
    val startTime: Long = System.currentTimeMillis()
) {
    fun getPreviousStageOutput(): String {
        return stageResults.joinToString("\n\n") { result ->
            "，{result.stage.displayName}】\n${result.summary}"
        }
    }
        fun getLastStageResult(): StageResult? {
        return stageResults.lastOrNull()
    }
        fun shouldContinueLoop(): Boolean {
        return loopCount < maxLoops
    }
        fun incrementLoop(): PipelineContext {
        return copy(loopCount = loopCount + 1)
    }
}

/**
 * 管道最终结�?*/
data class PipelineResult(
    val success: Boolean,
    val finalOutput: String,
    val stageResults: List<StageResult>,
    val totalDuration: Long,
    val totalTokenCost: Int,
    val loopCount: Int,
    val error: String? = null
)

/**
 * 阶段，Agent 管道
 * 按阶段顺序执行复杂任务，支持循环回退机制
 */
class StagedAgentPipeline {

    companion object {
        private const val TAG = "StagedAgentPipeline"
        private const val MAX_LOOPS = 3
    }
        private val stageAgents = mutableMapOf<PipelineStage, StageAgent>()
        private var isExecuting = false
    private var progressListener: PipelineProgressListener? = null

    init {
        // 初始化各阶段 Agent
        stageAgents[PipelineStage.RESEARCH] = ResearchAgent()
        stageAgents[PipelineStage.PLAN] = PlannerAgent()
        stageAgents[PipelineStage.IMPLEMENT] = ImplementerAgent()
        stageAgents[PipelineStage.REVIEW] = ReviewerAgent()
        stageAgents[PipelineStage.VALIDATE] = ValidatorAgent()
    }
        fun setProgressListener(listener: PipelineProgressListener) {
        progressListener = listener
    }

    /**
     * 执行管道
     */
    fun execute(goal: String, constraints: List<String> = emptyList()): PipelineResult {
        if (isExecuting) {
            AppLogger.w(TAG, "管道正在执行中，忽略重复调用")
        return PipelineResult(
                success = false,
                finalOutput = "",
                stageResults = emptyList(),
                totalDuration = 0,
                totalTokenCost = 0,
                loopCount = 0,
                error = "管道正在执行�?            )
        }

        isExecuting = true
        val startTime = System.currentTimeMillis()

        AppLogger.i(TAG, "开始执行管�?${goal}")
        progressListener?.onPipelineStarted(goal)
        var context = PipelineContext(
            originalGoal = goal,
            constraints = constraints,
            maxLoops = MAX_LOOPS
        )

        try {
            // 按阶段顺序执�?
    val stages = PipelineStage.entries.toList()
        var currentStageIndex = 0

            while (currentStageIndex < stages.size) {
                val stage = stages[currentStageIndex]
                context = context.copy(currentStage = stage)

                AppLogger.d(TAG, "执行阶段: ${stage.displayName}, 循环次数: ${context.loopCount}")
                progressListener?.onStageStarted(stage, context.loopCount)
        val stageAgent = stageAgents[stage]
                if (stageAgent == null) {
                    AppLogger.e(TAG, "未找到阶，Agent: ${stage}")
        return createFailureResult(context, startTime, "未找到阶，Agent: ${stage}")
                }

                // 执行阶段
    val stageResult = executeStage(stageAgent, context)
                context.stageResults.add(stageResult)

                progressListener?.onStageCompleted(stage, stageResult)
        if (!stageResult.success) {
                    AppLogger.w(TAG, "阶段执行失败: ${stage.displayName}, 错误: ${stageResult.error}")

                    // 验证阶段失败时回退到实现阶�?
    if (stage == PipelineStage.VALIDATE && context.shouldContinueLoop()) {
                        AppLogger.i(TAG, "验证失败，回退到实现阶段，当前循环: ${context.loopCount}")
                        progressListener?.onLoopBacktrack(context.loopCount + 1)

                        context = context.incrementLoop()
                        // 移除失败的验证结果，回退到实现阶�?                       context.stageResults.removeAt(context.stageResults.size - 1)
                        currentStageIndex = stages.indexOf(PipelineStage.IMPLEMENT)
                        continue
                    }
        return createFailureResult(context, startTime, stageResult.error ?: "阶段执行失败")
                }

                currentStageIndex++
            }

            // 所有阶段完�?
    val finalOutput = generateFinalOutput(context)
        val totalDuration = System.currentTimeMillis() - startTime
            val totalTokenCost = context.stageResults.sumOf { it.tokenCost }

            AppLogger.i(TAG, "管道执行完成，耗时: ${totalDuration}ms, Token消， ${totalTokenCost}")
            progressListener?.onPipelineCompleted(finalOutput)
        return PipelineResult(
                success = true,
                finalOutput = finalOutput,
                stageResults = context.stageResults.toList(),
                totalDuration = totalDuration,
                totalTokenCost = totalTokenCost,
                loopCount = context.loopCount
            )

        } catch (e: Exception) {
            AppLogger.e(TAG, "管道执行异常", e)
        return createFailureResult(context, startTime, e.message ?: "未知错误")
        } finally {
            isExecuting = false
        }
    }
        private fun executeStage(agent: StageAgent, context: PipelineContext): StageResult {
        val startTime = System.currentTimeMillis()
        return try {
            val result = agent.execute(context)
        val duration = System.currentTimeMillis() - startTime

            StageResult(
                stage = context.currentStage,
                output = result.output,
                summary = result.summary,
                duration = duration,
                tokenCost = result.tokenCost,
                success = result.success,
                error = result.error
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            AppLogger.e(TAG, "阶段执行异常: ${context.currentStage}", e)

            StageResult(
                stage = context.currentStage,
                output = "",
                summary = "执行失败",
                duration = duration,
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }
        private fun generateFinalOutput(context: PipelineContext): String {
        val sb = StringBuilder()
        sb.appendLine("# 任务执行报告")
        sb.appendLine()
        sb.appendLine("## 原始目标")
        sb.appendLine(context.originalGoal)
        sb.appendLine()
        sb.appendLine("## 执行过程")
        sb.appendLine()

        context.stageResults.forEach { result ->
            sb.appendLine("### ${result.stage.displayName}")
            sb.appendLine("- 状�?${if (result.success) "，成�? else "，失�?}")
            sb.appendLine("- 耗时: ${result.duration}ms")
            sb.appendLine("- Token消， ${result.tokenCost}")
            sb.appendLine()
            sb.appendLine("**摘要:**")
            sb.appendLine(result.summary)
            sb.appendLine()
        }

        sb.appendLine("## 总结")
        sb.appendLine("- 总循环次�?${context.loopCount}")
        sb.appendLine("- 总耗时: ${System.currentTimeMillis() - context.startTime}ms")
        sb.appendLine("- 总Token消， ${context.stageResults.sumOf { it.tokenCost }}")
        return sb.toString()
    }
        private fun createFailureResult(context: PipelineContext, startTime: Long, error: String): PipelineResult {
        val totalDuration = System.currentTimeMillis() - startTime
        val totalTokenCost = context.stageResults.sumOf { it.tokenCost }

        progressListener?.onPipelineFailed(error)
        return PipelineResult(
            success = false,
            finalOutput = "",
            stageResults = context.stageResults.toList(),
            totalDuration = totalDuration,
            totalTokenCost = totalTokenCost,
            loopCount = context.loopCount,
            error = error
        )
    }
        fun isExecuting(): Boolean = isExecuting

    fun cancel() {
        if (isExecuting) {
            AppLogger.i(TAG, "取消管道执行")
            isExecuting = false
            // 取消当前正在执行的阶�?           stageAgents.values.forEach { it.cancel() }
        }
    }
}

/**
 * 阶段 Agent 接口
 */
interface StageAgent {
    fun execute(context: PipelineContext): StageAgentResult
    fun cancel()
}

/**
 * 阶段 Agent 执行结果
 */
data class StageAgentResult(
    val output: String,
    val summary: String,
    val tokenCost: Int,
    val success: Boolean,
    val error: String? = null
)

/**
 * 管道进度监听�?*/
interface PipelineProgressListener {
    fun onPipelineStarted(goal: String) {}
        fun onStageStarted(stage: PipelineStage, loopCount: Int) {}
        fun onStageCompleted(stage: PipelineStage, result: StageResult) {}
        fun onLoopBacktrack(newLoopCount: Int) {}
        fun onPipelineCompleted(finalOutput: String) {}
        fun onPipelineFailed(error: String) {}
}
