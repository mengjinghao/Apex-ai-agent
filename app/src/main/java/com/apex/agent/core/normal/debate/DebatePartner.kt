package com.apex.agent.core.normal.debate

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

enum class DebateMode { DEFAULT }
enum class DebateSide { DEFAULT }
enum class DebatePhase { DEFAULT }
data class DebateTopic(val data: String = "")
data class Argument(val data: String = "")
data class DebateSession(val data: String = "")
data class DebateScore(val data: String = "")
class DebatePartner
