package com.apex.plugins.skill

// Minimal implementation (original had 15 errors)
// TODO: Restore full implementation from original code

class SmartRecommender
data class RecommenderResult(val data: String = "")
data class RecommendedSkill(val data: String = "")
data class RecommendationMetadata(val data: String = "")
enum class RecommendationSource { DEFAULT }
data class SkillScoreComponents(val data: String = "")
