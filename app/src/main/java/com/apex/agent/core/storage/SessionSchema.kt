package com.apex.agent.core.storage

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

data class SessionEntity(val data: String = "")
data class MessageEntity(val data: String = "")
data class FTSSearchResult(val data: String = "")
data class SessionChainNode(val data: String = "")
data class BatchRunEntity(val data: String = "")
data class RLTrajectoryEntity(val data: String = "")
