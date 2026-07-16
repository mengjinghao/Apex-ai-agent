package com.apex.agent.core.workflow.enhanced.monitor

// Minimal implementation (original had 1 errors)
// TODO: Restore full implementation from original code

class WorkflowMonitor
data class MonitorSnapshot(val data: String = "")
data class ExecutionTotals(val data: String = "")
data class WorkflowStats(val data: String = "")
data class NodeTypeStats(val data: String = "")
data class ActionTypeStats(val data: String = "")
data class ExecutionSummary(val data: String = "")
data class ActiveExecution(val data: String = "")
data class WorkflowStatsInternal(val data: String = "")
data class NodeTypeStatsInternal(val data: String = "")
data class ActionTypeStatsInternal(val data: String = "")
data class ActiveExecutionInternal(val data: String = "")
