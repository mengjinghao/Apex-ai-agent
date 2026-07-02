package com.apex.apk.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.apex.sdk.bridge.TypedServiceRegistry
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Voice APK 的核心服务实现。
 *
 * **能力清单**：
 *   1. ASR（语音识别）— 基于 Android SpeechRecognizer
 *   2. TTS（语音合成）— 基于 Android TextToSpeech
 *   3. 多语言支持（中文 / 英文 / 日文 / 韩文）
 *   4. 实时识别结果流（部分结果 + 最终结果）
 *   5. 语音参数控制（语速 / 音调 / 音量）
 *
 * **权限要求**：
 *   - RECORD_AUDIO（必须，已在 manifest 中声明）
 *
 * **使用方式**（其他 APK）：
 *   ```kotlin
 *   val voice = TypedServiceRegistry.get<VoiceServiceFacade>() ?: error("...")
 *   val text = voice.recognizeOnce()  // 阻塞直到识别完成
 *   voice.speak("你好，世界", language = "zh-CN")
 *   ```
 */
class VoiceServiceFacade(private val context: Context) {

    private const val TAG_SUB = "VoiceFacade"

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    private val _recognitionResults = MutableSharedFlow<RecognitionResult>(extraBufferCapacity = 32)
    val recognitionResults: SharedFlow<RecognitionResult> = _recognitionResults.asSharedFlow()

    /** 当前 TTS 默认参数。 */
    @Volatile var ttsParams: TtsParams = TtsParams()
        private set

