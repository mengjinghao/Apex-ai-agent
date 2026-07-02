package com.apex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilsTest {

    @Test
    fun `stripGeminiThoughtSignatureMeta removes signature meta tag`() {
        val input = """Some text <meta provider="gemini:thought_signature">abc123</meta> more text"""
        val result = ChatUtils.stripGeminiThoughtSignatureMeta(input)
        assertEquals("Some text  more text", result)
    }

    @Test
    fun `stripGeminiThoughtSignatureMeta removes signature meta tag at end`() {
        val input = """content<meta provider="gemini:thought_signature">xyz</meta>"""
        val result = ChatUtils.stripGeminiThoughtSignatureMeta(input)
        assertEquals("content", result)
    }

    @Test
    fun `stripGeminiThoughtSignatureMeta removes signature meta tag at start`() {
        val input = """<meta provider="gemini:thought_signature">sig</meta>content"""
        val result = ChatUtils.stripGeminiThoughtSignatureMeta(input)
        assertEquals("content", result)
    }

    @Test
    fun `stripGeminiThoughtSignatureMeta does not remove non-gemini meta tags`() {
        val input = """<meta provider="other">keep</meta>"""
        val result = ChatUtils.stripGeminiThoughtSignatureMeta(input)
        assertEquals(input, result)
    }

    @Test
    fun `stripGeminiThoughtSignatureMeta returns empty string unchanged`() {
        assertEquals("", ChatUtils.stripGeminiThoughtSignatureMeta(""))
    }

    @Test
    fun `stripGeminiThoughtSignatureMeta handles text without any meta tags`() {
        val input = "plain text without tags"
        assertEquals(input, ChatUtils.stripGeminiThoughtSignatureMeta(input))
    }

    @Test
    fun `removeThinkingContent removes think tag and its content`() {
        val input = "before<think>reasoning here</think>after"
        assertEquals("beforeafter", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent removes thinking tag and its content`() {
        val input = "hello <thinking>deep thinking</thinking> world"
        assertEquals("hello world", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent removes unclosed think tag`() {
        val input = "prefix<think>unclosed content"
        assertEquals("prefix", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent removes unclosed thinking tag`() {
        val input = "a<thinking>no closing"
        assertEquals("a", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent removes search tag and its content`() {
        val input = "text<search>query result</search>more"
        assertEquals("textmore", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent removes unclosed search tag`() {
        val input = "x<search>no end"
        assertEquals("x", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent handles multiple tags`() {
        val input = "<think>one</think>middle<search>two</search>end"
        assertEquals("middleend", ChatUtils.removeThinkingContent(input))
    }

    @Test
    fun `removeThinkingContent returns empty string unchanged`() {
        assertEquals("", ChatUtils.removeThinkingContent(""))
    }

    @Test
    fun `removeThinkingContent returns text without tags unchanged`() {
        assertEquals("hello world", ChatUtils.removeThinkingContent("hello world"))
    }

    @Test
    fun `extractThinkingContent extracts think content and returns cleaned pair`() {
        val input = "before<think>reason</think>after"
        val (cleaned, thinking) = ChatUtils.extractThinkingContent(input)
        assertEquals("beforeafter", cleaned)
        assertEquals("reason", thinking)
    }

    @Test
    fun `extractThinkingContent extracts thinking tag content`() {
        val input = "a<thinking>deep</thinking>b"
        val (cleaned, thinking) = ChatUtils.extractThinkingContent(input)
        assertEquals("ab", cleaned)
        assertEquals("deep", thinking)
    }

    @Test
    fun `extractThinkingContent returns empty thinking for no tags`() {
        val input = "no tags here"
        val (cleaned, thinking) = ChatUtils.extractThinkingContent(input)
        assertEquals(input, cleaned)
        assertEquals("", thinking)
    }

    @Test
    fun `extractThinkingContent handles empty input`() {
        val (cleaned, thinking) = ChatUtils.extractThinkingContent("")
        assertEquals("", cleaned)
        assertEquals("", thinking)
    }

    @Test
    fun `estimateTokenCount computes correctly for chinese text`() {
        val chinese = "你好世界"
        val tokens = ChatUtils.estimateTokenCount(chinese)
        assertEquals((4 * 1.5).toInt(), tokens)
    }

    @Test
    fun `estimateTokenCount computes correctly for english text`() {
        val english = "hello world"
        val tokens = ChatUtils.estimateTokenCount(english)
        assertEquals((11 * 0.25).toInt(), tokens)
    }

    @Test
    fun `estimateTokenCount computes correctly for mixed text`() {
        val mixed = "你好hello"
        val chineseCount = 2
        val otherCount = 5
        val expected = (chineseCount * 1.5 + otherCount * 0.25).toInt()
        assertEquals(expected, ChatUtils.estimateTokenCount(mixed))
    }

    @Test
    fun `estimateTokenCount returns 0 for empty string`() {
        assertEquals(0, ChatUtils.estimateTokenCount(""))
    }

    @Test
    fun `extractJson extracts json from code block`() {
        val input = "```json\n{\"key\": \"value\"}\n```"
        val result = ChatUtils.extractJson(input)
        assertEquals("{\"key\": \"value\"}", result)
    }

    @Test
    fun `extractJson extracts plain json`() {
        val input = "{\"key\": \"value\"}"
        assertEquals(input, ChatUtils.extractJson(input))
    }

    @Test
    fun `extractJson extracts json from text with surrounding content`() {
        val input = "here is the result: {\"key\": \"value\"} thanks"
        val result = ChatUtils.extractJson(input)
        assertEquals("{\"key\": \"value\"}", result)
    }

    @Test
    fun `extractJson returns original text when no json found`() {
        val input = "just text without json"
        assertEquals(input, ChatUtils.extractJson(input))
    }

    @Test
    fun `extractJson handles empty string`() {
        assertEquals("", ChatUtils.extractJson(""))
    }

    @Test
    fun `extractJsonArray extracts json array from code block`() {
        val input = "```json\n[1, 2, 3]\n```"
        val result = ChatUtils.extractJsonArray(input)
        assertEquals("[1, 2, 3]", result)
    }

    @Test
    fun `extractJsonArray extracts plain json array`() {
        val input = "[\"a\", \"b\"]"
        assertEquals(input, ChatUtils.extractJsonArray(input))
    }

    @Test
    fun `extractJsonArray extracts array from text with surrounding content`() {
        val input = "result: [1, 2] end"
        val result = ChatUtils.extractJsonArray(input)
        assertEquals("[1, 2]", result)
    }

    @Test
    fun `extractJsonArray returns original text when no array found`() {
        val input = "no array here"
        assertEquals(input, ChatUtils.extractJsonArray(input))
    }

    @Test
    fun `isGeminiProviderModel returns true for google`() {
        assertTrue(ChatUtils.isGeminiProviderModel("google:gemini-pro"))
    }

    @Test
    fun `isGeminiProviderModel returns true for gemini_generic`() {
        assertTrue(ChatUtils.isGeminiProviderModel("gemini_generic:model"))
    }

    @Test
    fun `isGeminiProviderModel returns true for uppercase GOOGLE`() {
        assertTrue(ChatUtils.isGeminiProviderModel("GOOGLE:gemini-pro"))
    }

    @Test
    fun `isGeminiProviderModel returns false for non-gemini models`() {
        assertTrue(!ChatUtils.isGeminiProviderModel("openai:gpt-4"))
    }

    @Test
    fun `isGeminiProviderModel returns false for empty string`() {
        assertTrue(!ChatUtils.isGeminiProviderModel(""))
    }

    @Test
    fun `isGeminiProviderModel returns false for anthropic`() {
        assertTrue(!ChatUtils.isGeminiProviderModel("anthropic:claude-3"))
    }

    @Test
    fun `isGeminiProviderModel returns true case insensitive`() {
        assertTrue(ChatUtils.isGeminiProviderModel("Google:gemini-pro"))
    }
}
