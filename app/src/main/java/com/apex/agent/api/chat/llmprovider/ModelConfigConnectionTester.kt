package com.apex.api.chat.llmprovider

import android.content.Context
import com.apex.agent.R
import com.apex.data.model.ModelConfigData
import com.apex.data.model.ToolParameterSchema
import com.apex.data.model.ToolPrompt
import com.apex.data.model.getModelByIndex
import com.apex.data.model.getValidModelIndex
import com.apex.data.preferences.ModelConfigManager
import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.PromptTurnKind
import com.apex.core.chat.hooks.toPromptTurns
import com.apex.util.AssetCopyUtils
import com.apex.util.ChatMarkupRegex
import com.apex.util.ImagePoolManager
import com.apex.util.MediaPoolManager
import kotlinx.coroutines.CancellationException

enum class ModelConnectionTestType {
    CHAT,
    TOOL_CALL,
    IMAGE,
    AUDIO,
    VIDEO
}

data class ModelConnectionTestItem(
    val type: ModelConnectionTestType,
    val success: Boolean,
    val error: String? = null
)

data class ModelConnectionTestReport(
    val configId: String,
    val configName: String,
    val providerType: String,
    val requestedModelIndex: Int,
    val actualModelIndex: Int,
    val testedModelName: String,
    val items: List<ModelConnectionTestItem>
) {
    val success: Boolean
        get() = items.all { it.success }
}

object ModelConfigConnectionTester {
    suspend fun run(
        context: Context,
        modelConfigManager: ModelConfigManager,
        config: ModelConfigData,
        requestedModelIndex: Int = 0,
        onActiveServiceChanged: (AIService) -> Unit = {}
    ): ModelConnectionTestReport {
        val actualModelIndex = getValidModelIndex(config.modelName, requestedModelIndex)
        val testedModelName = getModelByIndex(config.modelName, actualModelIndex)
        val configForTest = config.copy(modelName = testedModelName)
        val items = mutableListOf<ModelConnectionTestItem>()
        val service =
            AIServiceFactory.createService(
                config = configForTest,
                modelConfigManager = modelConfigManager,
                context = context
            )
        onActiveServiceChanged(service)

        try {
            val parameters = modelConfigManager.getModelParametersForConfig(configForTest.id)

            suspend fun runCase(type: ModelConnectionTestType, block: suspend () -> Unit) {
                val result =
                    try {
                        block()
                        Result.success(Unit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                items.add(
                    ModelConnectionTestItem(
                        type = type,
                        success = result.isSuccess,
                        error = result.exceptionOrNull()?.message
                    )
                )
            }

            runCase(ModelConnectionTestType.CHAT) {
                service.sendMessage(
                    context,
                    listOf(PromptTurn(kind = PromptTurnKind.USER, content = "Hi")),
                    parameters,
                    stream = false,
                    enableRetry = false
                ).collect { }
            }
        if (configForTest.enableToolCall) {
                runCase(ModelConnectionTestType.TOOL_CALL) {
                    val availableTools =
                        listOf(
                            ToolPrompt(
                                name = "echo",
                                description = "Echoes the provided text.",
                                parametersStructured =
                                    listOf(
                                        ToolParameterSchema(
                                            name = "text",
                                            type = "string",
                                            description = "Text to echo.",
                                            required = true
                                        )
                                    )
                            )
                        )

                    suspend fun runToolCallTest(toolName: String) {
                        val toolTagName = ChatMarkupRegex.generateRandomToolTagName()
        val toolResultTagName = ChatMarkupRegex.generateRandomToolResultTagName()
        val testHistory = mutableListOf("system" to "You are a helpful assistant.")
                        testHistory.add(
                            "assistant" to
                                "<${toolTagName} name=\"${toolName}\"><param name=\"text\">ping</param></${toolTagName}>"
                        )
                        testHistory.add(
                            "user" to
                                "<${toolResultTagName} name=\"${toolName}\" status=\"success\"><content>pong</content></${toolResultTagName}>"
                        )
                        service.sendMessage(
                            context,
                            testHistory.toPromptTurns(),
                            parameters,
                            stream = false,
                            availableTools = availableTools,
                            enableRetry = false
                        ).collect { }
                    }

                    runToolCallTest("echo")
                }
            }
        if (configForTest.enableDirectImageProcessing) {
                runCase(ModelConnectionTestType.IMAGE) {
                    val imageFile = AssetCopyUtils.copyAssetToCache(context, "test/1.jpg")
        val imageId = ImagePoolManager.addImage(imageFile.absolutePath)
        if (imageId == "error") {
                        throw IllegalStateException("Failed to create test image")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.image(context, imageId))
                                append("\n")
                                append(context.getString(R.string.conversation_analyze_image_prompt))
                            }
                        service.sendMessage(
                            context,
                            listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                            parameters,
                            stream = false,
                            enableRetry = false
                        ).collect { }
                    } finally {
                        ImagePoolManager.removeImage(imageId)
                        runCatching { imageFile.delete() }
                    }
                }
            }
        if (configForTest.enableDirectAudioProcessing) {
                runCase(ModelConnectionTestType.AUDIO) {
                    val audioFile = AssetCopyUtils.copyAssetToCache(context, "test/1.mp3")
        val audioId = MediaPoolManager.addMedia(audioFile.absolutePath, "audio/mpeg")
        if (audioId == "error") {
                        throw IllegalStateException("Failed to create test audio")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.audio(context, audioId))
                                append("\n")
                                append(context.getString(R.string.conversation_analyze_audio_prompt))
                            }
                        service.sendMessage(
                            context,
                            listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                            parameters,
                            stream = false,
                            enableRetry = false
                        ).collect { }
                    } finally {
                        MediaPoolManager.removeMedia(audioId)
                        runCatching { audioFile.delete() }
                    }
                }
            }
        if (configForTest.enableDirectVideoProcessing) {
                runCase(ModelConnectionTestType.VIDEO) {
                    val videoFile = AssetCopyUtils.copyAssetToCache(context, "test/1.mp4")
        val videoId = MediaPoolManager.addMedia(videoFile.absolutePath, "video/mp4")
        if (videoId == "error") {
                        throw IllegalStateException("Failed to create test video")
                    }
                    try {
                        val prompt =
                            buildString {
                                append(MediaLinkBuilder.video(context, videoId))
                                append("\n")
                                append(context.getString(R.string.conversation_analyze_video_prompt))
                            }
                        service.sendMessage(
                            context,
                            listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
                            parameters,
                            stream = false,
                            enableRetry = false
                        ).collect { }
                    } finally {
                        MediaPoolManager.removeMedia(videoId)
                        runCatching { videoFile.delete() }
                    }
                }
            }
        } catch (e: CancellationException) {
            runCatching { service.cancelStreaming() }
        throw e
        } catch (e: Exception) {
            if (items.none { it.type == ModelConnectionTestType.CHAT }) {
                items.add(
                    ModelConnectionTestItem(
                        type = ModelConnectionTestType.CHAT,
                        success = false,
                        error = e.message ?: "Unknown error"
                    )
                )
            }
        } finally {
            onActiveServiceChanged(null)
            service.release()
        }
        return ModelConnectionTestReport(
            configId = configForTest.id,
            configName = configForTest.name,
            providerType = configForTest.apiProviderType.name,
            requestedModelIndex = requestedModelIndex,
            actualModelIndex = actualModelIndex,
            testedModelName = testedModelName,
            items = items
        )
    }
}
