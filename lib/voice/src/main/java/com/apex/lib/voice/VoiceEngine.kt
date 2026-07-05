package com.apex.lib.voice

import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import com.apex.sdk.common.Trace
import com.apex.sdk.common.bridgeRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 语音引擎（VoiceEngine）。
 *
 * **职责**：
 *   - 持有 [sessionManager] 与每会话的 [ConversationBuffer]
 *   - 依赖 APK 注入的 [TtsGateway] / [AsrGateway] 执行实际 TTS / ASR
 *   - 聚合两个网关的回调事件，统一以 [VoiceEvent] 形式对外暴露
 *   - 所有公共 API 返回 [BridgeResult]，使用 `bridgeRun { }` 包装
 *
 * **典型使用流程**：
 *   ```kotlin
 *   val engine = VoiceEngine(ttsGateway, asrGateway)
 *   val sid = engine.startSession(VoiceMode.CONVERSATION, VoicePresets.ZH_CN_CONVERSATION).getOrNull()!!
 *   engine.speak(sid, "你好，请问有什么可以帮你？")
 *   engine.startListening(sid)
 *   // 订阅 engine.events 观察实时识别 / 朗读状态
 *   engine.closeSession(sid)
 *   ```
 */
class VoiceEngine(
    private val ttsGateway: TtsGateway,
    private val asrGateway: AsrGateway,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
) {

    val sessionManager = VoiceSessionManager()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    /** 每会话的对话缓冲（sessionId -> buffer）。 */
    private val buffers = mutableMapOf<String, ConversationBuffer>()

    /** 当前正在进行的 ASR utterance → sessionId 映射（用于事件聚合）。 */
    private val asrUtteranceToSession = mutableMapOf<String, String>()
    /** 当前正在进行的 TTS utterance → sessionId 映射。 */
    private val ttsUtteranceToSession = mutableMapOf<String, String>()

    init {
        // 聚合 TTS / ASR 网关事件，转换为 VoiceEvent 对外暴露
        scope.launch { ttsGateway.progress.collect { onTtsProgress(it) } }
        scope.launch { asrGateway.results.collect { onAsrResult(it) } }
    }

    // ============================================================
    // 会话生命周期
    // ============================================================

    /**
     * 启动一个语音会话。
     *
     * @param mode 会话模式
     * @param config 会话配置
     * @return 会话 ID
     */
    suspend fun startSession(
        mode: VoiceMode,
        config: VoiceConfig = VoicePresets.defaultFor(mode)
    ): BridgeResult<String> = bridgeRun {
        val session = sessionManager.register(mode, config)
        buffers[session.id] = ConversationBuffer(session.id, config.contextWindow)

        // 根据模式初始化对应的网关
        when (mode) {
            VoiceMode.TTS -> ttsGateway.initialize(config)
            VoiceMode.ASR -> asrGateway.initialize(config)
            VoiceMode.CONVERSATION -> {
                ttsGateway.initialize(config)
                asrGateway.initialize(config)
            }
        }

        _events.tryEmit(VoiceEvent.SessionStarted(session.id, mode))
        ApexLog.i(
            ApexSuite.ApkId.VOICE,
            "[Engine] session started: ${session.id} (mode=${mode.name}, language=${config.language})"
        )
        session.id
    }

    /**
     * 关闭会话（停止朗读 / 监听 + 释放缓冲）。
     */
    suspend fun closeSession(sessionId: String): BridgeResult<kotlin.Unit> = bridgeRun {
        val session = sessionManager.get(sessionId)
            ?: throw IllegalStateException("session not found: $sessionId")
        if (session.mode != VoiceMode.TTS) {
            runCatching { asrGateway.stopListening() }
        }
        if (session.mode != VoiceMode.ASR) {
            runCatching { ttsGateway.stop() }
        }
        sessionManager.markInactive(sessionId)
        sessionManager.remove(sessionId)
        buffers.remove(sessionId)
        asrUtteranceToSession.entries.removeIf { it.value == sessionId }
        ttsUtteranceToSession.entries.removeIf { it.value == sessionId }
        _events.tryEmit(VoiceEvent.SessionClosed(sessionId))
        ApexLog.i(ApexSuite.ApkId.VOICE, "[Engine] session closed: $sessionId")
    }

    /** 列出所有会话。 */
    fun listSessions(): List<VoiceSession> = sessionManager.list()

    /** 查询会话。 */
    fun getSession(sessionId: String): VoiceSession? = sessionManager.get(sessionId)

    // ============================================================
    // TTS
    // ============================================================

    /**
     * 朗读文本（流式）。
     *
     * @return utteranceId
     */
    suspend fun speak(sessionId: String, text: String): BridgeResult<String> = bridgeRun {
        val session = ensureSession(sessionId)
        if (session.mode == VoiceMode.ASR) {
            throw IllegalStateException("session $sessionId is ASR-only, cannot speak")
        }
        val cfg = session.config
        val req = TtsRequest(
            sessionId = sessionId,
            text = text,
            language = cfg.language,
            voice = cfg.ttsVoice,
            speed = cfg.ttsSpeed,
            pitch = cfg.ttsPitch,
            volume = cfg.ttsVolume,
            queueMode = TtsQueueMode.FLUSH
        )
        val utteranceId = ttsGateway.speak(req).getOrNull()
            ?: throw IllegalStateException("TTS speak failed")
        ttsUtteranceToSession[utteranceId] = sessionId
        buffers[sessionId]?.append(Utterance(text, VoiceRole.ASSISTANT, sessionId = sessionId))
        sessionManager.touch(sessionId)
        utteranceId
    }

    /**
     * 一次性合成完整音频（不朗读，仅生成音频数据）。
     *
     * @return utteranceId（业务侧可后续通过网关查询音频）
     */
    suspend fun synthesize(sessionId: String, text: String): BridgeResult<String> = bridgeRun {
        val session = ensureSession(sessionId)
        val cfg = session.config
        val req = TtsRequest(
            sessionId = sessionId,
            text = text,
            language = cfg.language,
            voice = cfg.ttsVoice,
            speed = cfg.ttsSpeed,
            pitch = cfg.ttsPitch,
            volume = cfg.ttsVolume
        )
        ttsGateway.synthesize(req).getOrNull()
            ?: throw IllegalStateException("TTS synthesize failed")
    }

    /** 停止当前会话的朗读。 */
    suspend fun stopSpeaking(sessionId: String): BridgeResult<kotlin.Unit> = bridgeRun {
        ensureSession(sessionId)
        ttsGateway.stop()
        sessionManager.touch(sessionId)
    }

    /** TTS 是否正在朗读。 */
    fun isSpeaking(): Boolean = ttsGateway.isSpeaking()

    /** 列出可用的 TTS 语音。 */
    suspend fun listTtsVoices(language: String? = null): BridgeResult<List<TtsVoiceInfo>> = bridgeRun {
        ttsGateway.listVoices(language).getOrNull() ?: emptyList()
    }

    // ============================================================
    // ASR
    // ============================================================

    /**
     * 开始监听语音。
     *
     * @return utteranceId
     */
    suspend fun startListening(sessionId: String): BridgeResult<String> = bridgeRun {
        val session = ensureSession(sessionId)
        if (session.mode == VoiceMode.TTS) {
            throw IllegalStateException("session $sessionId is TTS-only, cannot listen")
        }
        val cfg = session.config
        val req = AsrRequest(
            sessionId = sessionId,
            language = cfg.language,
            model = cfg.asrModel,
            partialResults = cfg.partialResults,
            maxAlternatives = cfg.maxAlternatives,
            enablePunctuation = cfg.enablePunctuation,
            sampleRate = cfg.sampleRate
        )
        val utteranceId = asrGateway.startListening(req).getOrNull()
            ?: throw IllegalStateException("ASR startListening failed")
        asrUtteranceToSession[utteranceId] = sessionId
        sessionManager.touch(sessionId)
        utteranceId
    }

    /** 停止监听（保留已识别的最终结果）。 */
    suspend fun stopListening(sessionId: String): BridgeResult<kotlin.Unit> = bridgeRun {
        ensureSession(sessionId)
        asrGateway.stopListening()
        sessionManager.touch(sessionId)
    }

    /** 取消当前识别。 */
    suspend fun cancelListening(sessionId: String): BridgeResult<kotlin.Unit> = bridgeRun {
        ensureSession(sessionId)
        asrGateway.cancel()
        sessionManager.touch(sessionId)
    }

    /** ASR 是否正在监听。 */
    fun isListening(): Boolean = asrGateway.isListening()

    /** 列出可用的 ASR 模型。 */
    suspend fun listAsrModels(): BridgeResult<List<AsrModelInfo>> = bridgeRun {
        asrGateway.listModels().getOrNull() ?: emptyList()
    }

    // ============================================================
    // 对话上下文 / 转写
    // ============================================================

    /**
     * 获取会话的完整对话历史（Utterance 列表）。
     */
    fun getConversation(sessionId: String): BridgeResult<List<Utterance>> = bridgeRun {
        ensureSession(sessionId)
        buffers[sessionId]?.snapshot() ?: emptyList()
    }

    /**
     * 获取会话的转写文本（ASR 全部 final 拼接）。
     */
    fun getTranscript(sessionId: String): BridgeResult<Transcript> = bridgeRun {
        ensureSession(sessionId)
        val buf = buffers[sessionId]
        val userUtterances = buf?.snapshot().orEmpty().filter { it.role == VoiceRole.USER }
        if (userUtterances.isEmpty()) {
            Transcript.EMPTY.copy(language = sessionManager.get(sessionId)?.config?.language ?: "zh-CN")
        } else {
            Transcript(
                fullText = userUtterances.joinToString(" ") { it.text },
                segments = userUtterances.map { it.text },
                confidence = userUtterances.map { it.confidence }.average().toFloat(),
                language = sessionManager.get(sessionId)?.config?.language ?: "zh-CN"
            )
        }
    }

    /**
     * 向会话上下文手动追加一条对话（不触发 TTS / ASR）。
     */
    fun appendUtterance(sessionId: String, utterance: Utterance): BridgeResult<kotlin.Unit> = bridgeRun {
        ensureSession(sessionId)
        buffers[sessionId]?.append(utterance)
            ?: throw IllegalStateException("buffer not found: $sessionId")
    }

    /** 清空会话对话历史。 */
    fun clearConversation(sessionId: String): BridgeResult<kotlin.Unit> = bridgeRun {
        ensureSession(sessionId)
        buffers[sessionId]?.clear()
    }

    // ============================================================
    // 网关事件聚合
    // ============================================================

    private suspend fun onTtsProgress(progress: TtsProgress) {
        when (progress) {
            is TtsProgress.Started -> {
                _events.tryEmit(VoiceEvent.TtsStarted(progress.sessionId, progress.utteranceId))
            }
            is TtsProgress.Progress -> {
                _events.tryEmit(
                    VoiceEvent.TtsProgress(progress.sessionId, progress.utteranceId, progress.progress)
                )
            }
            is TtsProgress.Completed -> {
                _events.tryEmit(
                    VoiceEvent.TtsCompleted(progress.sessionId, progress.utteranceId, progress.success)
                )
                ttsUtteranceToSession.remove(progress.utteranceId)
            }
            is TtsProgress.Failed -> {
                _events.tryEmit(
                    VoiceEvent.Error(progress.sessionId, "TTS_FAILED", progress.error)
                )
                ttsUtteranceToSession.remove(progress.utteranceId)
            }
        }
    }

    private suspend fun onAsrResult(result: AsrResult) {
        when (result) {
            is AsrResult.Ready -> {
                // 静默：仅记录
                ApexLog.d(ApexSuite.ApkId.VOICE, "[Engine] ASR ready: ${result.utteranceId}")
            }
            is AsrResult.SpeechStarted -> {
                _events.tryEmit(VoiceEvent.SpeechStarted(result.sessionId))
            }
            is AsrResult.Partial -> {
                _events.tryEmit(
                    VoiceEvent.PartialTranscript(result.sessionId, result.text, result.confidence)
                )
            }
            is AsrResult.Final -> {
                _events.tryEmit(
                    VoiceEvent.FinalTranscript(
                        result.sessionId, result.text, result.confidence, result.alternatives
                    )
                )
                // 写入对话缓冲
                buffers[result.sessionId]?.append(
                    Utterance(result.text, VoiceRole.USER, confidence = result.confidence, sessionId = result.sessionId)
                )
                sessionManager.touch(result.sessionId)
                asrUtteranceToSession.remove(result.utteranceId)
            }
            is AsrResult.SpeechEnded -> {
                _events.tryEmit(VoiceEvent.SpeechEnded(result.sessionId))
            }
            is AsrResult.Error -> {
                _events.tryEmit(
                    VoiceEvent.Error(result.sessionId, result.code, result.message)
                )
                asrUtteranceToSession.remove(result.utteranceId)
            }
        }
    }

    // ============================================================
    // 释放
    // ============================================================

    /** 释放引擎资源（关闭所有会话 + 关闭网关 + 取消协程）。 */
    fun shutdown() {
        ApexLog.i(ApexSuite.ApkId.VOICE, "[Engine] shutdown (sessions=${sessionManager.size()})")
        sessionManager.clear()
        buffers.clear()
        asrUtteranceToSession.clear()
        ttsUtteranceToSession.clear()
        runCatching { ttsGateway.shutdown() }
        runCatching { asrGateway.shutdown() }
        scope.cancel()
    }

    // ============================================================
    // 内部辅助
    // ============================================================

    private fun ensureSession(sessionId: String): VoiceSession {
        return sessionManager.get(sessionId)
            ?: throw IllegalStateException("session not found: $sessionId")
    }
}
