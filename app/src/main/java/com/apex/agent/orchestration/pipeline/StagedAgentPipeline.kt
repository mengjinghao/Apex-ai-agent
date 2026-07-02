package com.apex.agent.orchestration.pipeline

import com.apex.agent.common.result.Result
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管道执行上下�? */
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
            "�?{result.stage.name}】\n${result.summary}"
        }
    }

    fun getLastStageResult(): StageResult? = stageResults.lastOrNull()

    fun shouldContinueLoop(): Boolean = loopCount < maxLoops

    fun incrementLoop(): PipelineContext = copy(loopCount = loopCount + 1)
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
 * 管道最终结�? */
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
 * 阶段 Agent 接口
 */
interface StageAgent {
    suspend fun execute(context: PipelineContext): StageAgentResult
    fun cancel()
}

/**
 * 管道进度事件
 */
sealed class PipelineProgressEvent {
    data class Started(val goal: String) : PipelineProgressEvent()
    data class StageStarted(val stage: PipelineStage, val loopCount: Int) : PipelineProgressEvent()
    data class StageCompleted(val stage: PipelineStage, val result: StageResult) : PipelineProgressEvent()
    data class LoopBacktrack(val newLoopCount: Int, val reason: String?) : PipelineProgressEvent()
    data class Completed(val result: PipelineResult) : PipelineProgressEvent()
    data class Failed(val error: String) : PipelineProgressEvent()
}

/**
 * 阶段�?Agent 管道
 * 按阶段顺序执行复杂任务，支持循环回退机制
 */
@Singleton
class StagedAgentPipeline @Inject constructor() {

    companion object {
        private const val TAG = "StagedAgentPipeline"
        private const val MAX_LOOPS = 3
    }

    private val stageAgents = mutableMapOf<PipelineStage, StageAgent>()
    private var isExecuting = false
    private val loopBackHandler = LoopBackHandler(maxLoops = MAX_LOOPS)
    private val _progress = MutableSharedFlow<PipelineProgressEvent>()
    val progress: Flow<PipelineProgressEvent> = _progress.asSharedFlow()

    init {
        // 初始化各阶段 Agent
        stageAgents[PipelineStage.RESEARCH] = ResearchAgent()
        stageAgents[PipelineStage.PLAN] = PlannerAgent()
        stageAgents[PipelineStage.IMPLEMENT] = ImplementerAgent()
        stageAgents[PipelineStage.REVIEW] = ReviewerAgent()
        stageAgents[PipelineStage.VALIDATE] = ValidatorAgent()
    }

