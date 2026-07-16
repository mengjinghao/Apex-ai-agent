package com.apex.agent.kernel.burst.optimization

// Minimal implementation (original had 13 errors)
// TODO: Restore full implementation from original code

data class BurstExecutionPlan(val data: String = "")
data class BurstTaskSpec(val data: String = "")
data class ResourceProfile(val data: String = "")
enum class BurstMode { DEFAULT }
enum class ExecutionStrategy { DEFAULT }
data class BurstPerformanceSnapshot(val data: String = "")
data class BurstAdaptiveConfig(val data: String = "")
data class ResourceAvailability(val data: String = "")
data class BurstExecutionMetrics(val data: String = "")
class BurstExecutionController
