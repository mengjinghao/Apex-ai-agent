package com.apex.core.tools

import com.apex.api.voice.HttpTtsResponsePipelineStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale


/**
 * Model domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class ModelConfigResultItem(
    val id: String,
    val name: String,
    val apiProviderType: String,
    val apiEndpoint: String,
    val modelName: String,
    val modelList: List<String>,
    val apiKeySet: Boolean,
    val apiKeyPreview: String,
    val maxTokensEnabled: Boolean,
    val maxTokens: Int,
    val temperatureEnabled: Boolean,
    val temperature: Float,
    val topPEnabled: Boolean,
    val topP: Float,
    val topKEnabled: Boolean,
    val topK: Int,
    val presencePenaltyEnabled: Boolean,
    val presencePenalty: Float,
    val frequencyPenaltyEnabled: Boolean,
    val frequencyPenalty: Float,
    val repetitionPenaltyEnabled: Boolean,
    val repetitionPenalty: Float,
    val hasCustomParameters: Boolean,
    val customParameters: String,
    val hasCustomHeaders: Boolean,
    val customHeaders: String,
    val contextLength: Float,
    val maxContextLength: Float,
    val enableMaxContextMode: Boolean,
    val summaryTokenThreshold: Float,
    val enableSummary: Boolean,
    val enableSummaryByMessageCount: Boolean,
    val summaryMessageCountThreshold: Int,
    val mnnForwardType: Int,
    val mnnThreadCount: Int,
    val llamaThreadCount: Int,
    val llamaContextSize: Int,
    val llamaBatchSize: Int,
    val llamaUBatchSize: Int,
    val llamaGpuLayers: Int,
    val llamaUseMmap: Boolean,
    val llamaFlashAttention: Boolean,
    val llamaKvUnified: Boolean,
    val llamaOffloadKqv: Boolean,
    val enableDirectImageProcessing: Boolean,
    val enableDirectAudioProcessing: Boolean,
    val enableDirectVideoProcessing: Boolean,
    val enableGoogleSearch: Boolean,
    val enableToolCall: Boolean,
    val requestLimitPerMinute: Int,
    val maxConcurrentRequests: Int,
    val useMultipleApiKeys: Boolean,
    val apiKeyPoolCount: Int
)

/** 功能模型绑定条目 */

@Serializable
data class FunctionModelMappingResultItem(
    val functionType: String,
    val configId: String,
    val configName: String? = null,
    val modelIndex: Int,
    val actualModelIndex: Int? = null,
    val selectedModel: String? = null
)

/** 列出模型配置结果 */

@Serializable
data class ModelConfigsResultData(
    val totalConfigCount: Int,
    val defaultConfigId: String,
    val configs: List<ModelConfigResultItem>,
    val functionMappings: List<FunctionModelMappingResultItem>
) : ToolResultData() {
    override fun toString(): String {
        return "Model configs: ${totalConfigCount}, bindings: ${functionMappings.size}"
    }
}

/** 创建模型配置结果 */

@Serializable
data class ModelConfigCreateResultData(
    val created: Boolean,
    val config: ModelConfigResultItem,
    val changedFields: List<String>
) : ToolResultData() {
    override fun toString(): String {
        return "Model config created: ${config.id} (${config.name})"
    }
}

/** 更新模型配置结果 */

@Serializable
data class ModelConfigUpdateResultData(
    val updated: Boolean,
    val config: ModelConfigResultItem,
    val changedFields: List<String>,
    val affectedFunctions: List<String>
) : ToolResultData() {
    override fun toString(): String {
        return "Model config updated: ${config.id}, changed=${changedFields.size}, affectedFunctions=${affectedFunctions.size}"
    }
}

/** 删除模型配置结果 */

@Serializable
data class ModelConfigDeleteResultData(
    val deleted: Boolean,
    val configId: String,
    val affectedFunctions: List<String>,
    val fallbackConfigId: String
) : ToolResultData() {
    override fun toString(): String {
        return "Model config deleted: ${configId}, affectedFunctions=${affectedFunctions.size}"
    }
}

/** 列出功能模型绑定结果 */

@Serializable
data class FunctionModelConfigsResultData(
    val defaultConfigId: String,
    val mappings: List<FunctionModelMappingResultItem>
) : ToolResultData() {
    override fun toString(): String {
        return "Function model bindings: ${mappings.size}"
    }
}

/** 查询单个功能模型绑定结果 */

@Serializable
data class FunctionModelConfigResultData(
    val defaultConfigId: String,
    val functionType: String,
    val configId: String,
    val configName: String,
    val modelIndex: Int,
    val actualModelIndex: Int,
    val selectedModel: String,
    val config: ModelConfigResultItem
) : ToolResultData() {
    override fun toString(): String {
        return "Function model config: ${functionType} -> ${configId}[${actualModelIndex}]"
    }
}

/** 设置功能模型绑定结果 */

@Serializable
data class FunctionModelBindingResultData(
    val functionType: String,
    val configId: String,
    val configName: String,
    val requestedModelIndex: Int,
    val actualModelIndex: Int,
    val selectedModel: String
) : ToolResultData() {
    override fun toString(): String {
        return "Function binding updated: ${functionType} -> ${configId}[${actualModelIndex}]"
    }
}

/** 模型配置连接测试单项 */

@Serializable
data class ModelConfigConnectionTestItemResultData(
    val type: String,
    val success: Boolean,
    val error: String? = null
)

/** 模型配置连接测试结果 */

@Serializable
data class ModelConfigConnectionTestResultData(
    val configId: String,
    val configName: String,
    val providerType: String,
    val requestedModelIndex: Int,
    val actualModelIndex: Int,
    val testedModelName: String,
    val success: Boolean,
    val totalTests: Int,
    val passedTests: Int,
    val failedTests: Int,
    val tests: List<ModelConfigConnectionTestItemResultData>
) : ToolResultData() {
    override fun toString(): String {
        return "Model config connection test: ${configId}, success=${success}, passed=${passedTests}/${totalTests}"
    }
}

