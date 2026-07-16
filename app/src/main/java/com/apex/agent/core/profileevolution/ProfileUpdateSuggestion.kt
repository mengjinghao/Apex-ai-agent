package com.apex.agent.core.profileevolution

/**
 * 画像更新建议数据�?*/
data class ProfileUpdateSuggestion(
    val dimension: String,
    val newValue: String,
    val confidence: Double
)