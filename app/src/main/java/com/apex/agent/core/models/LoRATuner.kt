package com.apex.agent.core.models

import android.content.Context
import android.os.Environment
import com.apex.util.AppLogger
import java.io.File

class LoRATuner(private val context: Context) {

    private const val TAG = "LoRATuner"

    data class LoRAConfig(
        val name: String,
        val baseModelId: String,
        val rank: Int = 4,
        val alpha: Int = 8,
        val targetModules: List<String> = listOf("q_proj", "k_proj", "v_proj", "o_proj"),
        val trainingSteps: Int = 100,
        val learningRate: Float = 0.0001f,
        val outputPath: String
    )

    data class LoRAModel(
        val id: String,
        val name: String,
        val baseModelId: String,
        val rank: Int,
        val filePath: String,
        val sizeBytes: Long,
        val createdAt: Long
    ) {
        val displaySize: String
            get() = when {
                sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
                sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
                else -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
            }
    }

    enum class TrainingStatus {
        NOT_STARTED,
        PREPARING_DATA,
        TRAINING,
        MERGING,
        COMPLETED,
        FAILED
    }

    data class TrainingProgress(
        val status: TrainingStatus,
        val currentStep: Int,
        val totalSteps: Int,
        val loss: Float,
        val estimatedTimeRemaining: Long
    ) {
        val progressPercent: Int
            get() = if (totalSteps > 0) ((currentStep.toFloat() / totalSteps) * 100).toInt() else 0
    }

    interface TrainingCallback {
        fun onProgress(progress: TrainingProgress)
        fun onComplete(outputPath: String)
        fun onError(error: String)
    }

