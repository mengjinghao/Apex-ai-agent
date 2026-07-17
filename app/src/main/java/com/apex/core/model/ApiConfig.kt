package com.apex.core.model

/**
 * AI API 配置 — 支持所有 OpenAI 兼容的 Provider。
 */
data class ApiConfig(
    val endpoint: String = DEFAULT_ENDPOINT,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val temperature: Double = 0.7
) {
    val isConfigured: Boolean get() = apiKey.isNotEmpty()

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_SYSTEM_PROMPT = "你是 Apex AI Agent，一个智能、友善的 AI 助手。请用中文回答用户的问题，提供准确、有帮助的信息。"

        /** 预设 Provider 配置 */
        val PRESETS = listOf(
            ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            ProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            ProviderPreset("Kimi (月之暗面)", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            ProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            ProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),
            ProviderPreset("Ollama (本地)", "http://10.0.2.2:11434/v1", "llama3.2")
        )
    }
}

data class ProviderPreset(
    val name: String,
    val endpoint: String,
    val model: String
)
