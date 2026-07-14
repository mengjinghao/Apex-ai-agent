package com.apex.agent.services.core

import android.content.Context
import com.apex.agent.data.db.ApexAgentDatabase
import com.apex.agent.data.db.CostRecordDao
import com.apex.agent.data.db.CostRecordEntity
import com.apex.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * API 调用记录数据
 */
data class ApiCallRecord(
    val id: String = UUID.randomUUID().toString(),
    val model: String,
    val provider: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cost: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val taskId: String? = null,
    val agentId: String? = null,
    val skillId: String? = null
)

/**
 * 成本统计周期枚举
 */
enum class CostPeriod {
    DAILY,
    WEEKLY,
    MONTHLY
}

/**
 * 成本汇总数据类
 */
data class CostSummary(
    val totalCost: Float,
    val totalCalls: Int,
    val byModel: Map<String, Float>,
    val byTask: Map<String, Float>,
    val byAgent: Map<String, Float>,
    val bySkill: Map<String, Float>,
    val period: CostPeriod
)

/**
 * 模型单价配置（单位：每千 Token 价格，美元）
 *
 * @param model 模型名称
 * @param provider 提供商
 * @param inputPricePerKToken 输入价格（每千Token）
 * @param outputPricePerKToken 输出价格（每千Token）
 */
data class ModelPricing(
    val model: String,
    val provider: String,
    val inputPricePerKToken: Float,
    val outputPricePerKToken: Float
)

/**
 * 默认模型定价预设
 *
 * 提供常用模型的参考价格，用户可通过 CostTracker 的自定义定价覆盖。
 */
object DefaultPricing {

    /** 2024年标准定价预设 */
    val PRESET_2024: List<ModelPricing> = listOf(
        // OpenAI
        ModelPricing("gpt-4o", "openai", 0.005f, 0.015f),
        ModelPricing("gpt-4o-mini", "openai", 0.00015f, 0.0006f),
        ModelPricing("gpt-4-turbo", "openai", 0.01f, 0.03f),
        ModelPricing("gpt-4", "openai", 0.03f, 0.06f),
        ModelPricing("gpt-3.5-turbo", "openai", 0.0005f, 0.0015f),
        ModelPricing("o1", "openai", 0.015f, 0.06f),
        ModelPricing("o1-mini", "openai", 0.003f, 0.012f),
        // Anthropic
        ModelPricing("claude-3.5-sonnet", "anthropic", 0.003f, 0.015f),
        ModelPricing("claude-3-opus", "anthropic", 0.015f, 0.075f),
        ModelPricing("claude-3-sonnet", "anthropic", 0.003f, 0.015f),
        ModelPricing("claude-3-haiku", "anthropic", 0.00025f, 0.00125f),
        // Google
        ModelPricing("gemini-1.5-pro", "google", 0.00125f, 0.005f),
        ModelPricing("gemini-1.5-flash", "google", 0.000075f, 0.0003f),
        ModelPricing("gemini-2.0-flash", "google", 0.0001f, 0.0004f),
        // DeepSeek
        ModelPricing("deepseek-chat", "deepseek", 0.0005f, 0.001f),
        ModelPricing("deepseek-reasoner", "deepseek", 0.001f, 0.002f),
        // 本地模型（成本为0）
        ModelPricing("local", "local", 0f, 0f)
    )

    /** 按modelId索引的定价映射 */
    val BY_MODEL: Map<String, ModelPricing> by lazy {
        PRESET_2024.associateBy { it.model }
    }
}

/**
 * API 成本追踪器
 *
 * 负责记录每次 API 调用并持久化到 Room 数据库，
 * 提供按时间段汇总、分析成本分布和优化建议等功能。
 */
object CostTracker {

    private const val TAG = "CostTracker"

    // 默认模型单价表（每千 Token，单位美元）
    private val DEFAULT_PRICING = DefaultPricing.PRESET_2024

    // 用户自定义单价表（优先级高于默认值）
    private val customPricing = ConcurrentHashMap<String, ModelPricing>()

    private var dao: CostRecordDao? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 初始化 CostTracker，需在 Application 启动时调用
     */
    fun initialize(context: Context) {
        try {
            val database = ApexAgentDatabase.getInstance(context)
            dao = database.costRecordDao()
            AppLogger.i(TAG, "CostTracker initialized successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to initialize CostTracker", e)
        }
    }

    /**
     * 设置用户自定义模型单价（单条）
     */
    fun setCustomPricing(pricing: ModelPricing) {
        val key = "${pricing.provider}:${pricing.model}"
        customPricing[key] = pricing
        AppLogger.d(TAG, "Custom pricing set for ${key}: input=${pricing.inputPricePerKToken}, output=${pricing.outputPricePerKToken}")
    }