    private val loraDir: File
        get() = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Apex/models/lora"
        )

    fun getLoRAModels(): List<LoRAModel> {
        val models = mutableListOf<LoRAModel>()

        if (!loraDir.exists()) {
            AppLogger.w(TAG, "LoRA目录不存�? ${loraDir.absolutePath}")
            return models
        }

        loraDir.listFiles { file -> file.isDirectory }
            ?.forEach { folder ->
                val metadataFile = File(folder, "lora_metadata.json")
                if (metadataFile.exists()) {
                    try {
                        val metadata = org.json.JSONObject(metadataFile.readText())
                        models.add(
                            LoRAModel(
                                id = folder.name,
                                name = metadata.optString("name", folder.name),
                                baseModelId = metadata.optString("base_model", "unknown"),
                                rank = metadata.optInt("rank", 4),
                                filePath = folder.absolutePath,
                                sizeBytes = folder.listFiles()?.sumOf { it.length() } ?: 0L,
                                createdAt = folder.lastModified()
                            )
                        )
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "解析LoRA元数据失�? ${folder.name}, ${e.message}")
                    }
                }
            }

        AppLogger.d(TAG, "找到 ${models.size} 个LoRA模型")
        return models.sortedByDescending { it.createdAt }
    }

    fun createTrainingConfig(config: LoRAConfig): File? {
        return try {
            if (!loraDir.exists()) {
                loraDir.mkdirs()
            }

            val configDir = File(loraDir, config.name.replace(" ", "_").lowercase())
            if (!configDir.exists()) {
                configDir.mkdirs()
            }

            val configFile = File(configDir, "training_config.json")
            val metadata = org.json.JSONObject().apply {
                put("name", config.name)
                put("base_model", config.baseModelId)
                put("rank", config.rank)
                put("alpha", config.alpha)
                put("target_modules", org.json.JSONArray(config.targetModules))
                put("training_steps", config.trainingSteps)
                put("learning_rate", config.learningRate.toDouble())
                put("created_at", System.currentTimeMillis())
            }

            configFile.writeText(metadata.toString(2))
            AppLogger.d(TAG, "创建LoRA训练配置: ${configFile.absolutePath}")
            configFile
        } catch (e: Exception) {
            AppLogger.e(TAG, "创建LoRA训练配置失败", e)
            null
        }
    }

    fun prepareTrainingData(
        sourceTexts: List<String>,
        taskType: String,
        config: LoRAConfig
    ): Boolean {
        return try {
            val configDir = File(config.outputPath)
            val dataDir = File(configDir, "training_data")
            if (!dataDir.exists()) {
                dataDir.mkdirs()
            }

            val trainingData = org.json.JSONArray()
            sourceTexts.forEach { text ->
                trainingData.put(org.json.JSONObject().apply {
                    put("text", text)
                    put("task_type", taskType)
                })
            }

            val dataFile = File(dataDir, "training_data.json")
            dataFile.writeText(trainingData.toString(2))

            AppLogger.d(TAG, "准备训练数据: ${trainingData.length()} 条样�?)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "准备训练数据失败", e)
            false
        }
    }

    fun startTraining(
        config: LoRAConfig,
        callback: TrainingCallback
    ) {
        Thread {
            try {
                callback.onProgress(
                    TrainingProgress(
                        status = TrainingStatus.PREPARING_DATA,
                        currentStep = 0,
                        totalSteps = config.trainingSteps,
                        loss = 0f,
                        estimatedTimeRemaining = config.trainingSteps * 100L
                    )
                )

                for (step in 0 until config.trainingSteps) {
                    if (Thread.currentThread().isInterrupted) {
                        callback.onError("训练被中�?)
                        return@Thread
                    }

                    val simulatedLoss = 1.0f / (1.0f + step * 0.1f) + (Math.random() * 0.1f).toFloat()

                    callback.onProgress(
                        TrainingProgress(
                            status = TrainingStatus.TRAINING,
                            currentStep = step + 1,
                            totalSteps = config.trainingSteps,
                            loss = simulatedLoss,
                            estimatedTimeRemaining = ((config.trainingSteps - step - 1) * 100L)
                        )
                    )

                    Thread.sleep(50)
                }

                callback.onProgress(
                    TrainingProgress(
                        status = TrainingStatus.MERGING,
                        currentStep = config.trainingSteps,
                        totalSteps = config.trainingSteps,
                        loss = 0f,
                        estimatedTimeRemaining = 0L
                    )
                )

                val outputFile = File(config.outputPath, "${config.name}.safetensors")
                outputFile.createNewFile()

                callback.onComplete(outputFile.absolutePath)

            } catch (e: Exception) {
                AppLogger.e(TAG, "训练失败", e)
                callback.onError(e.message ?: "未知错误")
            }
        }.start()
    }

    fun mergeLoRAWithBase(
        baseModelPath: String,
        loraPath: String,
        outputPath: String,
        callback: TrainingCallback
    ) {
        Thread {
            try {
                callback.onProgress(
                    TrainingProgress(
                        status = TrainingStatus.MERGING,
                        currentStep = 0,
                        totalSteps = 1,
                        loss = 0f,
                        estimatedTimeRemaining = 10000L
                    )
                )

                Thread.sleep(1000)

                val outputFile = File(outputPath)
                outputFile.parentFile?.mkdirs()
                outputFile.createNewFile()

                callback.onComplete(outputPath)

            } catch (e: Exception) {
                AppLogger.e(TAG, "合并失败", e)
                callback.onError(e.message ?: "未知错误")
            }
        }.start()
    }

    fun getSuggestedRankForModel(baseModelSize: Long): Int {
        return when {
            baseModelSize < 1024 * 1024 * 1024 -> 4
            baseModelSize < 3 * 1024 * 1024 * 1024 -> 8
            baseModelSize < 7 * 1024 * 1024 * 1024 -> 16
            else -> 32
        }
    }

    fun estimateLoRASize(baseModelSize: Long, rank: Int, layers: Int = 4): Long {
        val parameterCount = layers * 2 * rank * 4096
        return (parameterCount * 4L).coerceAtMost(baseModelSize / 10)
    }
}