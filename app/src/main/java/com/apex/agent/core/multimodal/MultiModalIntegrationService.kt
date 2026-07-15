package com.apex.agent.core.multimodal

import android.content.Context
import com.apex.api.voice.VoiceService
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MultiModalIntegrationService(
    private val context: Context,
    private val multiModalFusionEngine: MultiModalFusionEngine,
    private val voiceService: VoiceService? = null
) {

    companion object {
        private const val TAG = "MultiModalIntegrationService"
    }

    suspend fun processMixedInput(
        textInput: String?,
        speechInput: ByteArray?,
        imageBase64: String?,
        context: String = ""
    ): FusionResult = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Processing mixed input - text: ${textInput != null}, speech: ${speechInput != null}, image: ${imageBase64 != null}")
        val modalities = mutableListOf<ModalData>()

        textInput?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(
                type = ModalType.TEXT,
                data = it,
                confidence = 1.0f
            ))
        }

        speechInput?.let { audioData ->
            val speechText = transcribeSpeech(audioData)
            modalities.add(ModalData(
                type = ModalType.SPEECH,
                data = speechText ?: "语音输入",
                metadata = mapOf("duration" to "unknown"),
                confidence = speechText?.let { 0.9f } ?: 0.5f
            ))
        }

        imageBase64?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(
                type = ModalType.IMAGE,
                data = it,
                metadata = mapOf("format" to "base64"),
                confidence = 0.85f
            ))
        }
        if (modalities.isEmpty()) {
            throw IllegalArgumentException("至少需要提供一种输入模�?)
        }
        val input = MultiModalInput(
            modalities = modalities,
            context = context
        )

        multiModalFusionEngine.processMultiModalInput(input)
    }
        private suspend fun transcribeSpeech(audioData: ByteArray): String? {
        return null
    }

    suspend fun processTextWithImage(text: String, imageBase64: String): FusionResult {
        return processMixedInput(
            textInput = text,
            speechInput = null,
            imageBase64 = imageBase64
        )
    }

    suspend fun processSpeechWithImage(speechData: ByteArray, imageBase64: String): FusionResult {
        return processMixedInput(
            textInput = null,
            speechInput = speechData,
            imageBase64 = imageBase64
        )
    }

    suspend fun processFullMultiModal(
        text: String?,
        speechData: ByteArray?,
        imageBase64: String?,
        videoInfo: String?,
        fileInfo: String?,
        structuredData: String?
    ): FusionResult = withContext(Dispatchers.IO) {

        val modalities = mutableListOf<ModalData>()

        text?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(ModalType.TEXT, it))
        }

        speechData?.let {
            val transcribed = transcribeSpeech(it)
            modalities.add(ModalData(
                type = ModalType.SPEECH,
                data = transcribed ?: "语音输入",
                confidence = transcribed?.let { 0.9f } ?: 0.5f
            ))
        }

        imageBase64?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(ModalType.IMAGE, it))
        }

        videoInfo?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(ModalType.VIDEO, it))
        }

        fileInfo?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(ModalType.FILE, it))
        }

        structuredData?.takeIf { it.isNotBlank() }?.let {
            modalities.add(ModalData(ModalType.STRUCTURED_DATA, it))
        }
        val input = MultiModalInput(modalities = modalities)
        multiModalFusionEngine.processMultiModalInput(input)
    }

    suspend fun generateSpokenResponse(fusionResult: FusionResult): Boolean {
        voiceService?.let { service ->
            if (!service.isInitialized) {
                service.initialize()
            }
        val responseText = fusionResult.insights
                .filter { it.type == InsightType.FACTS || it.type == InsightType.SUMMARY }
                .joinToString("\n") { it.content }
        return service.speak(responseText)
        }
        return false
    }

    suspend fun processAndRespond(
        text: String?,
        speechData: ByteArray?,
        imageBase64: String?
    ): FusionResult {
        val result = processMixedInput(text, speechData, imageBase64)
        generateSpokenResponse(result)
        return result
    }
        fun isVoiceAvailable(): Boolean {
        return voiceService?.isInitialized ?: false
    }
        fun getSupportedModalities(): List<ModalType> {
        return listOf(
            ModalType.TEXT,
            ModalType.SPEECH,
            ModalType.IMAGE,
            ModalType.VIDEO,
            ModalType.FILE,
            ModalType.STRUCTURED_DATA
        )
    }
}