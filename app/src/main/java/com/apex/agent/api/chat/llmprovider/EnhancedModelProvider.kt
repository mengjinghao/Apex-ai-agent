package com.apex.agent.api.chat.llmprovider

import android.content.Context
import com.apex.agent.core.models.LoRATuner
import com.apex.agent.core.models.ModelPerformanceOptimizer
import com.apex.agent.core.models.ModelRegistry
import com.apex.agent.core.models.ModelSelector
import com.apex.util.AppLogger

object EnhancedModelProvider {

    private const val TAG = "EnhancedModelProvider"

    fun getEnhancedModelList(): List<ModelRegistry.LocalModelInfo> {
        return ModelRegistry.getAllLocalModels()
    }

    fun getLlamaModelsEnhanced(): List<ModelRegistry.LocalModelInfo> {
        return ModelRegistry.getLlamaModels()
    }

    fun getMNNModelsEnhanced(): List<ModelRegistry.LocalModelInfo> {
        return ModelRegistry.getMNNModels()
    }

    fun getModelSelector(context: Context): ModelSelector {
        return ModelSelector(context)
    }

    fun selectOptimalModel(
        context: Context,
        taskType: ModelSelector.TaskType,
        criteria: ModelSelector.SelectionCriteria = ModelSelector.SelectionCriteria.BALANCED
    ): ModelSelector.SelectionResult {
        val selector = ModelSelector(context)
        return selector.selectOptimalModel(taskType, criteria)
    }

    fun getPerformanceOptimizer(context: Context): ModelPerformanceOptimizer {
        return ModelPerformanceOptimizer(context)
    }

    fun getOptimalConfig(context: Context): ModelPerformanceOptimizer.PerformanceConfig {
        return ModelPerformanceOptimizer(context).optimizeForDevice()
    }

    fun getLoRATuner(context: Context): LoRATuner {
        return LoRATuner(context)
    }

    fun getLoRAModels(context: Context): List<LoRATuner.LoRAModel> {
        return LoRATuner(context).getLoRAModels()
    }

    fun getQuantizationFormats(): List<ModelRegistry.QuantizationFormat> {
        return ModelRegistry.QuantizationFormat.entries
    }

    fun getDeviceCapabilities(context: Context): ModelSelector.DeviceCapabilities {
        return ModelSelector.DeviceCapabilities.detect(context)
    }

    fun suggestQuantization(context: Context, taskComplexity: ModelRegistry.TaskComplexity): ModelRegistry.QuantizationFormat {
        val capabilities = ModelSelector.DeviceCapabilities.detect(context)
        return ModelRegistry.suggestQuantizationForDevice(capabilities.totalRAM, taskComplexity)
    }

    fun getQuickSettings(context: Context): Map<String, Any> {
        return ModelPerformanceOptimizer(context).getQuickSettings()
    }

    fun getModelInfoSummary(): String {
        val allModels = ModelRegistry.getAllLocalModels()
        val llamaModels = allModels.filter { it.isLlama }
        val mnnModels = allModels.filter { it.isMNN }

        val totalSize = allModels.sumOf { it.sizeBytes }
        val totalSizeFormatted = ModelRegistry.LocalModelInfo.formatFileSize(totalSize)

        val quantizationCounts = allModels
            .mapNotNull { it.quantization }
            .groupingBy { it }
            .eachCount()

        return buildString {
            appendLine("=== 本地模型概览 ===")
            appendLine("模型总数: ${allModels.size}")
            appendLine("  - Llama.cpp: ${llamaModels.size}")
            appendLine("  - MNN: ${mnnModels.size}")
            appendLine("总大? ${totalSizeFormatted}")
            appendLine()
            appendLine("量化格式分布:")
            quantizationCounts.forEach { (format, count) ->
                appendLine("  ${format.displayName}: ${count}")
            }
        }
    }

    fun benchmarkCurrentConfig(
        context: Context,
        currentThreads: Int,
        currentBatchSize: Int,
        currentGpuLayers: Int
    ): ModelPerformanceOptimizer.OptimizationResult {
        val optimizer = ModelPerformanceOptimizer(context)
        val currentConfig = ModelPerformanceOptimizer.PerformanceConfig(
            threadCount = currentThreads,
            batchSize = currentBatchSize,
            gpuLayers = currentGpuLayers,
            useMemoryMapping = true,
            useMemoryLock = false,
            contextSize = 4096
        )
        return optimizer.benchmarkAndSuggest(currentConfig)
    }
}