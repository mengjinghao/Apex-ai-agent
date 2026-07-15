package com.apex.agent.core.provider

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class ProviderProfile(
    val name: String,
    val baseUrl: String,
    val apiKeyEnvVar: String,
    val authType: AuthType = AuthType.API_KEY,
    val supportsStreaming: Boolean = true,
    val defaultModel: String = "",
    val defaultAuxModel: String = "",
    private val hostname: String? = null,
    val modelsUrl: String = "",
    val icon: String = "",
    val description: String = "",
    val providerType: ProviderType = ProviderType.CLOUD
) {

    enum class AuthType {
        API_KEY,
        OAUTH,
        NONE
    }

    enum class ProviderType {
        CLOUD,
        LOCAL,
        SELF_HOSTED
    }
        fun getHostname(): String {
        return hostname ?: run {
            val url = baseUrl.removePrefix("https://").removePrefix("http://")
            url.split("/")[0]
        }
    }

    open fun prepareMessages(messages: List<Message>): List<Message> {
        return messages
    }

    open fun buildExtraBody(ctx: Map<String, Any>): Map<String, Any> {
        return emptyMap()
    }

    open fun buildApiKwargsExtras(ctx: Map<String, Any>): Pair<Map<String, Any>, Map<String, Any>> {
        return emptyMap() to emptyMap()
    }

    open suspend fun fetchModels(apiKey: String? = null): List<ModelInfo> {
        return withContext(Dispatchers.IO) {
            val url = if (modelsUrl.isNotEmpty()) modelsUrl else "${baseUrl}/models"
            try {
                val client = OkHttpClient()
        val request = Request.Builder()
                    .url(url)
                    .apply {
                        apiKey?.let {
                            addHeader("Authorization", "Bearer ${it}")
                        }
                    }
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.string()?.let { parseModels(it) } ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
            } catch (e: Exception) {
                LoggerFactory.getLogger(javaClass).warn("Failed to fetch models for ${name}: ${e.message}")
                emptyList()
            }
        }
    }

    protected open fun parseModels(json: String): List<ModelInfo> {
        return emptyList()
    }

    data class Message(
        @SerializedName("role") val role: String,
        @SerializedName("content") val content: String
    )

    data class ModelInfo(
        val id: String,
        val name: String,
        val description: String = "",
        val maxTokens: Int = 8192,
        val supportsVision: Boolean = false,
        val pricing: Pricing? = null
    )

    data class Pricing(
        val input: Double = 0.0,
        val output: Double = 0.0,
        val unit: String = "per_1k_tokens"
    )

    companion object {
        fun openAI(): ProviderProfile {
            return ProviderProfile(
                name = "openai",
                baseUrl = "https://api.openai.com/v1",
                apiKeyEnvVar = "OPENAI_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "gpt-4o",
                defaultAuxModel = "gpt-4o-mini",
                modelsUrl = "https://api.openai.com/v1/models"
            )
        }
        fun anthropic(): ProviderProfile {
            return ProviderProfile(
                name = "anthropic",
                baseUrl = "https://api.anthropic.com/v1",
                apiKeyEnvVar = "ANTHROPIC_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "claude-3-5-sonnet-20240620",
                defaultAuxModel = "claude-3-haiku-20240307",
                modelsUrl = "https://api.anthropic.com/v1/models"
            )
        }
        fun google(): ProviderProfile {
            return ProviderProfile(
                name = "google",
                baseUrl = "https://generativelanguage.googleapis.com/v1",
                apiKeyEnvVar = "GOOGLE_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "gemini-1.5-pro",
                defaultAuxModel = "gemini-1.5-flash"
            )
        }
        fun openRouter(): ProviderProfile {
            return ProviderProfile(
                name = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                apiKeyEnvVar = "OPENROUTER_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "anthropic/claude-3-5-sonnet",
                modelsUrl = "https://openrouter.ai/api/v1/models"
            )
        }
        fun ollama(): ProviderProfile {
            return ProviderProfile(
                name = "ollama",
                baseUrl = "http://localhost:11434/api",
                apiKeyEnvVar = "",
                authType = AuthType.NONE,
                supportsStreaming = true,
                defaultModel = "llama3",
                providerType = ProviderType.LOCAL
            )
        }
        fun kimi(): ProviderProfile {
            return ProviderProfile(
                name = "kimi",
                baseUrl = "https://api.moonshot.cn/v1",
                apiKeyEnvVar = "KIMI_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "moonshot-v1-8k",
                defaultAuxModel = "moonshot-v1-32k"
            )
        }
        fun minimax(): ProviderProfile {
            return ProviderProfile(
                name = "minimax",
                baseUrl = "https://api.minimax.chat/v1",
                apiKeyEnvVar = "MINIMAX_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "abab6.5s-chat"
            )
        }
        fun zai(): ProviderProfile {
            return ProviderProfile(
                name = "zai",
                baseUrl = "https://api.z.ai/v1",
                apiKeyEnvVar = "ZAI_API_KEY",
                authType = AuthType.API_KEY,
                supportsStreaming = true,
                defaultModel = "chatglm3-6b"
            )
        }
    }
}

