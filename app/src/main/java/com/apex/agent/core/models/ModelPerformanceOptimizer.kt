package com.apex.agent.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.apex.util.AppLogger

class ModelPerformanceOptimizer(private val context: Context) {

    private const val TAG = "ModelOptimizer"

    data class PerformanceConfig(
        val threadCount: Int,
        val batchSize: Int,
        val gpuLayers: Int,
        val useMemoryMapping: Boolean,
        val useMemoryLock: Boolean,
        val contextSize: Int
    )

    data class OptimizationResult(
        val config: PerformanceConfig,
        val estimatedSpeedUp: Float,
        val memoryUsage: String,
        val recommendations: List<String>
    )

    fun optimizeForDevice(): PerformanceConfig {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRAM = memInfo.totalMem
        val availableRAM = memInfo.availMem
        val cpuCores = Runtime.getRuntime().availableProcessors()

        val isHighEndDevice = totalRAM >= 8 * 1024 * 1024 * 1024 && cpuCores >= 6

        val threadCount = when {
            cpuCores <= 2 -> 2
            cpuCores <= 4 -> cpuCores - 1
            cpuCores <= 8 -> cpuCores - 2
            else -> cpuCores - 3
        }.coerceIn(1, cpuCores)

        val batchSize = when {
            isHighEndDevice -> 512
            totalRAM >= 6 * 1024 * 1024 * 1024 -> 256
            else -> 128
        }

        val gpuLayers = detectOptimalGpuLayers()

        val useMemoryMapping = availableRAM > 4 * 1024 * 1024 * 1024

        val useMemoryLock = !useMemoryMapping && availableRAM > 2 * 1024 * 1024 * 1024

        val contextSize = when {
            totalRAM >= 16 * 1024 * 1024 * 1024 -> 8192
            totalRAM >= 8 * 1024 * 1024 * 1024 -> 4096
            totalRAM >= 4 * 1024 * 1024 * 1024 -> 2048
            else -> 1024
        }

        return PerformanceConfig(
            threadCount = threadCount,
            batchSize = batchSize,
            gpuLayers = gpuLayers,
            useMemoryMapping = useMemoryMapping,
            useMemoryLock = useMemoryLock,
            contextSize = contextSize
        )
    }

    private fun detectOptimalGpuLayers(): Int {
        return try {
            val memInfo = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memInfo)

            val totalRAM = memInfo.totalMem
            val gpuMemoryEstimate = when {
                Build.HARDWARE.contains("adreno", ignoreCase = true) -> {
                    when {
                        Build.HARDWARE.contains("adreno 6") || Build.HARDWARE.contains("adreno 7") -> 48
                        Build.HARDWARE.contains("adreno 5") -> 32
                        else -> 16
                    }
                }
                Build.HARDWARE.contains("mali", ignoreCase = true) -> {
                    when {
                        Build.HARDWARE.contains("mali-g") -> 48
                        Build.HARDWARE.contains("mali-t") -> 24
                        else -> 16
                    }
                }
                else -> 0
            }

            gpuMemoryEstimate
        } catch (e: Exception) {
            AppLogger.w(TAG, "无法检测GPU层数，使用默认�? ${e.message}")
            0
        }
    }

    fun benchmarkAndSuggest(
        currentConfig: PerformanceConfig,
        testPromptLength: Int = 500
    ): OptimizationResult {
        val optimal = optimizeForDevice()

        val improvements = mutableListOf<String>()

        if (currentConfig.threadCount != optimal.threadCount) {
            improvements.add("线程�? ${currentConfig.threadCount} -> ${optimal.threadCount}")
        }

        if (currentConfig.batchSize != optimal.batchSize) {
            improvements.add("批处理大�? ${currentConfig.batchSize} -> ${optimal.batchSize}")
        }

        if (currentConfig.gpuLayers != optimal.gpuLayers) {
            improvements.add("GPU层数: ${currentConfig.gpuLayers} -> ${optimal.gpuLayers}")
        }

        if (!currentConfig.useMemoryMapping && optimal.useMemoryMapping) {
            improvements.add("启用内存映射以提升速度")
        }

        if (currentConfig.contextSize != optimal.contextSize) {
            improvements.add("上下文大�? ${currentConfig.contextSize} -> ${optimal.contextSize}")
        }

        val estimatedSpeedUp = calculateSpeedUp(currentConfig, optimal)

        val memoryUsage = estimateMemoryUsage(optimal)

        return OptimizationResult(
            config = optimal,
            estimatedSpeedUp = estimatedSpeedUp,
            memoryUsage = memoryUsage,
            recommendations = improvements
        )
    }

    private fun calculateSpeedUp(current: PerformanceConfig, optimal: PerformanceConfig): Float {
        var speedUp = 1.0f

        if (current.threadCount < optimal.threadCount) {
            speedUp *= 1.1f
        }

        if (current.gpuLayers < optimal.gpuLayers) {
            speedUp *= 1.2f
        }

        if (!current.useMemoryMapping && optimal.useMemoryMapping) {
            speedUp *= 1.15f
        }

        if (current.batchSize < optimal.batchSize) {
            speedUp *= 1.05f
        }

        return speedUp
    }

    private fun estimateMemoryUsage(config: PerformanceConfig): String {
        val baseMemory = config.contextSize * 4L
        val tensorMemory = config.batchSize * 2L * config.gpuLayers
        val totalMemory = baseMemory + tensorMemory

        return when {
            totalMemory < 1024 * 1024 * 1024 -> "${totalMemory / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", totalMemory / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun getQuickSettings(): Map<String, Any> {
        val config = optimizeForDevice()
        return mapOf(
            "threads" to config.threadCount,
            "batch_size" to config.batchSize,
            "gpu_layers" to config.gpuLayers,
            "context_size" to config.contextSize,
            "use_mmap" to config.useMemoryMapping,
            "use_mlock" to config.useMemoryLock
        )
    }
}