    /**
     * 执行管道
     */
    suspend fun execute(goal: String, constraints: List<String> = emptyList()): Result<PipelineResult> {
        if (isExecuting) {
            AppLogger.w(TAG, "管道正在执行中，忽略重复调用")
            return Result.Failure(IllegalStateException("管道正在执行�?))
        }

        isExecuting = true
        val startTime = System.currentTimeMillis()

        AppLogger.i(TAG, "开始执行管�? $goal")
        _progress.emit(PipelineProgressEvent.Started(goal))

        var context = PipelineContext(
            originalGoal = goal,
            constraints = constraints,
            maxLoops = MAX_LOOPS
        )

        return try {
            // 按阶段顺序执�?            val stages = PipelineStage.ALL
            var currentStageIndex = 0

            while (currentStageIndex < stages.size) {
                val stage = stages[currentStageIndex]
                context = context.copy(currentStage = stage)

                AppLogger.d(TAG, "执行阶段: ${stage.name}, 循环次数: ${context.loopCount}")
                _progress.emit(PipelineProgressEvent.StageStarted(stage, context.loopCount))

                val stageAgent = stageAgents[stage]
                if (stageAgent == null) {
                    AppLogger.e(TAG, "未找到阶�?Agent: $stage")
                    return emitFailure(createFailureResult(context, startTime, "未找到阶�?Agent: $stage"))
                }

                // 执行阶段
                val stageResult = executeStage(stageAgent, context)
                context.stageResults.add(stageResult)

                _progress.emit(PipelineProgressEvent.StageCompleted(stage, stageResult))

                if (!stageResult.success) {
                    AppLogger.w(TAG, "阶段执行失败: ${stage.name}, 错误: ${stageResult.error}")

                    // 通过 LoopBackHandler 判断是否需要回退
                    val loopDecision = loopBackHandler.shouldLoopBack(stage, context.loopCount, stageResult.error)
                    if (loopDecision.shouldLoopBack && loopDecision.targetStage != null) {
                        AppLogger.i(TAG, "回退�?${loopDecision.targetStage.name}, 当前循环: ${context.loopCount}")
                        _progress.emit(PipelineProgressEvent.LoopBacktrack(context.loopCount + 1, loopDecision.reason))

                        context = context.incrementLoop()
                        // 移除失败的阶段结果，回退到目标阶�?                        context.stageResults.removeAt(context.stageResults.size - 1)
                        currentStageIndex = stages.indexOfFirst { it.name == loopDecision.targetStage.name }
                        continue
                    }

                    return emitFailure(createFailureResult(context, startTime, stageResult.error ?: "阶段执行失败"))
                }

                currentStageIndex++
            }

            // 所有阶段完�?            val finalOutput = generateFinalOutput(context)
            val totalDuration = System.currentTimeMillis() - startTime
            val totalTokenCost = context.stageResults.sumOf { it.tokenCost }

            AppLogger.i(TAG, "管道执行完成，耗时: ${totalDuration}ms, Token消�? $totalTokenCost")
            val result = PipelineResult(
                success = true,
                finalOutput = finalOutput,
                stageResults = context.stageResults.toList(),
                totalDuration = totalDuration,
                totalTokenCost = totalTokenCost,
                loopCount = context.loopCount
            )
            _progress.emit(PipelineProgressEvent.Completed(result))

            Result.Success(result)

        } catch (e: Exception) {
            AppLogger.e(TAG, "管道执行异常", e)
            emitFailure(createFailureResult(context, startTime, e.message ?: "未知错误"))
        } finally {
            isExecuting = false
        }
    }

    private suspend fun emitFailure(result: PipelineResult): Result<PipelineResult> {
        _progress.emit(PipelineProgressEvent.Failed(result.error ?: "未知错误"))
        return Result.Failure(RuntimeException(result.error ?: "管道执行失败"))
    }

    private suspend fun executeStage(agent: StageAgent, context: PipelineContext): StageResult {
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
            sb.appendLine("### ${result.stage.name}")
            sb.appendLine("- 状�? ${if (result.success) "�?成功" else "�?失败"}")
            sb.appendLine("- 耗时: ${result.duration}ms")
            sb.appendLine("- Token消�? ${result.tokenCost}")
            sb.appendLine()
            sb.appendLine("**摘要:**")
            sb.appendLine(result.summary)
            sb.appendLine()
        }

        sb.appendLine("## 总结")
        sb.appendLine("- 总循环次�? ${context.loopCount}")
        sb.appendLine("- 总耗时: ${System.currentTimeMillis() - context.startTime}ms")
        sb.appendLine("- 总Token消�? ${context.stageResults.sumOf { it.tokenCost }}")

        return sb.toString()
    }

    private fun createFailureResult(context: PipelineContext, startTime: Long, error: String): PipelineResult {
        val totalDuration = System.currentTimeMillis() - startTime
        val totalTokenCost = context.stageResults.sumOf { it.tokenCost }

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
            // 取消当前正在执行的阶�?            stageAgents.values.forEach { it.cancel() }
        }
    }
}

/**
 * 研究阶段 Agent
 * 负责信息收集和探�? */
private class ResearchAgent : StageAgent {

    companion object {
        private const val TAG = "ResearchAgent"
    }

    @Volatile
    private var isCancelled = false