    /**
     * 初始化 TTS 引擎。
     */
    fun initializeTts(language: String = "zh-CN"): BridgeResult<Unit> = bridgeRun {
        if (tts != null) return@bridgeRun
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = parseLocale(language)
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ApexLog.w(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS language not supported: $language, fallback to en-US")
                    tts?.setLanguage(Locale.US)
                }
                _isTtsReady.value = true
                ApexLog.i(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS ready (language=$language)")
            } else {
                ApexLog.e(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS init failed: status=$status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                ApexLog.d(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onStart: $utteranceId")
            }
            override fun onDone(utteranceId: String?) {
                ApexLog.d(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onDone: $utteranceId")
            }
            override fun onError(utteranceId: String?) {
                ApexLog.w(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onError: $utteranceId")
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                // 可用于实时显示朗读位置
            }
        })
    }

    /**
     * 设置 TTS 参数。
     */
    fun setTtsParams(params: TtsParams): BridgeResult<Unit> = bridgeRun {
        ttsParams = params
        tts?.let { t ->
            t.setSpeechRate(params.speed)
            t.setPitch(params.pitch)
        }
    }

    /**
     * 同步朗读（阻塞直到朗读完成）。
     * @param text 要朗读的文本
     * @param language 语言代码（如 "zh-CN" / "en-US"）
     */
    suspend fun speak(text: String, language: String = "zh-CN"): BridgeResult<Unit> = bridgeRun {
        if (!_isTtsReady.value) initializeTts(language)
        val t = tts ?: throw IllegalStateException("TTS not initialized")
        val locale = parseLocale(language)
        t.setLanguage(locale)

        suspendCancellableCoroutine<Unit> { cont ->
            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsParams.volume)
            }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            val listener = object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (id == utteranceId) {
                        cont.resume(Unit)
                    }
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        cont.resume(Unit)
                    }
                }
            }
            t.setOnUtteranceProgressListener(listener)

            cont.invokeOnCancellation {
                t.stop()
            }
        }
    }

    /**
     * 异步朗读（立即返回，不等完成）。
     */
    fun speakAsync(text: String, language: String = "zh-CN"): BridgeResult<String> = bridgeRun {
        if (!_isTtsReady.value) initializeTts(language)
        val t = tts ?: throw IllegalStateException("TTS not initialized")
        val locale = parseLocale(language)
        t.setLanguage(locale)
        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, ttsParams.volume)
        }
        t.speak(text, TextToSpeech.QUEUE_ADD, params, utteranceId)
        utteranceId
    }

    /**
     * 停止朗读。
     */
    fun stopSpeaking(): BridgeResult<Unit> = bridgeRun {
        tts?.stop()
    }

    /**
     * 检查 TTS 是否正在朗读。
     */
    fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    /**
     * 列出可用的 TTS 引擎。
     */
    fun listTtsEngines(): List<TtsEngineInfo> {
        return tts?.engines?.map { e ->
            TtsEngineInfo(
                name = e.name,
                label = e.label,
                icon = e.icon?.toString() ?: ""
            )
        } ?: emptyList()
    }

    /**
     * 列出 TTS 支持的语言。
     */
    fun listSupportedLanguages(): List<String> {
        return tts?.availableLanguages?.map { it.toLanguageTag() }?.toList() ?: emptyList()
    }

    // ============================================================
    // ASR
    // ============================================================

    /**
     * 启动语音识别（实时返回结果到 [recognitionResults]）。
     * @param language 语言代码
     * @return sessionId
     */
    fun startRecognition(language: String = "zh-CN"): BridgeResult<String> = bridgeRun {
        if (_isRecognizing.value) throw IllegalStateException("recognition already in progress")
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("speech recognition not available on this device")
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        val sessionId = UUID.randomUUID().toString()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.READY))
            }
            override fun onBeginningOfSpeech() {
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.LISTENING))
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.ENDED))
            }
            override fun onError(error: Int) {
                _isRecognizing.value = false
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, "", false, RecognitionState.ERROR, errorCode = error)
                )
            }
            override fun onResults(results: Bundle?) {
                _isRecognizing.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, text, true, RecognitionState.FINAL)
                )
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, text, false, RecognitionState.PARTIAL)
                )
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
        _isRecognizing.value = true
        ApexLog.i(ApexSuite.ApkId.VOICE, "[$TAG_SUB] recognition started: $sessionId (language=$language)")
        sessionId
    }

    /**
     * 同步识别（阻塞直到识别完成或超时）。
     * @return 最终识别文本
     */
    suspend fun recognizeOnce(language: String = "zh-CN", timeoutMs: Long = 30_000L): BridgeResult<String> = bridgeRun {
        val sessionId = startRecognition(language).getOrNull() ?: throw IllegalStateException("failed to start recognition")

        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.flow.first(recognitionResults) { it.isFinal }
        } ?: throw IllegalStateException("recognition timeout")

        result.text
    }

    /**
     * 停止识别。
     */
    fun stopRecognition(): BridgeResult<Unit> = bridgeRun {
        recognizer?.stopListening()
        _isRecognizing.value = false
    }

    /**
     * 取消识别。
     */
    fun cancelRecognition(): BridgeResult<Unit> = bridgeRun {
        recognizer?.cancel()
        _isRecognizing.value = false
    }

    /**
     * 释放所有资源。
     */
    fun shutdown() {
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        tts = null
        recognizer = null
        _isTtsReady.value = false
        _isRecognizing.value = false
    }

    private fun parseLocale(language: String): Locale {
        return when (language.lowercase()) {
            "zh-cn", "zh", "chinese" -> Locale.SIMPLIFIED_CHINESE
            "zh-tw", "zh-hk" -> Locale.TRADITIONAL_CHINESE
            "en-us", "en", "english" -> Locale.US
            "en-gb" -> Locale.UK
            "ja", "ja-jp", "japanese" -> Locale.JAPANESE
            "ko", "ko-kr", "korean" -> Locale.KOREAN
            "fr", "fr-fr", "french" -> Locale.FRENCH
            "de", "de-de", "german" -> Locale.GERMAN
            "es", "es-es", "spanish" -> Locale("es")
            "ru", "ru-ru", "russian" -> Locale("ru")
            "ar", "ar-sa", "arabic" -> Locale("ar")
            else -> Locale.forLanguageTag(language)
        }
    }
}

/** TTS 参数。 */
data class TtsParams(
    val speed: Float = 1.0f,    // 0.5 ~ 2.0
    val pitch: Float = 1.0f,    // 0.5 ~ 2.0
    val volume: Float = 1.0f    // 0.0 ~ 1.0
)

/** 识别结果。 */
data class RecognitionResult(
    val sessionId: String,
    val text: String,
    val isFinal: Boolean,
    val state: RecognitionState,
    val errorCode: Int = 0
)

/** 识别状态。 */
enum class RecognitionState {
    READY, LISTENING, PARTIAL, FINAL, ENDED, ERROR
}

/** TTS 引擎信息。 */
data class TtsEngineInfo(
    val name: String,
    val label: String,
    val icon: String
)

/** 错误码 → 文本（便于 UI 显示）。 */
fun recognitionErrorToString(errorCode: Int): String = when (errorCode) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "无语音输入"
    SpeechRecognizer.ERROR_NO_MATCH -> "未匹配到语音"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足（缺少 RECORD_AUDIO）"
    else -> "未知错误($errorCode)"
}
