package com.apex.core.config

import android.content.Context
import com.apex.data.model.ModelConfigData
import com.apex.data.preferences.FunctionalConfigManager
import com.apex.data.preferences.ModelConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelConfigService private constructor(private val context: Context) {

    // 单例模式
    companion object {
        private var instance: ModelConfigService? = null
        private val mutex = Mutex()

        fun getInstance(context: Context): ModelConfigService {
            return instance ?: synchronized(this) {
                instance ?: ModelConfigService(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }
    }

    // 依赖的管理器
    private val modelConfigManager by lazy { ModelConfigManager(context) }
    private val functionalConfigManager by lazy { FunctionalConfigManager(context) }

    // 当前活跃的配置ID
    private val _activeConfigId = MutableStateFlow(ModelConfigManager.DEFAULT_CONFIG_ID)
    val activeConfigId: StateFlow<String> = _activeConfigId.asStateFlow()

    // 当前活跃的配�?
    private val _activeConfig = MutableStateFlow<ModelConfigData?>(null)
    val activeConfig: StateFlow<ModelConfigData?> = _activeConfig.asStateFlow()

    // 配置列表
    private val _configList = MutableStateFlow<List<String>>(emptyList())
    val configList: StateFlow<List<String>> = _configList.asStateFlow()

    // 配置缓存，使用ConcurrentHashMap提高并发性能
    private val configCache = ConcurrentHashMap<String, ModelConfigData>()

    // 缓存操作互斥�?
    private val cacheMutex = Mutex()

    // 配置变更通知
    private val _configChangeEvent = MutableSharedFlow<ConfigChangeEvent>()
    val configChangeEvent = _configChangeEvent.asSharedFlow()

    // 配置变更事件类型
    sealed class ConfigChangeEvent {
        data class ActiveConfigChanged(val configId: String) : ConfigChangeEvent()
        data class ConfigAdded(val configId: String) : ConfigChangeEvent()
        data class ConfigDeleted(val configId: String) : ConfigChangeEvent()
        data class ConfigUpdated(val configId: String) : ConfigChangeEvent()
    }

    // 配置模板数据�?
    data class ModelConfigTemplate(
        val name: String,
        val providerType: com.apex.data.model.ApiProviderType,
        val modelName: String,
        val apiEndpoint: String = ""
    )

    // 预定义的配置模板 - 支持所有主流API和本地部署模�?
    val CONFIG_TEMPLATES = listOf(
        // ========== OpenAI 系列 ==========
        ModelConfigTemplate(
            name = "OpenAI GPT-4o",
            providerType = com.apex.data.model.ApiProviderType.OPENAI,
            modelName = "gpt-4o",
            apiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenAI GPT-4 Turbo",
            providerType = com.apex.data.model.ApiProviderType.OPENAI,
            modelName = "gpt-4-turbo",
            apiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenAI GPT-4",
            providerType = com.apex.data.model.ApiProviderType.OPENAI,
            modelName = "gpt-4",
            apiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenAI GPT-3.5 Turbo",
            providerType = com.apex.data.model.ApiProviderType.OPENAI,
            modelName = "gpt-3.5-turbo",
            apiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenAI GPT-4 Vision",
            providerType = com.apex.data.model.ApiProviderType.OPENAI,
            modelName = "gpt-4-vision-preview",
            apiEndpoint = "https://api.openai.com/v1/chat/completions"
        ),

        // ========== Anthropic Claude 系列 ==========
        ModelConfigTemplate(
            name = "Claude 3.5 Sonnet",
            providerType = com.apex.data.model.ApiProviderType.ANTHROPIC,
            modelName = "claude-3-5-sonnet-20240620",
            apiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ModelConfigTemplate(
            name = "Claude 3.5 Haiku",
            providerType = com.apex.data.model.ApiProviderType.ANTHROPIC,
            modelName = "claude-3-5-haiku-20240307",
            apiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ModelConfigTemplate(
            name = "Claude 3 Opus",
            providerType = com.apex.data.model.ApiProviderType.ANTHROPIC,
            modelName = "claude-3-opus-20240229",
            apiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ModelConfigTemplate(
            name = "Claude 3 Sonnet",
            providerType = com.apex.data.model.ApiProviderType.ANTHROPIC,
            modelName = "claude-3-sonnet-20240229",
            apiEndpoint = "https://api.anthropic.com/v1/messages"
        ),
        ModelConfigTemplate(
            name = "Claude 3 Haiku",
            providerType = com.apex.data.model.ApiProviderType.ANTHROPIC,
            modelName = "claude-3-haiku-20240307",
            apiEndpoint = "https://api.anthropic.com/v1/messages"
        ),

        // ========== Google Gemini 系列 ==========
        ModelConfigTemplate(
            name = "Gemini 2.0 Flash",
            providerType = com.apex.data.model.ApiProviderType.GOOGLE,
            modelName = "gemini-2.0-flash",
            apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
        ),
        ModelConfigTemplate(
            name = "Gemini 1.5 Pro",
            providerType = com.apex.data.model.ApiProviderType.GOOGLE,
            modelName = "gemini-1.5-pro",
            apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent"
        ),
        ModelConfigTemplate(
            name = "Gemini 1.5 Flash",
            providerType = com.apex.data.model.ApiProviderType.GOOGLE,
            modelName = "gemini-1.5-flash",
            apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        ),
        ModelConfigTemplate(
            name = "Gemini 1.0 Pro",
            providerType = com.apex.data.model.ApiProviderType.GOOGLE,
            modelName = "gemini-1.0-pro",
            apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.0-pro:generateContent"
        ),

        // ========== DeepSeek 系列 ==========
        ModelConfigTemplate(
            name = "DeepSeek V4",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-v4",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek V4 Pro",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-v4-pro",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek V4 Flash",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-v4-flash",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek V3",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-chat",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek V3.1",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-v3.1",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek R1",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-reasoner",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek R1 Distill Qwen 32B",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-r1-distill-qwen-32b",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek R1 Distill Llama 70B",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-r1-distill-llama-70b",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek Coder",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-coder",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "DeepSeek Coder 33B",
            providerType = com.apex.data.model.ApiProviderType.DEEPSEEK,
            modelName = "deepseek-coder-33b-instruct",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions"
        ),

        // ========== Mistral AI 系列 ==========
        ModelConfigTemplate(
            name = "Mistral Large 2",
            providerType = com.apex.data.model.ApiProviderType.MISTRAL,
            modelName = "mistral-large-2",
            apiEndpoint = "https://api.mistral.ai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Mistral Small",
            providerType = com.apex.data.model.ApiProviderType.MISTRAL,
            modelName = "mistral-small",
            apiEndpoint = "https://api.mistral.ai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Codestral",
            providerType = com.apex.data.model.ApiProviderType.MISTRAL,
            modelName = "codestral",
            apiEndpoint = "https://api.mistral.ai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Codestral Mamba",
            providerType = com.apex.data.model.ApiProviderType.MISTRAL,
            modelName = "codestral-mamba",
            apiEndpoint = "https://api.mistral.ai/v1/chat/completions"
        ),

        // ========== 国内厂商 ==========
        ModelConfigTemplate(
            name = "阿里�?Qwen 2.5",
            providerType = com.apex.data.model.ApiProviderType.ALIYUN,
            modelName = "qwen-turbo",
            apiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "阿里�?Qwen 2.5 72B",
            providerType = com.apex.data.model.ApiProviderType.ALIYUN,
            modelName = "qwen2.5-72b-instruct",
            apiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "月之暗面 Moonshot V1",
            providerType = com.apex.data.model.ApiProviderType.MOONSHOT,
            modelName = "moonshot-v1-8k",
            apiEndpoint = "https://api.moonshot.cn/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "智谱 AI GLM-4",
            providerType = com.apex.data.model.ApiProviderType.ZHIPU,
            modelName = "glm-4",
            apiEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        ),
        ModelConfigTemplate(
            name = "智谱 AI GLM-4V",
            providerType = com.apex.data.model.ApiProviderType.ZHIPU,
            modelName = "glm-4v",
            apiEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        ),
        ModelConfigTemplate(
            name = "百度文心一言 4.0",
            providerType = com.apex.data.model.ApiProviderType.BAIDU,
            modelName = "ernie-4.0-8k-latest",
            apiEndpoint = "https://aip.baidubce.com/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/completions"
        ),
        ModelConfigTemplate(
            name = "百川大模�?4.0",
            providerType = com.apex.data.model.ApiProviderType.BAICHUAN,
            modelName = "baichuan4",
            apiEndpoint = "https://api.baichuan-ai.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "豆包 Doubao Pro",
            providerType = com.apex.data.model.ApiProviderType.DOUBAO,
            modelName = "doubao-pro-32k",
            apiEndpoint = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        ),

        // ========== Coding 专用模型 ==========
        ModelConfigTemplate(
            name = "Code Llama 70B",
            providerType = com.apex.data.model.ApiProviderType.CODECLLAMA,
            modelName = "codellama-70b-instruct",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "Code Llama 34B",
            providerType = com.apex.data.model.ApiProviderType.CODECLLAMA,
            modelName = "codellama-34b-instruct",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "Code Llama 13B",
            providerType = com.apex.data.model.ApiProviderType.CODECLLAMA,
            modelName = "codellama-13b-instruct",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "WizardCoder 33B",
            providerType = com.apex.data.model.ApiProviderType.WIZARDCODER,
            modelName = "wizardcoder-33b-awesome",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "StarCoder 15B",
            providerType = com.apex.data.model.ApiProviderType.STARCODER,
            modelName = "bigcode/starcoder",
            apiEndpoint = ""
        ),

        // ========== 国际厂商 ==========
        ModelConfigTemplate(
            name = "Groq Llama 3.1 70B",
            providerType = com.apex.data.model.ApiProviderType.GROQ,
            modelName = "llama-3.1-70b-versatile",
            apiEndpoint = "https://api.groq.com/openai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Groq Llama 3.1 8B",
            providerType = com.apex.data.model.ApiProviderType.GROQ,
            modelName = "llama-3.1-8b-instant",
            apiEndpoint = "https://api.groq.com/openai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Groq Mixtral 8x7B",
            providerType = com.apex.data.model.ApiProviderType.GROQ,
            modelName = "mixtral-8x7b-32768",
            apiEndpoint = "https://api.groq.com/openai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Together AI Llama 3",
            providerType = com.apex.data.model.ApiProviderType.TOGETHER_AI,
            modelName = "meta-llama/Llama-3-70b-chat-hf",
            apiEndpoint = "https://api.together.xyz/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Together AI Qwen 2",
            providerType = com.apex.data.model.ApiProviderType.TOGETHER_AI,
            modelName = "Qwen/Qwen2-72B-Instruct",
            apiEndpoint = "https://api.together.xyz/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Perplexity Llama 3 Sonar",
            providerType = com.apex.data.model.ApiProviderType.PERPLEXITY,
            modelName = "llama-3-sonar-large-32k-online",
            apiEndpoint = "https://api.perplexity.ai/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Fireworks AI Llama 3",
            providerType = com.apex.data.model.ApiProviderType.FIREWORKS_AI,
            modelName = "accounts/fireworks/models/llama-v3-70b-instruct",
            apiEndpoint = "https://api.fireworks.ai/inference/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Cerebras Llama 3.1 70B",
            providerType = com.apex.data.model.ApiProviderType.CEREBRAS,
            modelName = "llama-3.1-70b",
            apiEndpoint = "https://api.cerebras.ai/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Cohere Command R+",
            providerType = com.apex.data.model.ApiProviderType.COHERE,
            modelName = "command-r-plus",
            apiEndpoint = "https://api.cohere.ai/v1/chat"
        ),
        ModelConfigTemplate(
            name = "Cohere Command R",
            providerType = com.apex.data.model.ApiProviderType.COHERE,
            modelName = "command-r",
            apiEndpoint = "https://api.cohere.ai/v1/chat"
        ),

        // ========== AWS Bedrock ==========
        ModelConfigTemplate(
            name = "AWS Bedrock Claude 3.5",
            providerType = com.apex.data.model.ApiProviderType.AWS_BEDROCK,
            modelName = "anthropic.claude-3-5-sonnet-20240620-v1:0",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "AWS Bedrock Llama 3.1",
            providerType = com.apex.data.model.ApiProviderType.AWS_BEDROCK,
            modelName = "meta.llama3-1-70b-instruct-v1:0",
            apiEndpoint = ""
        ),

        // ========== Azure OpenAI ==========
        ModelConfigTemplate(
            name = "Azure OpenAI GPT-4o",
            providerType = com.apex.data.model.ApiProviderType.AZURE_OPENAI,
            modelName = "gpt-4o",
            apiEndpoint = ""
        ),
        ModelConfigTemplate(
            name = "Azure OpenAI GPT-4 Turbo",
            providerType = com.apex.data.model.ApiProviderType.AZURE_OPENAI,
            modelName = "gpt-4-turbo",
            apiEndpoint = ""
        ),

        // ========== 本地部署 - Ollama ==========
        ModelConfigTemplate(
            name = "Ollama Llama 3.1 8B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "llama3.1:8b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Llama 3.1 70B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "llama3.1:70b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Mistral 7B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "mistral:7b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Code Llama 7B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "codellama:7b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Phi-3.5 4B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "phi3:3.8b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Qwen 2.5 7B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "qwen2.5:7b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama DeepSeek Coder 6.7B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "deepseek-coder:6.7b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "Ollama Gemma 2 9B",
            providerType = com.apex.data.model.ApiProviderType.OLLAMA,
            modelName = "gemma2:9b",
            apiEndpoint = "http://localhost:11434/v1/chat/completions"
        ),

        // ========== 本地部署 - LM Studio ==========
        ModelConfigTemplate(
            name = "LM Studio Llama 3.1 8B",
            providerType = com.apex.data.model.ApiProviderType.LMSTUDIO,
            modelName = "lmstudio-community/Llama-3.1-8B-Instruct-GGUF",
            apiEndpoint = "http://localhost:1234/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "LM Studio Mistral 7B",
            providerType = com.apex.data.model.ApiProviderType.LMSTUDIO,
            modelName = "mistral-7b-instruct-v0.2",
            apiEndpoint = "http://localhost:1234/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "LM Studio Qwen 2.5 7B",
            providerType = com.apex.data.model.ApiProviderType.LMSTUDIO,
            modelName = "qwen2.5-7b-instruct",
            apiEndpoint = "http://localhost:1234/v1/chat/completions"
        ),

        // ========== 本地部署 - LocalAI ==========
        ModelConfigTemplate(
            name = "LocalAI Llama 3.1 8B",
            providerType = com.apex.data.model.ApiProviderType.LOCALAI,
            modelName = "llama3.1",
            apiEndpoint = "http://localhost:8080/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "LocalAI Mixtral 8x7B",
            providerType = com.apex.data.model.ApiProviderType.LOCALAI,
            modelName = "mixtral",
            apiEndpoint = "http://localhost:8080/v1/chat/completions"
        ),

        // ========== 本地部署 - vLLM ==========
        ModelConfigTemplate(
            name = "vLLM Llama 3.1 8B",
            providerType = com.apex.data.model.ApiProviderType.VLLM,
            modelName = "meta-llama/Llama-3.1-8B-Instruct",
            apiEndpoint = "http://localhost:8000/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "vLLM Qwen 2.5 7B",
            providerType = com.apex.data.model.ApiProviderType.VLLM,
            modelName = "Qwen/Qwen2-7B-Instruct",
            apiEndpoint = "http://localhost:8000/v1/chat/completions"
        ),

        // ========== OpenRouter 聚合 ==========
        ModelConfigTemplate(
            name = "OpenRouter Claude 3.5 Sonnet",
            providerType = com.apex.data.model.ApiProviderType.OPENROUTER,
            modelName = "anthropic/claude-3.5-sonnet",
            apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenRouter GPT-4o",
            providerType = com.apex.data.model.ApiProviderType.OPENROUTER,
            modelName = "openai/gpt-4o",
            apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenRouter Gemini 1.5 Pro",
            providerType = com.apex.data.model.ApiProviderType.OPENROUTER,
            modelName = "google/gemini-1.5-pro",
            apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "OpenRouter Llama 3.1 70B",
            providerType = com.apex.data.model.ApiProviderType.OPENROUTER,
            modelName = "meta-llama/llama-3.1-70b-instruct",
            apiEndpoint = "https://openrouter.ai/api/v1/chat/completions"
        ),

        // ========== NVIDIA NIM ==========
        ModelConfigTemplate(
            name = "NVIDIA Llama 3.1 70B",
            providerType = com.apex.data.model.ApiProviderType.NVIDIA,
            modelName = "meta/llama3.1-70b-instruct",
            apiEndpoint = "https://integrate.api.nvidia.com/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "NVIDIA Mistral",
            providerType = com.apex.data.model.ApiProviderType.NVIDIA,
            modelName = "mistralai/mixtral-8x7b-instruct-v0.1",
            apiEndpoint = "https://integrate.api.nvidia.com/v1/chat/completions"
        ),

        // ========== SiliconFlow (硅基流动�?==========
        ModelConfigTemplate(
            name = "SiliconFlow Qwen 2.5",
            providerType = com.apex.data.model.ApiProviderType.SILICONFLOW,
            modelName = "Qwen/Qwen2.5-7B-Instruct",
            apiEndpoint = "https://api.siliconflow.cn/v1/chat/completions"
        ),
        ModelConfigTemplate(
            name = "SiliconFlow DeepSeek V2.5",
            providerType = com.apex.data.model.ApiProviderType.SILICONFLOW,
            modelName = "deepseek-ai/DeepSeek-V2.5",
            apiEndpoint = "https://api.siliconflow.cn/v1/chat/completions"
        )
    )

    // 初始�?
    private fun initialize() {
        CoroutineScope(Dispatchers.IO).launch {
            // 初始化配置管理器
            modelConfigManager.initializeIfNeeded()
            functionalConfigManager.initializeIfNeeded()

            // 加载配置列表
            modelConfigManager.configListFlow.collectLatest {
                _configList.value = it
            }

            // 加载当前活跃配置
            loadActiveConfig()
        }
    }

    // 从缓存或加载配置
    private suspend fun getConfigFromCacheOrLoad(configId: String): ModelConfigData {
        // 先从缓存中获�?
        configCache[configId]?.let { return it }

        // 从ModelConfigManager加载
        val config = modelConfigManager.getModelConfig(configId)

        // 更新缓存
        configCache[configId] = config
        return config
    }

    // 加载当前活跃配置
    private suspend fun loadActiveConfig() {
        mutex.withLock {
            // 获取对话功能当前绑定的模型配�?
            val chatConfigId = functionalConfigManager.getConfigIdForFunction(com.apex.data.model.FunctionType.CHAT)
            val availableConfigIds = _configList.value

            val configId = availableConfigIds.firstOrNull { it == chatConfigId }
                ?: availableConfigIds.firstOrNull()
                ?: ModelConfigManager.DEFAULT_CONFIG_ID

            _activeConfigId.value = configId

            // 加载配置详情
            modelConfigManager.getModelConfigFlow(configId).collectLatest {
                _activeConfig.value = it
                // 更新缓存
                configCache[configId] = it
            }
        }
    }

    // 设置活跃配置
    suspend fun setActiveConfig(configId: String) {
        mutex.withLock {
            _activeConfigId.value = configId
            // 同时更新功能配置，确保所有功能使用相同的配置
            functionalConfigManager.setConfigIdForFunction(com.apex.data.model.FunctionType.CHAT, configId)
            functionalConfigManager.setConfigIdForFunction(com.apex.data.model.FunctionType.TOOL_CALL, configId)
            functionalConfigManager.setConfigIdForFunction(com.apex.data.model.FunctionType.IMAGE_GENERATION, configId)
            functionalConfigManager.setConfigIdForFunction(com.apex.data.model.FunctionType.TEXT_TO_SPEECH, configId)
            functionalConfigManager.setConfigIdForFunction(com.apex.data.model.FunctionType.SPEECH_TO_TEXT, configId)
            // 发布配置变更通知
            _configChangeEvent.emit(ConfigChangeEvent.ActiveConfigChanged(configId))
        }
    }

    // 获取当前活跃配置
    suspend fun getCurrentConfig(): ModelConfigData? {
        return _activeConfig.value ?: run {
            loadActiveConfig()
            _activeConfig.value
        }
    }

    // 刷新配置
    suspend fun refreshConfig() {
        loadActiveConfig()
    }

    // 创建新配�?
    suspend fun createConfig(name: String): String {
        val configId = modelConfigManager.createConfig(name)
        // 发布配置变更通知
        _configChangeEvent.emit(ConfigChangeEvent.ConfigAdded(configId))
        // 自动切换到新创建的配�?
        setActiveConfig(configId)
        return configId
    }

    // 删除配置
    suspend fun deleteConfig(configId: String) {
        if (configId == ModelConfigManager.DEFAULT_CONFIG_ID) {
            return // 不允许删除默认配�?
        }

        modelConfigManager.deleteConfig(configId)
        // 从缓存中删除
        configCache.remove(configId)
        // 发布配置变更通知
        _configChangeEvent.emit(ConfigChangeEvent.ConfigDeleted(configId))

        // 如果删除的是当前活跃配置，切换到其他配置
        if (_activeConfigId.value == configId) {
            val availableConfigIds = _configList.value
            val newConfigId = availableConfigIds.firstOrNull() ?: ModelConfigManager.DEFAULT_CONFIG_ID
            setActiveConfig(newConfigId)
        }
    }

    // 重命名配�?
    suspend fun renameConfig(configId: String, newName: String) {
        modelConfigManager.updateConfigBase(configId, newName)
        // 更新缓存
        configCache[configId]?.let {
            val updatedConfig = it.copy(name = newName)
            configCache[configId] = updatedConfig
        }
        // 发布配置变更通知
        _configChangeEvent.emit(ConfigChangeEvent.ConfigUpdated(configId))
    }

    // 根据模板创建配置
    suspend fun createConfigFromTemplate(template: ModelConfigTemplate, customName: String? = null): String {
        val configName = customName ?: template.name
        val configId = modelConfigManager.createConfig(configName)

        // 根据模板设置配置
        modelConfigManager.updateModelConfig(
            configId = configId,
            apiKey = "",
            apiEndpoint = template.apiEndpoint,
            modelName = template.modelName,
            apiProviderType = template.providerType
        )

        // 发布配置变更通知
        _configChangeEvent.emit(ConfigChangeEvent.ConfigAdded(configId))
        // 自动切换到新创建的配�?
        setActiveConfig(configId)
        return configId
    }

    // 获取所有配置模�?
    fun getConfigTemplates(): List<ModelConfigTemplate> {
        return CONFIG_TEMPLATES
    }

    suspend fun getConfigById(configId: String): ModelConfigData? {
        return try {
            getConfigFromCacheOrLoad(configId)
        } catch (e: Exception) {
            null
        }
    }

    // 获取所有配置摘�?
    suspend fun getAllConfigSummaries() = modelConfigManager.getAllConfigSummaries()

    // 导出所有配�?
    suspend fun exportAllConfigs() = modelConfigManager.exportAllConfigs()

    // 导入配置
    suspend fun importConfigs(jsonContent: String): Triple<Int, Int, Int> {
        val result = modelConfigManager.importConfigs(jsonContent)
        // 导入后清除缓存，确保下次加载最新配�?
        configCache.clear()
        return result
    }

    // 预加载所有配置到缓存
    suspend fun preloadConfigs() {
        modelConfigManager.preloadConfigs()
        // 从ModelConfigManager的缓存同步到当前服务的缓�?
        val configList = _configList.value
        for (configId in configList) {
            getConfigFromCacheOrLoad(configId)
        }
    }

    // 清除配置缓存
    fun clearConfigCache() {
        configCache.clear()
        modelConfigManager.clearConfigCache()
    }

    // 获取配置缓存状�?
    fun getCacheSize(): Int {
        return configCache.size
    }

    /**
     * 验证模型配置的有效�?
     */
    fun validateConfig(config: ModelConfigData): Pair<Boolean, String> {
        // 验证配置名称
        if (config.name.isBlank()) {
            return Pair(false, "配置名称不能为空")
        }

        // 验证API提供商类�?
        if (config.apiProviderType == com.apex.data.model.ApiProviderType.OTHER) {
            // 对于自定义提供商，需要验证API端点
            if (config.apiEndpoint.isBlank()) {
                return Pair(false, "自定义API提供商需要设置API端点")
            }
            // 验证API端点格式
            if (!config.apiEndpoint.startsWith("http://") && !config.apiEndpoint.startsWith("https://")) {
                return Pair(false, "API端点格式无效，需要以http://或https://开�?)
            }
        }

        // 验证模型名称
        if (config.modelName.isBlank()) {
            return Pair(false, "模型名称不能为空")
        }

        // 验证API密钥（某些提供商需要）
        val providersRequiringKey = listOf(
            com.apex.data.model.ApiProviderType.OPENAI,
            com.apex.data.model.ApiProviderType.ANTHROPIC,
            com.apex.data.model.ApiProviderType.GOOGLE,
            com.apex.data.model.ApiProviderType.DEEPSEEK,
            com.apex.data.model.ApiProviderType.MISTRAL,
            com.apex.data.model.ApiProviderType.AWS_BEDROCK,
            com.apex.data.model.ApiProviderType.AZURE_OPENAI,
            com.apex.data.model.ApiProviderType.COHERE,
            com.apex.data.model.ApiProviderType.GROQ,
            com.apex.data.model.ApiProviderType.TOGETHER_AI,
            com.apex.data.model.ApiProviderType.PERPLEXITY,
            com.apex.data.model.ApiProviderType.FIREWORKS_AI,
            com.apex.data.model.ApiProviderType.CEREBRAS,
            com.apex.data.model.ApiProviderType.NVIDIA
        )

        if (providersRequiringKey.contains(config.apiProviderType) && config.apiKey.isBlank()) {
            return Pair(false, "该API提供商需要设置API密钥")
        }

        return Pair(true, "配置有效")
    }
}