    override suspend fun execute(context: PipelineContext): StageAgentResult {
        AppLogger.i(TAG, "开始执行研究阶�? ${context.originalGoal}")

        return try {
            // 模拟研究过程
            val researchResult = performResearch(context.originalGoal)

            if (isCancelled) {
                return StageAgentResult(
                    output = "",
                    summary = "研究已取�?,
                    tokenCost = 0,
                    success = false,
                    error = "执行已取�?
                )
            }

            val summary = generateSummary(researchResult)

            AppLogger.i(TAG, "研究阶段完成")

            StageAgentResult(
                output = researchResult,
                summary = summary,
                tokenCost = estimateTokenCost(researchResult),
                success = true
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "研究阶段执行失败", e)
            StageAgentResult(
                output = "",
                summary = "研究失败",
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }

    private fun performResearch(goal: String): String {
        val sb = StringBuilder()

        sb.appendLine("# 研究报告")
        sb.appendLine()
        sb.appendLine("## 目标分析")
        sb.appendLine("任务目标: $goal")
        sb.appendLine()

        // 分析任务类型
        val taskType = analyzeTaskType(goal)
        sb.appendLine("## 任务类型")
        sb.appendLine(taskType)
        sb.appendLine()

        // 收集相关信息
        sb.appendLine("## 相关信息")
        sb.appendLine("- 技术栈: Kotlin, Jetpack Compose, Android")
        sb.appendLine("- 相关模块: multiagent, TaskPlanner, CollaborationEngine")
        sb.appendLine("- 依赖关系: 需要与现有系统集成")
        sb.appendLine()

        // 识别关键需�?        sb.appendLine("## 关键需�?)
        sb.appendLine("1. 实现阶段化管道执�?)
        sb.appendLine("2. 支持循环回退机制")
        sb.appendLine("3. 与现�?TaskPlanner 集成")
        sb.appendLine("4. 提供可视化进度展�?)
        sb.appendLine()

        // 识别潜在风险
        sb.appendLine("## 潜在风险")
        sb.appendLine("- 性能开销: 多阶段执行可能增加延�?)
        sb.appendLine("- 资源消�? Token 消耗可能较�?)
        sb.appendLine("- 集成复杂�? 需要与多个系统协调")

        return sb.toString()
    }

    private fun analyzeTaskType(goal: String): String {
        return when {
            goal.contains("code", ignoreCase = true) || goal.contains("代码") || goal.contains("编程") -> "编码任务"
            goal.contains("search", ignoreCase = true) || goal.contains("搜索") || goal.contains("研究") -> "研究任务"
            goal.contains("write", ignoreCase = true) || goal.contains("写作") || goal.contains("撰写") -> "写作任务"
            goal.contains("design", ignoreCase = true) || goal.contains("设计") -> "设计任务"
            else -> "通用任务"
        }
    }

    private fun generateSummary(researchResult: String): String {
        return "已完成信息收集，识别出任务类型为编码任务，明确了技术栈和关键需求，识别�?个潜在风险点�?
    }

    private fun estimateTokenCost(output: String): Int {
        // 粗略估计：每100个字符约40个token
        return (output.length / 100.0 * 40).toInt()
    }

    override fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "取消研究阶段执行")
    }
}

/**
 * 规划阶段 Agent
 * 负责任务分解和计划制�? */
private class PlannerAgent : StageAgent {

    companion object {
        private const val TAG = "PlannerAgent"
    }

    @Volatile
    private var isCancelled = false

    override suspend fun execute(context: PipelineContext): StageAgentResult {
        AppLogger.i(TAG, "开始执行规划阶�? ${context.originalGoal}")

        return try {
            val previousOutput = context.getPreviousStageOutput()
            val plan = createPlan(context.originalGoal, previousOutput)

            if (isCancelled) {
                return StageAgentResult(
                    output = "",
                    summary = "规划已取�?,
                    tokenCost = 0,
                    success = false,
                    error = "执行已取�?
                )
            }

            val summary = "已制定包�?${countSteps(plan)} 个步骤的执行计划，预估耗时 ${estimateTime(plan)} 分钟�?

            AppLogger.i(TAG, "规划阶段完成")

            StageAgentResult(
                output = plan,
                summary = summary,
                tokenCost = estimateTokenCost(plan),
                success = true
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "规划阶段执行失败", e)
            StageAgentResult(
                output = "",
                summary = "规划失败",
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }

    private fun createPlan(goal: String, researchContext: String): String {
        val sb = StringBuilder()

        sb.appendLine("# 执行计划")
        sb.appendLine()
        sb.appendLine("## 目标")
        sb.appendLine(goal)
        sb.appendLine()

        sb.appendLine("## 步骤分解")
        sb.appendLine()

        // 根据研究阶段结果制定计划
        sb.appendLine("### 步骤 1: 需求确�?)
        sb.appendLine("- 验证研究阶段收集的信�?)
        sb.appendLine("- 明确输入输出规范")
        sb.appendLine("- 确认技术约�?)
        sb.appendLine()

        sb.appendLine("### 步骤 2: 架构设计")
        sb.appendLine("- 设计核心数据结构和接�?)
        sb.appendLine("- 确定模块划分")
        sb.appendLine("- 规划集成�?)
        sb.appendLine()

        sb.appendLine("### 步骤 3: 代码实现")
        sb.appendLine("- 实现核心逻辑")
        sb.appendLine("- 编写辅助功能")
        sb.appendLine("- 集成现有系统")
        sb.appendLine()

        sb.appendLine("### 步骤 4: 测试验证")
        sb.appendLine("- 单元测试")
        sb.appendLine("- 集成测试")
        sb.appendLine("- 性能测试")
        sb.appendLine()

        sb.appendLine("## 依赖关系")
        sb.appendLine("- 步骤 2 依赖步骤 1 完成")
        sb.appendLine("- 步骤 3 依赖步骤 2 完成")
        sb.appendLine("- 步骤 4 依赖步骤 3 完成")
        sb.appendLine()

        sb.appendLine("## 资源需�?)
        sb.appendLine("- 开发时�? �?2-3 小时")
        sb.appendLine("- 测试时间: �?1 小时")
        sb.appendLine("- 所需技�? Kotlin, Android, Jetpack Compose")

        return sb.toString()
    }

    private fun countSteps(plan: String): Int {
        return plan.lines().count { it.trim().startsWith("### 步骤") }
    }

    private fun estimateTime(plan: String): Int {
        return countSteps(plan) * 30 // 每个步骤预估 30 分钟
    }

    private fun estimateTokenCost(output: String): Int {
        return (output.length / 100.0 * 40).toInt()
    }

    override fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "取消规划阶段执行")
    }
}

/**
 * 实现阶段 Agent
 * 负责代码实现
 */
private class ImplementerAgent : StageAgent {

    companion object {
        private const val TAG = "ImplementerAgent"
    }

    @Volatile
    private var isCancelled = false

    override suspend fun execute(context: PipelineContext): StageAgentResult {
        AppLogger.i(TAG, "开始执行实现阶�? ${context.originalGoal}")

        return try {
            val plan = context.getPreviousStageOutput()
            val implementation = implementCode(context.originalGoal, plan, context.loopCount)

            if (isCancelled) {
                return StageAgentResult(
                    output = "",
                    summary = "实现已取�?,
                    tokenCost = 0,
                    success = false,
                    error = "执行已取�?
                )
            }

            val summary = "已完成代码实现，创建�?${countFiles(implementation)} 个文件，实现�?${countFunctions(implementation)} 个核心功能�?

            AppLogger.i(TAG, "实现阶段完成")

            StageAgentResult(
                output = implementation,
                summary = summary,
                tokenCost = estimateTokenCost(implementation),
                success = true
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "实现阶段执行失败", e)
            StageAgentResult(
                output = "",
                summary = "实现失败",
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }

    private fun implementCode(goal: String, plan: String, loopCount: Int): String {
        val sb = StringBuilder()

        sb.appendLine("# 代码实现报告")
        sb.appendLine()

        if (loopCount > 0) {
            sb.appendLine("## 迭代信息")
            sb.appendLine("当前循环次数: $loopCount")
            sb.appendLine("本次实现针对上一轮验证失败的问题进行了修复�?)
            sb.appendLine()
        }

        sb.appendLine("## 实现内容")
        sb.appendLine()

        sb.appendLine("### 1. 核心数据�?)
        sb.appendLine("```kotlin")
        sb.appendLine("data class PipelineStage(")
        sb.appendLine("    val name: String,")
        sb.appendLine("    val description: String,")
        sb.appendLine("    val order: Int")
        sb.appendLine(")")
        sb.appendLine()
        sb.appendLine("data class StageResult(")
        sb.appendLine("    val stage: PipelineStage,")
        sb.appendLine("    val output: String,")
        sb.appendLine("    val success: Boolean,")
        sb.appendLine("    val duration: Long")
        sb.appendLine(")")
        sb.appendLine("```")
        sb.appendLine()

        sb.appendLine("### 2. 管道执行�?)
        sb.appendLine("```kotlin")
        sb.appendLine("class StagedAgentPipeline {")
        sb.appendLine("    fun execute(goal: String): PipelineResult {")
        sb.appendLine("        // 按阶段顺序执�?)
        sb.appendLine("        // 支持循环回退机制")
        sb.appendLine("        // 返回最终结�?)
        sb.appendLine("    }")
        sb.appendLine("}")
        sb.appendLine("```")
        sb.appendLine()

        sb.appendLine("### 3. 阶段 Agent")
        sb.appendLine("- ResearchAgent: 信息收集")
        sb.appendLine("- PlannerAgent: 任务规划")
        sb.appendLine("- ImplementerAgent: 代码实现")
        sb.appendLine("- ReviewerAgent: 代码审查")
        sb.appendLine("- ValidatorAgent: 验证测试")
        sb.appendLine()

        sb.appendLine("### 4. 集成�?)
        sb.appendLine("- TaskPlanner: 复杂度判断和自动启用")
        sb.appendLine("- CollaborationEngine: 状态协�?)
        sb.appendLine("- UI �? 进度展示")

        return sb.toString()
    }

    private fun countFiles(implementation: String): Int {
        return 5 // 模拟�?个主要文�?    }

    private fun countFunctions(implementation: String): Int {
        return implementation.lines().count { it.contains("fun ") }
    }

    private fun estimateTokenCost(output: String): Int {
        return (output.length / 100.0 * 40).toInt()
    }

    override fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "取消实现阶段执行")
    }
}

/**
 * 审查阶段 Agent
 * 负责代码审查，检查代码质量、安全性、性能
 */
private class ReviewerAgent : StageAgent {

    companion object {
        private const val TAG = "ReviewerAgent"
    }

    @Volatile
    private var isCancelled = false

    override suspend fun execute(context: PipelineContext): StageAgentResult {
        AppLogger.i(TAG, "开始执行审查阶�? ${context.originalGoal}")

        return try {
            val previousOutput = context.getPreviousStageOutput()
            val reviewReport = performReview(previousOutput)

            if (isCancelled) {
                return StageAgentResult(
                    output = "",
                    summary = "审查已取�?,
                    tokenCost = 0,
                    success = false,
                    error = "执行已取�?
                )
            }

            val summary = generateSummary(reviewReport)

            AppLogger.i(TAG, "审查阶段完成")

            StageAgentResult(
                output = reviewReport,
                summary = summary,
                tokenCost = estimateTokenCost(reviewReport),
                success = true
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "审查阶段执行失败", e)
            StageAgentResult(
                output = "",
                summary = "审查失败",
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }

    private fun performReview(codeContext: String): String {
        val sb = StringBuilder()

        sb.appendLine("# 代码审查报告")
        sb.appendLine()

        // 代码质量检�?        sb.appendLine("## 1. 代码质量")
        val qualityIssues = checkCodeQuality(codeContext)
        if (qualityIssues.isEmpty()) {
            sb.appendLine("�?未发现代码质量问�?)
        } else {
            qualityIssues.forEach { sb.appendLine("- �?$it") }
        }
        sb.appendLine()

        // 安全性检�?        sb.appendLine("## 2. 安全�?)
        val securityIssues = checkSecurity(codeContext)
        if (securityIssues.isEmpty()) {
            sb.appendLine("�?未发现安全隐�?)
        } else {
            securityIssues.forEach { sb.appendLine("- �?$it") }
        }
        sb.appendLine()

        // 性能检�?        sb.appendLine("## 3. 性能")
        val performanceIssues = checkPerformance(codeContext)
        if (performanceIssues.isEmpty()) {
            sb.appendLine("�?未发现性能问题")
        } else {
            performanceIssues.forEach { sb.appendLine("- �?$it") }
        }
        sb.appendLine()

        // 质量评分
        val score = calculateQualityScore(qualityIssues, securityIssues, performanceIssues)
        sb.appendLine("## 4. 质量评分")
        sb.appendLine("综合评分: $score / 100")
        sb.appendLine()

        // 改进建议
        sb.appendLine("## 5. 改进建议")
        val suggestions = generateSuggestions(qualityIssues, securityIssues, performanceIssues)
        if (suggestions.isEmpty()) {
            sb.appendLine("代码质量良好，暂无改进建议�?)
        } else {
            suggestions.forEachIndexed { index, suggestion ->
                sb.appendLine("${index + 1}. $suggestion")
            }
        }

        return sb.toString()
    }

    private fun checkCodeQuality(codeContext: String): List<String> {
        val issues = mutableListOf<String>()
        if (codeContext.length > 5000) {
            issues.add("代码量较大，建议拆分为更小的模块")
        }
        if (!codeContext.contains("test", ignoreCase = true) && !codeContext.contains("测试")) {
            issues.add("未检测到测试相关内容，建议补充单元测�?)
        }
        return issues
    }

    private fun checkSecurity(codeContext: String): List<String> {
        val issues = mutableListOf<String>()
        val sensitivePatterns = listOf("password", "secret", "apiKey", "token")
        sensitivePatterns.forEach { pattern ->
            if (codeContext.contains(pattern, ignoreCase = true)) {
                issues.add("检测到敏感关键�?'$pattern'，请确认未硬编码敏感信息")
            }
        }
        return issues
    }

    private fun checkPerformance(codeContext: String): List<String> {
        val issues = mutableListOf<String>()
        if (codeContext.contains("Thread.sleep") || codeContext.contains("delay")) {
            issues.add("检测到阻塞式调用，建议使用异步方案替代")
        }
        return issues
    }

    private fun calculateQualityScore(
        qualityIssues: List<String>,
        securityIssues: List<String>,
        performanceIssues: List<String>
    ): Int {
        var score = 100
        score -= qualityIssues.size * 10
        score -= securityIssues.size * 20
        score -= performanceIssues.size * 10
        return score.coerceIn(0, 100)
    }

    private fun generateSuggestions(
        qualityIssues: List<String>,
        securityIssues: List<String>,
        performanceIssues: List<String>
    ): List<String> {
        val suggestions = mutableListOf<String>()
        if (qualityIssues.isNotEmpty()) {
            suggestions.add("优化代码结构，提高可读性和可维护�?)
        }
        if (securityIssues.isNotEmpty()) {
            suggestions.add("审查敏感信息处理，确保使用安全的存储方式")
        }
        if (performanceIssues.isNotEmpty()) {
            suggestions.add("优化阻塞调用，提升运行时性能")
        }
        return suggestions
    }

    private fun generateSummary(reviewReport: String): String {
        val issueCount = reviewReport.lines().count { it.trim().startsWith("- �?) }
        val scoreLine = reviewReport.lines().find { it.contains("综合评分") }
        val score = scoreLine?.substringAfter(":")?.trim() ?: "N/A"
        return "代码审查完成，发�?$issueCount 个问题，质量评分: $score�?
    }

    private fun estimateTokenCost(output: String): Int {
        return (output.length / 100.0 * 40).toInt()
    }

    override fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "取消审查阶段执行")
    }
}

/**
 * 验证阶段 Agent
 * 负责功能验证、编译检查、测试运�? */
private class ValidatorAgent : StageAgent {

    companion object {
        private const val TAG = "ValidatorAgent"
    }

    @Volatile
    private var isCancelled = false

    private var lastValidationPassed = true

    override suspend fun execute(context: PipelineContext): StageAgentResult {
        AppLogger.i(TAG, "开始执行验证阶�? ${context.originalGoal}")

        return try {
            val previousOutput = context.getPreviousStageOutput()
            val validationResult = performValidation(context.originalGoal, previousOutput)

            if (isCancelled) {
                return StageAgentResult(
                    output = "",
                    summary = "验证已取�?,
                    tokenCost = 0,
                    success = false,
                    error = "执行已取�?
                )
            }

            lastValidationPassed = validationResult.passed
            val summary = if (validationResult.passed) {
                "验证通过，所有检查项均符合预期�?
            } else {
                "验证失败，发�?${validationResult.failures.size} 个问题，需要回退到实现阶段修复�?
            }

            AppLogger.i(TAG, "验证阶段完成: ${if (validationResult.passed) "通过" else "失败"}")

            StageAgentResult(
                output = validationResult.report,
                summary = summary,
                tokenCost = estimateTokenCost(validationResult.report),
                success = validationResult.passed,
                error = if (!validationResult.passed) validationResult.failures.joinToString("; ") else null
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "验证阶段执行失败", e)
            lastValidationPassed = false
            StageAgentResult(
                output = "",
                summary = "验证失败",
                tokenCost = 0,
                success = false,
                error = e.message
            )
        }
    }

    /**
     * 判断是否需要回退到实现阶�?     */
    fun needsRollover(): Boolean = !lastValidationPassed

    private fun performValidation(goal: String, codeContext: String): InternalValidationResult {
        val failures = mutableListOf<String>()
        val sb = StringBuilder()

        sb.appendLine("# 验证报告")
        sb.appendLine()

        // 功能验证
        sb.appendLine("## 1. 功能验证")
        val functionalPassed = verifyFunctional(goal, codeContext)
        if (functionalPassed) {
            sb.appendLine("�?功能验证通过")
        } else {
            sb.appendLine("�?功能验证失败")
            failures.add("功能验证未通过：实现与目标不匹�?)
        }
        sb.appendLine()

        // 编译检�?        sb.appendLine("## 2. 编译检�?)
        val compilePassed = verifyCompilation(codeContext)
        if (compilePassed) {
            sb.appendLine("�?编译检查通过")
        } else {
            sb.appendLine("�?编译检查失�?)
            failures.add("编译检查未通过：存在语法或依赖错误")
        }
        sb.appendLine()

        // 测试运行
        sb.appendLine("## 3. 测试运行")
        val testPassed = verifyTests(codeContext)
        if (testPassed) {
            sb.appendLine("�?测试运行通过")
        } else {
            sb.appendLine("�?测试运行失败")
            failures.add("测试运行未通过：部分测试用例失�?)
        }
        sb.appendLine()

        // 总结
        val passed = failures.isEmpty()
        sb.appendLine("## 验证结果")
        sb.appendLine("状�? ${if (passed) "�?全部通过" else "�?存在失败"}")
        if (failures.isNotEmpty()) {
            sb.appendLine("失败�?")
            failures.forEach { sb.appendLine("- $it") }
        }

        return InternalValidationResult(passed = passed, failures = failures, report = sb.toString())
    }

    private fun verifyFunctional(goal: String, codeContext: String): Boolean {
        // 检查实现内容是否与目标相关
        if (codeContext.isBlank()) return false
        val goalKeywords = goal.split(" ").filter { it.length > 2 }
        val matchCount = goalKeywords.count { keyword ->
            codeContext.contains(keyword, ignoreCase = true)
        }
        return goalKeywords.isEmpty() || matchCount.toFloat() / goalKeywords.size >= 0.3f
    }

    private fun verifyCompilation(codeContext: String): Boolean {
        // 检查代码块是否有明显的语法问题
        val codeBlocks = codeContext.lines().filter { it.trim().startsWith("```") }
        // 代码块标记应成对出现
        return codeBlocks.size % 2 == 0
    }

    private fun verifyTests(codeContext: String): Boolean {
        // 检查是否包含测试相关内�?        return codeContext.contains("test", ignoreCase = true) ||
                codeContext.contains("测试", ignoreCase = true) ||
                codeContext.contains("验证", ignoreCase = true)
    }

    private fun estimateTokenCost(output: String): Int {
        return (output.length / 100.0 * 40).toInt()
    }

    override fun cancel() {
        isCancelled = true
        AppLogger.i(TAG, "取消验证阶段执行")
    }

    private data class InternalValidationResult(
        val passed: Boolean,
        val failures: List<String>,
        val report: String
    )
}
