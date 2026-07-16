package com.apex.agent.orchestration.pipeline

/**
 * 阶段失败后的回退决策�? */
data class LoopBackDecision(
    val shouldLoopBack: Boolean,
    val targetStage: PipelineStage?,
    val reason: String?
)

/**
 * 处理阶段化管道执行失败时的循环回退逻辑�? */
class LoopBackHandler(
    private val maxLoops: Int = 3
) {

    /**
     * 判断当前阶段失败后是否需要回退到之前的阶段重新执行�?     */
    fun shouldLoopBack(
        failedStage: PipelineStage,
        loopCount: Int,
        reason: String? = null
    ): LoopBackDecision {
        if (loopCount >= maxLoops) {
            return LoopBackDecision(
                false,
                null,
                "达到最大循环次�?${maxLoops}"
            )
        }

        return when (failedStage.name) {
            PipelineStage.VALIDATE.name -> LoopBackDecision(
                true,
                PipelineStage.IMPLEMENT,
                reason ?: "验证阶段失败，回退到实现阶段修�?
            )
            else -> LoopBackDecision(
                false,
                null,
                reason ?: "阶段 ${failedStage.name} 失败且不支持回退"
            )
        }
    }
}
