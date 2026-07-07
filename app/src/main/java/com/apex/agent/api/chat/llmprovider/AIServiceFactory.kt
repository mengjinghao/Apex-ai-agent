package com.apex.api.chat.llmprovider

import android.content.Context
import com.apex.util.AppLogger
import com.apex.data.model.ApiProviderType
import com.apex.data.model.ModelConfigData
import com.apex.data.preferences.ModelConfigManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONObject

/**
 * A factory for creating and managing a shared OkHttpClient instance.
 * Using a shared client allows for efficient reuse of connections and resources.
 */
private object SharedHttpClient {
    val instance: OkHttpClient by lazy {
        UnsafeModelSsl.apply(
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(1000, TimeUnit.SECONDS)
                .writeTimeout(1000, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        )
            .build()
    }
}

/**
 * [架构优化] Provider实例缓存
 * key: config.id
 * value: Pair<指纹(用于检测配置变更）, AIService>
 *
 * 收益�?
 * - 避免每次请求重复构�?Provider 实例（反�?初始�?分配开销�?
 * - 减少 GC 压力（LlamaProvider 较重�?
 * - 利用 ConcurrentHashMap 的线程安全读�?
 */
private object ServiceCache {
    private val cache = ConcurrentHashMap<String, Pair<String, AIService>>()

    /** 生成用于比较配置是否变更的指�?*/
    fun fingerprint(config: ModelConfigData): String {
        return buildString {
            append(config.apiProviderType)
            append('|').append(config.apiEndpoint)
            append('|').append(config.modelName)
            append('|').append(config.apiKey)
            append('|').append(config.useMultipleApiKeys)
            append('|').append(config.customHeaders)
            append('|').append(config.enableDirectImageProcessing)
            append('|').append(config.enableDirectAudioProcessing)
            append('|').append(config.enableDirectVideoProcessing)
            append('|').append(config.enableToolCall)
            append('|').append(config.enableGoogleSearch)
            append('|').append(config.llamaThreadCount)
            append('|').append(config.llamaContextSize)
            append('|').append(config.llamaGpuLayers)
        }
    }

    fun get(configId: String, expectedFingerprint: String): AIService? {
        val cached = cache[configId] ?: return null
        return if (cached.first == expectedFingerprint) cached.second else null
    }

    fun put(configId: String, fingerprint: String, service: AIService) {
        cache[configId] = fingerprint to service
    }

    fun invalidate(configId: String) {
        cache.remove(configId)
    }

    fun clearAll() {
        cache.clear()
    }

    fun size(): Int = cache.size
}

/** AI服务工厂，根据提供商类型创建相应的AIService实例 */
object AIServiceFactory {

    private const val TAG = "AIServiceFactory"

    /**
     * 解析自定义请求头的JSON字符串为Map
     */
    private fun parseCustomHeaders(customHeadersJson: String): Map<String, String> {
        return try {
            val headers = mutableMapOf<String, String>()
            if (customHeadersJson.isNotEmpty() && customHeadersJson != "{}") {
                val jsonObject = JSONObject(customHeadersJson)
                for (key in jsonObject.keys()) {
                    headers[key] = jsonObject.getString(key)
                }
            }
            headers
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析自定义请求头失败", e)
            emptyMap()
        }
    }

    /** 显式失效某个配置的缓存（当用户修改了该配置时调用�?*/
    fun invalidateCache(configId: String) {
        ServiceCache.invalidate(configId)
        AppLogger.d(TAG, "缓存失效: configId=${configId}")
    }

