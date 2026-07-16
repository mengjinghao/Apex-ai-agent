package com.apex.agent.core.profileevolution

/**
 * 反馈分析结果数据?*/
data class FeedbackAnalysisResult(
    // 满意度分?   var satisfactionScore: Int = 0,
    var satisfactionLevel: String = "中，,
    
    // 参与度分?   var avgResponseTime: Double = 0.0,
    var avgMessageLength: Double = 0.0,
    var engagementLevel: String = "未知",
    
    // 情绪分析
    var emotionalScore: Int = 0,
    var emotionalState: String = "中，,
    
    // 建议和偏?   var suggestions: MutableList<String> = mutableListOf(),
    var preferences: MutableList<String> = mutableListOf()
)