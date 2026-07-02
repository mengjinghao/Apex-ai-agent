package com.apex.agent.core.behavior

/**
 * 用户行为分析结果数据�?*/
data class UserBehaviorProfile(
    // 对话模式
    var messageCount: Int = 0,
    var userMessageCount: Int = 0,
    var aiMessageCount: Int = 0,
    var avgMessageLength: Double = 0.0,
    var avgUserMessageLength: Double = 0.0,
    var avgAiMessageLength: Double = 0.0,
    var avgResponseTime: Double = 0.0,
    var responseTimeVariance: Double = 0.0,
    
    // 使用时间
    var peakUsageHour: Int = -1,
    var usageTimePattern: String = "未知",
    var peakUsageDay: Int = -1,
    var usageDayPattern: String = "未知",
    
    // 消息特征
    var questionCount: Int = 0,
    var statementCount: Int = 0,
    var commandCount: Int = 0,
    var formalMessageCount: Int = 0,
    var casualMessageCount: Int = 0,
    var dominantStyle: String = "中，,
    
    // 交互模式
    var avgTurnLength: Double = 0.0,
    var maxTurnLength: Int = 0,
    var minTurnLength: Int = 0,
    var responsePatterns: Map<String, Int> = emptyMap(),
    
    // 使用场景
    var usageScenarios: List<String> = emptyList(),
    var primaryUsageScenario: String? = null
)