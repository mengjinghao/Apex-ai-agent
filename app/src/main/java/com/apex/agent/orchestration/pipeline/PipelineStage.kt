package com.apex.agent.orchestration.pipeline

/**
 * 管道阶段数据类，描述阶段?Agent 管道中的一个执行阶段? */
data class PipelineStage(
    val name: String,
    val description: String,
    val order: Int
) {
    companion object {
        val RESEARCH = PipelineStage("研究阶段", "信息收集和探?, 0)
        val PLAN = PipelineStage("规划阶段", "任务分解和计划制?, 1)
        val IMPLEMENT = PipelineStage("实现阶段", "代码实现", 2)
        val REVIEW = PipelineStage("审查阶段", "代码审查和质量检?, 3)
        val VALIDATE = PipelineStage("验证阶段", "验证和测?, 4)

        val ALL = listOf(RESEARCH, PLAN, IMPLEMENT, REVIEW, VALIDATE)
    }
}
