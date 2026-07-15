package com.apex.api.chat.llmprovider

import com.apex.agent.core.multiagent.ModelConfig
import com.apex.agent.core.multiagent.ModelRequest
import com.apex.util.AppLogger
import kotlin.math.abs

private const val TOKEN_THRESHOLD_LIGHTWEIGHT = 2048
private const val TOKEN_THRESHOLD_STANDARD = 4096
private const val TOKEN_THRESHOLD_CAPABLE = 8192
private const val TOKEN_THRESHOLD_POWERFUL = 16384
private const val COST_LIGHTWEIGHT = 0.001f
private const val COST_STANDARD = 0.003f
private const val COST_CAPABLE = 0.008f
private const val COST_POWERFUL = 0.02f
private const val COST_DEFAULT = 0.005f
private const val TIER_MATCH_SCORE = 10f
private const val TIER_MISMATCH_PENALTY_PER_LEVEL = 3f
private const val CAPACITY_EXCEED_PENALTY = 5f
private const val COST_EFFICIENCY_FACTOR = 0.5f

/** 任务复杂度枚举，用于智能模型路由 */
enum class TaskComplexity {
    SIMPLE,       // 简单问答、闲聊
    SINGLE_FILE,  // 单文件操作、简单代码生成
    MULTI_FILE,   // 多文件操作、跨模块修改
    COMPLEX,      // 复杂架构设计、系统级任务
    SECURITY      // 安全审计、敏感操作
}

/** 模型层级定义 - 描述一个可用模型的成本/速度特征 */
data class ModelTier(
    val tier: String,
    val provider: String,
    val modelName: String,
    val costPerToken: Float,
    val speedRank: Int
)

/** 路由决策结果 - 记录路由器选择的模型及原因 */
data class RoutingDecision(
    val selectedTier: ModelTier,
    val reason: String,
    val estimatedCost: Float,
    val estimatedLatency: Long
)

/** 智能模型路由器 - 根据任务复杂度、可用模型和成本策略，自动选择最优模型 */
object SmartModelRouter {
    private const val TAG = "SmartModelRouter"

    // 复杂度映射推荐层级名称
    private val complexityToTier = mapOf(
        TaskComplexity.SIMPLE to "lightweight",
        TaskComplexity.SINGLE_FILE to "standard",
        TaskComplexity.MULTI_FILE to "capable",
        TaskComplexity.COMPLEX to "powerful",
        TaskComplexity.SECURITY to "powerful"
    )

    // 成本敏感模式下的降级映射
    private val costSensitiveTier = mapOf(
        TaskComplexity.SIMPLE to "lightweight",
        TaskComplexity.SINGLE_FILE to "lightweight",
        TaskComplexity.MULTI_FILE to "standard",
        TaskComplexity.COMPLEX to "standard",
        TaskComplexity.SECURITY to "capable"
    )

    /**
     * 根据请求和可用模型列表进行路由决策
     *
     * @param request 模型请求
     * @param availableModels 可用模型配置列表
     * @param costSensitive 是否启用成本敏感模式（优先选择低成本模型）
     * @return 路由决策结果；若无可用模型则返回 null
     */
    fun route(
        request: ModelRequest,
        availableModels: List<ModelConfig>,
        costSensitive: Boolean = false
    ): RoutingDecision? {
        if (availableModels.isEmpty()) {
            AppLogger.w(TAG, "无可用模型，跳过路由")
        return null
        }
        // 用户手动选择preferredProvider，跳过自动路由
    if (request.preferredProvider != null) {
            val manualConfig = availableModels.find {
                it.provider == request.preferredProvider
            }
        if (manualConfig != null) {
                AppLogger.i(TAG, "用户手动选择模型: ${manualConfig.provider}, 跳过自动路由")
        return RoutingDecision(
                    selectedTier = configToTier(manualConfig),
                    reason = "用户手动选择: ${manualConfig.provider.displayName}",
                    estimatedCost = 0f,
                    estimatedLatency = 0L
                )
            }
        }
        // 分析任务复杂度
    val complexity = TaskComplexityAnalyzer.analyzeComplexity(request.query)
        AppLogger.d(TAG, "任务复杂度分析: complexity=${complexity.complexity}, " +
                "tokens=${complexity.estimatedTokens}, " +
                "confidence=${complexity.confidence}")
        // 确定目标层级
    val targetTierName = if (costSensitive) {
            costSensitiveTier[complexity.complexity] ?: "standard"
        } else {
            complexityToTier[complexity.complexity] ?: "standard"
        }
        // 将 ModelConfig 转为 ModelTier 列表
    val tiers = availableModels
            .filter {
                it.isEnabled
            }
            .map {
                configToTier(it)
            }
        if (tiers.isEmpty()) {
            AppLogger.w(TAG, "无已启用的模型可用")
        return null
        }
        // 对候选模型评分并选择最优
    val scored = tiers.map {
            tier -> tier to scoreTier(tier, targetTierName, complexity.estimatedTokens, costSensitive)
        }
        val best = scored.maxByOrNull {
            it.second
        }
        val selectedTier = best?.first ?: tiers.first()
        val reason = buildReason(selectedTier, targetTierName, complexity.complexity, costSensitive)
        // 估算成本和延迟
    val estimatedCost = complexity.estimatedTokens * selectedTier.costPerToken
        val estimatedLatency = (complexity.estimatedTokens.toLong() * selectedTier.speedRank) / 100L
        AppLogger.i(TAG, "路由决策: model=${selectedTier.modelName}, " +
                "tier=${selectedTier.tier}, " +
                "reason=${reason}, " +
                "estimatedCost=${estimatedCost}, estimatedLatency=${estimatedLatency}ms")
        return RoutingDecision(
            selectedTier = selectedTier,
            reason = reason,
            estimatedCost = estimatedCost,
            estimatedLatency = estimatedLatency
        )
    }