class ProviderRegistry private constructor() {

    private val logger = LoggerFactory.getLogger(ProviderRegistry::class.java)
        private val providers = ConcurrentHashMap<String, ProviderProfile>()
        private var isInitialized = false

    fun registerProvider(profile: ProviderProfile, overwrite: Boolean = false) {
        if (providers.containsKey(profile.name) && !overwrite) {
            logger.warn("Provider ${profile.name} already registered, skipping")
        return
        }
        providers[profile.name] = profile
        logger.info("Registered provider: ${profile.name}")
    }
        fun registerCustomProvider(
        name: String,
        baseUrl: String,
        apiKeyEnvVar: String = "",
        configure: ProviderProfile.() -> Unit = {}
    ) {
        val profile = ProviderProfile(
            name = name,
            baseUrl = baseUrl,
            apiKeyEnvVar = apiKeyEnvVar
        ).apply(configure)
        registerProvider(profile)
    }
        fun getProviderProfile(name: String): ProviderProfile? {
        ensureInitialized()
        return providers[name]
    }
        fun listProviders(): List<ProviderProfile> {
        ensureInitialized()
        return providers.values.toList()
    }
        fun findProviderByHostname(hostname: String): ProviderProfile? {
        ensureInitialized()
        return providers.values.firstOrNull { it.getHostname() == hostname }
    }
        fun findProviderByUrl(url: String): ProviderProfile? {
        ensureInitialized()
        val hostname = url.removePrefix("https://").removePrefix("http://").split("/")[0]
    return findProviderByHostname(hostname)
    }
        fun findProvidersByModel(modelId: String): List<ProviderProfile> {
        ensureInitialized()
        return providers.values.filter { it.defaultModel == modelId }
    }
        fun registerDefaultProviders() {
        registerProvider(ProviderProfile.openAI())
        registerProvider(ProviderProfile.anthropic())
        registerProvider(ProviderProfile.google())
        registerProvider(ProviderProfile.openRouter())
        registerProvider(ProviderProfile.ollama())
        registerProvider(ProviderProfile.kimi())
        registerProvider(ProviderProfile.minimax())
        registerProvider(ProviderProfile.zai())
    }
        private fun ensureInitialized() {
        if (!isInitialized) {
            registerDefaultProviders()
            isInitialized = true
        }
    }
        fun isProviderRegistered(name: String): Boolean {
        return providers.containsKey(name)
    }
        fun getProviderCount(): Int {
        return providers.size
    }

    companion object {
        @Volatile
        private var instance: ProviderRegistry? = null

        fun getInstance(): ProviderRegistry {
            return instance ?: synchronized(this) {
                instance ?: ProviderRegistry().also { instance = it }
            }
        }
    }
}
