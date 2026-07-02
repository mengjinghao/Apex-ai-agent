package com.apex.agent.kernel.burst.engine.tuning

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * E12: 内核性能调优器
 *
 * 自适应参数调优：
 * - 基于指标自动调参
 * - A/B 测试
 * - 性能回归检测
 * - 调优历史
 */
class KernelPerformanceTuner(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val tuningIntervalMs: Long = 60_000L
) {

    data class TuningParameter(
        val name: String,
        val currentValue: Any,
        val minValue: Any,
        val maxValue: Any,
        val step: Any,
        val unit: String = ""
    )

    data class TuningResult(
        val parameter: String,
        val oldValue: Any,
        val newValue: Any,
        val reason: String,
        val expectedImprovement: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class PerformanceBaseline(
        val avgLatencyMs: Long,
        val successRate: Float,
        val throughput: Float,
        val memoryUsageMb: Long,
        val cpuUsage: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val parameters = ConcurrentHashMap<String, TuningParameter>()
    private val _currentBaseline = MutableStateFlow<PerformanceBaseline?>(null)
    val currentBaseline: StateFlow<PerformanceBaseline?> = _currentBaseline.asStateFlow()

    private val tuningHistory = mutableListOf<TuningResult>()
    private val _tuningResults = MutableStateFlow<List<TuningResult>>(emptyList())
    val tuningResults: StateFlow<List<TuningResult>> = _tuningResults.asStateFlow()

    private var tuningJob: kotlinx.coroutines.Job? = null
    private var metricProvider: (() -> PerformanceBaseline)? = null
    private var applyHandler: ((String, Any) -> Unit)? = null

    fun registerParameter(param: TuningParameter) {
        parameters[param.name] = param
    }

    fun setMetricProvider(provider: () -> PerformanceBaseline) {
        metricProvider = provider
    }

    fun setApplyHandler(handler: (String, Any) -> Unit) {
        applyHandler = handler
    }

    /**
     * 手动调优
     */
    fun tune(parameterName: String, direction: TuneDirection): TuningResult? {
        val param = parameters[parameterName] ?: return null
        val newValue = when (param.currentValue) {
            is Int -> adjustInt(param.currentValue, param.minValue as Int, param.maxValue as Int, param.step as Int, direction)
            is Long -> adjustLong(param.currentValue, param.minValue as Long, param.maxValue as Long, param.step as Long, direction)
            is Float -> adjustFloat(param.currentValue, param.minValue as Float, param.maxValue as Float, param.step as Float, direction)
            else -> param.currentValue
        }

        if (newValue == param.currentValue) return null

        val result = TuningResult(
            parameter = parameterName,
            oldValue = param.currentValue,
            newValue = newValue,
            reason = "手动${if (direction == TuneDirection.INCREASE) "提升" else "降低"} $parameterName",
            expectedImprovement = "预期${if (direction == TuneDirection.INCREASE) "提升" else "降低"} $parameterName"
        )

        parameters[parameterName] = param.copy(currentValue = newValue)
        applyHandler?.invoke(parameterName, newValue)
        tuningHistory.add(result)
        _tuningResults.value = tuningHistory.toList()

        return result
    }

    /**
     * 自动调优
     */
    fun startAutoTuning() {
        tuningJob?.cancel()
        tuningJob = scope.launch {
            while (true) {
                delay(tuningIntervalMs)
                autoTune()
            }
        }
    }

    fun stopAutoTuning() {
        tuningJob?.cancel()
    }

    private fun autoTune() {
        val baseline = _currentBaseline.value ?: return
        val current = metricProvider?.invoke() ?: return

        // 如果性能下降，尝试调优
        if (current.avgLatencyMs > baseline.avgLatencyMs * 1.2) {
            // 延迟增加，降低并发
            tune("maxConcurrency", TuneDirection.DECREASE)
        }
        if (current.successRate < baseline.successRate * 0.9) {
            // 成功率下降，增加重试
            tune("maxRetries", TuneDirection.INCREASE)
        }
        if (current.memoryUsageMb > baseline.memoryUsageMb * 1.5) {
            // 内存增加，降低上下文窗口
            tune("contextWindowTokens", TuneDirection.DECREASE)
        }
        if (current.cpuUsage > 0.9f) {
            // CPU 过高，降低并发
            tune("maxConcurrency", TuneDirection.DECREASE)
        }

        // 更新基线
        _currentBaseline.value = current
    }

    fun setBaseline(baseline: PerformanceBaseline) {
        _currentBaseline.value = baseline
    }

    fun getParameters(): List<TuningParameter> = parameters.values.toList()
    fun getHistory(): List<TuningResult> = tuningHistory.toList()

    fun shutdown() {
        tuningJob?.cancel()
        scope.cancel()
    }

    enum class TuneDirection { INCREASE, DECREASE }

    private fun adjustInt(current: Int, min: Int, max: Int, step: Int, direction: TuneDirection): Int {
        val newValue = if (direction == TuneDirection.INCREASE) current + step else current - step
        return newValue.coerceIn(min, max)
    }

    private fun adjustLong(current: Long, min: Long, max: Long, step: Long, direction: TuneDirection): Long {
        val newValue = if (direction == TuneDirection.INCREASE) current + step else current - step
        return newValue.coerceIn(min, max)
    }

    private fun adjustFloat(current: Float, min: Float, max: Float, step: Float, direction: TuneDirection): Float {
        val newValue = if (direction == TuneDirection.INCREASE) current + step else current - step
        return newValue.coerceIn(min, max)
    }
}
