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
 * Speech domain result data classes.
 * Split from ToolResultDataClasses.kt for maintainability.
 * Package kept as [com.apex.core.tools] to avoid breaking existing imports.
 */

@Serializable
data class SpeechTtsHttpConfigResultItem(
    val urlTemplate: String,
    val apiKeySet: Boolean,
    val apiKeyPreview: String,
    val headers: Map<String, String>,
    val httpMethod: String,
    val requestBody: String,
    val contentType: String,
    val voiceId: String,
    val modelName: String,
    val responsePipeline: List<HttpTtsResponsePipelineStep>
)

/** 语音服务 STT HTTP 配置条目 */

@Serializable
data class SpeechSttHttpConfigResultItem(
    val endpointUrl: String,
    val apiKeySet: Boolean,
    val apiKeyPreview: String,
    val modelName: String
)

/** 获取语音服务配置结果 */

@Serializable
data class SpeechServicesConfigResultData(
    val ttsServiceType: String,
    val ttsHttpConfig: SpeechTtsHttpConfigResultItem,
    val ttsCleanerRegexs: List<String>,
    val ttsSpeechRate: Float,
    val ttsPitch: Float,
    val sttServiceType: String,
    val sttHttpConfig: SpeechSttHttpConfigResultItem
) : ToolResultData() {
    override fun toString(): String {
        return "Speech services config: TTS=${ttsServiceType}, STT=${sttServiceType}"
    }
}

/** 更新语音服务配置结果 */

@Serializable
data class SpeechServicesUpdateResultData(
    val updated: Boolean,
    val changedFields: List<String>,
    val ttsServiceType: String,
    val sttServiceType: String,
    val ttsApiKeySet: Boolean,
    val sttApiKeySet: Boolean
) : ToolResultData() {
    override fun toString(): String {
        return "Speech services updated: changed=${changedFields.size}, TTS=${ttsServiceType}, STT=${sttServiceType}"
    }
}

/** TTS 单次播放测试结果 */

@Serializable
data class SpeechServicesTtsPlaybackTestResultData(
    val ttsServiceType: String,
    val providerClass: String,
    val initialized: Boolean,
    val playbackTriggered: Boolean,
    val interrupt: Boolean,
    val textLength: Int,
    val speechRate: Float,
    val pitch: Float,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val httpStatusCode: Int? = null,
    val errorBody: String? = null,
    val causeMessage: String? = null
) : ToolResultData() {
    override fun toString(): String {
        return "TTS playback test: type=${ttsServiceType}, initialized=${initialized}, triggered=${playbackTriggered}"
    }
}

/** 模型配置条目 */

