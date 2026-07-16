package com.apex.util

import android.content.Context
import com.apex.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.first

data class TokenSavingStats(
    val originalTokenCount: Int,
    val optimizedTokenCount: Int,
    val savedTokens: Int,
    val savingsRatio: Float,
    val complexity: TaskComplexity,
    val intensityLevel: Int,
    val wasDegraded: Boolean = false
)

class TokenSavingManager private constructor(private val context: Context) {

    private var pruningManager: ContextPruningManager? = null
    private var windowManager: AdaptiveWindowManager? = null
    private var isEnabled: Boolean = false
    private var semanticPruningEnabled: Boolean = true
    private var adaptiveWindowEnabled: Boolean = true
    private var minContextMessages: Int = 3
    private var importanceThreshold: Float = 0.5f
    private var tokenSavingIntensity: Int = 5
    private var currentIntensityConfig: TokenSavingIntensity = TokenSavingIntensity.intensityLevels[DEFAULT_INTENSITY_INDEX]

    private var lastStats: TokenSavingStats? = null

    companion object {
        @Volatile
        private var INSTANCE: TokenSavingManager? = null

        // 默认力度级别索引（对?level 5，索引从 0 开始）
        private const val DEFAULT_INTENSITY_INDEX = 4
        
        // 默认最大消息数
        private const val DEFAULT_MAX_MESSAGES = 50

        fun getInstance(context: Context): TokenSavingManager {
            return INSTANCE ?: synchronized(this) {
                val instance = TokenSavingManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        // 复杂任务关键词（用于检测是否需要自动降级）
        private val COMPLEX_TASK_KEYWORDS = listOf(
            "调试", "debug", "修复", "fix", "错误", "exception", "崩溃", "crash",
            "代码", "function", "函数", "方法", "?, "class", "algorithm", "算法",
            "重构", "refactor", "优化", "optimize", "分析", "analyze", "比较", "compare",
            "复杂", "complex", "数据?, "database", "查询", "query", "事务", "transaction",
            "系统", "system", "架构", "architecture", "设计", "design", "实现", "implement"
        )
    }

    suspend fun initialize() {
        val preferencesManager = UserPreferencesManager.getInstance(context)

        isEnabled = preferencesManager.tokenSavingModeEnabled.first()
        tokenSavingIntensity = preferencesManager.tokenSavingIntensity.first()
        semanticPruningEnabled = preferencesManager.semanticPruningEnabled.first()
        adaptiveWindowEnabled = preferencesManager.adaptiveWindowEnabled.first()
        
        // 根据力度级别自动计算配置
        applyIntensityConfig()
        
        pruningManager = ContextPruningManager(minContextMessages, importanceThreshold)
        windowManager = AdaptiveWindowManager(
            defaultMinMessages = minContextMessages,
            defaultMaxMessages = DEFAULT_MAX_MESSAGES
        )

        AppLogger.d("TokenSavingManager", "Initialized: enabled=${isEnabled}, intensity=${tokenSavingIntensity}, semanticPruning=${semanticPruningEnabled}")
    }

    suspend fun refreshSettings() {
        val preferencesManager = UserPreferencesManager.getInstance(context)

        val newEnabled = preferencesManager.tokenSavingModeEnabled.first()
        val newIntensity = preferencesManager.tokenSavingIntensity.first()
        val newSemanticPruning = preferencesManager.semanticPruningEnabled.first()
        val newAdaptiveWindow = preferencesManager.adaptiveWindowEnabled.first()

        val settingsChanged = isEnabled != newEnabled ||
                tokenSavingIntensity != newIntensity ||
                semanticPruningEnabled != newSemanticPruning ||
                adaptiveWindowEnabled != newAdaptiveWindow

        isEnabled = newEnabled
        tokenSavingIntensity = newIntensity
        semanticPruningEnabled = newSemanticPruning
        adaptiveWindowEnabled = newAdaptiveWindow

        if (settingsChanged) {
            // 根据力度级别重新计算配置
            applyIntensityConfig()
            
            pruningManager = ContextPruningManager(minContextMessages, importanceThreshold)
            windowManager = AdaptiveWindowManager(
                defaultMinMessages = minContextMessages,
                defaultMaxMessages = 50
            )
            AppLogger.d("TokenSavingManager", "Settings updated: intensity=${tokenSavingIntensity}, semanticPruning=${semanticPruningEnabled}")
        }
    }

    /**
     * 根据当前力度级别应用配置
     */
    private fun applyIntensityConfig() {
        currentIntensityConfig = TokenSavingIntensity.getIntensity(tokenSavingIntensity)
        minContextMessages = currentIntensityConfig.minMessages
        importanceThreshold = currentIntensityConfig.importanceThreshold
    }

    fun isTokenSavingEnabled(): Boolean = isEnabled

    /**
     * 获取当前力度级别配置
     */
    fun getCurrentIntensityConfig(): TokenSavingIntensity = currentIntensityConfig

    /**
     * 检测是否为复杂任务
     */
    fun isComplexTask(input: String): Boolean {
        val lowerInput = input.lowercase()
        return COMPLEX_TASK_KEYWORDS.any { lowerInput.contains(it) }
    }

    /**
     * 获取实际使用的配置（可能因复杂任务而降级）
     */
    fun getEffectiveConfig(input: String): TokenSavingIntensity {
        val isComplex = isComplexTask(input)
        return TokenSavingIntensity.getDegradedIntensity(tokenSavingIntensity, isComplex)
    }

    fun optimizeMessages(
        messages: List<Message>,
        currentInput: String
    ): List<Message> {
        if (!isEnabled || messages.isEmpty()) {
            return messages
        }

        // 获取实际使用的配置（可能因复杂任务而降级）
        val isComplex = isComplexTask(currentInput)
        val effectiveConfig = TokenSavingIntensity.getDegradedIntensity(tokenSavingIntensity, isComplex)
        val wasDegraded = effectiveConfig.level != tokenSavingIntensity
        
        // 如果配置与当前不同，临时调整
        val originalMinMessages = minContextMessages
        val originalThreshold = importanceThreshold
        
        if (wasDegraded) {
            minContextMessages = effectiveConfig.minMessages
            importanceThreshold = effectiveConfig.importanceThreshold
            pruningManager = ContextPruningManager(minContextMessages, importanceThreshold)
            AppLogger.d("TokenSavingManager", "Complex task detected, degraded from ${tokenSavingIntensity} to ${effectiveConfig.level}")
        }

        val originalTokens = messages.sumOf { ChatUtils.estimateTokenCount(it.content) }

        var optimizedMessages = messages

        if (adaptiveWindowEnabled && windowManager != null) {
            val windowConfig = windowManager?.calculateWindowConfig(currentInput, messages)
            
            if (windowConfig == null) {
                // 恢复原始配置
                if (wasDegraded) {
                    minContextMessages = originalMinMessages
                    importanceThreshold = originalThreshold
                }
                return messages
            }
            
            // 应用窗口乘数
            val adjustedWindowMultiplier = windowConfig.messageCount * effectiveConfig.windowMultiplier
            val adjustedMessageCount = adjustedWindowMultiplier.toInt().coerceAtLeast(effectiveConfig.minMessages)
            
            val messagesForWindow = if (windowConfig.messageCount > adjustedMessageCount) {
                windowConfig.copy(messageCount = adjustedMessageCount)
            } else {
                windowConfig
            }
            
            optimizedMessages = windowManager?.getRecommendedMessages(optimizedMessages, messagesForWindow) ?: messages

            if (!semanticPruningEnabled) {
                val optimizedTokens = optimizedMessages.sumOf { ChatUtils.estimateTokenCount(it.content) }
                lastStats = TokenSavingStats(
                    originalTokenCount = originalTokens,
                    optimizedTokenCount = optimizedTokens,
                    savedTokens = originalTokens - optimizedTokens,
                    savingsRatio = if (originalTokens > 0) (originalTokens - optimizedTokens).toFloat() / originalTokens else 0f,
                    complexity = windowConfig.complexity,
                    intensityLevel = tokenSavingIntensity,
                    wasDegraded = wasDegraded
                )
                
                // 恢复原始配置
                if (wasDegraded) {
                    minContextMessages = originalMinMessages
                    importanceThreshold = originalThreshold
                }
                
                return optimizedMessages
            }
        }

        if (semanticPruningEnabled && pruningManager != null) {
            val pruningResult = pruningManager?.pruneContext(optimizedMessages, currentInput)
            if (pruningResult != null) {
                optimizedMessages = pruningResult.prunedMessages

                lastStats = TokenSavingStats(
                    originalTokenCount = originalTokens,
                    optimizedTokenCount = originalTokens - pruningResult.savedTokens,
                    savedTokens = pruningResult.savedTokens,
                    savingsRatio = pruningResult.pruningRatio,
                    complexity = windowManager?.detectComplexity(currentInput, messages) ?: TaskComplexity.MODERATE,
                    intensityLevel = tokenSavingIntensity,
                    wasDegraded = wasDegraded
                )
            }
        }

        // 恢复原始配置
        if (wasDegraded) {
            minContextMessages = originalMinMessages
            importanceThreshold = originalThreshold
        }

        return optimizedMessages
    }

    fun getLastStats(): TokenSavingStats? = lastStats

    fun getComplexityStats(): Map<TaskComplexity, Int>? {
        return windowManager?.getComplexityStats()
    }

    fun estimateSavings(
        messages: List<Message>,
        currentInput: String
    ): TokenSavingStats? {
        if (messages.isEmpty()) return null

        val originalTokens = messages.sumOf { ChatUtils.estimateTokenCount(it.content) }

        if (!isEnabled) {
            return TokenSavingStats(
                originalTokenCount = originalTokens,
                optimizedTokenCount = originalTokens,
                savedTokens = 0,
                savingsRatio = 0f,
                complexity = TaskComplexity.MODERATE,
                intensityLevel = tokenSavingIntensity
            )
        }

        val isComplex = isComplexTask(currentInput)
        val effectiveConfig = TokenSavingIntensity.getDegradedIntensity(tokenSavingIntensity, isComplex)
        val wasDegraded = effectiveConfig.level != tokenSavingIntensity

        // 预估节省?
        val estimatedSavings = (originalTokens * (effectiveConfig.estimatedSavingsPercent.toFloat() / 100)).toInt()
        
        return TokenSavingStats(
            originalTokenCount = originalTokens,
            optimizedTokenCount = originalTokens - estimatedSavings,
            savedTokens = estimatedSavings,
            savingsRatio = effectiveConfig.estimatedSavingsPercent.toFloat() / 100,
            complexity = TaskComplexity.MODERATE,
            intensityLevel = tokenSavingIntensity,
            wasDegraded = wasDegraded
        )
    }

    fun resetLearning() {
        windowManager?.resetLearning()
    }
}
