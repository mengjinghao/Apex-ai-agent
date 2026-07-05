package com.apex.apk.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.apex.lib.voice.AsrGateway
import com.apex.lib.voice.AsrModelInfo
import com.apex.lib.voice.AsrRequest
import com.apex.lib.voice.AsrResult
import com.apex.lib.voice.TtsGateway
import com.apex.lib.voice.TtsProgress
import com.apex.lib.voice.TtsQueueMode
import com.apex.lib.voice.TtsRequest
import com.apex.lib.voice.TtsVoiceGender
import com.apex.lib.voice.TtsVoiceInfo
import com.apex.lib.voice.VoiceConfig
import com.apex.lib.voice.VoiceEngine
import com.apex.lib.voice.VoiceEvent
import com.apex.lib.voice.VoiceMode
import com.apex.lib.voice.VoicePresets
import com.apex.lib.voice.VoiceSession
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
 * Voice APK 的核心服务实现（lib:voice 集成版）。
 *
 * **架构**：
 *   - 本类同时实现 lib:voice 定义的 [TtsGateway] 与 [AsrGateway] 契约，
 *     对接 Android `TextToSpeech` / `SpeechRecognizer`
 *   - 持有 [VoiceEngine] 实例（注入自身为 TTS/ASR 网关）
 *   - 对外暴露两层 API：
 *       1. **会话式 API**（推荐）：[startSession] / [speak] / [startListening] / [closeSession]
 *          —— 委托给 [engine]，返回 [BridgeResult]，事件流 [events]
 *       2. **遗留 API**（兼容旧 BridgeImpl）：[initializeTts] / [speakAsync] /
 *          [startRecognition] / [recognizeOnce] —— 内部转换为会话式调用
 *
 * **能力清单**：
 *   1. ASR（语音识别）— 基于 Android SpeechRecognizer
 *   2. TTS（语音合成）— 基于 Android TextToSpeech
 *   3. 多语言支持（中文 / 英文 / 日文 / 韩文等）
 *   4. 实时识别结果流（partial + final）
 *   5. 语音参数控制（语速 / 音调 / 音量）
 *   6. 多会话管理 + 对话上下文缓冲
 *
 * **权限要求**：RECORD_AUDIO（已在 manifest 中声明）
 *
 * **使用方式**（其他 APK）：
 *   ```kotlin
 *   val voice = TypedServiceRegistry.get<VoiceServiceFacade>() ?: error("...")
 *   val sid = voice.startSession(VoiceMode.CONVERSATION, VoicePresets.ZH_CN_CONVERSATION)
 *       .getOrNull()!!
 *   voice.speak(sid, "你好")
 *   voice.startListening(sid)
 *   voice.closeSession(sid)
 *   ```
 */
class VoiceServiceFacade(private val context: Context) : TtsGateway, AsrGateway {

    private const val TAG_SUB = "VoiceFacade"

    // ===== Android 资源 =====
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null

    private val _isTtsReady = MutableStateFlow(false)
    val isTtsReady: StateFlow<Boolean> = _isTtsReady.asStateFlow()

    private val _isRecognizing = MutableStateFlow(false)
    val isRecognizing: StateFlow<Boolean> = _isRecognizing.asStateFlow()

    /** 当前 TTS 默认参数（遗留 API 使用）。 */
    @Volatile var ttsParams: TtsParams = TtsParams()
        private set

    // ===== lib:voice 网关流 =====
    private val _ttsProgress = MutableSharedFlow<TtsProgress>(extraBufferCapacity = 64)
    override val progress: SharedFlow<TtsProgress> = _ttsProgress.asSharedFlow()

    private val _asrResults = MutableSharedFlow<AsrResult>(extraBufferCapacity = 64)
    override val results: SharedFlow<AsrResult> = _asrResults.asSharedFlow()

    /** 遗留 API 暴露的识别结果流（兼容旧 UI / BridgeImpl）。 */
    private val _recognitionResults = MutableSharedFlow<RecognitionResult>(extraBufferCapacity = 32)
    val recognitionResults: SharedFlow<RecognitionResult> = _recognitionResults.asSharedFlow()

    // ===== lib:voice 引擎 =====
    private val engine: VoiceEngine = VoiceEngine(ttsGateway = this, asrGateway = this)

    /** 引擎事件流（聚合 TTS/ASR 所有事件）。 */
    val events: SharedFlow<VoiceEvent> = engine.events

