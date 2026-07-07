package com.apex.agent.kernel.burst

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val DEFAULT_MAX_CONCURRENCY = 3

data class HealthMetrics(
    val cpuUsage: Float = 0f,
    val memoryUsageMb: Int = 0,
    val taskQueueSize: Int = 0,
    val runningTaskCount: Int = 0,
    val skillSuccessRates: Map<String, Float> = emptyMap(),
    val batteryLevel: Int = -1,
    val isCharging: Boolean = false,
    val thermalStatus: Int = 0,
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY
)

class BurstHealthMonitor {
    private val _metrics = MutableStateFlow(HealthMetrics())
    val metrics: StateFlow<HealthMetrics> = _metrics.asStateFlow()

    fun updateStrategy(strategy: com.apex.agent.data.burstmode.performance.BurstAdaptiveGovernor.ExecutionStrategy) {
        _metrics.value = _metrics.value.copy(
            maxConcurrency = strategy.maxConcurrency
        )
    }

    fun updateDeviceState(state: com.apex.agent.data.burstmode.performance.BurstAdaptiveGovernor.DeviceState) {
        _metrics.value = _metrics.value.copy(
            batteryLevel = state.batteryLevel,
            isCharging = state.isCharging,
            thermalStatus = state.thermalStatus
        )
    }
}