    /**
     * 批量设置用户自定义模型单价（覆盖所有自定义定价）
     *
     * @param pricingList 自定义定价列表
     */
    fun setCustomPricing(pricingList: List<ModelPricing>) {
        customPricing.clear()
        pricingList.forEach { pricing ->
            val key = "${pricing.provider}:${pricing.model}"
            customPricing[key] = pricing
        }
        AppLogger.i(TAG, "Custom pricing updated: ${pricingList.size} models")
    }

    /**
     * 根据模型名和提供商获取单价配置
     *
     * @param model 模型名称
     * @param provider 提供商
     * @return 定价配置，不存在时返回null
     */
    fun getPricing(model: String, provider: String): ModelPricing? {
        val key = "${provider}:${model}"
        customPricing[key]?.let { return it }
        return DEFAULT_PRICING.find { it.model == model && it.provider == provider }
            ?: DEFAULT_PRICING.find { it.model == model }
    }

    /**
     * 根据模型ID获取单价配置（简化接口）
     *
     * @param modelId 模型ID（如 "gpt-4o"）
     * @return 定价配置，不存在时返回null
     */
    fun getPricing(modelId: String): ModelPricing? {
        customPricing.values.find { it.model == modelId }?.let { return it }
        return DEFAULT_PRICING.find { it.model == modelId }
    }

    /**
     * 根据 Token 用量自动计算成本
     *
     * @param model 模型名称
     * @param provider 提供商
     * @param inputTokens 输入Token数
     * @param outputTokens 输出Token数
     * @return 计算出的成本（美元）
     */
    fun calculateCost(model: String, provider: String, inputTokens: Int, outputTokens: Int): Float {
        val pricing = getPricing(model, provider) ?: run {
            AppLogger.w(TAG, "No pricing found for model=${model}, provider=${provider}, cost set to 0")
            return 0f
        }
        val inputCost = (inputTokens.toFloat() / 1000f) * pricing.inputPricePerKToken
        val outputCost = (outputTokens.toFloat() / 1000f) * pricing.outputPricePerKToken
        return inputCost + outputCost
    }

    /**
     * 根据模型ID和Token用量计算成本（简化接口）
     *
     * @param modelId 模型ID
     * @param inputTokens 输入Token数
     * @param outputTokens 输出Token数
     * @return 计算出的成本（美元）
     */
    fun getCost(modelId: String, inputTokens: Int, outputTokens: Int): Double {
        val pricing = getPricing(modelId) ?: return 0.0
        val inputCost = (inputTokens.toDouble() / 1000.0) * pricing.inputPricePerKToken.toDouble()
        val outputCost = (outputTokens.toDouble() / 1000.0) * pricing.outputPricePerKToken.toDouble()
        return inputCost + outputCost
    }

