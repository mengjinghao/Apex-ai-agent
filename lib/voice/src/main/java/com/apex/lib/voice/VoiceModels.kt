package com.apex.lib.voice

import kotlinx.serialization.Serializable

/**
 * 语音模块核心数据模型。
 *
 * 包含：
 *   - [VoiceMode] 会话模式（TTS / ASR / CONVERSATION）
 *   - [VoiceConfig] 会话配置（语言、采样率、TTS/ASR 模型等）
 *   - [VoiceSession] 会话句柄
 *   - [Utterance] 单条对话内容
 *   - [Transcript] 识别结果聚合
 *   - [VoiceEvent] 引擎事件（sealed class）
 */

/** 语音会话模式。 */
@Serializable
enum class VoiceMode(val displayName: String) {
    /** 纯语音合成（Text To Speech）。 */
    TTS("语音合成"),

    /** 纯语音识别（Automatic Speech Recognition）。 */
    ASR("语音识别"),

    /** 对话模式（TTS + ASR 轮替）。 */
    CONVERSATION("对话")
}

/** 单条对话角色。 */
@Serializable
enum class VoiceRole(val displayName: String) {
    /** 用户（来自 ASR 识别）。 */
    USER("用户"),

    /** 系统 / 助手（来自 TTS 朗读）。 */
    ASSISTANT("助手"),

    /** 系统（如错误提示）。 */
    SYSTEM("系统")
}

/**
 * 语音会话配置。
 *
 * @property language BCP-47 语言代码（如 "zh-CN" / "en-US"）
 * @property sampleRate 音频采样率（Hz，默认 16000）
 * @property ttsVoice TTS 语音 ID（如 "zh-CN-XiaoxiaoNeural"），null 表示使用默认
 * @property ttsSpeed TTS 语速（0.5 ~ 2.0，默认 1.0）
 * @property ttsPitch TTS 音调（0.5 ~ 2.0，默认 1.0）
 * @property ttsVolume TTS 音量（0.0 ~ 1.0，默认 1.0）
 * @property asrModel ASR 模型名（如 "default" / "cloud" / "whisper"）
 * @property partialResults 是否返回中间识别结果
 * @property maxAlternatives 返回候选结果数量上限
 * @property enablePunctuation ASR 是否启用自动标点
 * @property contextWindow 对话上下文窗口（保留多少条 Utterance，0=不限制）
 * @property metadata 业务自定义元数据
 */
@Serializable
data class VoiceConfig(
    val language: String = "zh-CN",
    val sampleRate: Int = 16_000,
    val ttsVoice: String? = null,
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val asrModel: String = "default",
    val partialResults: Boolean = true,
    val maxAlternatives: Int = 1,
    val enablePunctuation: Boolean = true,
    val contextWindow: Int = 20,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 语音会话句柄。
 *
 * @property id 会话唯一 ID
 * @property mode 会话模式
 * @property config 会话配置快照
 * @property createdAt 创建时间戳（毫秒）
 * @property active 是否仍活跃
 * @property lastActiveAt 最后活跃时间戳（毫秒）
 */
data class VoiceSession(
    val id: String,
    val mode: VoiceMode,
    val config: VoiceConfig,
    val createdAt: Long = System.currentTimeMillis(),
    val active: Boolean = true,
    val lastActiveAt: Long = System.currentTimeMillis()
)

/**
 * 单条对话内容。
 *
 * @property text 文本
 * @property role 角色
 * @property timestamp 时间戳（毫秒）
 * @property confidence 置信度（0.0 ~ 1.0），TTS 默认 1.0
 * @property sessionId 所属会话 ID
 */
@Serializable
data class Utterance(
    val text: String,
    val role: VoiceRole,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f,
    val sessionId: String? = null
)

/**
 * 识别结果聚合。
 *
 * @property fullText 完整拼接文本
 * @property segments 分段文本（每次 final / partial）
 * @property confidence 平均置信度
 * @property language 实际识别语言
 * @property alternatives 候选结果（按置信度降序）
 */
@Serializable
data class Transcript(
    val fullText: String,
    val segments: List<String> = emptyList(),
    val confidence: Float = 1.0f,
    val language: String = "zh-CN",
    val alternatives: List<String> = emptyList()
) {
    companion object {
        /** 空识别结果。 */
        val EMPTY = Transcript("", emptyList(), 0.0f, "", emptyList())
    }
}

/**
 * 语音引擎对外暴露的事件流。
 *
 * 消费者通过 [VoiceEngine.events] 订阅，事件按会话 ID 标识来源。
 */
sealed class VoiceEvent {
    /** 会话已启动。 */
    data class SessionStarted(val sessionId: String, val mode: VoiceMode) : VoiceEvent()

    /** ASR 检测到语音开始。 */
    data class SpeechStarted(val sessionId: String) : VoiceEvent()

    /** ASR 中间识别结果（partial）。 */
    data class PartialTranscript(val sessionId: String, val text: String, val confidence: Float) : VoiceEvent()

    /** ASR 最终识别结果（final）。 */
    data class FinalTranscript(val sessionId: String, val text: String, val confidence: Float, val alternatives: List<String>) : VoiceEvent()

    /** ASR 检测到语音结束。 */
    data class SpeechEnded(val sessionId: String) : VoiceEvent()

    /** TTS 朗读开始。 */
    data class TtsStarted(val sessionId: String, val utteranceId: String) : VoiceEvent()

    /** TTS 朗读进度（0 ~ 100）。 */
    data class TtsProgress(val sessionId: String, val utteranceId: String, val progress: Int) : VoiceEvent()

    /** TTS 朗读完成。 */
    data class TtsCompleted(val sessionId: String, val utteranceId: String, val success: Boolean) : VoiceEvent()

    /** 会话已关闭。 */
    data class SessionClosed(val sessionId: String) : VoiceEvent()

    /** 引擎错误。 */
    data class Error(val sessionId: String?, val code: String, val message: String) : VoiceEvent()
}
