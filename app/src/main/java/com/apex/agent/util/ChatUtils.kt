package com.apex.util

import com.apex.core.chat.hooks.PromptTurn
import com.apex.core.chat.hooks.withContent

/**
 * 聊天消息处理工具类
 *
 * 提供 Gemini 思考标记处理、Token 估算、JSON 提取等功能，
 * 用于清理和解析 AI 助手的响应消息。
 */
object ChatUtils {

    /**
     * 移除单条内容中的 Gemini 思考签名元数据
     *
     * @param content 原始内容字符串
     * @return 移除元数据后的内容
     */
    fun stripGeminiThoughtSignatureMeta(content: String): String {
        return ChatMarkupRegex.removeGeminiThoughtSignatureMeta(content)
    }

    /**
     * 批量移除消息列表中的 Gemini 思考签名元数据
     *
     * @param messages 消息列表，每项为 (角色, 内容) 的 Pair
     * @return 处理后的消息列表
     */
    fun stripGeminiThoughtSignatureMeta(messages: List<Pair<String, String>>): List<Pair<String, String>> {
        return messages.map { (role, content) ->
            role to stripGeminiThoughtSignatureMeta(content)
        }
    }

    /**
     * 批量移除 PromptTurn 列表中的 Gemini 思考签名元数据
     *
     * @param messages PromptTurn 消息列表
     * @return 处理后的 PromptTurn 列表
     */
    fun stripGeminiThoughtSignatureMetaTurns(messages: List<PromptTurn>): List<PromptTurn> {
        return messages.map { turn ->
            turn.withContent(stripGeminiThoughtSignatureMeta(turn.content))
        }
    }

    /**
     * 判断提供者模型是否为 Gemini 系列模型
     *
     * 根据 provider:model 格式中的 provider 部分进行判断，
     * 支持 "GOOGLE" 和 "GEMINI_GENERIC" 前缀。
     *
     * @param providerModel 提供者模型字符串（如 "GOOGLE:gemini-pro"）
     * @return true 为 Gemini 模型，false 不是
     */
    fun isGeminiProviderModel(providerModel: String): Boolean {
        return when (providerModel.substringBefore(":").uppercase()) {
            "GOOGLE", "GEMINI_GENERIC" -> true
            else -> false
        }
    }

    /**
     * 过滤掉内容中的思考部分和搜索来源
     *
     * 移除 &lt;think&gt;&lt;/think&gt;、&lt;thinking&gt;&lt;/thinking&gt;、
     * &lt;search&gt;&lt;/search&gt; 标签及其中的内容，并处理未闭合的情况。
     *
     * @param content 原始内容
     * @return 过滤后的内容
     */
    fun removeThinkingContent(content: String): String {
        val thinkPattern = "<think(?:ing)?>.*?(</think(?:ing)?>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)
        val searchPattern = "<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL)
        return content.replace(thinkPattern, "").replace(searchPattern, "").trim()
    }

    /**
     * 提取 think 标签内的内容（用于 DeepSeek 的 reasoning_content）
     *
     * @param content 包含 think 标签的内容
     * @return Pair(移除 think 标签后的内容, think 标签内的内容)
     */
    fun extractThinkingContent(content: String): Pair<String, String> {
        val thinkPattern = "<think(?:ing)?>([\\s\\S]*)</think(?:ing)?>".toRegex(RegexOption.DOT_MATCHES_ALL)
        val thinkMatches = thinkPattern.findAll(content)

        val thinkingContent = thinkMatches.joinToString("\n") { it.groupValues[1].trim() }

        val contentWithoutThink = content
            .replace(thinkPattern, "")
            .replace("<search>.*?(</search>|\\z)".toRegex(RegexOption.DOT_MATCHES_ALL), "")
            .trim()

        return Pair(contentWithoutThink, thinkingContent)
    }

    /**
     * 估算给定文本的 Token 数量
     *
     * 简单估算规则：中文每个字约 1.5 个 Token，英文每 4 个字符约 1 个 Token。
     *
     * @param text 要估算 Token 的文本
     * @return 估算的 Token 数量（取整）
     */
    fun estimateTokenCount(text: String): Int {
        val chineseCharCount = text.count { it.code in 0x4E00..0x9FFF }
        val otherCharCount = text.length - chineseCharCount
        return (chineseCharCount * 1.5 + otherCharCount * 0.25).toInt()
    }

    /**
     * 从 AI 响应中提取 JSON 对象部分
     *
     * AI 可能会在 JSON 前后添加说明文字或使用 ```json 代码块，
     * 此方法会提取出纯净的 JSON 字符串。
     *
     * @param response AI 原始响应文本
     * @return 提取出的 JSON 字符串，若未找到则返回原始文本
     */
    fun extractJson(response: String): String {
        var text = response.trim()

        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }

        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')

        return if (firstBrace != -1 && lastBrace != -1 && firstBrace < lastBrace) {
            text.substring(firstBrace, lastBrace + 1)
        } else {
            text
        }
    }

    /**
     * 从 AI 响应中提取 JSON 数组部分
     *
     * AI 可能会在 JSON 前后添加说明文字或使用 ```json 代码块，
     * 此方法会提取出纯净的 JSON 数组字符串。
     *
     * @param response AI 原始响应文本
     * @return 提取出的 JSON 数组字符串，若未找到则返回原始文本
     */
    fun extractJsonArray(response: String): String {
        var text = response.trim()

        if (text.startsWith("```")) {
            val lines = text.lines()
            text = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }

        val firstBracket = text.indexOf('[')
        val lastBracket = text.lastIndexOf(']')

        return if (firstBracket != -1 && lastBracket != -1 && firstBracket < lastBracket) {
            text.substring(firstBracket, lastBracket + 1)
        } else {
            text
        }
    }
}
