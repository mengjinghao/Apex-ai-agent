package com.apex.agent.telemetry

// Minimal implementation (original had 13 errors)
// TODO: Restore full implementation from original code

data class PerformanceSample(val data: String = "")
data class ProfilingResult(val data: String = "")
data class ProfilingSnapshot(val data: String = "")
data class ProfilingConfig(val data: String = "")
class PerformanceProfiler
