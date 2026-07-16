package com.apex.agent.core.trajectory

// Minimal implementation (original had 34 errors)
// TODO: Restore full implementation from original code

data class TrajectoryData(val data: String = "")
data class TrajectoryTurn(val data: String = "")
data class ToolCallPair(val data: String = "")
data class ToolCall(val data: String = "")
data class ToolResponse(val data: String = "")
data class CompressedRegion(val data: String = "")
data class TrajectoryPartition(val data: String = "")
data class TokenBudget(val data: String = "")
data class TrajectoryStats(val data: String = "")
data class CompressionQualityReport(val data: String = "")
    fun init() { }
}
object MiddleCompression {
    fun init() { }
}
    fun init() { }
}
object TrajectoryCompressor {
    fun init() { }
}
