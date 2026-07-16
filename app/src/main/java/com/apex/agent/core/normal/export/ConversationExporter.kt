package com.apex.agent.core.normal.export

import java.io.OutputStream

/**
 * F24: 对话导出多格式
 *
 * 支持多种格式的对话导出：
 * - Markdown：人类可读，含格式
 * - JSON：结构化，可导入
 * - HTML：带样式，可打印
 * - TXT：纯文本
 * - PDF：正式文档（占位）
 * - CSV：表格形式
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 导出含 Agent 间通信
 * - 狂暴导出含策略执行细节
 * - 本功能是**用户对话导出**，面向用户使用与归档
 */

/**
 * 导出格式
 */

/**
 * 导出选项
 */
data class ExportOptions(
    val format: ExportFormat,
    val includeMetadata: Boolean = true,
    val includeThinking: Boolean = false,
    val includeToolCalls: Boolean = false,
    val includeTimestamps: Boolean = true,
    val includeSummary: Boolean = false,
    val includeHealthReport: Boolean = false,
    val dateFormat: String = "yyyy-MM-dd HH:mm:ss",
    val timeZone: String = "Asia/Shanghai",
    val filter: ExportFilter = ExportFilter()
)

data class ExportFilter(
    val startDate: Long? = null,
    val endDate: Long? = null,
    val roles: Set<String> = emptySet(),  // 空=全部
    val searchQuery: String? = null,
    val tags: Set<String> = emptySet()
)

/**
 * 导出消息
 */
data class ExportMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val thinkingContent: String? = null,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

data class ToolCallRecord(
    val toolName: String,
    val arguments: Map<String, Any?>,
    val result: String?,
    val success: Boolean,
    val durationMs: Long
)

/**
 * 导出结果
 */

/**
 * 对话导出器
 */
class ConversationExporter {

    /**
     * 导出对话
     */
    fun export(
        messages: List<ExportMessage>,
        chatTitle: String = "对话记录",
        options: ExportOptions
    ): ExportResult {
        val filtered = applyFilter(messages, options.filter)

        return try {
            val content = when (options.format) {
                ExportFormat.MARKDOWN -> exportToMarkdown(filtered, chatTitle, options)
                ExportFormat.JSON -> exportToJson(filtered, chatTitle, options)
                ExportFormat.HTML -> exportToHtml(filtered, chatTitle, options)
                ExportFormat.PLAIN_TEXT -> exportToPlainText(filtered, chatTitle, options)
                ExportFormat.CSV -> exportToCsv(filtered, options)
                ExportFormat.PDF -> exportToPdfPlaceholder(filtered, chatTitle, options)
            }

            ExportResult(
                success = true,
                format = options.format,
                content = content,
                messageCount = filtered.size,
                fileSizeBytes = content.toByteArray().size.toLong()
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                format = options.format,
                content = "",
                messageCount = 0,
                fileSizeBytes = 0,
                errors = listOf(e.message ?: "导出失败")
            )
        }
    }

    /**
     * 导出到输出流
     */
    fun exportToStream(
        messages: List<ExportMessage>,
        chatTitle: String,
        options: ExportOptions,
        stream: OutputStream
    ): ExportResult {
        val result = export(messages, chatTitle, options)
        if (result.success) {
            stream.write(result.content.toByteArray())
            stream.flush()
        }
        return result
    }

    // ============ 格式实现 ============