    /** 清空全部缓存（切换账�?/ 重置场景�?*/
    fun clearAllCaches() {
        ServiceCache.clearAll()
        AppLogger.d(TAG, "全部缓存已清�?)
    }

    /**
     * [优化版] 创建或复用AI服务实例
     * - 相同 config.id + 相同参数指纹 �?复用缓存实例
     * - 指纹不匹�?�?重新构造并更新缓存
     */
    fun createService(
        config: ModelConfigData,
        modelConfigManager: ModelConfigManager,
        context: Context
    ): AIService {
        val fingerprint = ServiceCache.fingerprint(config)
        val cached = ServiceCache.get(config.id, fingerprint)
        if (cached != null) {
            reportCacheEvent(context, isHit = true)
            return cached
        }

        reportCacheEvent(context, isHit = false)
        AppLogger.d(TAG, "缓存 miss，构造新 Provider (id=${config.id}, type=${config.apiProviderType})")
        val service = buildServiceInternal(config, modelConfigManager, context)
        ServiceCache.put(config.id, fingerprint, service)
        AppLogger.d(TAG, "当前缓存大小: ${ServiceCache.size()}")
        return service
    }

    /**
     * 轻量级缓存事件上�?(使用反射避免直接耦合 ArchitectureHealthCheck)
     */
    private fun reportCacheEvent(context: Context, isHit: Boolean) {
        try {
            val healthClass = Class.forName("com.apex.agent.core.application.ArchitectureHealthCheck")
            val healthInstance = healthClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val methodName = if (isHit) "recordCacheHit" else "recordCacheMiss"
            healthClass.getMethod(methodName).invoke(healthInstance)
            if (!isHit) {
                healthClass.getMethod("updateCacheSize", Int::class.javaPrimitiveType)
                    .invoke(healthInstance, ServiceCache.size())
            }
        } catch (_: Throwable) {
            // 静默失败：健康检查模块非必需功能，不影响主流�?
        }
    }

    /**
     * 内部方法：真正构�?Provider 实例
     */
    private fun buildServiceInternal(
        config: ModelConfigData,
        modelConfigManager: ModelConfigManager,
        context: Context
    ): AIService {
        val httpClient = SharedHttpClient.instance
        val customHeaders = parseCustomHeaders(config.customHeaders)

        val apiKeyProvider = if (config.useMultipleApiKeys) {
            MultiApiKeyProvider(config.id, modelConfigManager)
        } else {
            SingleApiKeyProvider(config.apiKey)
        }

        val supportsVision = config.enableDirectImageProcessing
        val supportsAudio = config.enableDirectAudioProcessing
        val supportsVideo = config.enableDirectVideoProcessing
        val enableToolCall = config.enableToolCall

        return when (config.apiProviderType) {
            // OpenAI 系列
            ApiProviderType.OPENAI,
            ApiProviderType.OPENAI_GENERIC ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // OpenAI Responses API
            ApiProviderType.OPENAI_RESPONSES,
            ApiProviderType.OPENAI_RESPONSES_GENERIC ->
                OpenAIResponsesProvider(
                    responsesApiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    responsesProviderType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Claude 系列
            ApiProviderType.ANTHROPIC,
            ApiProviderType.ANTHROPIC_GENERIC -> ClaudeProvider(config.apiEndpoint, apiKeyProvider, config.modelName, httpClient, customHeaders, config.apiProviderType, enableToolCall)

            // Gemini 系列
            ApiProviderType.GOOGLE,
            ApiProviderType.GEMINI_GENERIC -> GeminiProvider(config.apiEndpoint, apiKeyProvider, config.modelName, httpClient, customHeaders, config.apiProviderType, config.enableGoogleSearch, enableToolCall)

            // 国内厂商
            ApiProviderType.BAIDU,
            ApiProviderType.XUNFEI,
            ApiProviderType.ZHIPU,
            ApiProviderType.BAICHUAN ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 阿里�?
            ApiProviderType.ALIYUN ->
                QwenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 月之暗面 (Moonshot)
            ApiProviderType.MOONSHOT ->
                KimiProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // DeepSeek
            ApiProviderType.DEEPSEEK ->
                DeepseekProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Mistral
            ApiProviderType.MISTRAL ->
                MistralProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 硅基流动
            ApiProviderType.SILICONFLOW ->
                QwenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // iFlow
            ApiProviderType.IFLOW ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // OpenRouter
            ApiProviderType.OPENROUTER ->
                OpenRouterProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 无问芯穹
            ApiProviderType.INFINIAI ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 支付宝百�?
            ApiProviderType.ALIPAY_BAILING ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 豆包
            ApiProviderType.DOUBAO ->
                DoubaoAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // NVIDIA
            ApiProviderType.NVIDIA ->
                NvidiaAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 派欧�?
            ApiProviderType.PPINFRA ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Novita AI
            ApiProviderType.NOVITA ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // AWS Bedrock - 使用专用Provider处理AWS签名
            ApiProviderType.AWS_BEDROCK ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Azure OpenAI - 使用专用Provider处理Azure认证
            ApiProviderType.AZURE_OPENAI ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 国际厂商 - 大部分使用OpenAI兼容格式
            ApiProviderType.COHERE,
            ApiProviderType.REPLICATE,
            ApiProviderType.TOGETHER_AI,
            ApiProviderType.PERPLEXITY,
            ApiProviderType.GROQ,
            ApiProviderType.CLOUDFLARE_AI,
            ApiProviderType.CEREBRAS,
            ApiProviderType.FIREWORKS_AI,
            ApiProviderType.AI21,
            ApiProviderType.ALEPH_ALPHA,
            ApiProviderType.ANYSCALE,
            ApiProviderType.BASETEN,
            ApiProviderType.WRITER,
            ApiProviderType.QUORA_POE ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // 本地部署 - OpenAI兼容格式
            ApiProviderType.LMSTUDIO,
            ApiProviderType.LOCALAI,
            ApiProviderType.VLLM,
            ApiProviderType.TEXT_GENERATION_WEBUI,
            ApiProviderType.JAN,
            ApiProviderType.FASTCHAT ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Ollama - 使用专用Provider
            ApiProviderType.OLLAMA ->
                OllamaProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )

            // Tabby - 代码补全专用
            ApiProviderType.TABBY ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = false,
                    supportsAudio = false,
                    supportsVideo = false,
                    enableToolCall = false
                )

            // Coding专用模型 - 通过OpenAI兼容格式
            ApiProviderType.CODECLLAMA,
            ApiProviderType.STARCODER,
            ApiProviderType.WIZARDCODER,
            ApiProviderType.PHICODER ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = false,
                    supportsAudio = false,
                    supportsVideo = false,
                    enableToolCall = enableToolCall
                )

            // 其他提供商（自定义端点）
            ApiProviderType.OTHER ->
                OpenAIProvider(
                    apiEndpoint = config.apiEndpoint,
                    apiKeyProvider = apiKeyProvider,
                    modelName = config.modelName,
                    client = httpClient,
                    customHeaders = customHeaders,
                    providerType = config.apiProviderType,
                    supportsVision = supportsVision,
                    supportsAudio = supportsAudio,
                    supportsVideo = supportsVideo,
                    enableToolCall = enableToolCall
                )
        }
    }
}