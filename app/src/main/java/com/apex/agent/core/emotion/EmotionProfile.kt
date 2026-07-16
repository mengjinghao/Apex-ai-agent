package com.apex.agent.core.emotion

/**
 * 情绪分析结果数据?*/
data class EmotionProfile(
    // 情绪状?   var dominantEmotion: String = "中，,
    var emotionScores: Map<String, Int> = emptyMap(),
    
    // 情绪强度
    var avgEmotionIntensity: Double = 0.0,
    var maxEmotionIntensity: Int = 0,
    
    // 情绪趋势
    var emotionTrend: String = "稳定",
    
    // 情绪触发因素
    var primaryEmotionTrigger: String? = null,
    var emotionTriggers: Map<String, Int> = emptyMap()
)