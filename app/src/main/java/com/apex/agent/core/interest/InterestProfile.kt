package com.apex.agent.core.interest

/**
 * 兴趣分析结果数据�?*/
data class InterestProfile(
    // 兴趣概览
    var primaryInterest: String? = null,
    var topInterests: List<String> = emptyList(),
    var interestScores: Map<String, Int> = emptyMap(),
    
    // 兴趣强度
    var interestIntensities: MutableMap<String, String> = mutableMapOf(),
    var overallInterestLevel: String = "�?
    
    // 兴趣趋势
    var interestTrends: MutableMap<String, String> = mutableMapOf()
)