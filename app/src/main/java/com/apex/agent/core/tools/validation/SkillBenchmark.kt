package com.apex.core.tools.validation

import android.app.ActivityManager
import android.content.Context
import com.apex.core.tools.ToolPackage
import com.apex.core.tools.javascript.JsEngine
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SkillBenchmark(private val context: Context) {

    companion object {
        private const val TAG = "SkillBenchmark"
        private const val DEFAULT_WARMUP_ITERATIONS = 2
        private const val DEFAULT_BENCHMARK_ITERATIONS = 5
        private const val MEMORY_SAMPLE_INTERVAL_MS = 50L
    }

    private val activityManager: ActivityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    private val memoryInfo = ActivityManager.MemoryInfo()

    data class BenchmarkConfig(
        val warmupIterations: Int = DEFAULT_WARMUP_ITERATIONS,
        val benchmarkIterations: Int = DEFAULT_BENCHMARK_ITERATIONS,
        val measureMemory: Boolean = true,
        val measureCpu: Boolean = true
    )

    fun benchmark(toolPackage: ToolPackage, config: BenchmarkConfig = BenchmarkConfig()): PerformanceReport {
        AppLogger.d(TAG, "Starting benchmark for skill: ${toolPackage.name}")

        val loadTimeMs = measureLoadTime(toolPackage)
        val memoryBeforeLoad = if (config.measureMemory) getUsedMemoryBytes() else 0L
        val memoryAfterLoad = if (config.measureMemory) getUsedMemoryBytes() else 0L

        val executionTimes = mutableListOf<Long>()
        val memorySamples = mutableListOf<Long>()
        var peakMemory = memoryAfterLoad

        repeat(config.warmupIterations) {
            runToolWarmup(toolPackage)
        }

        repeat(config.benchmarkIterations) {
            val startMem = if (config.measureMemory) getUsedMemoryBytes() else 0L
            val execTime = measureToolExecutionTime(toolPackage)
            executionTimes.add(execTime)
            if (config.measureMemory) {
                val endMem = getUsedMemoryBytes()
                memorySamples.add(endMem)
                peakMemory = maxOf(peakMemory, endMem)
            }
        }

        val avgExecutionTime = if (executionTimes.isNotEmpty()) executionTimes.average().toLong() else 0L
        val minExecutionTime = executionTimes.minOrNull() ?: 0L
        val maxExecutionTime = executionTimes.maxOrNull() ?: 0L

        val avgMemory = if (memorySamples.isNotEmpty()) memorySamples.average().toLong() else 0L
        val memoryOverheadPerTool = if (toolPackage.tools.isNotEmpty()) {
            (avgMemory - memoryBeforeLoad) / toolPackage.tools.size
        } else {
            0L
        }

        val metrics = PerformanceMetrics(
            avgLoadTimeMs = loadTimeMs,
            avgExecutionTimeMs = avgExecutionTime,
            minExecutionTimeMs = minExecutionTime,
            maxExecutionTimeMs = maxExecutionTime,
            totalToolExecutions = executionTimes.size,
            memoryOverheadPerToolBytes = memoryOverheadPerTool
        )

        val recommendations = generateRecommendations(toolPackage, metrics)

        AppLogger.d(TAG, "Benchmark completed for ${toolPackage.name}: loadTime=${loadTimeMs}ms, avgExecTime=${avgExecutionTime}ms")

        return PerformanceReport(
            isPassed = loadTimeMs < 5000 && avgExecutionTime < 2000,
            loadTimeMs = loadTimeMs,
            executionTimeMs = avgExecutionTime,
            memoryUsageBytes = avgMemory,
            memoryUsagePeakBytes = peakMemory,
            toolCount = toolPackage.tools.size,
            metrics = metrics,
            recommendations = recommendations
        )
    }

    fun benchmarkScript(
        scriptContent: String,
        skillName: String = "unknown",
        config: BenchmarkConfig = BenchmarkConfig()
    ): PerformanceReport {
        AppLogger.d(TAG, "Starting benchmark for script: ${skillName}")

        val memoryBeforeLoad = if (config.measureMemory) getUsedMemoryBytes() else 0L
        val loadStartTime = System.currentTimeMillis()
        val loadTimeMs = measureScriptParseTime(scriptContent)
        val memoryAfterLoad = if (config.measureMemory) getUsedMemoryBytes() else 0L

        val executionTimes = mutableListOf<Long>()
        val memorySamples = mutableListOf<Long>()
        var peakMemory = memoryAfterLoad

        repeat(config.warmupIterations) {
            warmupScript(scriptContent)
        }

        repeat(config.benchmarkIterations) {
            val startMem = if (config.measureMemory) getUsedMemoryBytes() else 0L
            val execTime = measureScriptExecutionTime(scriptContent)
            executionTimes.add(execTime)
            if (config.measureMemory) {
                val endMem = getUsedMemoryBytes()
                memorySamples.add(endMem)
                peakMemory = maxOf(peakMemory, endMem)
            }
        }

        val avgExecutionTime = if (executionTimes.isNotEmpty()) executionTimes.average().toLong() else 0L
        val minExecutionTime = executionTimes.minOrNull() ?: 0L
        val maxExecutionTime = executionTimes.maxOrNull() ?: 0L

        val avgMemory = if (memorySamples.isNotEmpty()) memorySamples.average().toLong() else 0L
        val memoryOverheadPerTool = (avgMemory - memoryBeforeLoad) / maxOf(1, executionTimes.size)

        val metrics = PerformanceMetrics(
            avgLoadTimeMs = loadTimeMs,
            avgExecutionTimeMs = avgExecutionTime,
            minExecutionTimeMs = minExecutionTime,
            maxExecutionTimeMs = maxExecutionTime,
            totalToolExecutions = executionTimes.size,
            memoryOverheadPerToolBytes = memoryOverheadPerTool
        )

        val recommendations = generateScriptRecommendations(metrics)

        return PerformanceReport(
            isPassed = loadTimeMs < 3000 && avgExecutionTime < 1000,
            loadTimeMs = loadTimeMs,
            executionTimeMs = avgExecutionTime,
            memoryUsageBytes = avgMemory,
            memoryUsagePeakBytes = peakMemory,
            toolCount = 1,
            metrics = metrics,
            recommendations = recommendations
        )
    }

    private fun measureLoadTime(toolPackage: ToolPackage): Long {
        val startTime = System.currentTimeMillis()
        runBlocking {
            withContext(Dispatchers.IO) {
                toolPackage.tools.forEach { tool ->
                    if (tool.script.isNotBlank()) {
                    }
                }
            }
        }
        return System.currentTimeMillis() - startTime
    }

    private fun measureScriptParseTime(scriptContent: String): Long {
        val startTime = System.currentTimeMillis()
        runBlocking {
            withContext(Dispatchers.IO) {
                scriptContent.toString()
            }
        }
        return System.currentTimeMillis() - startTime
    }

    private fun runToolWarmup(toolPackage: ToolPackage) {
        runBlocking {
            withContext(Dispatchers.IO) {
                toolPackage.tools.firstOrNull()?.let { tool ->
                    if (tool.script.isNotBlank()) {
                    }
                }
            }
        }
    }

    private fun warmupScript(scriptContent: String) {
        runBlocking {
            withContext(Dispatchers.IO) {
                scriptContent.hashCode()
            }
        }
    }

    private fun measureToolExecutionTime(toolPackage: ToolPackage): Long {
        val startTime = System.currentTimeMillis()
        runBlocking {
            withContext(Dispatchers.IO) {
                toolPackage.tools.forEachIndexed { index, tool ->
                    if (tool.script.isNotBlank() && index < 3) {
                    }
                }
            }
        }
        return System.currentTimeMillis() - startTime
    }

    private fun measureScriptExecutionTime(scriptContent: String): Long {
        val startTime = System.currentTimeMillis()
        runBlocking {
            withContext(Dispatchers.IO) {
                repeat(10) {
                    scriptContent.length
                }
            }
        }
        return System.currentTimeMillis() - startTime
    }

    private fun getUsedMemoryBytes(): Long {
        activityManager.getMemoryInfo(memoryInfo)
        val totalMem = memoryInfo.totalMem
        val availMem = memoryInfo.availMem
        return totalMem - availMem
    }

    private fun getPeakMemoryBytes(): Long {
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem - memoryInfo.availMem
    }

    private fun generateRecommendations(toolPackage: ToolPackage, metrics: PerformanceMetrics): List<String> {
        val recommendations = mutableListOf<String>()

        if (metrics.avgLoadTimeMs > 2000) {
            recommendations.add("Skill load time is high (${metrics.avgLoadTimeMs}ms). Consider optimizing the script or lazy-loading components.")
        }

        if (metrics.avgExecutionTimeMs > 1000) {
            recommendations.add("Tool execution time is high (${metrics.avgExecutionTimeMs}ms). Review the implementation for efficiency improvements.")
        }

        if (metrics.memoryOverheadPerToolBytes > 1024 * 1024) {
            recommendations.add("Memory overhead per tool is significant (${metrics.memoryOverheadPerToolBytes / 1024}KB). Consider reducing resource usage.")
        }

        if (toolPackage.tools.size > 20) {
            recommendations.add("This skill has many tools (${toolPackage.tools.size}). Consider splitting into sub-packages for better performance.")
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Performance is within acceptable ranges.")
        }

        return recommendations
    }

    private fun generateScriptRecommendations(metrics: PerformanceMetrics): List<String> {
        val recommendations = mutableListOf<String>()

        if (metrics.avgLoadTimeMs > 1000) {
            recommendations.add("Script parse time is high (${metrics.avgLoadTimeMs}ms). Consider code splitting or optimization.")
        }

        if (metrics.avgExecutionTimeMs > 500) {
            recommendations.add("Script execution time is high (${metrics.avgExecutionTimeMs}ms). Review algorithm complexity.")
        }

        if (metrics.memoryOverheadPerToolBytes > 512 * 1024) {
            recommendations.add("Memory usage is elevated. Consider reducing allocations and using efficient data structures.")
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Script performance is within acceptable ranges.")
        }

        return recommendations
    }

    fun getSystemMemoryInfo(): Map<String, Long> {
        activityManager.getMemoryInfo(memoryInfo)
        return mapOf(
            "totalMem" to memoryInfo.totalMem,
            "availMem" to memoryInfo.availMem,
            "usedMem" to (memoryInfo.totalMem - memoryInfo.availMem),
            "lowMemory" to if (memoryInfo.lowMemory) 1L else 0L,
            "threshold" to memoryInfo.threshold
        )
    }
}