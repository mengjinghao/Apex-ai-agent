package com.apex.agent.kernel.burst

import com.apex.agent.plugins.burst.base.ChatMessage
import com.apex.agent.plugins.burst.base.ILLMService
import com.apex.agent.plugins.burst.base.LLMConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LLM 服务实现
 *
 * 本地模型（llama.cpp）已移除，改为纯 fallback mock 响应。
 * 业务侧应注入云端 LLM 服务（如 DeepSeek/OpenAI/Claude）替代。
 */
class LLMService : ILLMService {
    private var config: LLMConfig = LLMConfig()
    private var initialized = false

    override fun isAvailable(): Boolean {
        return false  // 本地模型已移除
    }

    override fun getUnavailableReason(): String {
        return "本地 llama.cpp 推理已移除，请使用云端 LLM 服务"
    }

    override fun initialize(config: LLMConfig): Boolean {
        this.config = config
        initialized = true
        return true  // 标记初始化成功，实际推理走 fallback
    }

    override suspend fun generate(prompt: String, maxTokens: Int): String = withContext(Dispatchers.IO) {
        fallbackGenerate(prompt)
    }

    override suspend fun generateStream(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val response = fallbackGenerate(prompt)
        onToken(response)
        true
    }

    override suspend fun chat(messages: List<ChatMessage>, maxTokens: Int): String = withContext(Dispatchers.IO) {
        fallbackChat(messages)
    }

    override fun countTokens(text: String): Int {
        return text.length / 2
    }

    override fun release() {
        initialized = false
    }

    private fun fallbackGenerate(prompt: String): String {
        return buildString {
            appendLine("[LLM Fallback] 本地模型已移除，请注入云端 LLM 服务。")
            appendLine()
            appendLine("Prompt 分析:")
            appendLine("- 长度: ${prompt.length} 字符")
            appendLine("- Token: ~${prompt.length / 2}")
            appendLine()
            appendLine("基于 prompt 模式的 mock 响应:")
            when {
                prompt.contains("analyze", ignoreCase = true) || prompt.contains("分析", ignoreCase = true) -> {
                    appendLine("分析完成。输入包含 ${countWords(prompt)} 词。")
                    appendLine("已识别关键主题并处理。")
                }
                prompt.contains("summarize", ignoreCase = true) || prompt.contains("总结", ignoreCase = true) -> {
                    appendLine("摘要: 已从提供的内容中提取要点。")
                }
                prompt.contains("code", ignoreCase = true) || prompt.contains("代码", ignoreCase = true) -> {
                    appendLine("代码分析: 已审查结构，识别模式。")
                    appendLine("可提供改进建议。")
                }
                else -> {
                    appendLine("处理完成。输入已确认并分析。")
                }
            }
        }
    }

    private fun fallbackChat(messages: List<ChatMessage>): String {
        val lastUserMsg = messages.lastOrNull { it.role == "user" }?.content ?: ""
        return fallbackGenerate(lastUserMsg)
    }

    private fun countWords(text: String): Int {
        return text.split("\\s+".toRegex()).count { it.isNotBlank() }
    }
}
