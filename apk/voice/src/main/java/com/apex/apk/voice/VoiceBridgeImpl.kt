package com.apex.apk.voice

import com.apex.sdk.bridge.IApkBridgeInternal
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import com.apex.sdk.common.BridgeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
                        buildResult(facade.speak(text, lang)) { JsonObject(emptyMap()) }
                    }
                    "voice/speakAsync" -> {
                        val text = args["text"]?.jsonPrimitive?.content ?: ""
                        val lang = args["language"]?.jsonPrimitive?.content ?: "zh-CN"
                        buildResult(facade.speakAsync(text, lang)) { JsonPrimitive(it) }
                    }
                    "voice/stopSpeaking" -> {
                        buildResult(facade.stopSpeaking()) { JsonObject(emptyMap()) }
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

    private fun <T> buildResult(result: BridgeResult<T>, transform: (T) -> JsonObject): String = when (result) {
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
}