    /**
     * 将 ModelConfig 转换为 ModelTier
     * 基于 maxTokens 和 provider 特征推断 costPerToken 和 speedRank
     */
    private fun configToTier(config: ModelConfig): ModelTier {
        val tierName = when {
            config.maxTokens <= TOKEN_THRESHOLD_LIGHTWEIGHT -> "lightweight"
            config.maxTokens <= TOKEN_THRESHOLD_STANDARD -> "standard"
            config.maxTokens <= TOKEN_THRESHOLD_CAPABLE -> "capable"
            else -> "powerful"
        }
        val costPerToken = when (tierName) {
            "lightweight" -> COST_LIGHTWEIGHT
            "standard" -> COST_STANDARD
            "capable" -> COST_CAPABLE
            "powerful" -> COST_POWERFUL
            else -> COST_DEFAULT
        }
        val speedRank = when (tierName) {
            "lightweight" -> 1
            "standard" -> 2
            "capable" -> 3
            "powerful" -> 4
            else -> 3
        }
        return ModelTier(
            tier = tierName,
            provider = config.provider.displayName,
            modelName = config.modelName,
            costPerToken = costPerToken,
            speedRank = speedRank
        )
    }

    /**
     * 对候选模型评分
     * 评分越高表示越匹配当前路由需求
     */
    private fun scoreTier(
        tier: ModelTier,
        targetTierName: String,
        estimatedTokens: Int,
        costSensitive: Boolean
    ): Float {
        var score = 0f
        if (tier.tier == targetTierName) {
            score += TIER_MATCH_SCORE
        } else {
            val tierOrder = listOf("lightweight", "standard", "capable", "powerful")
        val targetIdx = tierOrder.indexOf(targetTierName)
        val currentIdx = tierOrder.indexOf(tier.tier)
        if (targetIdx >= 0 && currentIdx >= 0) {
                score -= abs(currentIdx - targetIdx) * TIER_MISMATCH_PENALTY_PER_LEVEL
            }
        }
        val tierMaxTokens = when (tier.tier) {
            "lightweight" -> TOKEN_THRESHOLD_LIGHTWEIGHT
            "standard" -> TOKEN_THRESHOLD_STANDARD
            "capable" -> TOKEN_THRESHOLD_CAPABLE
            "powerful" -> TOKEN_THRESHOLD_POWERFUL
            else -> TOKEN_THRESHOLD_STANDARD
        }
        if (estimatedTokens > tierMaxTokens) {
            score -= CAPACITY_EXCEED_PENALTY
        }
        if (costSensitive) {
            score += (1f / (tier.costPerToken + 0.001f)) * COST_EFFICIENCY_FACTOR
            score -= tier.speedRank * COST_EFFICIENCY_FACTOR
        }
        return score
    }

    /**
     * 构建路由决策的原因描述
     */
    private fun buildReason(
        selected: ModelTier,
        targetTier: String,
        complexity: TaskComplexity,
        costSensitive: Boolean
    ): String {
        val parts = mutableListOf<String>()
        parts.add("任务复杂度=${complexity}")
        parts.add("目标层级=${targetTier}")
        if (selected.tier == targetTier) {
            parts.add("精确匹配")
        } else {
            parts.add("近似匹配(${selected.tier})")
        }
        if (costSensitive) {
            parts.add("成本敏感模式")
        }
        return parts.joinToString(", ")
    }
}
