package com.apex.util

import com.apex.util.SyntaxCheckUtil.SyntaxCheckResult
import com.apex.util.SyntaxCheckUtil.SyntaxError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxCheckUtilTest {

    @Test
    fun `checkJavaScript returns empty errors for correct js`() {
        val code = """
            function hello() {
                console.log("world");
                return 42;
            }
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        assertTrue("Expected no errors", result.errors.isEmpty())
    }

    @Test
    fun `checkJavaScript detects mismatched brackets`() {
        val code = """
            function test() {
                if (true) {
                    console.log("mismatch");
            } // missing one closing brace
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        assertTrue("Expected errors for mismatched brackets", result.errors.isNotEmpty())
        val hasBracketError = result.errors.any { it.message.contains("Mismatched", ignoreCase = true) }
        assertTrue("Expected mismatched bracket error", hasBracketError)
    }

    @Test
    fun `checkJavaScript detects unclosed brackets`() {
        val code = """
            function test() {
                if (true) {
                    console.log("unclosed");
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        assertTrue("Expected errors for unclosed brackets", result.errors.isNotEmpty())
        val hasUnclosedError = result.errors.any { it.message.contains("Unclosed", ignoreCase = true) }
        assertTrue("Expected unclosed bracket error", hasUnclosedError)
    }

    @Test
    fun `checkJavaScript detects unclosed string`() {
        val code = """
            var x = "unclosed string;
            var y = 1;
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        assertTrue("Expected errors for unclosed string", result.errors.isNotEmpty())
        val hasStringError = result.errors.any { it.message.contains("Unclosed string", ignoreCase = true) }
        assertTrue("Expected unclosed string literal error", hasStringError)
    }

    @Test
    fun `checkJavaScript handles empty content`() {
        val result = SyntaxCheckUtil.checkJavaScript("empty.js", "")
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `checkJavaScript handles single line js`() {
        val code = "var x = 1;"
        val result = SyntaxCheckUtil.checkJavaScript("simple.js", code)
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `checkJavaScript detects double semicolon warning`() {
        val code = "var x = 1;;"
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        val hasDoubleSemicolon = result.errors.any { it.message.contains("Double semicolon", ignoreCase = true) }
        assertTrue("Expected double semicolon warning", hasDoubleSemicolon)
    }

    @Test
    fun `checkJavaScript handles return on separate line warning`() {
        val code = """
            function getValue() {
                return
                    42;
            }
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        val hasReturnWarning = result.errors.any { it.message.contains("Return statement", ignoreCase = true) }
        assertTrue("Expected return statement warning", hasReturnWarning)
    }

    @Test
    fun `checkHtml returns empty errors for correct html`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head><title>Test</title></head>
            <body><p>Hello</p></body>
            </html>
        """.trimIndent()
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected no errors for correct HTML", result.errors.isEmpty())
    }

    @Test
    fun `checkHtml detects mismatched tags`() {
        val html = """
            <div>
                <p>text</div>
            </div>
        """.trimIndent()
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected errors for mismatched tags", result.errors.isNotEmpty())
        val hasMismatch = result.errors.any { it.message.contains("Mismatched", ignoreCase = true) }
        assertTrue("Expected mismatched tag error", hasMismatch)
    }

    @Test
    fun `checkHtml detects unclosed tags`() {
        val html = """
            <div>
                <p>text</p>
            <!-- missing closing div -->
        """.trimIndent()
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected errors for unclosed tags", result.errors.isNotEmpty())
        val hasUnclosed = result.errors.any { it.message.contains("Unclosed", ignoreCase = true) }
        assertTrue("Expected unclosed tag error", hasUnclosed)
    }

    @Test
    fun `checkHtml detects unclosed comment`() {
        val html = """
            <p>hello</p>
            <!-- unclosed comment
            <p>world</p>
        """.trimIndent()
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected errors for unclosed comment", result.errors.isNotEmpty())
        val hasCommentError = result.errors.any { it.message.contains("Unclosed HTML comment", ignoreCase = true) }
        assertTrue("Expected unclosed HTML comment error", hasCommentError)
    }

    @Test
    fun `checkHtml warns about unquoted attribute values`() {
        val html = """<div class=container>content</div>"""
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        val hasQuoteWarning = result.errors.any { it.message.contains("should be quoted", ignoreCase = true) }
        assertTrue("Expected unquoted attribute warning", hasQuoteWarning)
    }

    @Test
    fun `checkHtml handles empty content`() {
        val result = SyntaxCheckUtil.checkHtml("empty.html", "")
        assertEquals(0, result.errors.size)
    }

    @Test
    fun `checkHtml handles self-closing tags correctly`() {
        val html = """<br><img src="test.jpg"><input type="text">"""
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected no errors for self-closing tags", result.errors.isEmpty())
    }

    @Test
    fun `checkSyntax returns result for js file`() {
        val result = SyntaxCheckUtil.checkSyntax("script.js", "var x = 1;")
        assertNotNull("Expected result for .js", result)
        assertEquals("JavaScript", result!!.fileType)
    }

    @Test
    fun `checkSyntax returns result for mjs file`() {
        val result = SyntaxCheckUtil.checkSyntax("module.mjs", "export const x = 1;")
        assertNotNull("Expected result for .mjs", result)
    }

    @Test
    fun `checkSyntax returns result for jsx file`() {
        val result = SyntaxCheckUtil.checkSyntax("component.jsx", "const el = <div/>;")
        assertNotNull("Expected result for .jsx", result)
    }

    @Test
    fun `checkSyntax returns result for html file`() {
        val result = SyntaxCheckUtil.checkSyntax("index.html", "<p>hello</p>")
        assertNotNull("Expected result for .html", result)
    }

    @Test
    fun `checkSyntax returns result for htm file`() {
        val result = SyntaxCheckUtil.checkSyntax("page.htm", "<p>hello</p>")
        assertNotNull("Expected result for .htm", result)
    }

    @Test
    fun `checkSyntax returns null for unsupported file type`() {
        val result = SyntaxCheckUtil.checkSyntax("data.py", "print('hello')")
        assertNull("Expected null for unsupported type", result)
    }

    @Test
    fun `checkSyntax returns null for no extension`() {
        val result = SyntaxCheckUtil.checkSyntax("Makefile", "all: build")
        assertNull("Expected null for no extension", result)
    }

    @Test
    fun `checkSyntax handles empty content`() {
        val result = SyntaxCheckUtil.checkSyntax("test.js", "")
        assertNotNull(result)
        assertTrue(result!!.errors.isEmpty())
    }

    @Test
    fun `SyntaxError toString formats correctly`() {
        val error = SyntaxError(5, 10, "test message", SyntaxError.Severity.ERROR)
        val str = error.toString()
        assertTrue(str.contains("Line"))
        assertTrue(str.contains("5"))
        assertTrue(str.contains("10"))
        assertTrue(str.contains("ERROR"))
        assertTrue(str.contains("test message"))
    }

    @Test
    fun `SyntaxCheckResult hasErrors returns true when errors exist`() {
        val errors = listOf(SyntaxError(1, 1, "error", SyntaxError.Severity.ERROR))
        val result = SyntaxCheckResult("f.js", "JavaScript", errors)
        assertTrue(result.hasErrors)
    }

    @Test
    fun `SyntaxCheckResult toString works with empty errors`() {
        val result = SyntaxCheckResult("f.js", "JavaScript", emptyList())
        val str = result.toString()
        assertTrue(str.contains("No syntax errors"))
    }

    @Test
    fun `checkJavaScript ignores content in strings and comments`() {
        val code = """
            var str = "string with { and ( brackets";
            // comment with [brackets]
            /* multi-line
               { also } ignored */
            function valid() { return true; }
        """.trimIndent()
        val result = SyntaxCheckUtil.checkJavaScript("test.js", code)
        assertTrue("Expected no errors", result.errors.isEmpty())
    }

    @Test
    fun `checkJavaScript correctly processes cjs extension`() {
        val result = SyntaxCheckUtil.checkSyntax("module.cjs", "module.exports = {};")
        assertNotNull(result)
    }

    @Test
    fun `checkHtml handles script tags correctly`() {
        val html = """
            <html>
            <script>
                if (a < b) { console.log("test"); }
            </script>
            <body></body>
            </html>
        """.trimIndent()
        val result = SyntaxCheckUtil.checkHtml("test.html", html)
        assertTrue("Expected no errors from script content interfering", result.errors.isEmpty())
    }
}
