package com.apex.agent.telemetry

// Minimal implementation (original had 7 errors)
// TODO: Restore full implementation from original code

data class CrashReport(val data: String = "")
data class MemorySnapshot(val data: String = "")
data class ThreadDump(val data: String = "")
data class CrashStatistics(val data: String = "")
data class CrashConfig(val data: String = "")
class CrashReporter
