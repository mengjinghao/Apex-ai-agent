package com.apex.agent.orchestration.core

import com.apex.agent.common.result.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MultiModelOrchestrator @Inject constructor() {

    enum class ModelProvider(val displayName: String, val maxTokens: Int = 4096) {
        OPENAI("OpenAI", 16384),
        ANTHROPIC("Anthropic", 8192),
        GOOGLE("Google", 8192),
        DEEPSEEK("DeepSeek", 8192),
        LOCAL("本地模型", 2048)
    }

    data class ProviderCapability(
        val reasoningScore: Float = 0.5f,
        val codeScore: Float = 0.5f,
        val creativityScore: Float = 0.5f,
        val speedScore: Float = 0.5f,
        val costScore: Float = 0.5f,
        val contextWindow: Int = 4096
    )

    data class ModelConfig(
        val provider: ModelProvider,
        val modelName: String,
        val apiKey: String = "",
        val baseUrl: String = "",
        val temperature: Float = 0.7f,
        val maxTokens: Int = 4096,
        val isEnabled: Boolean = true,
        val capability: ProviderCapability = ProviderCapability()
    )

    data class ModelRequest(
        val query: String,
        val systemPrompt: String = "",
        val context: List<String> = emptyList(),
        val preferredProvider: ModelProvider? = null,
        val requiredCapabilities: List<String> = emptyList(),
        val maxTokens: Int = 4096,
        val temperature: Float = 0.7f
    )

    data class ModelResponse(
        val success: Boolean,
        val content: String? = null,
        val provider: ModelProvider,
        val modelName: String = "",
        val latency: Long = 0,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val error: String? = null
    )
        private val configs = mutableMapOf<ModelProvider, ModelConfig>()
        private val _currentProvider = MutableStateFlow(ModelProvider.OPENAI)
        val currentProvider: StateFlow<ModelProvider> = _currentProvider

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
        private val JSON = "application/json".toMediaType()
        private val providerCapabilities = mapOf(
        ModelProvider.OPENAI to ProviderCapability(
            reasoningScore = 0.85f, codeScore = 0.9f, creativityScore = 0.8f,
            speedScore = 0.75f, costScore = 0.4f, contextWindow = 16384
        ),
        ModelProvider.ANTHROPIC to ProviderCapability(
            reasoningScore = 0.9f, codeScore = 0.85f, creativityScore = 0.85f,
            speedScore = 0.7f, costScore = 0.35f, contextWindow = 8192
        ),
        ModelProvider.GOOGLE to ProviderCapability(
            reasoningScore = 0.7f, codeScore = 0.7f, creativityScore = 0.75f,
            speedScore = 0.8f, costScore = 0.5f, contextWindow = 8192
        ),
        ModelProvider.DEEPSEEK to ProviderCapability(
            reasoningScore = 0.85f, codeScore = 0.85f, creativityScore = 0.7f,
            speedScore = 0.8f, costScore = 0.7f, contextWindow = 8192
        ),
        ModelProvider.LOCAL to ProviderCapability(
            reasoningScore = 0.4f, codeScore = 0.4f, creativityScore = 0.3f,
            speedScore = 0.3f, costScore = 0.9f, contextWindow = 2048
        )
    )
        fun configureProvider(config: ModelConfig) {
        configs[config.provider] = config
    }
        fun switchProvider(provider: ModelProvider): Boolean {
        return configs[provider]?.isEnabled == true && run {
            _currentProvider.value = provider
            true
        }
    }
        fun selectOptimalProvider(request: ModelRequest): ModelProvider {
        if (request.preferredProvider != null && configs[request.preferredProvider]?.isEnabled == true) {
            return request.preferredProvider
        }
        val capabilities = resolveRequiredCapabilities(request)
        val complexity = estimateComplexity(request.query)
        val enabledProviders = configs.values.filter { it.isEnabled }.map { it.provider }
        if (enabledProviders.isEmpty()) return _currentProvider.value

        return enabledProviders.maxByOrNull { provider ->
            computeProviderFit(provider, capabilities, complexity, request)
        } ?: _currentProvider.value
    }
        private fun computeProviderFit(
        provider: ModelProvider,
        requiredCaps: Map<String, Float>,
        complexity: Float,
        request: ModelRequest
    ): Float {
        val cap = providerCapabilities[provider] ?: return 0.5f
        var score = 0f
        var totalWeight = 0f

        val weights = mapOf(
            "reasoning" to 0.3f to cap.reasoningScore,
            "code" to 0.25f to cap.codeScore,
            "creativity" to 0.15f to cap.creativityScore,
            "speed" to 0.1f to cap.speedScore,
            "cost" to 0.2f to cap.costScore
        )
        for ((weightKey, baseScore) in weights) {
            val (weight, _) = weightKey to baseScore
            val requirementWeight = requiredCaps[weightKey] ?: 0.5f
            score += baseScore * requirementWeight
            totalWeight += weight
        }
        val complexityPenalty = if (complexity > 0.8f && cap.contextWindow < 8192) 0.2f else 0f
        val contextFit = if (request.maxTokens <= cap.contextWindow) 0f else 0.15f

        return (score / totalWeight) * (1f - complexityPenalty) * (1f - contextFit)
    }
        private fun resolveRequiredCapabilities(request: ModelRequest): Map<String, Float> {
        val caps = mutableMapOf<String, Float>(
            "reasoning" to 0.5f, "code" to 0.3f, "creativity" to 0.3f,
            "speed" to 0.5f, "cost" to 0.5f
        )
        val lower = request.query.lowercase()
        val systemLower = request.systemPrompt.lowercase()
        if (lower.contains("code") || lower.contains("program") || lower.contains("function") ||
            lower.contains("algorithm") || lower.contains("debug") || lower.contains("bug") ||
            lower.contains("implement") || lower.contains("class") || lower.contains("api") ||
            systemLower.contains("code") || systemLower.contains("programming")
        ) {
            caps["code"] = 0.9f
            caps["reasoning"] = 0.8f
        }
        if (lower.contains("creative") || lower.contains("write") || lower.contains("story") ||
            lower.contains("article") || lower.contains("content") || lower.contains("marketing")
        ) {
            caps["creativity"] = 0.9f
        }
        if (lower.contains("reason") || lower.contains("analy") || lower.contains("complex") ||
            lower.contains("math") || lower.contains("logic") || lower.contains("think") ||
            lower.contains("explain")
        ) {
            caps["reasoning"] = 0.9f
        }
        if (request.preferredProvider != null) {
            caps["cost"] = 0.1f
        }
        if (lower.length > 500 || request.context.size > 5) {
            caps["context"] = 1.0f
        }
        return caps
    }
        fun estimateComplexity(query: String): Float {
        val lower = query.lowercase()
        var complexity = 0.3f
        val lengthScore = (query.length.toFloat() / 2000f).coerceAtMost(0.3f)
        complexity += lengthScore
        val indicators = listOf(
            "complex" to 0.15f, "advanced" to 0.1f, "difficult" to 0.1f,
            "multi-step" to 0.1f, "distributed" to 0.15f, "optimize" to 0.1f,
            "concurrent" to 0.1f, "security" to 0.1f, "integrat" to 0.08f,
            "architect" to 0.12f, "scalable" to 0.1f, "pipeline" to 0.08f,
            "algorithm" to 0.12f
        )
        for ((keyword, delta) in indicators) {
            if (lower.contains(keyword)) complexity += delta
        }
        if (lower.lines().size > 15) complexity += 0.1f
        return complexity.coerceIn(0f, 1f)
    }

    suspend fun executeRequest(request: ModelRequest): Result<ModelResponse> {
        val startTime = System.currentTimeMillis()
        val provider = selectOptimalProvider(request)
        val config = configs[provider]
            ?: return Result.Failure(IllegalStateException("Provider not configured: $provider"))
        val modelName = config.modelName
        val baseUrl = config.baseUrl.ifEmpty { getDefaultBaseUrl(provider) }
        return try {
            val content = withContext(Dispatchers.IO) {
                callLLM(baseUrl, config.apiKey, modelName, request)
            }
            Result.Success(
                ModelResponse(
                    success = true,
                    content = content,
                    provider = provider,
                    modelName = modelName,
                    latency = System.currentTimeMillis() - startTime
                )
            )
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }
        private fun callLLM(baseUrl: String, apiKey: String, modelName: String, request: ModelRequest): String {
        val messages = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", request.systemPrompt)
            })
        }
        for (ctx in request.context) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", ctx)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", request.query)
        })
        val body = JSONObject().apply {
            put("model", modelName)
            put("messages", messages)
            put("temperature", request.temperature.toDouble())
            put("max_tokens", request.maxTokens)
        }
        val requestBody = body.toString().toRequestBody(JSON)
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()
        val response = httpClient.newCall(httpRequest).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        if (!response.isSuccessful) {
            throw IOException("API error ${response.code}: $responseBody")
        }
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IOException("No choices in response: $responseBody")
        }
        return choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content", "")
            ?: ""
    }
        private fun getDefaultBaseUrl(provider: ModelProvider): String {
        return when (provider) {
            ModelProvider.OPENAI -> "https://api.openai.com/v1"
            ModelProvider.ANTHROPIC -> "https://api.anthropic.com/v1"
            ModelProvider.GOOGLE -> "https://generativelanguage.googleapis.com/v1beta"
            ModelProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
            ModelProvider.LOCAL -> "http://localhost:11434/v1"
        }
    }
        fun getAvailableProviders(): List<ModelProvider> {
        return configs.values.filter { it.isEnabled }.map { it.provider }
    }
        fun getConfig(provider: ModelProvider): ModelConfig? = configs[provider]
}
