package com.apex.util

/**
 * 省Token模式力度级别配置
 * @param level 级别 (1-10)
 * @param minMessages 最小保留消息数
 * @param importanceThreshold 重要性阈�?
 * @param windowMultiplier 自适应窗口乘数
 * @param estimatedSavingsPercent 预估节省百分�?
 * @param description 描述文案
 */
data class TokenSavingIntensity(
    val level: Int,
    val minMessages: Int,
    val importanceThreshold: Float,
    val windowMultiplier: Float,
    val estimatedSavingsPercent: Int,
    val description: String
) {
    companion object {
        /**
         * 1-10级力度配�?
         */
        val intensityLevels = listOf(
            TokenSavingIntensity(
                level = 1,
                minMessages = 8,
                importanceThreshold = 0.2f,
                windowMultiplier = 1.0f,
                estimatedSavingsPercent = 5,
                description = "保守：保留完整上下文，轻微优�?
            ),
            TokenSavingIntensity(
                level = 2,
                minMessages = 7,
                importanceThreshold = 0.25f,
                windowMultiplier = 0.9f,
                estimatedSavingsPercent = 15,
                description = "轻微：主要保留近期消�?
            ),
            TokenSavingIntensity(
                level = 3,
                minMessages = 6,
                importanceThreshold = 0.3f,
                windowMultiplier = 0.8f,
                estimatedSavingsPercent = 25,
                description = "较轻：开始裁剪低价值消�?
            ),
            TokenSavingIntensity(
                level = 4,
                minMessages = 5,
                importanceThreshold = 0.35f,
                windowMultiplier = 0.7f,
                estimatedSavingsPercent = 35,
                description = "轻度：平衡性能和质�?
            ),
            TokenSavingIntensity(
                level = 5,
                minMessages = 4,
                importanceThreshold = 0.5f,
                windowMultiplier = 0.6f,
                estimatedSavingsPercent = 45,
                description = "标准：推荐配�?
            ),
            TokenSavingIntensity(
                level = 6,
                minMessages = 4,
                importanceThreshold = 0.55f,
                windowMultiplier = 0.5f,
                estimatedSavingsPercent = 55,
                description = "中度：显著节�?
            ),
            TokenSavingIntensity(
                level = 7,
                minMessages = 3,
                importanceThreshold = 0.6f,
                windowMultiplier = 0.4f,
                estimatedSavingsPercent = 65,
                description = "激进：大幅节省"
            ),
            TokenSavingIntensity(
                level = 8,
                minMessages = 3,
                importanceThreshold = 0.65f,
                windowMultiplier = 0.35f,
                estimatedSavingsPercent = 75,
                description = "非常激进：极端节省"
            ),
            TokenSavingIntensity(
                level = 9,
                minMessages = 2,
                importanceThreshold = 0.7f,
                windowMultiplier = 0.3f,
                estimatedSavingsPercent = 85,
                description = "极度：最小化上下�?
            ),
            TokenSavingIntensity(
                level = 10,
                minMessages = 2,
                importanceThreshold = 0.75f,
                windowMultiplier = 0.25f,
                estimatedSavingsPercent = 90,
                description = "最激进：仅保留最关键消息"
            )
        )

        /**
         * 根据级别获取配置
         */
        fun getIntensity(level: Int): TokenSavingIntensity {
            val clampedLevel = level.coerceIn(1, 10)
            return intensityLevels.firstOrNull { it.level == clampedLevel } ?: intensityLevels[4]
        }

        /**
         * 根据复杂任务自动降级
         */
        fun getDegradedIntensity(currentLevel: Int, isComplexTask: Boolean): TokenSavingIntensity {
            if (!isComplexTask || currentLevel <= 5) {
                return getIntensity(currentLevel)
            }
            // 复杂任务时降级到级别5（标准）
            return getIntensity(5)
        }

        /**
         * 检查是否为高力度级�?
         */
        fun isHighIntensity(level: Int): Boolean {
            return level >= 7
        }

        /**
         * 检查是否为超高度级�?
         */
        fun isUltraIntensity(level: Int): Boolean {
            return level >= 9
        }
    }
}
