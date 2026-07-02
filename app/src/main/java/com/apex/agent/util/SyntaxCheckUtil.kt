package com.apex.util

import com.apex.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Stack

/**
 * 语法检查工具类
 *
 * 提供 JavaScript、HTML、CSS、Python、JSON、XML、SQL
 * 等多种文件格式的简单语法检查功能。
 *
 * 注意：此工具类执行的是基础语法规则检查，并非完整的语言解析器，
 * 适用于代码提交前的快速校验场景。
 */
object SyntaxCheckUtil {
    private const val TAG = "SyntaxCheckUtil"

    /**
     * 语法错误信息数据类
     *
     * @param line 错误所在行号
     * @param column 错误所在列号
     * @param message 错误描述信息
     * @param severity 严重级别（ERROR 或 WARNING）
     */
    data class SyntaxError(
        val line: Int,
        val column: Int,
        val message: String,
        val severity: Severity = Severity.ERROR
    ) {
        enum class Severity {
            ERROR,
            WARNING
        }

        override fun toString(): String {
            return "Line ${line}:${column} - ${severity.name}: ${message}"
        }
    }

    /**
     * 语法检查结果数据类
     *
     * @param filePath 被检查的文件路径
     * @param fileType 文件类型（如 JavaScript、HTML、CSS）
     * @param errors 发现的错误/警告列表
     * @param hasErrors 是否存在 ERROR 级别的问题
     */
    data class SyntaxCheckResult(
        val filePath: String,
        val fileType: String,
        val errors: List<SyntaxError>,
        val hasErrors: Boolean = errors.any { it.severity == SyntaxError.Severity.ERROR }
    ) {
        override fun toString(): String {
            if (errors.isEmpty()) {
                return "${filePath}: No syntax errors found"
            }

            val sb = StringBuilder()
            sb.appendLine("Syntax check for ${filePath} (${fileType}):")
            sb.appendLine("Found ${errors.size} issue(s):")
            errors.forEach { error ->
                sb.appendLine("  ${error}")
            }
            return sb.toString()
        }
    }

    /**
     * 根据文件路径自动选择对应的语法检查器
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果，如果文件类型不受支持则返回 null
     */
    fun checkSyntax(filePath: String, content: String): SyntaxCheckResult? {
        val file = File(filePath)
        val extension = file.extension.lowercase()

        return when (extension) {
            "js", "mjs", "cjs", "jsx" -> checkJavaScript(filePath, content)
            "html", "htm" -> checkHtml(filePath, content)
            "css", "scss", "less" -> checkCss(filePath, content)
            "py", "python" -> checkPython(filePath, content)
            "json" -> checkJson(filePath, content)
            "xml", "xsd", "xsl", "xslt", "svg", "plist" -> checkXml(filePath, content)
            "sql" -> checkSql(filePath, content)
            else -> null
        }
    }

    // ========================================================================
    // JavaScript 语法检查
    // ========================================================================

    /**
     * 检查 JavaScript 语法
     *
     * 执行简单的语法检查，包括：
     * - 括号匹配（圆括号、方括号、花括号）
     * - 引号匹配（单引号、双引号、反引号）
     * - 注释完整性
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkJavaScript(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()

        checkBracketMatching(lines, errors)
        checkQuoteMatching(lines, errors)
        checkCommentMatching(lines, errors)
        checkCommonJsErrors(lines, errors)

        return SyntaxCheckResult(filePath, "JavaScript", errors)
    }

    // ========================================================================
    // HTML 语法检查
    // ========================================================================

    /**
     * 检查 HTML 语法
     *
     * 执行简单的语法检查，包括：
     * - 标签匹配
     * - 属性引号
     * - 注释完整性
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkHtml(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()

        checkHtmlTagMatching(lines, errors)
        checkHtmlComments(lines, errors)
        checkHtmlAttributeQuotes(lines, errors)

        return SyntaxCheckResult(filePath, "HTML", errors)
    }

    // ========================================================================
    // CSS 语法检查
    // ========================================================================

    /**
     * 检查 CSS 语法
     *
     * 执行基础语法检查，包括：
     * - 花括号匹配
     * - 选择器格式
     * - 属性:值格式
     * - 注释完整性
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkCss(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()
        val contentTrimmed = content.trim()

        if (contentTrimmed.isBlank()) {
            return SyntaxCheckResult(filePath, "CSS", errors)
        }

        checkCssBraceMatching(lines, errors)
        checkCssSelectorFormat(lines, errors)
        checkCssPropertyFormat(lines, errors)
        checkCommentMatching(lines, errors)

        return SyntaxCheckResult(filePath, "CSS", errors)
    }

    /**
     * 检查 CSS 花括号匹配
     */
    private fun checkCssBraceMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        val stack = Stack<Pair<Int, Int>>()
        var inString = false
        var stringChar = ' '
        var inBlockComment = false

