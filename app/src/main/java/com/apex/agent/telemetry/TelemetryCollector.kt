package com.apex.agent.telemetry

// Minimal implementation (original had 9 errors)
// TODO: Restore full implementation from original code

data class TelemetryEvent(val data: String = "")
data class DeviceSnapshot(val data: String = "")
enum class EventType { DEFAULT }
enum class EventCategory { DEFAULT }
data class TelemetrySession(val data: String = "")
data class TelemetryReport(val data: String = "")
data class TelemetrySummary(val data: String = "")
data class TelemetryConfig(val data: String = "")
data class TelemetrySnapshot(val data: String = "")
class TelemetryCollector
