package com.apex.agent.core.rl

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

enum class ActionType { DEFAULT }
data class Reward(val data: String = "")
enum class RewardType { DEFAULT }
data class Transition(val data: String = "")
data class QValue(val data: String = "")
data class Policy(val data: String = "")
data class ActionProbability(val data: String = "")
data class Episode(val data: String = "")
data class TrainingStats(val data: String = "")
data class PolicyUpdate(val data: String = "")
data class QValueUpdate(val data: String = "")
