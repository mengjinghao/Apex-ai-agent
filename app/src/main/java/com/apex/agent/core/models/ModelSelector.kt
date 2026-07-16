package com.apex.agent.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.apex.util.AppLogger

class ModelSelector(private val context: Context) {

    private const val TAG = "ModelSelector"

    enum class SelectionCriteria {
        SPEED,
        QUALITY,
        BALANCED,
        MEMORY_EFFICIENCY
    }

    data class DeviceCapabilities(
        val totalRAM: Long,
        val availableRAM: Long,
        val cpuCores: Int,
        val isHighEnd: Boolean,
        val supportsGPU: Boolean
    ) {
        companion object {
            fun detect(context: Context): DeviceCapabilities {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)

                val totalRAM = memInfo.totalMem
                val availableRAM = memInfo.availMem
                val cpuCores = Runtime.getRuntime().availableProcessors()

                val isHighEnd = when {
                    totalRAM >= 12 * 1024 * 1024 * 1024 -> true
                    cpuCores >= 8 && totalRAM >= 8 * 1024 * 1024 * 1024 -> true
                    else -> false
                }

                return DeviceCapabilities(
                    totalRAM = totalRAM,
                    availableRAM = availableRAM,
                    cpuCores = cpuCores,
                    isHighEnd = isHighEnd,
                    supportsGPU = detectGPUAcceleration()
                )
            }

            private fun detectGPUAcceleration(): Boolean {
                return try {
                    val gpuInfo = StringBuilder()
                    val process = Runtime.getRuntime().exec("getprop ro.hardware")
                    val reader = process.inputStream.bufferedReader()
                    val hardware = reader.readLine() ?: ""
                    reader.close()
                    process.destroy()

                    val knownGPUHardware = listOf(
                        "adreno", "mali", "powervr", "tegra", "apple", "samsung", "huawei"
                    )
                    knownGPUHardware.any { gpu ->
                        hardware.lowercase().contains(gpu) ||
                        System.getProperty("ro.hardware", "").lowercase().contains(gpu)
                    }
                } catch (e: Exception) {
                    false
                }
            }
        }
    }

    data class SelectionResult(
        val recommendedModel: ModelRegistry.LocalModelInfo?,
        val alternativeModels: List<ModelRegistry.LocalModelInfo>,
        val reasoning: String
    )

    fun selectOptimalModel(
        taskType: TaskType,
        criteria: SelectionCriteria = SelectionCriteria.BALANCED
    ): SelectionResult {
        val capabilities = DeviceCapabilities.detect(context)
        val allModels = ModelRegistry.getAllLocalModels()

        if (allModels.isEmpty()) {
            return SelectionResult(
                recommendedModel = null,
                alternativeModels = emptyList(),
                reasoning = "未找到本地模型，请下载模型后重试"
            )
        }

        val complexity = when (taskType) {
            TaskType.SIMPLE_QA -> ModelRegistry.TaskComplexity.SIMPLE
            TaskType.CODE_GENERATION,
            TaskType.SUMMARIZATION,
            TaskType.TRANSLATION -> ModelRegistry.TaskComplexity.MODERATE
            TaskType.COMPLEXReasoning,
            TaskType.CREATIVE_WRITING,
            TaskType.ANALYSIS -> ModelRegistry.TaskComplexity.COMPLEX
        }

        val suggestedQuant = ModelRegistry.suggestQuantizationForDevice(
            capabilities.availableRAM,
            complexity
        )

        val filteredModels = allModels.filter { model ->
            if (model.quantization == null) true
            else {
                val modelBits = model.quantization.bits
                val suggestedBits = suggestedQuant.bits
                when (criteria) {
                    SelectionCriteria.SPEED, SelectionCriteria.MEMORY_EFFICIENCY -> modelBits <= suggestedBits
                    SelectionCriteria.QUALITY -> true
                    SelectionCriteria.BALANCED -> modelBits <= suggestedBits + 1
                }
            }
        }.ifEmpty { allModels }

        val sortedModels = when (criteria) {
            SelectionCriteria.SPEED -> filteredModels.sortedBy { it.quantization?.bits ?: 8 }
            SelectionCriteria.QUALITY -> filteredModels.sortedByDescending { it.quantization?.bits ?: 0 }
            SelectionCriteria.BALANCED -> filteredModels.sortedBy {
                val bits = it.quantization?.bits ?: 8
                val sizeScore = kotlin.math.ln(it.sizeBytes.toDouble().coerceAtLeast(1.0))
                (bits * 10 + sizeScore).toInt()
            }
            SelectionCriteria.MEMORY_EFFICIENCY -> filteredModels.sortedBy { it.sizeBytes }
        }

        val recommended = sortedModels.firstOrNull()
        val alternatives = sortedModels.drop(1).take(3)

        val reasoning = buildString {
            append("设备内存: ${formatRAM(capabilities.totalRAM)} (可用: ${formatRAM(capabilities.availableRAM)})")
            appendLine()
            append("CPU核心: ${capabilities.cpuCores}")
            appendLine()
            append("任务类型: ${taskType.displayName}")
            appendLine()
            append("选择策略: ${criteria.name}")
            appendLine()
            append("推荐量化格式: ${suggestedQuant.displayName} (${suggestedQuant.bits}bit)")
            appendLine()
            if (recommended != null) {
                append("最终选择: ${recommended.name}")
                append(" [${recommended.displayQuantization}]")
                append(" - ${recommended.displaySize}")
            } else {
                append("未找到符合条件的模型")
            }
        }

        AppLogger.d(TAG, "模型选择结果: ${reasoning}")

        return SelectionResult(
            recommendedModel = recommended,
            alternativeModels = alternatives,
            reasoning = reasoning
        )
    }

    fun getModelForPrompt(
        promptLength: Int,
        historySize: Int,
        hasTools: Boolean
    ): SelectionResult {
        val taskType = when {
            hasTools -> TaskType.CODE_GENERATION
            promptLength < 200 && historySize < 5 -> TaskType.SIMPLE_QA
            promptLength < 500 -> TaskType.SUMMARIZATION
            else -> TaskType.COMPLEXReasoning
        }

        val criteria = when {
            hasTools -> SelectionCriteria.SPEED
            promptLength > 1000 -> SelectionCriteria.MEMORY_EFFICIENCY
            else -> SelectionCriteria.BALANCED
        }

        return selectOptimalModel(taskType, criteria)
    }

    enum class TaskType(val displayName: String) {
        SIMPLE_QA("简单问�?),
        CODE_GENERATION("代码生成"),
        SUMMARIZATION("摘要总结"),
        TRANSLATION("翻译"),
        COMPLEXReasoning("复杂推理"),
        CREATIVE_WRITING("创意写作"),
        ANALYSIS("分析")
    }

    private fun formatRAM(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format("%.1f GB", gb)
    }
}