package com.apex.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkupRegexTest {

    @Test
    fun `isToolTagName returns true for tool`() {
        assertTrue(ChatMarkupRegex.isToolTagName("tool"))
    }

    @Test
    fun `isToolTagName returns true for tool_suffix`() {
        assertTrue(ChatMarkupRegex.isToolTagName("tool_browse"))
    }

    @Test
    fun `isToolTagName returns true for tool with numbers`() {
        assertTrue(ChatMarkupRegex.isToolTagName("tool_123"))
    }

    @Test
    fun `isToolTagName returns false for tool_result`() {
        assertFalse(ChatMarkupRegex.isToolTagName("tool_result"))
    }

    @Test
    fun `isToolTagName returns false for empty string`() {
        assertFalse(ChatMarkupRegex.isToolTagName(""))
    }

    @Test
    fun `isToolTagName is case insensitive`() {
        assertTrue(ChatMarkupRegex.isToolTagName("TOOL"))
        assertTrue(ChatMarkupRegex.isToolTagName("Tool_Browse"))
    }

    @Test
    fun `isToolResultTagName returns true for tool_result`() {
        assertTrue(ChatMarkupRegex.isToolResultTagName("tool_result"))
    }

    @Test
    fun `isToolResultTagName returns true for tool_result_suffix`() {
        assertTrue(ChatMarkupRegex.isToolResultTagName("tool_result_abc"))
    }

    @Test
    fun `isToolResultTagName returns false for tool`() {
        assertFalse(ChatMarkupRegex.isToolResultTagName("tool"))
    }

    @Test
    fun `isToolResultTagName returns false for empty string`() {
        assertFalse(ChatMarkupRegex.isToolResultTagName(""))
    }

    @Test
    fun `normalizeToolLikeTagName normalizes tool`() {
        assertEquals("tool", ChatMarkupRegex.normalizeToolLikeTagName("tool"))
    }

    @Test
    fun `normalizeToolLikeTagName normalizes tool_suffix`() {
        assertEquals("tool", ChatMarkupRegex.normalizeToolLikeTagName("tool_search"))
    }

    @Test
    fun `normalizeToolLikeTagName normalizes tool_result`() {
        assertEquals("tool_result", ChatMarkupRegex.normalizeToolLikeTagName("tool_result"))
    }

    @Test
    fun `normalizeToolLikeTagName normalizes tool_result_suffix`() {
        assertEquals("tool_result", ChatMarkupRegex.normalizeToolLikeTagName("tool_result_file"))
    }

    @Test
    fun `normalizeToolLikeTagName returns input for non-tool tags`() {
        assertEquals("div", ChatMarkupRegex.normalizeToolLikeTagName("div"))
    }

    @Test
    fun `containsToolTag returns true when tool tag present`() {
        assertTrue(ChatMarkupRegex.containsToolTag("<tool_search>content</tool_search>"))
    }

    @Test
    fun `containsToolTag returns false when no tool tag`() {
        assertFalse(ChatMarkupRegex.containsToolTag("<div>content</div>"))
    }

    @Test
    fun `containsToolTag returns false for tool_result tag`() {
        assertFalse(ChatMarkupRegex.containsToolTag("<tool_result>content</tool_result>"))
    }

    @Test
    fun `containsToolResultTag returns true when tool_result present`() {
        assertTrue(ChatMarkupRegex.containsToolResultTag("<tool_result name=\"test\">result</tool_result>"))
    }

    @Test
    fun `containsToolResultTag returns false for tool tag`() {
        assertFalse(ChatMarkupRegex.containsToolResultTag("<tool>content</tool>"))
    }

    @Test
    fun `containsAnyToolLikeTag returns true for tool tag`() {
        assertTrue(ChatMarkupRegex.containsAnyToolLikeTag("<tool>content</tool>"))
    }

    @Test
    fun `containsAnyToolLikeTag returns true for tool_result tag`() {
        assertTrue(ChatMarkupRegex.containsAnyToolLikeTag("<tool_result>content</tool_result>"))
    }

    @Test
    fun `containsAnyToolLikeTag returns false for non-tool tags`() {
        assertFalse(ChatMarkupRegex.containsAnyToolLikeTag("<div>content</div>"))
    }

    @Test
    fun `extractOpeningTagName extracts tag name`() {
        assertEquals("div", ChatMarkupRegex.extractOpeningTagName("<div class=\"test\">"))
    }

    @Test
    fun `extractOpeningTagName extracts tool tag name`() {
        assertEquals("tool", ChatMarkupRegex.extractOpeningTagName("<tool name=\"test\">"))
    }

    @Test
    fun `extractOpeningTagName returns null for non-xml`() {
        assertNull(ChatMarkupRegex.extractOpeningTagName("just text"))
    }

    @Test
    fun `extractOpeningTagName returns null for empty string`() {
        assertNull(ChatMarkupRegex.extractOpeningTagName(""))
    }

    @Test
    fun `generateRandomToolTagName starts with tool_`() {
        val name = ChatMarkupRegex.generateRandomToolTagName()
        assertTrue(name.startsWith("tool_"))
        assertTrue(name.length > 5)
    }

    @Test
    fun `generateRandomToolTagName produces unique names`() {
        val names = (1..100).map { ChatMarkupRegex.generateRandomToolTagName() }.toSet()
        assertEquals(100, names.size)
    }

    @Test
    fun `generateRandomToolResultTagName starts with tool_result_`() {
        val name = ChatMarkupRegex.generateRandomToolResultTagName()
        assertTrue(name.startsWith("tool_result_"))
        assertTrue(name.length > 12)
    }

    @Test
    fun `geminiThoughtSignatureMetaTag produces correct tag`() {
        val tag = ChatMarkupRegex.geminiThoughtSignatureMetaTag("base64sig==")
        assertTrue(tag.contains("meta"))
        assertTrue(tag.contains("provider=\"gemini:thought_signature\""))
        assertTrue(tag.contains("base64sig=="))
    }

    @Test
    fun `extractGeminiThoughtSignature extracts signature from meta tag`() {
        val content = """<meta provider="gemini:thought_signature">abc123</meta>"""
        val sig = ChatMarkupRegex.extractGeminiThoughtSignature(content)
        assertEquals("abc123", sig)
    }

    @Test
    fun `extractGeminiThoughtSignature returns null when no meta tag`() {
        val sig = ChatMarkupRegex.extractGeminiThoughtSignature("no meta tag")
        assertNull(sig)
    }

    @Test
    fun `extractGeminiThoughtSignature returns null for non-gemini meta`() {
        val content = """<meta provider="other">data</meta>"""
        val sig = ChatMarkupRegex.extractGeminiThoughtSignature(content)
        assertNull(sig)
    }

    @Test
    fun `extractGeminiThoughtSignature extracts last signature when multiple`() {
        val content = """<meta provider="other">first</meta><meta provider="gemini:thought_signature">last</meta>"""
        val sig = ChatMarkupRegex.extractGeminiThoughtSignature(content)
        assertEquals("last", sig)
    }

    @Test
    fun `removeGeminiThoughtSignatureMeta removes gemini meta`() {
        val input = "before<meta provider=\"gemini:thought_signature\">sig</meta>after"
        val result = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(input)
        assertEquals("beforeafter", result)
    }

    @Test
    fun `removeGeminiThoughtSignatureMeta preserves other meta tags`() {
        val input = """<meta provider="other">keep</meta>"""
        val result = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(input)
        assertEquals(input, result)
    }

    @Test
    fun `removeGeminiThoughtSignatureMeta removes only gemini signature meta`() {
        val input = """<meta provider="other">keep</meta><meta provider="gemini:thought_signature">remove</meta>"""
        val result = ChatMarkupRegex.removeGeminiThoughtSignatureMeta(input)
        assertTrue(result.contains("other"))
        assertFalse(result.contains("thought_signature"))
    }

    @Test
    fun `toolCallPattern matches tool call`() {
        val input = """<tool_search name="search">query text</tool_search>"""
        val match = ChatMarkupRegex.toolCallPattern.find(input)
        assertNotNull(match)
        assertEquals("tool_search", match!!.groupValues[1])
        assertEquals("search", match.groupValues[2])
        assertEquals("query text", match.groupValues[3])
    }

    @Test
    fun `toolCallPattern matches tool_result call`() {
        val input = """<tool_result_file name="result.txt">content</tool_result_file>"""
        val match = ChatMarkupRegex.toolCallPattern.find(input)
        assertNotNull(match)
        assertEquals("tool_result_file", match!!.groupValues[1])
        assertEquals("result.txt", match.groupValues[2])
    }

    @Test
    fun `toolTag matches tool tags`() {
        val input = """<tool_browse>content</tool_browse>"""
        val match = ChatMarkupRegex.toolTag.find(input)
        assertNotNull(match)
        assertEquals(input, match!!.value)
    }

    @Test
    fun `toolSelfClosingTag matches self-closing tool tags`() {
        val input = """<tool_search name="test"/>"""
        assertTrue(ChatMarkupRegex.toolSelfClosingTag.containsMatchIn(input))
    }

    @Test
    fun `toolResultTag matches tool_result tags`() {
        val input = """<tool_result name="test">content</tool_result>"""
        val match = ChatMarkupRegex.toolResultTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `toolResultSelfClosingTag matches self-closing tool_result`() {
        val input = """<tool_result name="test"/>"""
        assertTrue(ChatMarkupRegex.toolResultSelfClosingTag.containsMatchIn(input))
    }

    @Test
    fun `thinkTag matches think tags`() {
        val input = "<think>reasoning</think>"
        val match = ChatMarkupRegex.thinkTag.find(input)
        assertNotNull(match)
        assertEquals(input, match!!.value)
    }

    @Test
    fun `thinkTag matches thinking tags`() {
        val input = "<thinking>deep thoughts</thinking>"
        val match = ChatMarkupRegex.thinkTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `thinkSelfClosingTag matches self-closing think`() {
        val input = "<think/>"
        assertTrue(ChatMarkupRegex.thinkSelfClosingTag.containsMatchIn(input))
    }

    @Test
    fun `searchTag matches search tags`() {
        val input = "<search>query</search>"
        val match = ChatMarkupRegex.searchTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `metaTag matches meta tags`() {
        val input = """<meta provider="gemini:thought_signature">sig</meta>"""
        val match = ChatMarkupRegex.metaTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `emotionTag matches emotion tags`() {
        val input = "<emotion type=\"happy\">joy</emotion>"
        val match = ChatMarkupRegex.emotionTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `memoryTag matches memory tags`() {
        val input = "<memory>user likes coding</memory>"
        val match = ChatMarkupRegex.memoryTag.find(input)
        assertNotNull(match)
    }

    @Test
    fun `namePattern extracts name attribute`() {
        val input = """<tool_search name="myTool">content</tool_search>"""
        val match = ChatMarkupRegex.namePattern.find(input)
        assertNotNull(match)
        assertEquals("myTool", match!!.groupValues[1])
    }

    @Test
    fun `nameAttr extracts name attribute from generic tags`() {
        val input = """name="value""""
        val match = ChatMarkupRegex.nameAttr.find(input)
        assertNotNull(match)
        assertEquals("value", match!!.groupValues[1])
    }

    @Test
    fun `statusAttr extracts status attribute`() {
        val input = """status="success""""
        val match = ChatMarkupRegex.statusAttr.find(input)
        assertNotNull(match)
        assertEquals("success", match!!.groupValues[1])
    }

    @Test
    fun `toolOrToolResultBlock matches tool blocks`() {
        val input = "<tool>content</tool>"
        assertTrue(ChatMarkupRegex.toolOrToolResultBlock.containsMatchIn(input))
    }

    @Test
    fun `toolOrToolResultBlock matches tool_result blocks`() {
        val input = "<tool_result>content</tool_result>"
        assertTrue(ChatMarkupRegex.toolOrToolResultBlock.containsMatchIn(input))
    }

    @Test
    fun `anyXmlTag matches any xml tag`() {
        val input = "<randomTag>"
        assertTrue(ChatMarkupRegex.anyXmlTag.containsMatchIn(input))
    }

    @Test
    fun `anyXmlTag matches closing tags`() {
        assertTrue(ChatMarkupRegex.anyXmlTag.containsMatchIn("</div>"))
    }

    @Test
    fun `toolParamPattern matches param tags`() {
        val input = "<param name=\"key\">value</param>"
        val match = ChatMarkupRegex.toolParamPattern.find(input)
        assertNotNull(match)
        assertEquals("key", match!!.groupValues[1])
        assertEquals("value", match.groupValues[2])
    }

    @Test
    fun `xmlToolResultPattern matches structured tool_result`() {
        val input = """<tool_result name="test" status="success">
            <content>result data</content>
        </tool_result>""".trimIndent()
        val match = ChatMarkupRegex.xmlToolResultPattern.find(input)
        assertNotNull(match)
    }

    @Test
    fun `contentTag matches content tags`() {
        val input = "<content>text</content>"
        val match = ChatMarkupRegex.contentTag.find(input)
        assertNotNull(match)
        assertEquals("text", match!!.groupValues[1])
    }

    @Test
    fun `errorTag matches error tags`() {
        val input = "<error>something went wrong</error>"
        val match = ChatMarkupRegex.errorTag.find(input)
        assertNotNull(match)
        assertEquals("something went wrong", match!!.groupValues[1])
    }
}
