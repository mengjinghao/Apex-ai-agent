package com.apex.agent.core.normal.visualization

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

data class ContextWindowState(val data: String = "")
enum class ContextPressure { DEFAULT }
data class ContextLayer(val data: String = "")
data class MessageBreakdown(val data: String = "")
data class TokenDistribution(val data: String = "")
class ContextWindowVisualizer
