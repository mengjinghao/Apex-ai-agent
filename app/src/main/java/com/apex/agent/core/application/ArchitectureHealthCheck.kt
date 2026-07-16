package com.apex.agent.core.application

// Minimal implementation (original had 83 errors)
// TODO: Restore full implementation from original code

class ArchitectureHealthCheck
data class ColdStartMetrics(val data: String = "")
data class ConcurrencyMetrics(val data: String = "")
data class CacheMetrics(val data: String = "")
data class SerializationMetrics(val data: String = "")
data class MemoryMetrics(val data: String = "")
data class HealthSnapshot(val data: String = "")
