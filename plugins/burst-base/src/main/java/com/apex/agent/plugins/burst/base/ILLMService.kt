package com.apex.agent.plugins.burst.base

data class LLMConfig(
    val modelPath: String = "",
    val nThreads: Int = 4,
    val nCtx: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.1f,
    val maxTokens: Int = 2048,
    val frequencyPenalty: Float = 0f,
    val presencePenalty: Float = 0f,

    val enableUtilityProcessor: Boolean = false,
    val utilityApiKey: String = "",
    val utilityEndpoint: String = "https://api.openai.com/v1/chat/completions",
    val utilityModelName: String = "gpt-4o-mini",
    val utilityProvider: String = "OPENAI",
    val utilityMaxTokens: Int = 512,
    val utilityTemperature: Float = 0.1f,
    val utilityRecordToMemory: Boolean = true,
    val utilityTimeoutMs: Long = 5000L,
)

interface ILLMService {
    fun isAvailable(): Boolean
    fun getUnavailableReason(): String
    fun initialize(config: LLMConfig): Boolean
    suspend fun generate(prompt: String, maxTokens: Int = 2048): String
    suspend fun generateStream(prompt: String, maxTokens: Int, onToken: (String) -> Boolean): Boolean
    suspend fun chat(messages: List<ChatMessage>, maxTokens: Int = 2048): String
    fun countTokens(text: String): Int
    fun release()
}

data class ChatMessage(
    val role: String,
    val content: String
)
