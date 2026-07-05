package com.apex.lib.voice

/**
 * 语音预设配置。
 *
 * 集中维护常用语言 / 语音 / 模型组合，避免每次手动构造 [VoiceConfig]。
 */
object VoicePresets {

    /** 中文 TTS（默认女声、语速 1.0）。 */
    val ZH_CN_TTS: VoiceConfig = VoiceConfig(
        language = "zh-CN",
        ttsVoice = null,
        ttsSpeed = 1.0f,
        ttsPitch = 1.0f,
        asrModel = "default"
    )

    /** 中文 ASR（自动标点、返回中间结果）。 */
    val ZH_CN_ASR: VoiceConfig = VoiceConfig(
        language = "zh-CN",
        partialResults = true,
        enablePunctuation = true,
        maxAlternatives = 3,
        asrModel = "default"
    )

    /** 中文对话模式（TTS + ASR，上下文 20 轮）。 */
    val ZH_CN_CONVERSATION: VoiceConfig = VoiceConfig(
        language = "zh-CN",
        partialResults = true,
        enablePunctuation = true,
        maxAlternatives = 1,
        contextWindow = 20,
        ttsSpeed = 1.0f
    )

    /** 英文 TTS。 */
    val EN_US_TTS: VoiceConfig = VoiceConfig(
        language = "en-US",
        ttsVoice = null,
        ttsSpeed = 1.0f,
        ttsPitch = 1.0f
    )

    /** 英文 ASR。 */
    val EN_US_ASR: VoiceConfig = VoiceConfig(
        language = "en-US",
        partialResults = true,
        enablePunctuation = true,
        maxAlternatives = 3
    )

    /** 英文对话模式。 */
    val EN_US_CONVERSATION: VoiceConfig = VoiceConfig(
        language = "en-US",
        partialResults = true,
        enablePunctuation = true,
        contextWindow = 20
    )

    /** 日文对话模式。 */
    val JA_JP_CONVERSATION: VoiceConfig = VoiceConfig(
        language = "ja-JP",
        partialResults = true,
        enablePunctuation = true,
        contextWindow = 20
    )

    /** 韩文对话模式。 */
    val KO_KR_CONVERSATION: VoiceConfig = VoiceConfig(
        language = "ko-KR",
        partialResults = true,
        enablePunctuation = true,
        contextWindow = 20
    )

    /** 高质量云端 ASR（需要网络）。 */
    val CLOUD_ASR: VoiceConfig = VoiceConfig(
        language = "zh-CN",
        asrModel = "cloud",
        partialResults = true,
        enablePunctuation = true,
        maxAlternatives = 5
    )

    /** 低延迟流式 ASR（边缘模型）。 */
    val EDGE_STREAM_ASR: VoiceConfig = VoiceConfig(
        language = "zh-CN",
        asrModel = "edge",
        partialResults = true,
        enablePunctuation = false,
        sampleRate = 16_000
    )

    /**
     * 根据会话模式返回默认预设。
     */
    fun defaultFor(mode: VoiceMode): VoiceConfig = when (mode) {
        VoiceMode.TTS -> ZH_CN_TTS
        VoiceMode.ASR -> ZH_CN_ASR
        VoiceMode.CONVERSATION -> ZH_CN_CONVERSATION
    }

    /**
     * 根据语言代码返回对应对话预设。
     */
    fun conversationByLanguage(language: String): VoiceConfig = when (language.lowercase()) {
        "zh-cn", "zh", "chinese" -> ZH_CN_CONVERSATION
        "en-us", "en", "english" -> EN_US_CONVERSATION
        "ja-jp", "ja", "japanese" -> JA_JP_CONVERSATION
        "ko-kr", "ko", "korean" -> KO_KR_CONVERSATION
        else -> ZH_CN_CONVERSATION.copy(language = language)
    }

    /** 所有预设清单（便于 UI 展示 / 选择）。 */
    val ALL: List<Pair<String, VoiceConfig>> = listOf(
        "中文-TTS" to ZH_CN_TTS,
        "中文-ASR" to ZH_CN_ASR,
        "中文-对话" to ZH_CN_CONVERSATION,
        "英文-TTS" to EN_US_TTS,
        "英文-ASR" to EN_US_ASR,
        "英文-对话" to EN_US_CONVERSATION,
        "日文-对话" to JA_JP_CONVERSATION,
        "韩文-对话" to KO_KR_CONVERSATION,
        "云端-ASR" to CLOUD_ASR,
        "边缘-流式ASR" to EDGE_STREAM_ASR
    )
}
