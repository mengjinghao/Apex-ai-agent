package com.apex.lib.voice
import com.apex.sdk.common.BridgeResult

import kotlinx.coroutines.flow.SharedFlow

/**
 * ASR 网关契约。
 *
 * lib 不直接依赖 Android `SpeechRecognizer`，而是定义本接口；
 * APK 侧负责将其映射到具体的 ASR 实现（系统识别 / 第三方 SDK / 云端 ASR）。
 *
 * **生命周期**：
 *   1. [initialize] 准备 ASR 引擎
 *   2. [startListening] 开始监听，partial / final 结果通过 [results] 暴露
 *   3. [stopListening] 主动停止
 *   4. [shutdown] 释放资源
 *
 * **事件**：识别结果通过 [results] 流式输出，lib 侧聚合成 [VoiceEvent]。
 */
interface AsrGateway {

    /** ASR 识别结果流。 */
    val results: SharedFlow<AsrResult>

    /** 当前是否正在监听。 */
    fun isListening(): Boolean

    /**
     * 初始化 ASR 引擎。
     *
     * @param config 语音配置
     * @return BridgeResult 成功 / 失败
     */
    suspend fun initialize(config: VoiceConfig): BridgeResult<kotlin.Unit>

    /**
     * 开始监听语音。
     *
     * @param request 识别请求
     * @return BridgeResult 包含 utteranceId（本次识别会话标识）
     */
    suspend fun startListening(request: AsrRequest): BridgeResult<String>

    /**
     * 停止监听（不再接收新音频，但已缓冲的 final 结果仍可能回调）。
     */
    suspend fun stopListening(utteranceId: String? = null): BridgeResult<kotlin.Unit>

    /**
     * 取消当前识别（丢弃所有结果）。
     */
    suspend fun cancel(utteranceId: String? = null): BridgeResult<kotlin.Unit>

    /**
     * 列出可用的 ASR 模型 / 引擎。
     */
    suspend fun listModels(): BridgeResult<List<AsrModelInfo>>

    /** 释放 ASR 资源。 */
    fun shutdown()
}

/**
 * ASR 识别请求。
 *
 * @property sessionId 关联的语音会话 ID
 * @property language 语言（如 "zh-CN"）
 * @property model ASR 模型名（如 "default" / "cloud"）
 * @property partialResults 是否返回中间结果
 * @property maxAlternatives 候选结果上限
 * @property enablePunctuation 是否启用自动标点
 * @property sampleRate 音频采样率（Hz）
 * @property extras 业务自定义参数
 */
data class AsrRequest(
    val sessionId: String,
    val language: String = "zh-CN",
    val model: String = "default",
    val partialResults: Boolean = true,
    val maxAlternatives: Int = 1,
    val enablePunctuation: Boolean = true,
    val sampleRate: Int = 16_000,
    val extras: Map<String, String> = emptyMap()
)

/**
 * ASR 识别结果。
 */
sealed class AsrResult {
    /** 识别器就绪。 */
    data class Ready(val utteranceId: String, val sessionId: String) : AsrResult()

    /** 检测到语音开始。 */
    data class SpeechStarted(val utteranceId: String, val sessionId: String) : AsrResult()

    /** 中间识别结果（partial）。 */
    data class Partial(
        val utteranceId: String,
        val sessionId: String,
        val text: String,
        val confidence: Float = 0.0f
    ) : AsrResult()

    /** 最终识别结果（final）。 */
    data class Final(
        val utteranceId: String,
        val sessionId: String,
        val text: String,
        val confidence: Float = 1.0f,
        val alternatives: List<String> = emptyList()
    ) : AsrResult()

    /** 检测到语音结束。 */
    data class SpeechEnded(val utteranceId: String, val sessionId: String) : AsrResult()

    /** 识别出错。 */
    data class Error(val utteranceId: String, val sessionId: String, val code: String, val message: String) : AsrResult()
}

/** ASR 模型 / 引擎信息。 */
data class AsrModelInfo(
    val id: String,
    val displayName: String,
    val languages: List<String>,
    val supportsStreaming: Boolean = true,
    val requiresNetwork: Boolean = false,
    val description: String = ""
)
