package com.apex.lib.workingfiles

import java.io.File

/**
 * 代码预览工具 — 解析文件内容，提供语法高亮所需的结构化数据。
 *
 * 实际 UI 渲染由各 APK 自己的 Compose 组件完成（如自定义 CodeView）。
 * 本类只负责：
 *   - 检测语言（根据扩展名）
 *   - 读取文件内容（按行）
 *   - 提供简单的 token 化（关键字 / 字符串 / 注释 / 数字）
 */
object CodePreview {

    data class Token(
        val text: String,
        val type: TokenType
    )

    enum class TokenType {
        PLAIN, KEYWORD, STRING, COMMENT, NUMBER, OPERATOR, IDENTIFIER
    }

    data class CodeFile(
        val path: String,
        val language: String,
        val lineCount: Int,
        val totalChars: Int,
        val content: String
    )

    fun load(file: File): CodeFile? {
        if (!file.exists() || !file.isFile) return null
        val content = try {
            file.readText()
        } catch (t: Throwable) {
            return null
        }
        val lines = content.count { it == '\n' } + 1
        val lang = detectLanguage(file.name)
        return CodeFile(
            path = file.absolutePath,
            language = lang,
            lineCount = lines,
            totalChars = content.length,
            content = content
        )
    }

    fun detectLanguage(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "py" -> "python"
            "js", "mjs", "cjs" -> "javascript"
            "ts" -> "typescript"
            "json" -> "json"
            "xml" -> "xml"
            "html", "htm" -> "html"
            "css" -> "css"
            "md" -> "markdown"
            "sh", "bash" -> "shell"
            "c", "h" -> "c"
            "cpp", "cc", "cxx", "hpp" -> "cpp"
            "go" -> "go"
            "rs" -> "rust"
            "rb" -> "ruby"
            "php" -> "php"
            "swift" -> "swift"
            "dart" -> "dart"
            "sql" -> "sql"
            "yaml", "yml" -> "yaml"
            "toml" -> "toml"
            "gradle" -> "gradle"
            "proto" -> "protobuf"
            else -> "text"
        }
    }

    /**
     * 简单 token 化 — 仅支持关键字 / 字符串 / 注释 / 数字。
     * 真实高亮应该用各语言专门的词法分析器。
     */
    fun tokenize(content: String, language: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val keywords = keywordSet(language)
        val buffer = StringBuilder()
        var inString = false
        var stringChar = '"'
        var inLineComment = false
        var inBlockComment = false

        fun flush() {
            if (buffer.isEmpty()) return
            val text = buffer.toString()
            val type = when {
                keywords.contains(text) -> TokenType.KEYWORD
                text.matches(Regex("-?\\d+(\\.\\d+)?[fLl]?")) -> TokenType.NUMBER
                text.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) -> TokenType.IDENTIFIER
                else -> TokenType.PLAIN
            }
            tokens.add(Token(text, type))
            buffer.clear()
        }

        var i = 0
        while (i < content.length) {
            val c = content[i]
            val next = if (i + 1 < content.length) content[i + 1] else ' '

            if (inLineComment) {
                buffer.append(c)
                if (c == '\n') {
                    tokens.add(Token(buffer.toString(), TokenType.COMMENT))
                    buffer.clear()
                    inLineComment = false
                }
                i++; continue
            }
            if (inBlockComment) {
                buffer.append(c)
                if (c == '*' && next == '/') {
                    buffer.append('/')
                    i += 2
                    tokens.add(Token(buffer.toString(), TokenType.COMMENT))
                    buffer.clear()
                    inBlockComment = false
                    continue
                }
                i++; continue
            }
            if (inString) {
                buffer.append(c)
                if (c == stringChar && (i == 0 || content[i - 1] != '\\')) {
                    tokens.add(Token(buffer.toString(), TokenType.STRING))
                    buffer.clear()
                    inString = false
                }
                i++; continue
            }

            when {
                c == '/' && next == '/' -> {
                    flush()
                    inLineComment = true
                    buffer.append(c)
                    i++
                }
                c == '/' && next == '*' -> {
                    flush()
                    inBlockComment = true
                    buffer.append(c)
                    i++
                }
                c == '"' || c == '\'' || c == '`' -> {
                    flush()
                    inString = true
                    stringChar = c
                    buffer.append(c)
                    i++
                }
                c.isLetterOrDigit() || c == '_' -> {
                    buffer.append(c)
                    i++
                }
                c.isWhitespace() -> {
                    flush()
                    tokens.add(Token(c.toString(), TokenType.PLAIN))
                    i++
                }
                else -> {
                    flush()
                    tokens.add(Token(c.toString(), TokenType.OPERATOR))
                    i++
                }
            }
        }
        flush()
        return tokens
    }

    private fun keywordSet(language: String): Set<String> = when (language) {
        "kotlin" -> setOf(
            "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data",
            "if", "else", "when", "for", "while", "do", "return", "break", "continue",
            "import", "package", "as", "is", "in", "out", "by", "where", "init",
            "constructor", "this", "super", "null", "true", "false", "typeof",
            "private", "public", "protected", "internal", "abstract", "open", "final",
            "override", "operator", "inline", "suspend", "companion", "lateinit",
            "const", "vararg", "reified", "annotation", "crossinline", "noinline",
            "tailrec", "external", "infix", "in", "out"
        )
        "java" -> setOf(
            "public", "private", "protected", "static", "final", "abstract", "class",
            "interface", "enum", "extends", "implements", "package", "import",
            "if", "else", "switch", "case", "default", "for", "while", "do",
            "return", "break", "continue", "new", "this", "super", "null",
            "true", "false", "void", "int", "long", "double", "float", "boolean",
            "char", "byte", "short", "try", "catch", "finally", "throw", "throws",
            "instanceof", "synchronized", "volatile", "transient", "native", "strictfp"
        )
        "python" -> setOf(
            "def", "class", "if", "elif", "else", "for", "while", "return",
            "import", "from", "as", "with", "try", "except", "finally", "raise",
            "lambda", "yield", "global", "nonlocal", "pass", "break", "continue",
            "and", "or", "not", "in", "is", "True", "False", "None", "self",
            "async", "await", "assert", "del"
        )
        "javascript", "typescript" -> setOf(
            "var", "let", "const", "function", "return", "if", "else", "for",
            "while", "do", "switch", "case", "break", "continue", "new",
            "this", "super", "class", "extends", "import", "export", "from",
            "default", "async", "await", "yield", "typeof", "instanceof",
            "in", "of", "delete", "void", "null", "undefined", "true", "false",
            "try", "catch", "finally", "throw", "interface", "type", "enum",
            "namespace", "public", "private", "protected", "readonly", "static",
            "abstract", "as", "is", "implements"
        )
        else -> emptySet()
    }
}
