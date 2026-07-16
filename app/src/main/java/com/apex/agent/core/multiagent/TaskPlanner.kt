package com.apex.agent.core.multiagent

import android.content.Context
import com.apex.util.AppLogger
import java.util.UUID

/**
 * 任务规划系统 - 参数GitHub Agentic Workflows
 * ，Agent 能够规划和分解复杂任�? */
data class TaskPlan(
    val planId: String = UUID.randomUUID().toString(),
    val originalGoal: String,
    val steps: List<PlanStep>,
    val estimatedTimeSeconds: Long = 0,
    val requiredCapabilities: List<String> = emptyList(),
    val dependencies: Map<String, List<String>> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis()
)

data class PlanStep(
    val stepNumber: Int,
    val description: String,
    val agentRole: AgentRole,
    val estimatedTime: Long = 0,
    val dependencies: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
    val status: StepStatus = StepStatus.PENDING
)

enum class StepStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
}

enum class AgentRole {
    RESEARCHER,
    DESIGNER,
    DEVELOPER,
    ANALYST,
    EXECUTOR,
    COORDINATOR
}

class TaskPlanner(private val context: Context) {

    companion object {
        private const val TAG = "TaskPlanner"
    }

    fun createPlan(goal: String): TaskPlan {
        val lowerGoal = goal.lowercase()
        val steps = when {
            lowerGoal.contains("code") || lowerGoal.contains("代码") || lowerGoal.contains("编程") -> createCodingSteps(goal)
            lowerGoal.contains("search") || lowerGoal.contains("搜索") || lowerGoal.contains("研究") -> createResearchSteps(goal)
            lowerGoal.contains("write") || lowerGoal.contains("写作") || lowerGoal.contains("撰写") -> createWritingSteps(goal)
            else -> createGenericSteps(goal)
        }

        val estimatedTime = steps.sumOf { it.estimatedTime }

        return TaskPlan(
            originalGoal = goal,
            steps = steps,
            estimatedTimeSeconds = estimatedTime,
            requiredCapabilities = listOf("general")
        )
    }

    private fun createCodingSteps(goal: String): List<PlanStep> {
        return listOf(
            PlanStep(1, "分析需求和理解任务", AgentRole.RESEARCHER, 120, emptyList(), listOf("analysis", "web_search")),
            PlanStep(2, "设计解决方案", AgentRole.DESIGNER, 180, listOf("1"), listOf("design", "planning")),
            PlanStep(3, "实现代码", AgentRole.DEVELOPER, 600, listOf("2"), listOf("coding", "debugging")),
            PlanStep(4, "测试和验�? AgentRole.EXECUTOR, 300, listOf("3"), listOf("testing", "review"))
        )
    }

    private fun createResearchSteps(goal: String): List<PlanStep> {
        return listOf(
            PlanStep(1, "定义搜索范围", AgentRole.RESEARCHER, 60, emptyList(), listOf("analysis")),
            PlanStep(2, "执行网络搜索", AgentRole.RESEARCHER, 300, listOf("1"), listOf("web_search")),
            PlanStep(3, "分析和总结结果", AgentRole.ANALYST, 240, listOf("2"), listOf("analysis", "summary")),
            PlanStep(4, "生成报告", AgentRole.EXECUTOR, 180, listOf("3"), listOf("reporting"))
        )
    }

    private fun createWritingSteps(goal: String): List<PlanStep> {
        return listOf(
            PlanStep(1, "收集相关信息", AgentRole.RESEARCHER, 120, emptyList(), listOf("research")),
            PlanStep(2, "构思大纲和结构", AgentRole.DESIGNER, 120, listOf("1"), listOf("outline")),
            PlanStep(3, "撰写内容", AgentRole.EXECUTOR, 600, listOf("2"), listOf("writing")),
            PlanStep(4, "编辑和润�? AgentRole.EXECUTOR, 300, listOf("3"), listOf("editing"))
        )
    }

    private fun createGenericSteps(goal: String): List<PlanStep> {
        return listOf(
            PlanStep(1, "理解和分析需�? AgentRole.COORDINATOR, 60, emptyList(), listOf("analysis")),
            PlanStep(2, "制定计划", AgentRole.COORDINATOR, 120, listOf("1"), listOf("planning")),
            PlanStep(3, "执行任务", AgentRole.EXECUTOR, 300, listOf("2"), listOf("execution"))
        )
    }

    fun updateStepStatus(plan: TaskPlan, stepNumber: Int, status: StepStatus): TaskPlan {
        val newSteps = plan.steps.map {
            if (it.stepNumber == stepNumber) it.copy(status = status) else it
        }
        return plan.copy(steps = newSteps)
    }

    fun getProgress(plan: TaskPlan): Float {
        val total = plan.steps.size
        val completed = plan.steps.count { it.status == StepStatus.COMPLETED }
        return if (total > 0) completed.toFloat() / total else 0f
    }

    /**
     * 判断是否应该使用管道执行
     * 基于任务复杂度：字数 > 200 或包含多步骤关键词时返回 true
     */
    fun shouldUsePipeline(goal: String): Boolean {
        if (goal.length > 200) return true

        val pipelineKeywords = listOf("复杂", "多步�? "完整", "全流�? "complex", "multi-step")
        return pipelineKeywords.any { goal.contains(it, ignoreCase = true) }
    }

    /**
     * 使用 StagedAgentPipeline 执行目标
     * 如果不需要管道则返回 null
     */
    suspend fun executeWithPipeline(goal: String): PipelineResult? {
        if (!shouldUsePipeline(goal)) {
            AppLogger.d(TAG, "任务不需要管道执�?${goal}")
            return null
        }

        AppLogger.i(TAG, "使用管道执行任务: ${goal}")
        val pipeline = StagedAgentPipeline()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            pipeline.execute(goal)
        }
    }
}
