package com.apex.apk.market.llm

import com.apex.agent.integration.category.models.BuiltinModelProviders
import com.apex.agent.integration.installed.InstalledManager
import com.apex.sdk.common.ApexLog
import com.apex.sdk.common.ApexSuite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LLM 调用器 — 真实实现 invokeModel。
 *
 * **支持 11 个内置 Provider**（OpenAI 兼容协议）：
 *   - DeepSeek / Claude / OpenAI / 通义千问 / 智谱 GLM
 *   - Moonshot / MiniMax / Baichuan / Ollama / Agnes / ...
 *
 * **认证方式**：
 *   - 优先用 InstalledManager 中已安装的 model platform 的 apiKey
 *   - 没有则用 ProviderConfig.apiKeyEnvVar 对应的环境变量（已注入到 ApexDataStore）
 *   - 都没有则抛 IllegalStateException
 *
 * **协议**：
 *   - 大部分 Provider 用 OpenAI 兼容的 /chat/completions
 *   - Claude 用 Anthropic 自己的 /messages 格式（自动适配）
 *
 * **流式支持**：当前仅支持非流式（一次返回完整结果）。
 * 流式版本可后续扩展为 Flow<String>。
 */
class LlmInvoker(
    private val installedManager: InstalledManager
) {
    private const val TAG_SUB = "LlmInvoker"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 调用 LLM。
     *
     * @param provider Provider 名称（如 "deepseek" / "openai" / "claude"）
     * @param modelName 模型名（如 "deepseek-chat" / "gpt-4o"）
     * @param prompt 用户输入
     * @param maxTokens 最大 token 数
     * @param systemPrompt 系统提示词（可选）
     * @param temperature 温度（0-2，默认 0.7）
     * @return LLM 响应文本
     */
    suspend fun invoke(
        provider: String,
        modelName: String,
        prompt: String,
        maxTokens: Int = 2048,
        systemPrompt: String? = null,
        temperature: Float = 0.7f
    ): String = withContext(Dispatchers.IO) {
        val config = findProviderConfig(provider)
            ?: throw IllegalArgumentException("unknown provider: $provider")

        val apiKey = resolveApiKey(provider, config)
            ?: throw IllegalStateException(
                "no API key for provider '$provider'. " +
                "Install it via Market APK or set ${config.apiKeyEnvVar} in ApexDataStore."
            )

        ApexLog.i(ApexSuite.ApkId.MARKET, "[$TAG_SUB] invoking $provider/$modelName (prompt=${prompt.take(60)}...)")

        // Claude 用 /messages，其他用 OpenAI 兼容的 /chat/completions
        val response = if (provider == "claude") {
            invokeClaude(config.baseUrl, apiKey, modelName, prompt, systemPrompt, maxTokens, temperature)
        } else {
            invokeOpenAiCompatible(config.baseUrl, apiKey, modelName, prompt, systemPrompt, maxTokens, temperature)
        }

        response
    }

    /**
     * 列出所有可用 Provider（含 apiKey 状态）。
     */
    fun listAvailableProviders(): List<ProviderAvailability> {
        return BuiltinModelProviders.providers.map { config ->
            val apiKey = resolveApiKey(config.name, config)
            ProviderAvailability(
                name = config.name,
                displayName = config.displayName,
                baseUrl = config.baseUrl,
                defaultModel = config.defaultModel,
                hasApiKey = apiKey != null,
                apiKeySource = if (apiKey != null) "installed/env" else "missing",
                region = config.region,
                freeQuota = config.freeQuota,
                supportsStreaming = config.supportsStreaming
            )
        }
    }

    /**
     * 检查某 Provider 是否可用（有 apiKey）。
     */
    fun isProviderAvailable(provider: String): Boolean {
        val config = findProviderConfig(provider) ?: return false
        return resolveApiKey(provider, config) != null
    }

    // ============================================================
    // 内部实现
    // ============================================================

    private fun findProviderConfig(provider: String): BuiltinModelProviders.ProviderConfig? {
        return BuiltinModelProviders.providers.firstOrNull { it.name == provider }
    }

    /**
     * 解析 API Key：
     *   1. 从 InstalledManager 查找已安装的 model platform（其 metadata 含 "apiKey"）
     *   2. 从环境变量（通过 System.getenv，但 Android 通常没有）
     *   3. 返回 null
     */
    private fun resolveApiKey(
        provider: String,
        config: BuiltinModelProviders.ProviderConfig
    ): String? {
        // 1. 从已安装的 model platform 查找
        // InstalledItem.metadata 可能含 "apiKey" 字段
        val installed = installedManager.getAll().firstOrNull {
            it.category == com.apex.agent.integration.api.IntegrationCategory.MODEL_PLATFORMS &&
            it.marketId == "builtin_models" &&
            it.name.equals(provider, ignoreCase = true)
        }
        installed?.metadata?.get("apiKey")?.takeIf { it.isNotBlank() }?.let { return it }

        // 2. 从所有已安装中按 name 查找
        val byName = installedManager.getAll().firstOrNull {
            it.name.equals(provider, ignoreCase = true) &&
            it.category == com.apex.agent.integration.api.IntegrationCategory.MODEL_PLATFORMS
        }
        byName?.metadata?.get("apiKey")?.takeIf { it.isNotBlank() }?.let { return it }

        // 3. 环境变量（Android 上通常无效，但保留作为 fallback）
        try {
            val envValue = System.getenv(config.apiKeyEnvVar)
            if (!envValue.isNullOrBlank()) return envValue
        } catch (_: Throwable) {}

        return null
    }

    /**
     * OpenAI 兼容协议（DeepSeek/OpenAI/Qwen/GLM/Moonshot/MiniMax/Baichuan/Ollama/Agnes）。
     */
    private fun invokeOpenAiCompatible(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Float
    ): String {
        val messages = buildJsonArray {
            if (!systemPrompt.isNullOrBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", prompt)
            })
        }

        val requestBody = buildJsonObject {
            put("model", modelName)
            put("messages", messages)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            put("stream", false)
        }.toString()

        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("empty response from $url")
            if (!resp.isSuccessful) {
                ApexLog.w(ApexSuite.ApkId.MARKET, "[$TAG_SUB] LLM call failed: ${resp.code} ${resp.message}, body=${body.take(300)}")
                throw IllegalStateException("LLM call failed: ${resp.code} ${resp.message}")
            }
            return parseOpenAiResponse(body)
        }
    }

    /**
     * Claude（Anthropic）协议。
     */
    private fun invokeClaude(
        baseUrl: String,
        apiKey: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Float
    ): String {
        val requestBody = buildJsonObject {
            put("model", modelName)
            put("max_tokens", maxTokens)
            put("temperature", temperature)
            if (!systemPrompt.isNullOrBlank()) {
                put("system", systemPrompt)
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        val url = baseUrl.trimEnd('/') + "/messages"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: throw IllegalStateException("empty response from Claude")
            if (!resp.isSuccessful) {
                throw IllegalStateException("Claude call failed: ${resp.code} ${resp.message}")
            }
            return parseClaudeResponse(body)
        }
    }

    /**
     * 解析 OpenAI 兼容响应。
     * {"choices":[{"message":{"content":"..."}}]}
     */
    private fun parseOpenAiResponse(body: String): String {
        val root = json.parseToJsonElement(body) as JsonObject
        val choices = root["choices"] as? JsonArray ?: throw IllegalStateException("no choices in response")
        val firstChoice = choices.firstOrNull() as? JsonObject ?: throw IllegalStateException("empty choices")
        val message = firstChoice["message"] as? JsonObject ?: throw IllegalStateException("no message")
        val content = message["content"] as? JsonPrimitive ?: throw IllegalStateException("no content")
        return content.content
    }

    /**
     * 解析 Claude 响应。
     * {"content":[{"type":"text","text":"..."}]}
     */
    private fun parseClaudeResponse(body: String): String {
        val root = json.parseToJsonElement(body) as JsonObject
        val content = root["content"] as? JsonArray ?: throw IllegalStateException("no content in Claude response")
        val firstBlock = content.firstOrNull() as? JsonObject ?: throw IllegalStateException("empty content")
        val text = firstBlock["text"] as? JsonPrimitive ?: throw IllegalStateException("no text")
        return text.content
    }
}

/** Provider 可用性信息。 */
@Serializable
data class ProviderAvailability(
    val name: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val hasApiKey: Boolean,
    val apiKeySource: String,
    val region: String,
    val freeQuota: String,
    val supportsStreaming: Boolean
)
