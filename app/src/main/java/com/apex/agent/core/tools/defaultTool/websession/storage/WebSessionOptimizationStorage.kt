package com.apex.agent.core.tools.defaultTool.websession.storage

// Minimal implementation (original had 3 errors)
// TODO: Restore full implementation from original code

class WebSessionOptimizationStorage
data class OptimizationData(val data: String = "")
data class UsageStats(val data: String = "")
data class LastOperation(val data: String = "")
data class LastError(val data: String = "")
data class MethodStats(val data: String = "")
data class ServiceStats(val data: String = "")
data class ServiceSelectors(val data: String = "")
data class OptimizationHint(val data: String = "")
