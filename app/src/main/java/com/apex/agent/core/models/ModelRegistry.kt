package com.apex.agent.core.models

import android.content.Context
import android.os.Environment
import com.apex.data.model.ModelOption
import com.apex.util.AppLogger
import java.io.File

object ModelRegistry {

    private const val TAG = "ModelRegistry"

    enum class QuantizationFormat(
        val displayName: String,
        val bits: Int,
        val suffix: String
    ) {
        Q2_K("Q2_K", 2, "q2_k"),
        Q3_K_S("Q3_K Small", 3, "q3_k_s"),
        Q3_K_M("Q3_K Medium", 3, "q3_k_m"),
        Q3_K_L("Q3_K Large", 3, "q3_k_l"),
        Q4_0("Q4_0 (Legacy)", 4, "q4_0"),
        Q4_1_S("Q4_1 Small (Legacy)", 4, "q4_1_s"),
        Q4_1_M("Q4_1 Medium (Legacy)", 4, "q4_1_m"),
        Q4_K_S("Q4_K Small", 4, "q4_k_s"),
        Q4_K_M("Q4_K Medium", 4, "q4_k_m"),
        Q5_0("Q5_0 (Legacy)", 5, "q5_0"),
        Q5_1_S("Q5_1 Small (Legacy)", 5, "q5_1_s"),
        Q5_1_M("Q5_1 Medium (Legacy)", 5, "q5_1_m"),
        Q5_K_S("Q5_K Small", 5, "q5_k_s"),
        Q5_K_M("Q5_K Medium", 5, "q5_k_m"),
        Q6_K("Q6_K", 6, "q6_k"),
        Q8_0("Q8_0 (Full)", 8, "q8_0"),
        F16("F16 (Half)", 16, "f16"),
        F32("F32 (Float)", 32, "f32")
    }

    data class LocalModelInfo(
        val id: String,
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val quantization: QuantizationFormat?,
        val isLlama: Boolean,
        val isMNN: Boolean,
        val isGGUF: Boolean = true
    ) {
        val displaySize: String
            get() = formatFileSize(sizeBytes)

        val displayQuantization: String
            get() = quantization?.displayName ?: "Unknown"

        companion object {
            fun formatFileSize(sizeBytes: Long): String {
                return when {
                    sizeBytes < 1024 -> "${sizeBytes} B"
                    sizeBytes < 1024 * 1024 -> String.format("%.2f KB", sizeBytes / 1024.0)
                    sizeBytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", sizeBytes / (1024.0 * 1024.0))
                    else -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
                }
            }
        }
    }

    private val llamaModelsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Apex/models/llama"
        )

    private val mnnModelsDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Apex/models/mnn"
        )

    fun detectQuantization(fileName: String): QuantizationFormat? {
        val lowerName = fileName.lowercase()
        return QuantizationFormat.entries.find { format ->
            lowerName.contains(format.suffix) ||
            lowerName.contains("_${format.bits}") ||
            lowerName.contains("-${format.bits}")
        }
    }

    fun getLlamaModels(): List<LocalModelInfo> {
        val models = mutableListOf<LocalModelInfo>()

        if (!llamaModelsDir.exists()) {
            AppLogger.w(TAG, "Llama模型目录不存�? ${llamaModelsDir.absolutePath}")
            return models
        }

        llamaModelsDir.listFiles { file -> file.isFile && file.name.lowercase().endsWith(".gguf") }
            ?.forEach { file ->
                val quantization = detectQuantization(file.name)
                models.add(
                    LocalModelInfo(
                        id = file.name,
                        name = file.nameWithoutExtension,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        quantization = quantization,
                        isLlama = true,
                        isMNN = false,
                        isGGUF = true
                    )
                )
            }

        AppLogger.d(TAG, "找到 ${models.size} 个Llama模型")
        return models.sortedByDescending { it.sizeBytes }
    }

    fun getMNNModels(): List<LocalModelInfo> {
        val models = mutableListOf<LocalModelInfo>()

        if (!mnnModelsDir.exists()) {
            AppLogger.w(TAG, "MNN模型目录不存�? ${mnnModelsDir.absolutePath}")
            return models
        }

        mnnModelsDir.listFiles { file -> file.isDirectory }
            ?.forEach { folder ->
                val mnnFile = File(folder, "llm.mnn")
                if (mnnFile.exists()) {
                    val totalSize = folder.listFiles()?.sumOf { it.length() } ?: 0L
                    models.add(
                        LocalModelInfo(
                            id = folder.name,
                            name = folder.name,
                            path = folder.absolutePath,
                            sizeBytes = totalSize,
                            quantization = null,
                            isLlama = false,
                            isMNN = true,
                            isGGUF = false
                        )
                    )
                }
            }

        AppLogger.d(TAG, "找到 ${models.size} 个MNN模型")
        return models.sortedByDescending { it.sizeBytes }
    }

    fun getAllLocalModels(): List<LocalModelInfo> {
        return (getLlamaModels() + getMNNModels()).sortedByDescending { it.sizeBytes }
    }

    fun getModelOptions(): List<ModelOption> {
        return getAllLocalModels().map { model ->
            val sizeStr = model.displaySize
            val quantStr = if (model.quantization != null) " [${model.quantization.displayName}]" else ""
            val typeStr = when {
                model.isLlama -> " (llama.cpp)"
                model.isMNN -> " (MNN)"
                else -> ""
            }
            ModelOption(
                id = model.id,
                name = "${model.name}${quantStr} - ${sizeStr}${typeStr}"
            )
        }
    }

    fun suggestQuantizationForDevice(deviceRAM: Long, taskComplexity: TaskComplexity): QuantizationFormat {
        val ramGB = deviceRAM / (1024.0 * 1024.0 * 1024.0)

        return when {
            ramGB < 4 -> when (taskComplexity) {
                TaskComplexity.SIMPLE -> QuantizationFormat.Q2_K
                TaskComplexity.MODERATE -> QuantizationFormat.Q3_K_M
                TaskComplexity.COMPLEX -> QuantizationFormat.Q4_K_M
            }
            ramGB < 8 -> when (taskComplexity) {
                TaskComplexity.SIMPLE -> QuantizationFormat.Q3_K_M
                TaskComplexity.MODERATE -> QuantizationFormat.Q4_K_M
                TaskComplexity.COMPLEX -> QuantizationFormat.Q5_K_M
            }
            ramGB < 16 -> when (taskComplexity) {
                TaskComplexity.SIMPLE -> QuantizationFormat.Q4_K_M
                TaskComplexity.MODERATE -> QuantizationFormat.Q5_K_M
                TaskComplexity.COMPLEX -> QuantizationFormat.Q6_K
            }
            else -> when (taskComplexity) {
                TaskComplexity.SIMPLE -> QuantizationFormat.Q5_K_M
                TaskComplexity.MODERATE -> QuantizationFormat.Q6_K
                TaskComplexity.COMPLEX -> QuantizationFormat.Q8_0
            }
        }
    }

    enum class TaskComplexity {
        SIMPLE,
        MODERATE,
        COMPLEX
    }
}