        lines.forEachIndexed { lineIndex, line ->
            var i = 0
            while (i < line.length) {
                val ch = line[i]

                // 处理注释
                if (!inString && i < line.length - 1) {
                    if (line[i] == '/' && line[i + 1] == '*') {
                        inBlockComment = true
                        i += 2
                        continue
                    }
                    if (inBlockComment && line[i] == '*' && line[i + 1] == '/') {
                        inBlockComment = false
                        i += 2
                        continue
                    }
                }

                // 处理字符串
                if (!inBlockComment && (ch == '"' || ch == '\'')) {
                    if (!inString) {
                        inString = true
                        stringChar = ch
                    } else if (ch == stringChar) {
                        // 检查是否转义
                        var escapeCount = 0
                        var j = i - 1
                        while (j >= 0 && line[j] == '\\') { escapeCount++; j-- }
                        if (escapeCount % 2 == 0) {
                            inString = false
                        }
                    }
                }

                if (!inString && !inBlockComment) {
                    if (ch == '{') {
                        stack.push(lineIndex + 1 to i + 1)
                    } else if (ch == '}') {
                        if (stack.isEmpty()) {
                            errors.add(SyntaxError(lineIndex + 1, i + 1, "Unexpected closing brace '}'"))
                        } else {
                            stack.pop()
                        }
                    }
                }
                i++
            }
        }

        stack.forEach { (line, col) ->
            errors.add(SyntaxError(line, col, "Unclosed brace '{'"))
        }

