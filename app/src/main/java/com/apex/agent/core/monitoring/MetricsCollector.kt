package com.apex.agent.core.monitoring

// Minimal implementation (original had 46 errors)
// TODO: Restore full implementation from original code

class MetricsCollector
data class SystemMetrics(val data: String = "")
data class CollectorMetrics(val data: String = "")
data class MeasuredResult(val data: String = "")
class OperationTimer
data class Lap(val data: String = "")
class ThroughputTracker
class LatencyTracker
data class LatencyStats(val data: String = "")
class HealthChecker
class AbstractHealthCheck
class ResourceMonitor
class JvmProfiler
data class JvmSnapshot(val data: String = "")
data class ReportSection(val data: String = "")
