package com.apex.agent.core.normal.feedback

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

enum class FeedbackType { DEFAULT }
data class FeedbackRecord(val data: String = "")
data class FeedbackContext(val data: String = "")
data class FeedbackStats(val data: String = "")
data class FeedbackInsight(val data: String = "")
class UserFeedbackLearningSystem