        if (inBlockComment) {
            errors.add(SyntaxError(lines.size, 1, "Unclosed multi-line comment"))
        }
    }

    /**
     * 检查 CSS 选择器格式
     */
    private fun checkCssSelectorFormat(lines: List<String>, errors: MutableList<SyntaxError>) {
        var inBlock = false
        var bracketDepth = 0

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("/*") || trimmed.startsWith("*")) return@forEachIndexed

            if (trimmed.startsWith("}")) {
                bracketDepth--
                if (bracketDepth <= 0) {
                    inBlock = false
                    bracketDepth = 0
                }
            }

            if (!inBlock && trimmed.endsWith("{") && !trimmed.startsWith("/*")) {
                val selector = trimmed.removeSuffix("{").trim()
                // 检查选择器是否为空
                if (selector.isEmpty()) {
                    errors.add(SyntaxError(lineIndex + 1, 1, "Empty selector", SyntaxError.Severity.WARNING))
                }
                // 检查是否以合法的选择器字符开头
                if (selector.isNotEmpty()) {
                    val firstChar = selector.first()
                    if (firstChar !in ".#*:[]@abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") {
                        errors.add(SyntaxError(lineIndex + 1, 1, "Invalid selector start character '$firstChar'"))
                    }
                }
                inBlock = true
                bracketDepth = 1
            }

            // 统计块内的花括号嵌套
            if (inBlock) {
                for (ch in trimmed) {
                    if (ch == '{') bracketDepth++
                    if (ch == '}') bracketDepth--
                }
                if (bracketDepth <= 0) {
                    inBlock = false
                    bracketDepth = 0
                }
            }
        }
    }

    /**
     * 检查 CSS 属性:值格式
     */
    private fun checkCssPropertyFormat(lines: List<String>, errors: MutableList<SyntaxError>) {
        var inBlock = false
        var bracketDepth = 0

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("/*")) return@forEachIndexed

            if (trimmed.startsWith("}")) {
                bracketDepth--
                if (bracketDepth <= 0) { inBlock = false; bracketDepth = 0 }
            }

            if (trimmed.endsWith("{") && !trimmed.startsWith("/*")) {
                inBlock = true
                bracketDepth = 1
            }

            if (inBlock) {
                for (ch in trimmed) {
                    if (ch == '{') bracketDepth++
                    if (ch == '}') bracketDepth--
                }
                if (bracketDepth <= 0) { inBlock = false; bracketDepth = 0 }

                // 检查属性行是否包含冒号
                if (inBlock && trimmed.contains(":") && !trimmed.startsWith("/*") && !trimmed.endsWith("{")) {
                    val colonIndex = trimmed.indexOf(':')
                    val propertyName = trimmed.substring(0, colonIndex).trim()
                    if (propertyName.isEmpty()) {
                        errors.add(SyntaxError(lineIndex + 1, 1, "Empty property name", SyntaxError.Severity.WARNING))
                    }
                }
            }
        }
    }

    // ========================================================================
    // Python 语法检查
    // ========================================================================

    /** Python 块起始关键字 */
    private val pythonBlockKeywords = setOf("if", "elif", "else", "for", "while", "def", "class", "try", "except", "finally", "with")

    /**
     * 检查 Python 语法
     *
     * 执行基础语法检查，包括：
     * - 缩进一致性（Tab 与空格混用检查）
     * - 块语句冒号检查（if/for/while/def/class 等后是否跟冒号）
     * - 括号匹配
     * - 三引号匹配
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkPython(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()

        checkPythonIndentation(lines, errors)
        checkPythonColon(lines, errors)
        checkBracketMatching(lines, errors)
        checkPythonTripleQuotes(lines, errors)

        return SyntaxCheckResult(filePath, "Python", errors)
    }

    /**
     * 检查 Python 缩进一致性（Tab 与空格混用）
     */
    private fun checkPythonIndentation(lines: List<String>, errors: MutableList<SyntaxError>) {
        var hasTabIndent = false
        var hasSpaceIndent = false
        var tabLine = -1
        var spaceLine = -1

        lines.forEachIndexed { lineIndex, line ->
            if (line.isBlank()) return@forEachIndexed
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) return@forEachIndexed

            val leadingWhitespace = line.length - trimmed.length
            if (leadingWhitespace > 0) {
                if (line.startsWith("\t")) {
                    if (!hasTabIndent) { hasTabIndent = true; tabLine = lineIndex + 1 }
                } else {
                    if (!hasSpaceIndent) { hasSpaceIndent = true; spaceLine = lineIndex + 1 }
                }
            }
        }

        if (hasTabIndent && hasSpaceIndent) {
            errors.add(
                SyntaxError(
                    1, 1,
                    "Mixed tabs and spaces in indentation (tab at line $tabLine, spaces at line $spaceLine)",
                    SyntaxError.Severity.WARNING
                )
            )
        }
    }

    /**
     * 检查 Python 块语句后是否缺少冒号
     */
    private fun checkPythonColon(lines: List<String>, errors: MutableList<SyntaxError>) {
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed

            // 检查块关键字开头的语句是否以冒号结尾
            val keyword = pythonBlockKeywords.firstOrNull { trimmed.startsWith("$it ") || trimmed.startsWith("$it:") }
            if (keyword != null) {
                // 提取关键字后的部分
                val afterKeyword = trimmed.removePrefix(keyword).trim()
                // 跳过条件表达式中的括号内容，检查最终是否以冒号结尾
                if (!trimmed.endsWith(":")) {
                    errors.add(
                        SyntaxError(
                            lineIndex + 1,
                            trimmed.length,
                            "Missing colon after '$keyword' statement",
                            SyntaxError.Severity.ERROR
                        )
                    )
                }
            }
        }
    }

    /**
     * 检查 Python 三引号匹配
     */
    private fun checkPythonTripleQuotes(lines: List<String>, errors: MutableList<SyntaxError>) {
        val content = lines.joinToString("\n")
        var i = 0
        var inTripleSingle = false
        var inTripleDouble = false
        var tripleStartLine = -1

        var lineCount = 1
        var colCount = 1
        while (i < content.length) {
            if (content[i] == '\n') { lineCount++; colCount = 1; i++; continue }

            if (!inTripleSingle && !inTripleDouble && i + 2 < content.length) {
                if (content.startsWith("'''", i)) {
                    inTripleSingle = true
                    tripleStartLine = lineCount
                    i += 3
                    colCount += 3
                    continue
                }
                if (content.startsWith("\"\"\"", i)) {
                    inTripleDouble = true
                    tripleStartLine = lineCount
                    i += 3
                    colCount += 3
                    continue
                }
            }

            if (inTripleSingle && content.startsWith("'''", i)) {
                inTripleSingle = false
                i += 3
                colCount += 3
                continue
            }
            if (inTripleDouble && content.startsWith("\"\"\"", i)) {
                inTripleDouble = false
                i += 3
                colCount += 3
                continue
            }

            i++
            colCount++
        }

        if (inTripleSingle) {
            errors.add(SyntaxError(tripleStartLine, 1, "Unclosed triple single-quoted string"))
        }
        if (inTripleDouble) {
            errors.add(SyntaxError(tripleStartLine, 1, "Unclosed triple double-quoted string"))
        }
    }

    // ========================================================================
    // JSON 语法检查
    // ========================================================================

    /**
     * 检查 JSON 语法
     *
     * 使用 org.json.JSONObject / JSONArray 进行实际解析，
     * 并报告具体错误位置（行号和列号）。
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkJson(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val trimmed = content.trim()

        if (trimmed.isEmpty()) {
            return SyntaxCheckResult(filePath, "JSON", errors)
        }

        try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else if (trimmed.startsWith("{")) {
                JSONObject(trimmed)
            } else {
                errors.add(SyntaxError(1, 1, "JSON must start with '{' or '['"))
            }
        } catch (e: org.json.JSONException) {
            // 尝试从异常消息中提取行号和列号
            val message = e.message ?: "Unknown JSON error"
            val lineMatch = Regex("""at line (\d+)""").find(message)
            val colMatch = Regex("""column (\d+)""").find(message)

            val line = lineMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val col = colMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            errors.add(SyntaxError(line, col, message))
        } catch (e: Exception) {
            errors.add(SyntaxError(1, 1, "JSON parse error: ${e.message}"))
        }

        return SyntaxCheckResult(filePath, "JSON", errors)
    }

    // ========================================================================
    // XML 语法检查
    // ========================================================================

    /**
     * 检查 XML 语法
     *
     * 执行基础语法检查，包括：
     * - 标签匹配
     * - 属性引号
     * - XML 声明检查
     * - 注释完整性
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkXml(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()

        checkXmlDeclaration(lines, errors)
        checkXmlTagMatching(lines, errors)
        checkHtmlAttributeQuotes(lines, errors)
        checkXmlComments(lines, errors)

        return SyntaxCheckResult(filePath, "XML", errors)
    }

    /**
     * 检查 XML 声明
     */
    private fun checkXmlDeclaration(lines: List<String>, errors: MutableList<SyntaxError>) {
        if (lines.isEmpty()) return
        val firstLine = lines[0].trim()
        // 如果第一行看起来像 XML 声明但不是有效的声明
        if (firstLine.startsWith("<?") && !firstLine.startsWith("<?xml")) {
            errors.add(SyntaxError(1, 1, "XML declaration should be '<?xml ... ?>', found '${firstLine.substring(0, minOf(firstLine.length, 20))}'", SyntaxError.Severity.WARNING))
        }
        // 检查声明是否闭合
        if (firstLine.startsWith("<?xml") && !firstLine.endsWith("?>")) {
            errors.add(SyntaxError(1, 1, "XML declaration not properly closed"))
        }
    }

    /**
     * 检查 XML 标签匹配
     */
    private fun checkXmlTagMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        val stack = Stack<Pair<String, Pair<Int, Int>>>()
        val selfClosingTags = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
        )

        val content = lines.joinToString("\n")
        val cleanedContent = removeXmlComments(content)
        val tagPattern = Regex("<(/)([a-zA-Z_][a-zA-Z0-9_.-]*)(\\s[^>]*?)?\\s*(/)?>")

        tagPattern.findAll(cleanedContent).forEach { match ->
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()
            val isSelfClosing = match.groupValues[4] == "/"

            val position = match.range.first
            var line = 1
            var col = 1
            var currentPos = 0
            for ((lineIndex, lineContent) in lines.withIndex()) {
                if (currentPos + lineContent.length >= position) {
                    line = lineIndex + 1
                    col = position - currentPos + 1
                    break
                }
                currentPos += lineContent.length + 1
            }

            if (isClosing) {
                if (stack.isEmpty()) {
                    errors.add(SyntaxError(line, col, "Unexpected closing tag </$tagName>"))
                } else {
                    val (openTag, _) = stack.peek()
                    if (openTag == tagName) {
                        stack.pop()
                    } else {
                        errors.add(SyntaxError(line, col, "Mismatched tag: expected </$openTag>, found </$tagName>"))
                    }
                }
            } else if (!isSelfClosing && tagName !in selfClosingTags) {
                // 忽略 XML 声明的伪标签
                if (!tagName.startsWith("?")) {
                    stack.push(tagName to (line to col))
                }
            }
        }

        stack.forEach { (tagName, position) ->
            errors.add(SyntaxError(position.first, position.second, "Unclosed tag <$tagName>"))
        }
    }

    /**
     * 移除 XML 注释内容以避免误匹配
     */
    private fun removeXmlComments(content: String): String {
        val result = StringBuilder()
        var i = 0
        while (i < content.length) {
            if (content.startsWith("<!--", i)) {
                val commentEnd = content.indexOf("-->", i + 4)
                if (commentEnd != -1) {
                    result.append(" ".repeat(commentEnd + 3 - i))
                    i = commentEnd + 3
                    continue
                }
            }
            result.append(content[i])
            i++
        }
        return result.toString()
    }

    /**
     * 检查 XML 注释完整性
     */
    private fun checkXmlComments(lines: List<String>, errors: MutableList<SyntaxError>) {
        val content = lines.joinToString("\n")
        var inComment = false
        var commentStartLine = -1
        var commentStartCol = -1

        var lineIdx = 1
        var colIdx = 1
        var i = 0
        while (i < content.length) {
            if (content[i] == '\n') { lineIdx++; colIdx = 1; i++; continue }
            if (!inComment && content.startsWith("<!--", i)) {
                inComment = true
                commentStartLine = lineIdx
                commentStartCol = colIdx
                i += 4
                colIdx += 4
                continue
            }
            if (inComment && content.startsWith("-->", i)) {
                inComment = false
                i += 3
                colIdx += 3
                continue
            }
            i++
            colIdx++
        }

        if (inComment) {
            errors.add(SyntaxError(commentStartLine, commentStartCol, "Unclosed XML comment"))
        }
    }

    // ========================================================================
    // SQL 语法检查
    // ========================================================================

    /** SQL 常用关键字列表 */
    private val sqlKeywords = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "DROP", "ALTER", "ADD", "COLUMN", "INDEX", "VIEW",
        "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "ON", "AND", "OR",
        "NOT", "IN", "LIKE", "BETWEEN", "EXISTS", "IS", "NULL", "AS", "ORDER",
        "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT",
        "CASE", "WHEN", "THEN", "ELSE", "END", "BEGIN", "COMMIT", "ROLLBACK",
        "GRANT", "REVOKE", "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT",
        "DEFAULT", "CHECK", "UNIQUE", "AUTO_INCREMENT", "CASCADE", "TRIGGER",
        "PROCEDURE", "FUNCTION", "RETURNS", "DECLARE", "CURSOR", "FETCH", "LOOP"
    )

    /**
     * 检查 SQL 语法
     *
     * 执行基础语法检查，包括：
     * - 关键字大小写一致性
     * - 字符串字面量匹配
     * - 注释完整性
     *
     * @param filePath 文件路径
     * @param content 文件内容
     * @return 语法检查结果
     */
    fun checkSql(filePath: String, content: String): SyntaxCheckResult {
        val errors = mutableListOf<SyntaxError>()
        val lines = content.lines()

        checkSqlKeywordCase(lines, errors)
        checkSqlStringLiterals(lines, errors)
        checkSqlComments(lines, errors)

        return SyntaxCheckResult(filePath, "SQL", errors)
    }

    /**
     * 检查 SQL 关键字大小写一致性
     */
    private fun checkSqlKeywordCase(lines: List<String>, errors: MutableList<SyntaxError>) {
        var upperCount = 0
        var lowerCount = 0

        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("/*")) return@forEachIndexed

            // 检查每行中出现的 SQL 关键字
            val words = trimmed.split(Regex("\\s+"))
            for (word in words) {
                val cleanWord = word.replace(Regex("[^a-zA-Z]"), "")
                if (cleanWord.length >= 3 && cleanWord.uppercase() in sqlKeywords) {
                    if (cleanWord == cleanWord.uppercase()) upperCount++
                    if (cleanWord == cleanWord.lowercase()) lowerCount++
                }
            }
        }

        // 如果同时出现大写和小写关键字，给出警告
        if (upperCount > 0 && lowerCount > 0) {
            errors.add(
                SyntaxError(
                    1, 1,
                    "Inconsistent SQL keyword casing (mix of UPPER and lower case)",
                    SyntaxError.Severity.WARNING
                )
            )
        }
    }

    /**
     * 检查 SQL 字符串字面量匹配
     */
    private fun checkSqlStringLiterals(lines: List<String>, errors: MutableList<SyntaxError>) {
        val content = lines.joinToString("\n")
        var inString = false
        var stringStartLine = -1
        var stringStartCol = -1
        var inLineComment = false
        var inBlockComment = false

        var lineIdx = 1
        var colIdx = 1
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (ch == '\n') { lineIdx++; colIdx = 1; i++; inLineComment = false; continue }

            if (!inString) {
                // 检查注释
                if (!inBlockComment && i + 1 < content.length && content[i] == '-' && content[i + 1] == '-') {
                    inLineComment = true
                }
                if (!inLineComment && i + 1 < content.length && content[i] == '/' && content[i + 1] == '*') {
                    inBlockComment = true
                    i += 2
                    colIdx += 2
                    continue
                }
                if (inBlockComment && i + 1 < content.length && content[i] == '*' && content[i + 1] == '/') {
                    inBlockComment = false
                    i += 2
                    colIdx += 2
                    continue
                }
            }

            if (!inLineComment && !inBlockComment) {
                if (ch == '\'' && (i == 0 || content[i - 1] != '\\')) {
                    if (!inString) {
                        inString = true
                        stringStartLine = lineIdx
                        stringStartCol = colIdx
                    } else {
                        // 检查是否为连续两个单引号（SQL 转义）
                        if (i + 1 < content.length && content[i + 1] == '\'') {
                            i += 2
                            colIdx += 2
                            continue
                        }
                        inString = false
                    }
                }
            }

            i++
            colIdx++
        }

        if (inString) {
            errors.add(SyntaxError(stringStartLine, stringStartCol, "Unclosed string literal", SyntaxError.Severity.WARNING))
        }
        if (inBlockComment) {
            errors.add(SyntaxError(1, 1, "Unclosed block comment"))
        }
    }

    /**
     * 检查 SQL 注释完整性
     */
    private fun checkSqlComments(lines: List<String>, errors: MutableList<SyntaxError>) {
        val content = lines.joinToString("\n")
        var inBlockComment = false
        var commentStartLine = -1

        var lineIdx = 1
        var i = 0
        while (i < content.length) {
            if (content[i] == '\n') { lineIdx++; i++; continue }
            if (!inBlockComment && i + 1 < content.length && content[i] == '/' && content[i + 1] == '*') {
                inBlockComment = true
                commentStartLine = lineIdx
                i += 2
                continue
            }
            if (inBlockComment && i + 1 < content.length && content[i] == '*' && content[i + 1] == '/') {
                inBlockComment = false
                i += 2
                continue
            }
            i++
        }

        if (inBlockComment) {
            errors.add(SyntaxError(commentStartLine, 1, "Unclosed SQL block comment"))
        }
    }

    // ========================================================================
    // 私有辅助方法（括号、引号、注释匹配等）
    // ========================================================================

    /**
     * 检查括号匹配
     */
    private fun checkBracketMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        val stack = mutableListOf<Pair<Char, Pair<Int, Int>>>()
        val bracketPairs = mapOf('(' to ')', '[' to ']', '{' to '}')

        var inString = false
        var stringChar = ' '
        var inMultiLineComment = false

        lines.forEachIndexed { lineIndex, line ->
            var inSingleLineComment = false
            var i = 0

            while (i < line.length) {
                val char = line[i]

                if (!inString && !inSingleLineComment && i < line.length - 1) {
                    if (line[i] == '/' && line[i + 1] == '*') {
                        inMultiLineComment = true
                        i += 2
                        continue
                    }
                    if (inMultiLineComment && line[i] == '*' && line[i + 1] == '/') {
                        inMultiLineComment = false
                        i += 2
                        continue
                    }
                }

                if (!inString && !inMultiLineComment && i < line.length - 1 && line[i] == '/' && line[i + 1] == '/') {
                    inSingleLineComment = true
                    break
                }

                if (!inMultiLineComment && !inSingleLineComment && (char == '"' || char == '\'' || char == '`')) {
                    var isEscaped = false
                    var escapeCount = 0
                    var j = i - 1
                    while (j >= 0 && line[j] == '\\') {
                        escapeCount++
                        j--
                    }
                    isEscaped = escapeCount % 2 == 1

                    if (!isEscaped) {
                        if (!inString) {
                            inString = true
                            stringChar = char
                        } else if (char == stringChar) {
                            inString = false
                        }
                    }
                }

                if (!inString && !inSingleLineComment && !inMultiLineComment) {
                    if (char in bracketPairs.keys) {
                        stack.add(char to (lineIndex + 1 to i + 1))
                    } else if (char in bracketPairs.values) {
                        if (stack.isEmpty()) {
                            errors.add(
                                SyntaxError(
                                    lineIndex + 1,
                                    i + 1,
                                    "Unexpected closing bracket '${char}'"
                                )
                            )
                        } else {
                            val (openBracket, _) = stack.last()
                            if (bracketPairs[openBracket] == char) {
                                stack.removeAt(stack.size - 1)
                            } else {
                                errors.add(
                                    SyntaxError(
                                        lineIndex + 1,
                                        i + 1,
                                        "Mismatched bracket: expected '${bracketPairs[openBracket]}', found '${char}'"
                                    )
                                )
                            }
                        }
                    }
                }

                i++
            }

            if (inString && stringChar != '`') {
                inString = false
            }
        }

        stack.forEach { (bracket, position) ->
            errors.add(
                SyntaxError(
                    position.first,
                    position.second,
                    "Unclosed bracket '${bracket}'"
                )
            )
        }

        if (inMultiLineComment) {
            errors.add(
                SyntaxError(
                    lines.size,
                    1,
                    "Unclosed multi-line comment"
                )
            )
        }

        if (inString && stringChar == '`') {
            errors.add(
                SyntaxError(
                    lines.size,
                    1,
                    "Unclosed template string"
                )
            )
        }
    }

    /**
     * 检查引号匹配
     */
    private fun checkQuoteMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        var inString = false
        var stringChar = ' '
        var stringStartLine = -1
        var stringStartCol = -1
        var inMultiLineComment = false

        lines.forEachIndexed { lineIndex, line ->
            var inSingleLineComment = false
            var i = 0

            while (i < line.length) {
                if (!inString && !inSingleLineComment && i < line.length - 1) {
                    if (line[i] == '/' && line[i + 1] == '*') {
                        inMultiLineComment = true
                        i += 2
                        continue
                    }
                    if (inMultiLineComment && line[i] == '*' && line[i + 1] == '/') {
                        inMultiLineComment = false
                        i += 2
                        continue
                    }
                }

                if (!inString && !inMultiLineComment && i < line.length - 1 && line[i] == '/' && line[i + 1] == '/') {
                    inSingleLineComment = true
                    break
                }

                if (!inMultiLineComment && !inSingleLineComment && line[i] in listOf('"', '\'', '`')) {
                    var escapeCount = 0
                    var j = i - 1
                    while (j >= 0 && line[j] == '\\') {
                        escapeCount++
                        j--
                    }
                    val isEscaped = escapeCount % 2 == 1

                    if (!isEscaped) {
                        if (!inString) {
                            inString = true
                            stringChar = line[i]
                            stringStartLine = lineIndex + 1
                            stringStartCol = i + 1
                        } else if (line[i] == stringChar) {
                            inString = false
                        }
                    }
                }

                i++
            }

            if (inString && stringChar != '`') {
                errors.add(
                    SyntaxError(
                        stringStartLine,
                        stringStartCol,
                        "Unclosed string literal (single/double quotes cannot span multiple lines)",
                        SyntaxError.Severity.WARNING
                    )
                )
                inString = false
            }
        }

        if (inString && stringChar == '`') {
            errors.add(
                SyntaxError(
                    stringStartLine,
                    stringStartCol,
                    "Unclosed template string literal",
                    SyntaxError.Severity.WARNING
                )
            )
        }
    }

    /**
     * 检查注释完整性
     */
    private fun checkCommentMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        var inMultiLineComment = false
        var commentStartLine = -1
        var commentStartCol = -1

        lines.forEachIndexed { lineIndex, line ->
            var i = 0
            while (i < line.length - 1) {
                if (!inMultiLineComment && line[i] == '/' && line[i + 1] == '*') {
                    inMultiLineComment = true
                    commentStartLine = lineIndex + 1
                    commentStartCol = i + 1
                    i += 2
                    continue
                }

                if (inMultiLineComment && line[i] == '*' && line[i + 1] == '/') {
                    inMultiLineComment = false
                    i += 2
                    continue
                }

                i++
            }
        }

        if (inMultiLineComment) {
            errors.add(
                SyntaxError(
                    commentStartLine,
                    commentStartCol,
                    "Unclosed multi-line comment"
                )
            )
        }
    }

    /**
     * 检查常见的 JavaScript 错误
     */
    private fun checkCommonJsErrors(lines: List<String>, errors: MutableList<SyntaxError>) {
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()

            if (trimmed.matches(Regex(".*;\\s*;"))) {
                errors.add(
                    SyntaxError(
                        lineIndex + 1,
                        line.indexOf(";;") + 1,
                        "Double semicolon detected",
                        SyntaxError.Severity.WARNING
                    )
                )
            }

            if (trimmed == "return" && lineIndex < lines.size - 1) {
                val nextLine = lines[lineIndex + 1].trim()
                if (nextLine.isNotEmpty() && !nextLine.startsWith("//") && !nextLine.startsWith("/*")) {
                    errors.add(
                        SyntaxError(
                            lineIndex + 1,
                            line.indexOf("return") + 1,
                            "Return statement should have value on same line",
                            SyntaxError.Severity.WARNING
                        )
                    )
                }
            }
        }
    }

    /**
     * 检查 HTML 标签匹配
     */
    private fun checkHtmlTagMatching(lines: List<String>, errors: MutableList<SyntaxError>) {
        val stack = mutableListOf<Pair<String, Pair<Int, Int>>>()
        val selfClosingTags = setOf(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr"
        )

        val content = lines.joinToString("\n")
        val cleanedContent = removeHtmlCommentsAndScriptContent(content)
        val tagPattern = Regex("<(/)([a-zA-Z][a-zA-Z0-9]*)(\\s[^>]*)?>")

        tagPattern.findAll(cleanedContent).forEach { match ->
            val isClosing = match.groupValues[1] == "/"
            val tagName = match.groupValues[2].lowercase()

            val position = match.range.first
            var line = 1
            var col = 1
            var currentPos = 0
            for ((lineIndex, lineContent) in lines.withIndex()) {
                if (currentPos + lineContent.length >= position) {
                    line = lineIndex + 1
                    col = position - currentPos + 1
                    break
                }
                currentPos += lineContent.length + 1
            }

            if (isClosing) {
                if (stack.isEmpty()) {
                    errors.add(SyntaxError(line, col, "Unexpected closing tag </$tagName>"))
                } else {
                    val (openTag, _) = stack.last()
                    if (openTag == tagName) {
                        stack.removeAt(stack.size - 1)
                    } else {
                        errors.add(SyntaxError(line, col, "Mismatched tag: expected </$openTag>, found </$tagName>"))
                    }
                }
            } else {
                if (tagName !in selfClosingTags && !match.value.endsWith("/>")) {
                    stack.add(tagName to (line to col))
                }
            }
        }

        stack.forEach { (tagName, position) ->
            errors.add(SyntaxError(position.first, position.second, "Unclosed tag <$tagName>"))
        }
    }

    /**
     * 移除 HTML 注释和 script/style 标签内的内容，避免误匹配
     * 用空格替代被移除的内容以保持位置索引不变
     */
    private fun removeHtmlCommentsAndScriptContent(content: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < content.length) {
            if (content.startsWith("<!--", i)) {
                val commentEnd = content.indexOf("-->", i + 4)
                if (commentEnd != -1) {
                    result.append(" ".repeat(commentEnd + 3 - i))
                    i = commentEnd + 3
                    continue
                }
            }

            val scriptMatch = Regex("<script(\\s[^>]*)?>", RegexOption.IGNORE_CASE).matchAt(content, i)
            if (scriptMatch != null) {
                result.append(scriptMatch.value)
                i += scriptMatch.value.length
                val scriptContentEnd = findScriptEnd(content, i)
                result.append(" ".repeat(scriptContentEnd - i))
                i = scriptContentEnd
                if (content.startsWith("</script>", i, ignoreCase = true)) {
                    result.append(content.substring(i, i + 9))
                    i += 9
                }
                continue
            }

            val styleMatch = Regex("<style(\\s[^>]*)?>", RegexOption.IGNORE_CASE).matchAt(content, i)
            if (styleMatch != null) {
                result.append(styleMatch.value)
                i += styleMatch.value.length
                val styleEnd = content.indexOf("</style>", i, ignoreCase = true)
                if (styleEnd != -1) {
                    result.append(" ".repeat(styleEnd - i))
                    result.append(content.substring(styleEnd, styleEnd + 8))
                    i = styleEnd + 8
                    continue
                }
            }

            result.append(content[i])
            i++
        }

        return result.toString()
    }

    /**
     * 在 script 标签内查找真正的 </script> 闭合标签位置
     * 需要跳过 JavaScript 字符串和注释中的内容
     */
    private fun findScriptEnd(content: String, startIndex: Int): Int {
        var i = startIndex
        var inString = false
        var stringChar = ' '
        var inSingleLineComment = false
        var inMultiLineComment = false

        while (i < content.length) {
            val char = content[i]

            if (!inString && !inSingleLineComment && i < content.length - 1) {
                if (content[i] == '/' && content[i + 1] == '*') {
                    inMultiLineComment = true
                    i += 2
                    continue
                }
                if (inMultiLineComment && content[i] == '*' && content[i + 1] == '/') {
                    inMultiLineComment = false
                    i += 2
                    continue
                }
            }

            if (!inString && !inMultiLineComment && i < content.length - 1 && content[i] == '/' && content[i + 1] == '/') {
                inSingleLineComment = true
                i += 2
                continue
            }

            if (inSingleLineComment && char == '\n') {
                inSingleLineComment = false
                i++
                continue
            }

            if (!inMultiLineComment && !inSingleLineComment && (char == '"' || char == '\'' || char == '`')) {
                var escapeCount = 0
                var j = i - 1
                while (j >= 0 && content[j] == '\\') {
                    escapeCount++
                    j--
                }
                val isEscaped = escapeCount % 2 == 1

                if (!isEscaped) {
                    if (!inString) {
                        inString = true
                        stringChar = char
                    } else if (char == stringChar) {
                        inString = false
                    }
                }
            }

            if (!inString && !inSingleLineComment && !inMultiLineComment &&
                content.startsWith("</script>", i, ignoreCase = true)) {
                return i
            }

            i++
        }

        return content.length
    }

    /**
     * 检查 HTML 注释
     */
    private fun checkHtmlComments(lines: List<String>, errors: MutableList<SyntaxError>) {
        val content = lines.joinToString("\n")
        var inComment = false
        var commentStartLine = -1
        var commentStartCol = -1

        var i = 0
        for ((lineIndex, line) in lines.withIndex()) {
            var col = 0
            while (col < line.length) {
                if (!inComment && line.substring(col).startsWith("<!--")) {
                    inComment = true
                    commentStartLine = lineIndex + 1
                    commentStartCol = col + 1
                    col += 4
                    continue
                }

                if (inComment && line.substring(col).startsWith("-->")) {
                    inComment = false
                    col += 3
                    continue
                }

                col++
            }
        }

        if (inComment) {
            errors.add(SyntaxError(commentStartLine, commentStartCol, "Unclosed HTML comment"))
        }
    }

    /**
     * 检查 HTML 属性引号
     */
    private fun checkHtmlAttributeQuotes(lines: List<String>, errors: MutableList<SyntaxError>) {
        val tagContentPattern = Regex("""<[^>]*>""")

        lines.forEachIndexed { lineIndex, line ->
            tagContentPattern.findAll(line).forEach { tagMatch ->
                val tagContent = tagMatch.value
                var i = 0

                while (i < tagContent.length) {
                    while (i < tagContent.length && tagContent[i].isWhitespace()) i++
                    if (i >= tagContent.length) break

                    val attrNameStart = i
                    while (i < tagContent.length && (tagContent[i].isLetterOrDigit() || tagContent[i] in setOf('-', '_', ':'))) i++
                    if (i >= tagContent.length || tagContent[i] != '=') {
                        i++
                        continue
                    }

                    val attrName = tagContent.substring(attrNameStart, i)
                    i++

                    if (i < tagContent.length) {
                        val quoteChar = tagContent[i]
                        when (quoteChar) {
                            '"', '\'' -> {
                                i++
                                while (i < tagContent.length && tagContent[i] != quoteChar) {
                                    if (tagContent[i] == '\\' && i + 1 < tagContent.length) {
                                        i += 2
                                    } else {
                                        i++
                                    }
                                }
                                if (i < tagContent.length) i++
                            }
                            else -> {
                                val valueStart = i
                                while (i < tagContent.length && !tagContent[i].isWhitespace() && tagContent[i] != '>') i++
                                val attrValue = tagContent.substring(valueStart, i)
                                errors.add(
                                    SyntaxError(
                                        lineIndex + 1,
                                        tagMatch.range.first + valueStart + 1,
                                        "Attribute '$attrName' value should be quoted",
                                        SyntaxError.Severity.WARNING
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
