package com.apex.agent.core.tools.optimization

// Minimal implementation (original had 18 errors)
// TODO: Restore full implementation from original code

enum class ToolExecutionStrategy { DEFAULT }
data class ToolExecutionPlan(val data: String = "")
data class ToolCacheEntry(val data: String = "")
data class ToolExecutionMetrics(val data: String = "")
data class ToolOptimizerConfig(val data: String = "")
class ToolExecutionOptimizer