    /** 暴露引擎以便高级用法直接访问。 */
    fun engine(): VoiceEngine = engine

    // ============================================================
    // TtsGateway 实现
    // ============================================================

    override suspend fun initialize(config: VoiceConfig): BridgeResult<Unit> = bridgeRun {
        if (tts != null) return@bridgeRun
        val language = config.language
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = parseLocale(language)
                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ApexLog.w(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS language not supported: $language, fallback to en-US")
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(config.ttsSpeed)
                tts?.setPitch(config.ttsPitch)
                _isTtsReady.value = true
                ApexLog.i(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS ready (language=$language)")
            } else {
                ApexLog.e(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS init failed: status=$status")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val sid = utteranceId?.split("|")?.getOrNull(0) ?: ""
                val uid = utteranceId?.split("|")?.getOrNull(1) ?: utteranceId ?: ""
                _ttsProgress.tryEmit(TtsProgress.Started(uid, sid))
                ApexLog.d(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onStart: $uid (session=$sid)")
            }
            override fun onDone(utteranceId: String?) {
                val (sid, uid) = splitUtteranceId(utteranceId)
                _ttsProgress.tryEmit(TtsProgress.Completed(uid, sid, success = true))
                ApexLog.d(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onDone: $uid")
            }
            override fun onError(utteranceId: String?) {
                val (sid, uid) = splitUtteranceId(utteranceId)
                _ttsProgress.tryEmit(TtsProgress.Completed(uid, sid, success = false))
                ApexLog.w(ApexSuite.ApkId.VOICE, "[$TAG_SUB] TTS onError: $uid")
            }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val (sid, uid) = splitUtteranceId(utteranceId)
                val progress = if (end <= 0) 0 else (start * 100 / end).coerceIn(0, 100)
                _ttsProgress.tryEmit(TtsProgress.Progress(uid, sid, progress))
            }
        })
    }

    override suspend fun synthesize(request: TtsRequest): BridgeResult<String> = bridgeRun {
        ensureTts(request.language)
        // Android 系统 TTS 不直接返回音频字节；这里返回 utteranceId，
        // 业务侧如需原始音频可通过 AudioTrack 录制或第三方 TTS 替换。
        val uid = makeUtteranceId(request.sessionId)
        val params = buildTtsParams(uid, request.volume)
        val queueMode = if (request.queueMode == TtsQueueMode.ADD)
            TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts?.speak(request.text, queueMode, params, uid) ?: throw IllegalStateException("TTS not initialized")
        uid
    }

    override suspend fun speak(request: TtsRequest): BridgeResult<String> = synthesize(request)

    override suspend fun stop(utteranceId: String?): BridgeResult<Unit> = bridgeRun {
        tts?.stop()
    }

    override fun isSpeaking(): Boolean = tts?.isSpeaking ?: false

    override suspend fun listVoices(language: String?): BridgeResult<List<TtsVoiceInfo>> = bridgeRun {
        ensureTts(language ?: "zh-CN")
        tts?.availableLanguages?.let { langs ->
            langs.filter { language == null || it.toLanguageTag().equals(language, ignoreCase = true) }
                .map { locale ->
                    TtsVoiceInfo(
                        id = locale.toLanguageTag(),
                        displayName = locale.displayName,
                        language = locale.toLanguageTag(),
                        gender = TtsVoiceGender.UNKNOWN,
                        engine = "android-system"
                    )
                }
        } ?: emptyList()
    }

    override fun shutdown() {
        engine.shutdown()
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        try { recognizer?.destroy() } catch (_: Throwable) {}
        tts = null
        recognizer = null
        _isTtsReady.value = false
        _isRecognizing.value = false
    }

    // ============================================================
    // AsrGateway 实现
    // ============================================================

    override fun isListening(): Boolean = _isRecognizing.value

    override suspend fun initialize(config: VoiceConfig): BridgeResult<Unit> = bridgeRun {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            throw IllegalStateException("speech recognition not available on this device")
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }
    }

