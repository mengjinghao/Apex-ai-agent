package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SkillDevAssistant private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillDevAssistant"

        private val KEYWORDS = setOf(
            "function", "const", "let", "var", "if", "else", "for", "while", "return",
            "class", "interface", "type", "import", "export", "from", "async", "await",
            "try", "catch", "finally", "throw", "new", "this", "true", "false", "null",
            "undefined", "typeof", "instanceof", "in", "of", "switch", "case", "break",
            "continue", "default", "do", "delete", "void", "yield", "static", "get", "set"
        )

        private val SNIPPETS = mapOf(
            "func" to "function ${1:name}(${2:params}) {\n\t�?{0}\n}",
            "arrow" to "const ${1:name} = (${2:params}) => {\n\t�?{0}\n}",
            "log" to "console.log(${1:message})",
            "async" to "async function ${1:name}(${2:params}) {\n\t�?{0}\n}",
            "try" to "try {\n\t�?{0}\n} catch (error) {\n\tconsole.error(error)\n}",
            "if" to "if (${1:condition}) {\n\t�?{0}\n}",
            "for" to "for (let ${1:i} = 0; ${1:i} < ${2:length}; ${1:i}++) {\n\t�?{0}\n}",
            "class" to "class ${1:ClassName} {\n\tconstructor(${2:params}) {\n\t\t�?{0}\n\t}\n}"
        )

        @Volatile private var INSTANCE: SkillDevAssistant? = null

        fun getInstance(context: Context): SkillDevAssistant {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillDevAssistant(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class EditorDocument(
        val id: String = "doc_${System.currentTimeMillis()}",
        val filePath: String,
        var content: String,
        var isDirty: Boolean = false,
        var cursorPosition: Int = 0,
        var selectionStart: Int = 0,
        var selectionEnd: Int = 0,
        val lastModified: Long = System.currentTimeMillis()
    )

    data class CompletionItem(
        val label: String,
        val kind: CompletionKind,
        val detail: String = "",
        val insertText: String = label,
        val documentation: String = ""
    )

    enum class CompletionKind {
        KEYWORD,
        FUNCTION,
        VARIABLE,
        SNIPPET,
        FILE,
        PROPERTY
    }

    data class Diagnostic(
        val line: Int,
        val column: Int,
        val message: String,
        val severity: DiagnosticSeverity,
        val source: String = "SkillDevAssistant"
    )

    enum class DiagnosticSeverity {
        ERROR,
        WARNING,
        INFO,
        HINT
    }

    data class SyntaxToken(
        val startIndex: Int,
        val endIndex: Int,
        val type: TokenType,
        val value: String
    )

    enum class TokenType {
        KEYWORD,
        IDENTIFIER,
        STRING,
        NUMBER,
        COMMENT,
        OPERATOR,
        PUNCTUATION,
        FUNCTION,
        VARIABLE,
        PROPERTY,
        TYPE,
        REGEX
    }

    interface AssistantListener {
        fun onCompletionsRequested(document: EditorDocument, position: Int, completions: List<CompletionItem>)
        fun onDiagnosticsGenerated(document: EditorDocument, diagnostics: List<Diagnostic>)
        fun onSyntaxTokensGenerated(document: EditorDocument, tokens: List<SyntaxToken>)
        fun onPreviewUpdated(preview: PreviewResult)
        fun onError(error: String)
    }

    private val config = DevServerConfig.getInstance(context)
    private val skillManager = SkillManager.getInstance(context)
    private val devServer = SkillDevServer.getInstance(context)
    private val hotReloader = HotReloader.getInstance(context)

    private val documents = ConcurrentHashMap<String, EditorDocument>()
    private val listeners = CopyOnWriteArrayList<AssistantListener>()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var previewJob: Job? = null

    private val languageServers = ConcurrentHashMap<String, LanguageServer>()

    init {
        initializeLanguageServers()
    }

    private fun initializeLanguageServers() {
        languageServers["javascript"] = JSLanguageServer()
        languageServers["typescript"] = TypeScriptLanguageServer()
        languageServers["markdown"] = MarkdownLanguageServer()
    }

    fun addAssistantListener(listener: AssistantListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeAssistantListener(listener: AssistantListener) {
        listeners.remove(listener)
    }

    fun openDocument(filePath: String): EditorDocument? {
        val file = File(filePath)
        if (!file.exists()) {
            AppLogger.w(TAG, "File does not exist: ${filePath}")
            return null
        }

        val content = try {
            file.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error reading file: ${filePath}", e)
            return null
        }

        val document = EditorDocument(
            filePath = filePath,
            content = content,
            isDirty = false
        )

        documents[document.id] = document
        analyzeDocument(document)

        return document
    }

    fun getDocument(documentId: String): EditorDocument? {
        return documents[documentId]
    }

    fun updateDocument(documentId: String, content: String) {
        documents[documentId]?.let { doc ->
            doc.content = content
            doc.isDirty = true
            analyzeDocument(doc)
        }
    }

    fun saveDocument(documentId: String): Boolean {
        val document = documents[documentId] ?: return false

        return try {
            val file = File(document.filePath)
            file.parentFile?.mkdirs()
            file.writeText(document.content)
            document.isDirty = false
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error saving document: ${documentId}", e)
            false
        }
    }

    fun closeDocument(documentId: String) {
        documents.remove(documentId)
    }

    fun getCompletions(document: EditorDocument, position: Int): List<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()

        val line = document.content.substring(0, position.coerceAtMost(document.content.length))
        val lastWord = line.substringAfterLast(" ").substringAfterLast(".")

        if (lastWord.isNotEmpty()) {
            KEYWORDS.filter { it.startsWith(lastWord) }.forEach { keyword ->
                completions.add(
                    CompletionItem(
                        label = keyword,
                        kind = CompletionKind.KEYWORD,
                        detail = "Keyword"
                    )
                )
            }

            SNIPPETS.filter { it.key.startsWith(lastWord) || it.key.contains(lastWord) }.forEach { (key, template) ->
                completions.add(
                    CompletionItem(
                        label = key,
                        kind = CompletionKind.SNIPPET,
                        detail = "Snippet",
                        insertText = template
                    )
                )
            )

            extractIdentifiers(document.content, lastWord).forEach { identifier ->
                completions.add(
                    CompletionItem(
                        label = identifier,
                        kind = CompletionKind.VARIABLE,
                        detail = "Variable"
                    )
                )
            }
        }

        notifyCompletionsRequested(document, position, completions)
        return completions
    }

    private fun extractIdentifiers(content: String, prefix: String): Set<String> {
        val identifiers = mutableSetOf<String>()
        val regex = Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b")
        regex.findAll(content).forEach { match ->
            if (match.value.startsWith(prefix) && match.value != prefix) {
                identifiers.add(match.value)
            }
        }
        return identifiers
    }

    fun analyzeDocument(document: EditorDocument) {
        scope.launch {
            try {
                val tokens = tokenize(document)
                notifySyntaxTokensGenerated(document, tokens)

                val diagnostics = analyzeErrors(document)
                notifyDiagnosticsGenerated(document, diagnostics)

                updatePreview(document)

            } catch (e: Exception) {
                AppLogger.e(TAG, "Error analyzing document", e)
                notifyError("Analysis error: ${e.message}")
            }
        }
    }

    private fun tokenize(document: EditorDocument): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val content = document.content
        val extension = document.filePath.substringAfterLast(".", "md")

        when (extension) {
            "js", "ts" -> tokenizeJSTokens(content, tokens)
            "md" -> tokenizeMarkdownTokens(content, tokens)
        }

        return tokens
    }

    private fun tokenizeJSTokens(content: String, tokens: MutableList<SyntaxToken>) {
        val patterns = listOf(
            TokenPattern(Regex("\"[^\"]*\""), TokenType.STRING),
            TokenPattern(Regex("'[^']*'"), TokenType.STRING),
            TokenPattern(Regex("`[^`]*`"), TokenType.STRING),
            TokenPattern(Regex("//.*"), TokenType.COMMENT),
            TokenPattern(Regex("/\\*[\\s\\S]*?\\*/"), TokenType.COMMENT),
            TokenPattern(Regex("\\b\\d+\\.?\\d*\\b"), TokenType.NUMBER),
            TokenPattern(Regex("\\b(function|const|let|var|class|return|if|else|for|while)\\b"), TokenType.KEYWORD),
            TokenPattern(Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*(?=\\s*\\()"), TokenType.FUNCTION),
            TokenPattern(Regex("[+\\-*/%=<>!&|^~?:,;.[\\]{}()]"), TokenType.OPERATOR),
            TokenPattern(Regex("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b"), TokenType.IDENTIFIER)
        )

        var processed = 0

        for (pattern in patterns) {
            pattern.regex.findAll(content).forEach { match ->
                if (match.range.first >= processed) {
                    val type = if (KEYWORDS.contains(match.value)) TokenType.KEYWORD else pattern.type
                    tokens.add(SyntaxToken(match.range.first, match.range.last + 1, type, match.value))
                    processed = match.range.last + 1
                }
            }
        }
    }

    private fun tokenizeMarkdownTokens(content: String, tokens: MutableList<SyntaxToken>) {
        val lines = content.split("\n")
        var index = 0

        lines.forEachIndexed { lineNum, line ->
            val lineStart = index

            when {
                line.startsWith("#") -> {
                    tokens.add(SyntaxToken(lineStart, lineStart + line.length, TokenType.KEYWORD, line))
                }
                line.startsWith("```") -> {
                    tokens.add(SyntaxToken(lineStart, lineStart + line.length, TokenType.COMMENT, line))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    tokens.add(SyntaxToken(lineStart, lineStart + 2, TokenType.OPERATOR, line.substring(0, 2)))
                }
            }

            index += line.length + 1
        }
    }

    private fun analyzeErrors(document: EditorDocument): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val content = document.content
        val lines = content.split("\n")
        val extension = document.filePath.substringAfterLast(".", "md")

        when (extension) {
            "js", "ts" -> {
                lines.forEachIndexed { index, line ->
                    analyzeJSLine(line, index, diagnostics)
                }
            }
            "md" -> {
                lines.forEachIndexed { index, line ->
                    analyzeMarkdownLine(line, index, diagnostics)
                }
            }
        }

        return diagnostics
    }

    private fun analyzeJSLine(line: String, lineNumber: Int, diagnostics: MutableList<Diagnostic>) {
        if (line.contains("console.log") && line.contains("debug")) {
            diagnostics.add(
                Diagnostic(
                    line = lineNumber,
                    column = 0,
                    message = "Debug statement found",
                    severity = DiagnosticSeverity.WARNING,
                    source = "SkillDevAssistant"
                )
            )
        }

        val openBraces = line.count { it == '{' }
        val closeBraces = line.count { it == '}' }
        if (openBraces > 0 && closeBraces > 0 && openBraces != closeBraces) {
            diagnostics.add(
                Diagnostic(
                    line = lineNumber,
                    column = line.indexOf('{').coerceAtLeast(0),
                    message = "Mismatched braces",
                    severity = DiagnosticSeverity.ERROR
                )
            )
        }

        if (line.contains("TODO") || line.contains("FIXME")) {
            diagnostics.add(
                Diagnostic(
                    line = lineNumber,
                    column = line.indexOf("TODO").coerceAtLeast(line.indexOf("FIXME")),
                    message = "Unresolved task: ${line.trim()}",
                    severity = DiagnosticSeverity.INFO
                )
            )
        }
    }

    private fun analyzeMarkdownLine(line: String, lineNumber: Int, diagnostics: MutableList<Diagnostic>) {
        if (line.contains("[[") && !line.contains("]]")) {
            diagnostics.add(
                Diagnostic(
                    line = lineNumber,
                    column = line.indexOf("[["),
                    message = "Unclosed wiki link",
                    severity = DiagnosticSeverity.ERROR
                )
            )
        }

        if (line.startsWith(" ") && !line.startsWith("  ")) {
            diagnostics.add(
                Diagnostic(
                    line = lineNumber,
                    column = 0,
                    message = "Incorrect indentation",
                    severity = DiagnosticSeverity.WARNING
                )
            )
        }
    }

    private fun updatePreview(document: EditorDocument) {
        previewJob?.cancel()

        previewJob = scope.launch {
            delay(config.getPreviewSettings().refreshDelayMs)

            val preview = generatePreview(document)
            notifyPreviewUpdated(preview)
        }
    }

    private fun generatePreview(document: EditorDocument): PreviewResult {
        return when (document.filePath.substringAfterLast(".", "md")) {
            "js", "ts" -> PreviewResult(
                type = PreviewType.JAVASCRIPT,
                html = generateJSPreview(document.content)
            )
            "md" -> PreviewResult(
                type = PreviewType.MARKDOWN,
                html = generateMarkdownPreview(document.content)
            )
            else -> PreviewResult(
                type = PreviewType.TEXT,
                html = "<pre>${document.content}</pre>"
            )
        }
    }

    private fun generateJSPreview(code: String): String {
        return buildString {
            append("<!DOCTYPE html><html><head><style>")
            append("body{font-family:monospace;padding:20px;background:#1e1e1e;color:#d4d4d4;}")
            append(".keyword{color:#569cd6;}.string{color:#ce9178;}.number{color:#b5cea8;}")
            append(".comment{color:#6a9955;}.function{color:#dcdcaa;}")
            append("</style></head><body><script>")
            append(code)
            append("</script></body></html>")
        }
    }

    private fun generateMarkdownPreview(content: String): String {
        return buildString {
            append("<!DOCTYPE html><html><head><style>")
            append("body{font-family:system-ui;padding:20px;max-width:800px;margin:0 auto;}")
            append("h1{color:#333;}h2{color:#555;}code{background:#f4f4f4;padding:2px 6px;border-radius:3px;}")
            append("pre{background:#f4f4f4;padding:16px;border-radius:8px;overflow-x:auto;}")
            append("</style></head><body>")
            append(content.lines().joinToString("<br>"))
            append("</body></html>")
        }
    }

    fun getSkillStructure(skillName: String): SkillStructure? {
        val skillDir = File(config.getSkillsRootDirectory(), skillName)
        if (!skillDir.exists()) return null

        val structure = mutableListOf<FileNode>()
        skillDir.walkTopDown().forEach { file ->
            if (file.parentFile?.absolutePath != skillDir.absolutePath || file == skillDir) continue

            val relativePath = file.relativeTo(skillDir).path
            structure.add(FileNode(
                name = file.name,
                path = relativePath,
                isDirectory = file.isDirectory,
                children = if (file.isDirectory) {
                    file.listFiles()?.map { child ->
                        FileNode(
                            name = child.name,
                            path = "${relativePath}/${child.name}",
                            isDirectory = child.isDirectory
                        )
                    } ?: emptyList()
                } else null
            ))
        }

        return SkillStructure(skillName, skillDir.absolutePath, structure)
    }

    data class SkillStructure(
        val skillName: String,
        val rootPath: String,
        val files: List<FileNode>
    )

    data class FileNode(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val children: List<FileNode>? = null
    )

    data class PreviewResult(
        val type: PreviewType,
        val html: String
    )

    enum class PreviewType {
        JAVASCRIPT,
        MARKDOWN,
        TEXT
    }

    private data class TokenPattern(val regex: Regex, val type: TokenType)

    abstract class LanguageServer {
        abstract fun getCompletions(document: EditorDocument, position: Int): List<CompletionItem>
        abstract fun analyzeErrors(document: EditorDocument): List<Diagnostic>
    }

    inner class JSLanguageServer : LanguageServer() {
        override fun getCompletions(document: EditorDocument, position: Int): List<CompletionItem> {
            return getCompletions(document, position)
        }

        override fun analyzeErrors(document: EditorDocument): List<Diagnostic> {
            return analyzeErrors(document)
        }
    }

    inner class TypeScriptLanguageServer : LanguageServer() {
        override fun getCompletions(document: EditorDocument, position: Int): List<CompletionItem> {
            return getCompletions(document, position)
        }

        override fun analyzeErrors(document: EditorDocument): List<Diagnostic> {
            return analyzeErrors(document)
        }
    }

    inner class MarkdownLanguageServer : LanguageServer() {
        override fun getCompletions(document: EditorDocument, position: Int): List<CompletionItem> {
            return emptyList()
        }

        override fun analyzeErrors(document: EditorDocument): List<Diagnostic> {
            return analyzeErrors(document)
        }
    }

    private fun notifyCompletionsRequested(document: EditorDocument, position: Int, completions: List<CompletionItem>) {
        listeners.forEach { listener ->
            runCatching {
                listener.onCompletionsRequested(document, position, completions)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying completions", e)
            }
        }
    }

    private fun notifyDiagnosticsGenerated(document: EditorDocument, diagnostics: List<Diagnostic>) {
        listeners.forEach { listener ->
            runCatching {
                listener.onDiagnosticsGenerated(document, diagnostics)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying diagnostics", e)
            }
        }
    }

    private fun notifySyntaxTokensGenerated(document: EditorDocument, tokens: List<SyntaxToken>) {
        listeners.forEach { listener ->
            runCatching {
                listener.onSyntaxTokensGenerated(document, tokens)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying syntax tokens", e)
            }
        }
    }

    private fun notifyPreviewUpdated(preview: PreviewResult) {
        listeners.forEach { listener ->
            runCatching {
                listener.onPreviewUpdated(preview)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying preview", e)
            }
        }
    }

    private fun notifyError(error: String) {
        listeners.forEach { listener ->
            runCatching {
                listener.onError(error)
            }.onFailure { e ->
                AppLogger.e(TAG, "Error notifying error", e)
            }
        }
    }
}