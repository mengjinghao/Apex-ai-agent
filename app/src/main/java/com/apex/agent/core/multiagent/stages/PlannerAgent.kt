package com.apex.agent.core.multiagent.stages

import com.apex.agent.core.multiagent.PipelineContext
import com.apex.agent.core.multiagent.StageAgent
import com.apex.agent.core.multiagent.StageAgentResult
import com.apex.util.AppLogger/** * 规划阶段 Agent * 负责任务分解和计划制?*/class PlannerAgent : StageAgent {
import com.apex.util.AppLogger
companion
    object {
private const
    val TAG = "PlannerAgent"
}
    @Volatile    private
    var isCancelled = false    override
    fun execute(context: PipelineContext): StageAgentResult {
AppLogger.i(TAG, "开始执行规划阶?${
context.originalGoal
}
")        return try {
val previousOutput = context.getPreviousStageOutput()
    val plan = createPlan(context.originalGoal, previousOutput)            if (isCancelled) {
return StageAgentResult(                    output = "",                    summary = "规划已取?                    tokenCost = 0,                    success = false,                    error = "执行已取?               )
}
    val summary = "已制定包?{{
countSteps(plan)
}
 个步骤的执行计划，预估耗时 ${
estimateTime(plan)
}
 分钟?           AppLogger.i(TAG, "规划阶段完成")            StageAgentResult(                output = plan,                summary = summary,                tokenCost = estimateTokenCost(plan),                success = true            )
}
 catch (e: Exception) {
AppLogger.e(TAG, "规划阶段执行失败", e)            StageAgentResult(                output = "",                summary = "规划失败",                tokenCost = 0,                success = false,                error = e.message            )
}
}
    private
    fun createPlan(goal: String, researchContext: String): String {
val sb = StringBuilder()        sb.appendLine("# 执行计划")        sb.appendLine()        sb.appendLine("## 目标")        sb.appendLine(goal)        sb.appendLine()        sb.appendLine("## 步骤分解")        sb.appendLine()        // 根据研究阶段结果制定计划        sb.appendLine("### 步骤 1: 需求确认）        sb.appendLine("- 验证研究阶段收集的信息）        sb.appendLine("- 明确输入输出规范")        sb.appendLine("- 确认技术约束）        sb.appendLine()        sb.appendLine("### 步骤 2: 架构设计")        sb.appendLine("- 设计核心数据结构和接口）        sb.appendLine("- 确定模块划分")        sb.appendLine("- 规划集成方案?       sb.appendLine()        sb.appendLine("### 步骤 3: 代码实现")        sb.appendLine("- 实现核心逻辑")        sb.appendLine("- 编写辅助功能")        sb.appendLine("- 集成现有系统")        sb.appendLine()        sb.appendLine("### 步骤 4: 测试验证")        sb.appendLine("- 单元测试")        sb.appendLine("- 集成测试")        sb.appendLine("- 性能测试")        sb.appendLine()        sb.appendLine("## 依赖关系")        sb.appendLine("- 步骤 2 依赖步骤 1 完成")        sb.appendLine("- 步骤 3 依赖步骤 2 完成")        sb.appendLine("- 步骤 4 依赖步骤 3 完成")        sb.appendLine()        sb.appendLine("## 资源需求）        sb.appendLine("- 开发时间：2-3 小时")        sb.appendLine("- 测试时间? 小时")        sb.appendLine("- 所需技?Kotlin, Android, Jetpack Compose")        return sb.toString()
}
    private
    fun countSteps(plan: String): Int {
return plan.lines().count {
it.trim().startsWith("### 步骤")
}
}
    private
    fun estimateTime(plan: String): Int {
return countSteps(plan) * 30 // 每个步骤预估 30 分钟
}
    private
    fun estimateTokenCost(output: String): Int {
return (output.length / 100.0 * 40).toInt()
}
    override
    fun cancel() {
isCancelled = true        AppLogger.i(TAG, "取消规划阶段执行")
}
}