    private fun exportToMarkdown(
        messages: List<ExportMessage>,
        title: String,
        options: ExportOptions
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# $title")
        sb.appendLine()

        if (options.includeMetadata) {
            sb.appendLine("> **导出时间**: ${formatDate(System.currentTimeMillis(), options)}")
            sb.appendLine("> **消息数**: ${messages.size}")
            sb.appendLine("> **格式**: Markdown")
            sb.appendLine()
        }

        if (options.includeSummary) {
            sb.appendLine("## 摘要")
            sb.appendLine(generateSummary(messages))
            sb.appendLine()
        }

        sb.appendLine("---")
        sb.appendLine()

        for (msg in messages) {
            val roleLabel = when (msg.role.lowercase()) {
                "user" -> "👤 **用户**"
                "assistant" -> "🤖 **助手**"
                "system" -> "⚙️ **系统**"
                else -> "**${msg.role}**"
            }

            if (options.includeTimestamps) {
                sb.appendLine("### $roleLabel · ${formatDate(msg.timestamp, options)}")
            } else {
                sb.appendLine("### $roleLabel")
            }
            sb.appendLine()
            sb.appendLine(msg.content)
            sb.appendLine()

            if (options.includeThinking && msg.thinkingContent != null) {
                sb.appendLine("<details>")
                sb.appendLine("<summary>💭 思考过程</summary>")
                sb.appendLine()
                sb.appendLine(msg.thinkingContent)
                sb.appendLine()
                sb.appendLine("</details>")
                sb.appendLine()
            }

            if (options.includeToolCalls && msg.toolCalls.isNotEmpty()) {
                sb.appendLine("<details>")
                sb.appendLine("<summary>🔧 工具调用 (${msg.toolCalls.size})</summary>")
                sb.appendLine()
                for (tc in msg.toolCalls) {
                    val status = if (tc.success) "✓" else "✗"
                    sb.appendLine("- $status **${tc.toolName}** (${tc.durationMs}ms)")
                    sb.appendLine("  - 参数: `${tc.arguments}`")
                    if (tc.result != null) {
                        sb.appendLine("  - 结果: `${tc.result.take(200)}`")
                    }
                }
                sb.appendLine()
                sb.appendLine("</details>")
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private fun exportToJson(
        messages: List<ExportMessage>,
        title: String,
        options: ExportOptions
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"title\": ${escapeJson(title)},")
        sb.appendLine("  \"exportedAt\": ${System.currentTimeMillis()},")
        sb.appendLine("  \"messageCount\": ${messages.size},")
        if (options.includeMetadata) {
            sb.appendLine("  \"metadata\": {")
            sb.appendLine("    \"format\": \"json\",")
            sb.appendLine("    \"includeThinking\": ${options.includeThinking},")
            sb.appendLine("    \"includeToolCalls\": ${options.includeToolCalls}")
            sb.appendLine("  },")
        }
        sb.appendLine("  \"messages\": [")
        messages.forEachIndexed { i, msg ->
            sb.appendLine("    {")
            sb.appendLine("      \"id\": ${escapeJson(msg.id)},")
            sb.appendLine("      \"role\": ${escapeJson(msg.role)},")
            if (options.includeTimestamps) {
                sb.appendLine("      \"timestamp\": ${msg.timestamp},")
                sb.appendLine("      \"formattedTime\": ${escapeJson(formatDate(msg.timestamp, options))},")
            }
            sb.appendLine("      \"content\": ${escapeJson(msg.content)}")
            if (options.includeThinking && msg.thinkingContent != null) {
                sb.appendLine("      ,\"thinking\": ${escapeJson(msg.thinkingContent)}")
            }
            if (options.includeToolCalls && msg.toolCalls.isNotEmpty()) {
                sb.appendLine("      ,\"toolCalls\": [")
                msg.toolCalls.forEachIndexed { j, tc ->
                    sb.appendLine("        {")
                    sb.appendLine("          \"tool\": ${escapeJson(tc.toolName)},")
                    sb.appendLine("          \"success\": ${tc.success},")
                    sb.appendLine("          \"durationMs\": ${tc.durationMs}")
                    sb.appendLine("        }${if (j < msg.toolCalls.size - 1) "," else ""}")
                }
                sb.appendLine("      ]")
            }
            sb.appendLine("    }${if (i < messages.size - 1) "," else ""}")
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        return sb.toString()
    }

    private fun exportToHtml(
        messages: List<ExportMessage>,
        title: String,
        options: ExportOptions
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<!DOCTYPE html>")
        sb.appendLine("<html lang=\"zh-CN\">")
        sb.appendLine("<head>")
        sb.appendLine("<meta charset=\"UTF-8\">")
        sb.appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        sb.appendLine("<title>${escapeHtml(title)}</title>")
        sb.appendLine("<style>")
        sb.appendHTMLStyles(sb)
        sb.appendLine("</style>")
        sb.appendLine("</head>")
        sb.appendLine("<body>")
        sb.appendLine("<div class=\"container\">")
        sb.appendLine("<h1>${escapeHtml(title)}</h1>")

        if (options.includeMetadata) {
            sb.appendLine("<div class=\"metadata\">")
            sb.appendLine("<p>导出时间: ${escapeHtml(formatDate(System.currentTimeMillis(), options))}</p>")
            sb.appendLine("<p>消息数: ${messages.size}</p>")
            sb.appendLine("</div>")
        }

        sb.appendLine("<div class=\"messages\">")
        for (msg in messages) {
            val roleClass = msg.role.lowercase()
            sb.appendLine("<div class=\"message $roleClass\">")
            sb.appendLine("<div class=\"header\">")
            sb.appendLine("<span class=\"role\">${escapeHtml(roleLabel(msg.role))}</span>")
            if (options.includeTimestamps) {
                sb.appendLine("<span class=\"time\">${escapeHtml(formatDate(msg.timestamp, options))}</span>")
            }
            sb.appendLine("</div>")
            sb.appendLine("<div class=\"content\">${markdownToHtml(msg.content)}</div>")

            if (options.includeThinking && msg.thinkingContent != null) {
                sb.appendLine("<details class=\"thinking\">")
                sb.appendLine("<summary>💭 思考过程</summary>")
                sb.appendLine("<div class=\"content\">${escapeHtml(msg.thinkingContent)}</div>")
                sb.appendLine("</details>")
            }

            if (options.includeToolCalls && msg.toolCalls.isNotEmpty()) {
                sb.appendLine("<details class=\"tool-calls\">")
                sb.appendLine("<summary>🔧 工具调用 (${msg.toolCalls.size})</summary>")
                sb.appendLine("<ul>")
                for (tc in msg.toolCalls) {
                    val status = if (tc.success) "✓" else "✗"
                    sb.appendLine("<li>$status <strong>${escapeHtml(tc.toolName)}</strong> (${tc.durationMs}ms)</li>")
                }
                sb.appendLine("</ul>")
                sb.appendLine("</details>")
            }

            sb.appendLine("</div>")
        }
        sb.appendLine("</div>")
        sb.appendLine("</div>")
        sb.appendLine("</body>")
        sb.appendLine("</html>")
        return sb.toString()
    }

    private fun exportToPlainText(
        messages: List<ExportMessage>,
        title: String,
        options: ExportOptions
    ): String {
        val sb = StringBuilder()
        sb.appendLine("=== $title ===")
        sb.appendLine()
        for (msg in messages) {
            if (options.includeTimestamps) {
                sb.appendLine("[${formatDate(msg.timestamp, options)}] ${roleLabel(msg.role)}:")
            } else {
                sb.appendLine("${roleLabel(msg.role)}:")
            }
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun exportToCsv(
        messages: List<ExportMessage>,
        options: ExportOptions
    ): String {
        val sb = StringBuilder()
        sb.appendLine("id,role,timestamp,content")
        for (msg in messages) {
            val time = if (options.includeTimestamps) formatDate(msg.timestamp, options) else ""
            sb.appendLine("${escapeCsv(msg.id)},${escapeCsv(msg.role)},${escapeCsv(time)},${escapeCsv(msg.content)}")
        }
        return sb.toString()
    }

    private fun exportToPdfPlaceholder(
        messages: List<ExportMessage>,
        title: String,
        options: ExportOptions
    ): String {
        // PDF 生成需要专门库（如 iText/PDFBox），这里返回 HTML 作为占位
        return exportToHtml(messages, title, options).also {
            // 添加 PDF 提示
        }
    }

    // ============ 辅助方法 ============

    private fun applyFilter(messages: List<ExportMessage>, filter: ExportFilter): List<ExportMessage> {
        return messages.filter { msg ->
            (filter.startDate == null || msg.timestamp >= filter.startDate) &&
            (filter.endDate == null || msg.timestamp <= filter.endDate) &&
            (filter.roles.isEmpty() || msg.role in filter.roles) &&
            (filter.searchQuery == null || msg.content.contains(filter.searchQuery, ignoreCase = true))
        }
    }

    private fun formatDate(timestamp: Long, options: ExportOptions): String {
        val sdf = java.text.SimpleDateFormat(options.dateFormat)
        sdf.timeZone = java.util.TimeZone.getTimeZone(options.timeZone)
        return sdf.format(java.util.Date(timestamp))
    }

    private fun roleLabel(role: String): String = when (role.lowercase()) {
        "user" -> "用户"
        "assistant" -> "助手"
        "system" -> "系统"
        else -> role
    }

    private fun generateSummary(messages: List<ExportMessage>): String {
        val userMessages = messages.filter { it.role.equals("user", true) }
        if (userMessages.isEmpty()) return "空对话"
        val firstMsg = userMessages.first().content.take(100)
        return "本次对话共 ${messages.size} 条消息，用户提问 ${userMessages.size} 次，主要话题：$firstMsg"
    }

    private fun escapeJson(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun escapeHtml(s: String): String = s.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun escapeCsv(s: String): String {
        return if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    private fun markdownToHtml(md: String): String {
        var html = escapeHtml(md)
        // 代码块
        html = Regex("```(\\w*)\\n([\\s\\S]*?)```").replace(html) { m ->
            "<pre><code class=\"language-${m.groupValues[1]}\">${m.groupValues[2]}</code></pre>"
        }
        // 标题
        html = Regex("^#{6}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h6>$1</h6>")
        html = Regex("^#{5}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h5>$1</h5>")
        html = Regex("^#{4}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h4>$1</h4>")
        html = Regex("^#{3}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h3>$1</h3>")
        html = Regex("^#{2}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h2>$1</h2>")
        html = Regex("^#{1}\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<h1>$1</h1>")
        // 加粗
        html = Regex("\\*\\*(.+?)\\*\\*").replace(html, "<strong>$1</strong>")
        // 斜体
        html = Regex("\\*(.+?)\\*").replace(html, "<em>$1</em>")
        // 行内代码
        html = Regex("`(.+?)`").replace(html, "<code>$1</code>")
        // 列表
        html = Regex("^[-*+]\\s+(.+)$", RegexOption.MULTILINE).replace(html, "<li>$1</li>")
        // 段落
        html = Regex("\n\n").replace(html, "</p><p>")
        return "<p>$html</p>"
    }

    private fun StringBuilder.appendHTMLStyles(sb: StringBuilder) {
        sb.append("""
            body { font-family: -apple-system, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; color: #333; }
            .container { background: #fff; }
            h1 { color: #2c3e50; border-bottom: 2px solid #eee; padding-bottom: 10px; }
            .metadata { background: #f8f9fa; padding: 10px; border-radius: 5px; margin: 10px 0; font-size: 14px; color: #666; }
            .messages { margin-top: 20px; }
            .message { margin-bottom: 20px; padding: 15px; border-radius: 10px; }
            .message.user { background: #e3f2fd; }
            .message.assistant { background: #f1f8e9; }
            .message.system { background: #fff3e0; }
            .header { display: flex; justify-content: space-between; margin-bottom: 8px; font-size: 13px; color: #666; }
            .role { font-weight: bold; }
            .content { line-height: 1.6; }
            .content pre { background: #f4f4f4; padding: 10px; border-radius: 5px; overflow-x: auto; }
            .content code { background: #f4f4f4; padding: 2px 4px; border-radius: 3px; font-family: monospace; }
            details { margin-top: 10px; }
            summary { cursor: pointer; color: #666; }
        """.trimIndent())
    }
}