    /**
     * 记录一次 API 调用（异步写入数据库）
     */
    fun recordCall(record: ApiCallRecord) {
        scope.launch {
            try {
                val entity = CostRecordEntity(
                    id = record.id,
                    model = record.model,
                    provider = record.provider,
                    inputTokens = record.inputTokens,
                    outputTokens = record.outputTokens,
                    cost = record.cost,
                    timestamp = record.timestamp,
                    taskId = record.taskId,
                    agentId = record.agentId,
                    skillId = record.skillId
                )
                dao?.insert(entity)
                AppLogger.d(
                    TAG,
                    "Recorded API call: model=${record.model}, cost=${record.cost}, " +
                        "inputTokens=${record.inputTokens}, outputTokens=${record.outputTokens}"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to record API call", e)
            }
        }
    }

    /**
     * 获取指定周期的成本汇总
     */
    suspend fun getSummary(period: CostPeriod): CostSummary = withContext(Dispatchers.IO) {
        val dao = dao ?: run {
            AppLogger.w(TAG, "CostTracker not initialized, returning empty summary")
            return@withContext CostSummary(0f, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap(), period)
        }

        val (startTime, endTime) = getTimeRange(period)

        try {
            val totalCost = dao.getTotalCostByTimeRange(startTime, endTime) ?: 0f
            val totalCalls = dao.getCallCountByTimeRange(startTime, endTime)

            val byModel = dao.getCostByModel(startTime, endTime)
                .associate { it.model to it.cost }

            val byTask = dao.getCostByTask(startTime, endTime)
                .associate { it.taskId to it.cost }

            val byAgent = dao.getCostByAgent(startTime, endTime)
                .associate { it.agentId to it.cost }

            val bySkill = dao.getCostBySkill(startTime, endTime)
                .associate { it.skillId to it.cost }

            CostSummary(
                totalCost = totalCost,
                totalCalls = totalCalls,
                byModel = byModel,
                byTask = byTask,
                byAgent = byAgent,
                bySkill = bySkill,
                period = period
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to get cost summary for period=${period}", e)
            CostSummary(0f, 0, emptyMap(), emptyMap(), emptyMap(), emptyMap(), period)
        }
    }

    /**
     * 获取成本优化建议
     */
    suspend fun getOptimizationSuggestions(): List<String> = withContext(Dispatchers.IO) {
        val dao = dao ?: return@withContext listOf("CostTracker 未初始化，请先调用 initialize()")

        try {
            val suggestions = mutableListOf<String>()
            val (startTime, endTime) = getTimeRange(CostPeriod.DAILY)

            // 1. 分析模型成本分布
    val modelCosts = dao.getCostByModel(startTime, endTime)
            if (modelCosts.isNotEmpty()) {
                val totalCost = modelCosts.sumOf { it.cost.toDouble() }.toFloat()
                val mostExpensive = modelCosts.maxByOrNull { it.cost }
                if (mostExpensive != null && totalCost > 0) {
                    val ratio = mostExpensive.cost / totalCost
                    if (ratio > 0.7f) {
                        suggestions.add(
                            "模型 ${mostExpensive.model} 占据 ${String.format("%.1f", ratio * 100)}% 的成本，" +
                                "建议评估是否可以用更经济的模型替代部分调用"
                        )
                    }
                }
            }

            // 2. 分析调用次数
    val callCounts = dao.getCallCountByModel(startTime, endTime)
            if (callCounts.isNotEmpty()) {
                val highFreqModel = callCounts.maxByOrNull { it.count }
                if (highFreqModel != null && highFreqModel.count > 100) {
                    val pricing = getPricing(highFreqModel.model, "")
                    if (pricing != null && (pricing.inputPricePerKToken + pricing.outputPricePerKToken) > 0.01f) {
                        suggestions.add(
                            "模型 ${highFreqModel.model} 今日调用 ${highFreqModel.count} 次，" +
                                "属于高频调用，建议考虑使用缓存或批处理减少调用次数"
                        )
                    }
                }
            }

            // 3. 分析 Agent 成本
    val agentCosts = dao.getCostByAgent(startTime, endTime)
            if (agentCosts.size > 1) {
                val sorted = agentCosts.sortedByDescending { it.cost }
                val topAgent = sorted.first()
                val totalAgentCost = sorted.sumOf { it.cost.toDouble() }.toFloat()
                if (totalAgentCost > 0 && topAgent.cost / totalAgentCost > 0.5f) {
                    suggestions.add(
                        "Agent ${topAgent.agentId} 消耗了 ${String.format("%.1f", topAgent.cost / totalAgentCost * 100)}% 的 Agent 成本，" +
                            "建议检查其任务逻辑是否高效"
                    )
                }
            }

            // 4. 分析 Skill 成本
    val skillCosts = dao.getCostBySkill(startTime, endTime)
            if (skillCosts.isNotEmpty()) {
                val topSkill = skillCosts.maxByOrNull { it.cost }
                if (topSkill != null && topSkill.cost > totalCost(dao, startTime, endTime) * 0.3f) {
                    suggestions.add(
                        "Skill ${topSkill.skillId} 成本较高，建议优化其 prompt 长度或调用频率"
                    )
                }
            }

            // 5. 通用建议
    if (modelCosts.any { it.model.contains("gpt-4", ignoreCase = true) }) {
                suggestions.add("检测到使用了 GPT-4 系列模型，对于简单任务建议使用 GPT-4o-mini 或 GPT-3.5-turbo 以降低成本")
            }

            if (suggestions.isEmpty()) {
                suggestions.add("当前成本分布正常，暂无优化建议")
            }

            suggestions
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to generate optimization suggestions", e)
            listOf("生成优化建议时发生错误: ${e.message}")
        }
    }

    /**
     * 清理过期数据
     */
    fun cleanupOldRecords(olderThanMs: Long) {
        scope.launch {
            try {
                val cutoff = System.currentTimeMillis() - olderThanMs
                dao?.deleteOlderThan(cutoff)
                AppLogger.i(TAG, "Cleaned up records older than ${olderThanMs} ms")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to cleanup old records", e)
            }
        }
    }

    // ---- 内部辅助方法 ----
    private suspend fun totalCost(dao: CostRecordDao, startTime: Long, endTime: Long): Float {
        return dao.getTotalCostByTimeRange(startTime, endTime) ?: 0f
    }

    /**
     * 根据周期计算时间范围 [startTime, endTime)
     */
    private fun getTimeRange(period: CostPeriod): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()

        val startTime = when (period) {
            CostPeriod.DAILY -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            CostPeriod.WEEKLY -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            CostPeriod.MONTHLY -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
        }

        return startTime to now
    }
}
