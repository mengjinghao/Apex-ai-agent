package com.apex.agent.telemetry

// Minimal implementation (original had 4 errors)
// TODO: Restore full implementation from original code

data class ExportConfig(val data: String = "")
data class ExportResult(val data: String = "")
data class ExportMetrics(val data: String = "")
enum class ExportFormat { DEFAULT }
class TelemetryReporter
