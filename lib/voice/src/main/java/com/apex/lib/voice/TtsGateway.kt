package com.apex.lib.voice
import com.apex.sdk.common.BridgeResult

import kotlinx.coroutines.flow.SharedFlow

/**
 * TTS 网关契约。
 *
 * lib 不直接依赖 Android `TextToSpeech`，而是定义本接口；
 * APK 侧负责将其映射到具体的 TTS 实现（系统 TTS / 第三方 SDK / 云端 TTS）。
 *
 * **生命周期**：
 *   1. [initialize] 在会话首次使用前调用，准备 TTS 引擎
 *   2. [synthesize] 一次性合成完整音频
 *   3. [speak] 流式朗读（通过 [progress] 回调返回进度）
 *   4. [stop] 停止当前朗读
 *   5. [shutdown] 释放资源
 *
 * **事件**：所有朗读事件通过 [progress] 暴露，lib 侧再聚合成 [VoiceEvent]。
 */
interface TtsGateway {

    /** TTS 进度流。 */
    val progress: SharedFlow<TtsProgress>

    /**
     * 初始化 TTS 引擎。
     *
     * @param config 语音配置
     * @return BridgeResult 成功 / 失败
     */
    suspend fun initialize(config: VoiceConfig): BridgeResult<kotlin.Unit>

    /**
     * 一次性合成完整音频。
     *
     * @param request 合成请求
     * @return BridgeResult 包含 utteranceId（可后续用于停止 / 查询）
     */
    suspend fun synthesize(request: TtsRequest): BridgeResult<String>

    /**
     * 异步朗读（流式），立即返回 utteranceId；进度通过 [progress] 暴露。
     *
     * @param request 朗读请求
     * @return BridgeResult 包含 utteranceId
     */
    suspend fun speak(request: TtsRequest): BridgeResult<String>

    /** 停止指定 utterance 的朗读；utteranceId=null 表示停止所有。 */
    suspend fun stop(utteranceId: String? = null): BridgeResult<kotlin.Unit>

    /** 当前是否正在朗读。 */
    fun isSpeaking(): Boolean

    /** 列出可用的 TTS 语音（如 "zh-CN-XiaoxiaoNeural"）。 */
    suspend fun listVoices(language: String? = null): BridgeResult<List<TtsVoiceInfo>>

    /** 释放 TTS 资源。 */
    fun shutdown()
}

/**
 * TTS 合成 / 朗读请求。
 *
 * @property sessionId 关联的语音会话 ID
 * @property text 要朗读的文本
 * @property language 语言（如 "zh-CN"），null 表示使用会话默认
 * @property voice 语音 ID（如 "zh-CN-XiaoxiaoNeural"），null 表示使用默认
 * @property speed 语速（0.5 ~ 2.0）
 * @property pitch 音调（0.5 ~ 2.0）
 * @property volume 音量（0.0 ~ 1.0）
 * @property queueMode 队列模式：FLUSH（清空队列）/ ADD（追加）
 * @property ssml 是否为 SSML 格式
 */
data class TtsRequest(
    val sessionId: String,
    val text: String,
    val language: String? = null,
    val voice: String? = null,
    val speed: Float = 1.0f,
    val pitch: Float = 1.0f,
    val volume: Float = 1.0f,
    val queueMode: TtsQueueMode = TtsQueueMode.FLUSH,
    val ssml: Boolean = false
)

/** TTS 队列模式。 */
enum class TtsQueueMode {
    /** 清空当前队列，立即朗读。 */
    FLUSH,

    /** 追加到队列末尾。 */
    ADD
}

/**
 * TTS 进度事件。
 */
sealed class TtsProgress {
    /** 朗读开始。 */
    data class Started(val utteranceId: String, val sessionId: String) : TtsProgress()

    /** 朗读进度（progress ∈ [0, 100]）。 */
    data class Progress(val utteranceId: String, val sessionId: String, val progress: Int) : TtsProgress()

    /** 朗读完成（success=false 表示出错或被打断）。 */
    data class Completed(val utteranceId: String, val sessionId: String, val success: Boolean) : TtsProgress()

    /** 朗读出错。 */
    data class Failed(val utteranceId: String, val sessionId: String, val error: String) : TtsProgress()
}

/** TTS 语音信息。 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val language: String,
    val gender: TtsVoiceGender = TtsVoiceGender.UNKNOWN,
    val engine: String = ""
)

/** TTS 语音性别。 */
enum class TtsVoiceGender(val displayName: String) {
    MALE("男声"),
    FEMALE("女声"),
    NEUTRAL("中性"),
    UNKNOWN("未知")
}