    override suspend fun startListening(request: AsrRequest): BridgeResult<String> = bridgeRun {
        if (_isRecognizing.value) throw IllegalStateException("recognition already in progress")
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val utteranceId = UUID.randomUUID().toString()
        val sessionId = request.sessionId
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, request.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, request.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, request.maxAlternatives)
        }

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _asrResults.tryEmit(AsrResult.Ready(utteranceId, sessionId))
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.READY))
            }
            override fun onBeginningOfSpeech() {
                _asrResults.tryEmit(AsrResult.SpeechStarted(utteranceId, sessionId))
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.LISTENING))
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                _asrResults.tryEmit(AsrResult.SpeechEnded(utteranceId, sessionId))
                _recognitionResults.tryEmit(RecognitionResult(sessionId, "", false, RecognitionState.ENDED))
            }
            override fun onError(error: Int) {
                _isRecognizing.value = false
                val msg = recognitionErrorToString(error)
                _asrResults.tryEmit(AsrResult.Error(utteranceId, sessionId, "ASR_ERROR_$error", msg))
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, "", false, RecognitionState.ERROR, errorCode = error)
                )
            }
            override fun onResults(results: Bundle?) {
                _isRecognizing.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = matches.firstOrNull() ?: ""
                val alternatives = matches.take(5)
                _asrResults.tryEmit(
                    AsrResult.Final(utteranceId, sessionId, text, 1.0f, alternatives)
                )
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, text, true, RecognitionState.FINAL)
                )
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = matches.firstOrNull() ?: ""
                _asrResults.tryEmit(AsrResult.Partial(utteranceId, sessionId, text))
                _recognitionResults.tryEmit(
                    RecognitionResult(sessionId, text, false, RecognitionState.PARTIAL)
                )
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        recognizer?.startListening(intent)
        _isRecognizing.value = true
        ApexLog.i(ApexSuite.ApkId.VOICE, "[$TAG_SUB] ASR started: $utteranceId (session=$sessionId, lang=${request.language})")
        utteranceId
    }

    override suspend fun stopListening(utteranceId: String?): BridgeResult<Unit> = bridgeRun {
        recognizer?.stopListening()
        _isRecognizing.value = false
    }

    override suspend fun cancel(utteranceId: String?): BridgeResult<Unit> = bridgeRun {
        recognizer?.cancel()
        _isRecognizing.value = false
    }

    override suspend fun listModels(): BridgeResult<List<AsrModelInfo>> = bridgeRun {
        listOf(
            AsrModelInfo(
                id = "default",
                displayName = "系统语音识别",
                languages = listOf("zh-CN", "en-US", "ja-JP", "ko-KR"),
                supportsStreaming = true,
                requiresNetwork = false,
                description = "Android SpeechRecognizer（设备自带）"
            )
        )
    }

    // ============================================================
    // 会话式 API（委托给 engine）
    // ============================================================

    /** 启动一个语音会话。 */
    suspend fun startSession(
        mode: VoiceMode,
        config: VoiceConfig = VoicePresets.defaultFor(mode)
    ): BridgeResult<String> = engine.startSession(mode, config)

    /** 关闭会话。 */
    suspend fun closeSession(sessionId: String): BridgeResult<Unit> = engine.closeSession(sessionId)

    /** 列出所有会话。 */
    fun listSessions(): List<VoiceSession> = engine.listSessions()

    /** 朗读文本（会话式）。 */
    suspend fun speak(sessionId: String, text: String): BridgeResult<String> = engine.speak(sessionId, text)

    /** 开始监听语音（会话式）。 */
    suspend fun startListening(sessionId: String): BridgeResult<String> = engine.startListening(sessionId)

    /** 停止监听（会话式）。 */
    suspend fun stopListening(sessionId: String): BridgeResult<Unit> = engine.stopListening(sessionId)

    /** 取消识别（会话式）。 */
    suspend fun cancelListening(sessionId: String): BridgeResult<Unit> = engine.cancelListening(sessionId)

    /** 停止朗读（会话式）。 */
    suspend fun stopSpeaking(sessionId: String): BridgeResult<Unit> = engine.stopSpeaking(sessionId)

    // ============================================================
    // 遗留 API（兼容旧 BridgeImpl）
    // ============================================================

    /** 初始化 TTS 引擎（遗留 API）。 */
    fun initializeTts(language: String = "zh-CN"): BridgeResult<Unit> = bridgeRun {
        val config = VoiceConfig(language = language)
        // 同步等待 init（Android TTS init 是异步的，这里仅触发并立即返回）
        kotlinx.coroutines.runBlocking { initialize(config) }
    }

    /** 设置 TTS 参数（遗留 API）。 */
    fun setTtsParams(params: TtsParams): BridgeResult<Unit> = bridgeRun {
        ttsParams = params
        tts?.let { t ->
            t.setSpeechRate(params.speed)
            t.setPitch(params.pitch)
        }
    }

    /**
     * 同步朗读（阻塞直到完成，遗留 API）。
     * 内部启动一个临时会话并复用引擎。
     */
    suspend fun speak(text: String, language: String = "zh-CN"): BridgeResult<Unit> = bridgeRun {
        ensureTts(language)
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
                    if (id == utteranceId) cont.resume(Unit)
                }
                override fun onError(id: String?) {
                    if (id == utteranceId) cont.resume(Unit)
                }
            }
            t.setOnUtteranceProgressListener(listener)

            cont.invokeOnCancellation { t.stop() }
        }
    }

    /** 异步朗读（遗留 API）。 */
    suspend fun speakAsync(text: String, language: String = "zh-CN"): BridgeResult<String> = bridgeRun {
        ensureTts(language)
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

    /** 停止朗读（遗留 API）。 */
    fun stopSpeaking(): BridgeResult<Unit> = bridgeRun { tts?.stop() }

    /** 列出可用的 TTS 引擎。 */
    fun listTtsEngines(): List<TtsEngineInfo> {
        return tts?.engines?.map { e ->
            TtsEngineInfo(name = e.name, label = e.label, icon = e.icon?.toString() ?: "")
        } ?: emptyList()
    }

    /** 列出 TTS 支持的语言。 */
    fun listSupportedLanguages(): List<String> {
        return tts?.availableLanguages?.map { it.toLanguageTag() }?.toList() ?: emptyList()
    }

    /** 启动语音识别（遗留 API，返回 sessionId）。 */
    suspend fun startRecognition(language: String = "zh-CN"): BridgeResult<String> = bridgeRun {
        // 用一个临时 ASR 会话承载
        val sid = engine.startSession(VoiceMode.ASR, VoicePresets.ZH_CN_ASR.copy(language = language))
            .getOrNull() ?: throw IllegalStateException("failed to start ASR session")
        engine.startListening(sid)
        sid
    }

    /** 同步识别（遗留 API）。 */
    suspend fun recognizeOnce(language: String = "zh-CN", timeoutMs: Long = 30_000L): BridgeResult<String> = bridgeRun {
        val sid = startRecognition(language).getOrNull()
            ?: throw IllegalStateException("failed to start recognition")
        val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            kotlinx.coroutines.flow.first(events) { ev ->
                ev is VoiceEvent.FinalTranscript && ev.sessionId == sid
            }
        } ?: throw IllegalStateException("recognition timeout")
        engine.closeSession(sid)
        (result as VoiceEvent.FinalTranscript).text
    }

    /** 停止识别（遗留 API）。 */
    fun stopRecognition(): BridgeResult<Unit> = bridgeRun {
        recognizer?.stopListening()
        _isRecognizing.value = false
    }

    /** 取消识别（遗留 API）。 */
    fun cancelRecognition(): BridgeResult<Unit> = bridgeRun {
        recognizer?.cancel()
        _isRecognizing.value = false
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private suspend fun ensureTts(language: String) {
        if (!_isTtsReady.value || tts == null) {
            initialize(VoiceConfig(language = language))
            // Android TTS init 是异步回调，等待最多 2 秒
            val deadline = System.currentTimeMillis() + 2_000L
            while (!_isTtsReady.value && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(50L)
            }
        }
    }

    private fun buildTtsParams(utteranceId: String, volume: Float): Bundle = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
    }

    /** 复合 utteranceId = "sessionId|rawUid"，便于回调中还原 sessionId。 */
    private fun makeUtteranceId(sessionId: String): String = "$sessionId|${UUID.randomUUID()}"

    private fun splitUtteranceId(raw: String?): Pair<String, String> {
        if (raw == null) return "" to ""
        val parts = raw.split("|", limit = 2)
        return if (parts.size == 2) parts[0] to parts[1] else "" to raw
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

/** TTS 参数（遗留数据类，兼容旧 BridgeImpl）。 */
data class TtsParams(
    val speed: Float = 1.0f,    // 0.5 ~ 2.0
    val pitch: Float = 1.0f,    // 0.5 ~ 2.0
    val volume: Float = 1.0f    // 0.0 ~ 1.0
)

/** 识别结果（遗留数据类，兼容旧 BridgeImpl）。 */
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
