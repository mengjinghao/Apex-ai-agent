package com.apex.apk.voice

import com.apex.lib.voice.Utterance
import com.apex.lib.voice.VoiceConfig
import com.apex.lib.voice.VoiceMode
import com.apex.lib.voice.VoicePresets
import com.apex.lib.voice.VoiceRole
import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class VoiceBridgeImpl(
    private val facade: VoiceServiceFacade
) : IApkBridgeInternal {

    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(method: String, argsJson: String): String {
        ApexLog.d(ApexSuite.ApkId.VOICE, "[VoiceBridge] invoke: $method")
        val args = try {
            json.parseToJsonElement(argsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (_: Throwable) { JsonObject(emptyMap()) }

        return runCatching {
            runBlocking {
                when (method) {
                    "voice/initializeTts" -> {
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        buildResult(facade.initializeTts(lang)) { JsonObject(emptyMap()) }
                    }
                    "voice/setTtsParams" -> {
                        val speed = args["speed"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
                        val pitch = args["pitch"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
                        val volume = args["volume"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f
                        buildResult(facade.setTtsParams(TtsParams(speed, pitch, volume))) { JsonObject(emptyMap()) }
                    }
                    "voice/speak" -> {
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        val ttsReq = com.apex.lib.voice.TtsRequest(text = text, language = lang)
                        val speakResult = facade.speak(ttsReq)
                        buildResult<Unit>(speakResult) { JsonObject(emptyMap()) }
                    }
                    "voice/speakAsync" -> {
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        buildResult(facade.speakAsync(text, lang)) { JsonPrimitive(it) }
                    }
                    "voice/stopSpeaking" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content
                        if (sessionId.isNullOrEmpty()) {
                            buildResult(facade.stopSpeaking()) { JsonObject(emptyMap()) }
                        } else {
                            buildResult(facade.engine().stopSpeaking(sessionId)) { JsonObject(emptyMap()) }
                        }
                    }
                    "voice/isSpeaking" -> {
                        buildJsonObject { put("success", true); put("speaking", facade.isSpeaking()) }.toString()
                    }
                    "voice/listTtsEngines" -> {
                        val list = facade.listTtsEngines()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("engines", list.joinToString("\n") { "${it.name}: ${it.label}" })
                        }.toString()
                    }
                    "voice/listSupportedLanguages" -> {
                        val list = facade.listSupportedLanguages()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("languages", list.joinToString(","))
                        }.toString()
                    }
                    "voice/startRecognition" -> {
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        buildResult(facade.startRecognition(lang)) { JsonPrimitive(it) }
                    }
                    "voice/recognizeOnce" -> {
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        val timeoutMs = args["timeoutMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30_000L
                        buildResult(facade.recognizeOnce(lang, timeoutMs)) { JsonPrimitive(it) }
                    }
                    "voice/stopRecognition" -> {
                        buildResult(facade.stopRecognition()) { JsonObject(emptyMap()) }
                    }
                    "voice/cancelRecognition" -> {
                        buildResult(facade.cancelRecognition()) { JsonObject(emptyMap()) }
                    }

                    // ===== 会话式 API（lib:voice VoiceEngine 新能力）=====

                    "voice/startSession" -> {
                        val modeStr = args["mode"]?.jsonPrimitive?.content ?: "CONVERSATION"
                        val mode = runCatching { VoiceMode.valueOf(modeStr) }
                            .getOrDefault(VoiceMode.CONVERSATION)
                        val config = parseVoiceConfig(args, mode)
                        buildResult(facade.engine().startSession(mode, config)) { JsonPrimitive(it) }
                    }
                    "voice/closeSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().closeSession(sessionId)) { JsonObject(emptyMap()) }
                    }
                    "voice/speakInSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().speak(sessionId, text)) { JsonPrimitive(it) }
                    }
                    "voice/synthesize" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().synthesize(sessionId, text)) { JsonPrimitive(it) }
                    }
                    "voice/startListening" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().startListening(sessionId)) { JsonPrimitive(it) }
                    }
                    "voice/stopListening" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().stopListening(sessionId)) { JsonObject(emptyMap()) }
                    }
                    "voice/cancelListening" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().cancelListening(sessionId)) { JsonObject(emptyMap()) }
                    }
                    "voice/listSessions" -> {
                        val list = facade.engine().listSessions()
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("sessions", list.joinToString("\n") {
                                "${it.id}|${it.mode.name}|${it.config.language}|active=${it.active}"
                            })
                        }.toString()
                    }
                    "voice/getSession" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val session = facade.engine().getSession(sessionId)
                        if (session == null) {
                            buildJsonObject {
                                put("success", false)
                                put("errorMessage", "session not found: $sessionId")
                            }.toString()
                        } else {
                            buildJsonObject {
                                put("success", true)
                                put("sessionId", session.id)
                                put("mode", session.mode.name)
                                put("language", session.config.language)
                                put("active", session.active)
                                put("createdAt", session.createdAt)
                                put("lastActiveAt", session.lastActiveAt)
                            }.toString()
                        }
                    }
                    "voice/getConversation" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val maxUtterances = args["maxUtterances"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        buildResult(facade.engine().getConversation(sessionId)) { list ->
                            val trimmed = if (maxUtterances > 0) list.takeLast(maxUtterances) else list
                            buildJsonObject {
                                put("count", trimmed.size)
                                put("utterances", trimmed.joinToString("\n") {
                                    "${it.role.name}: ${it.text}"
                                })
                            }
                        }
                    }
                    "voice/getTranscript" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().getTranscript(sessionId)) { t ->
                            buildJsonObject {
                                put("fullText", t.fullText)
                                put("segments", t.segments.joinToString("\n"))
                                put("confidence", t.confidence)
                                put("language", t.language)
                                put("alternatives", t.alternatives.joinToString("||"))
                            }
                        }
                    }
                    "voice/appendUtterance" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        val roleStr = args["role"]?.jsonPrimitive?.content ?: "USER"
                        val role = runCatching { VoiceRole.valueOf(roleStr) }
                            .getOrDefault(VoiceRole.USER)
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        val utterance = Utterance(text = text, role = role, sessionId = sessionId)
                        buildResult(facade.engine().appendUtterance(sessionId, utterance)) {
                            JsonObject(emptyMap())
                        }
                    }
                    "voice/clearConversation" -> {
                        val sessionId = args["sessionId"]?.jsonPrimitive?.content ?: ""
                        buildResult(facade.engine().clearConversation(sessionId)) { JsonObject(emptyMap()) }
                    }
                    "voice/isListening" -> {
                        buildJsonObject {
                            put("success", true)
                            put("listening", facade.engine().isListening())
                        }.toString()
                    }
                    "voice/listTtsVoices" -> {
                        val language = args["language"]?.jsonPrimitive?.content
                        buildResult(facade.engine().listTtsVoices(language)) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("voices", list.joinToString("\n") {
                                    "${it.id}|${it.displayName}|${it.language}|${it.gender.displayName}"
                                })
                            }
                        }
                    }
                    "voice/listAsrModels" -> {
                        buildResult(facade.engine().listAsrModels()) { list ->
                            buildJsonObject {
                                put("count", list.size)
                                put("models", list.joinToString("\n") {
                                    "${it.id}|${it.displayName}|${it.languages.joinToString(",")}|" +
                                        "streaming=${it.supportsStreaming}|network=${it.requiresNetwork}"
                                })
                            }
                        }
                    }
                    "voice/listPresets" -> {
                        val list = VoicePresets.ALL
                        buildJsonObject {
                            put("success", true)
                            put("count", list.size)
                            put("presets", list.joinToString("\n") { (id, cfg) ->
                                "$id|lang=${cfg.language}|sampleRate=${cfg.sampleRate}" +
                                    "|ttsVoice=${cfg.ttsVoice ?: "default"}|asrModel=${cfg.asrModel}"
                            })
                        }.toString()
                    }
                    "voice/getDefaultPreset" -> {
                        val modeStr = args["mode"]?.jsonPrimitive?.content ?: "CONVERSATION"
                        val mode = runCatching { VoiceMode.valueOf(modeStr) }
                            .getOrDefault(VoiceMode.CONVERSATION)
                        val cfg = VoicePresets.defaultFor(mode)
                        buildJsonObject {
                            put("success", true)
                            put("mode", mode.name)
                            put("language", cfg.language)
                            put("sampleRate", cfg.sampleRate)
                            put("ttsVoice", cfg.ttsVoice ?: "")
                            put("ttsSpeed", cfg.ttsSpeed)
                            put("ttsPitch", cfg.ttsPitch)
                            put("ttsVolume", cfg.ttsVolume)
                            put("asrModel", cfg.asrModel)
                            put("partialResults", cfg.partialResults)
                            put("maxAlternatives", cfg.maxAlternatives)
                            put("enablePunctuation", cfg.enablePunctuation)
                            put("contextWindow", cfg.contextWindow)
                        }.toString()
                    }
                    else -> errorResponse("unknown method: $method")
                }
            }
        }.getOrElse { t -> errorResponse(t.message ?: t.javaClass.simpleName) }
    }

    override fun invokeAsync(method: String, argsJson: String, onProgress: (Int, String) -> Unit): String {
        onProgress(50, "executing")
        return invoke(method, argsJson)
    }

    override fun openStream(channelName: String): String = channelName
    override fun closeStream(channelName: String) {}

    private fun <T> buildResult(result: BridgeResult<T>, transform: (T) -> JsonElement): String = when (result) {
        is BridgeResult.Success -> buildJsonObject {
            put("success", true)
            put("data", transform(result.value))
        }.toString()
        is BridgeResult.Failure -> buildJsonObject {
            put("success", false)
            put("errorCode", result.error.code)
            put("errorMessage", result.error.message)
        }.toString()
    }

    private fun errorResponse(message: String): String = buildJsonObject {
        put("success", false)
        put("errorMessage", message)
    }.toString()

    /** 从 args 解析可选的 VoiceConfig；无任何 config 字段时返回 mode 的默认预设。 */
    private fun parseVoiceConfig(args: JsonObject, mode: VoiceMode): VoiceConfig {
        val hasConfig = args["language"] != null || args["sampleRate"] != null ||
            args["ttsVoice"] != null || args["asrModel"] != null ||
            args["ttsSpeed"] != null || args["ttsPitch"] != null ||
            args["ttsVolume"] != null || args["contextWindow"] != null
        if (!hasConfig) return VoicePresets.defaultFor(mode)
        return VoiceConfig(
            language = args["language"]?.jsonPrimitive?.content ?: "zh-CN",
            sampleRate = args["sampleRate"]?.jsonPrimitive?.content?.toIntOrNull() ?: 16_000,
            ttsVoice = args["ttsVoice"]?.jsonPrimitive?.content,
            ttsSpeed = args["ttsSpeed"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
            ttsPitch = args["ttsPitch"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
            ttsVolume = args["ttsVolume"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1.0f,
            asrModel = args["asrModel"]?.jsonPrimitive?.content ?: "default",
            partialResults = args["partialResults"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            maxAlternatives = args["maxAlternatives"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
            enablePunctuation = args["enablePunctuation"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
            contextWindow = args["contextWindow"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
        )
    }
}
