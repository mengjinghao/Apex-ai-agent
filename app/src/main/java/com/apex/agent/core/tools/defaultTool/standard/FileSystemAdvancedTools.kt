package com.apex.agent.core.tools.defaultTool.standard

import android.content.Context
import com.apex.agent.util.AppLogger
import com.apex.agent.api.chat.EnhancedAIService
import com.apex.agent.api.chat.llmprovider.AIService
import com.apex.agent.core.chat.hooks.PromptTurn
import com.apex.agent.core.chat.hooks.PromptTurnKind
import com.apex.agent.core.tools.FindFilesResultData
import com.apex.agent.core.tools.GrepResultData
import com.apex.agent.core.tools.StringResultData
import com.apex.agent.core.tools.ToolProgressBus
import com.apex.agent.core.tools.ToolExecutionLimits
import com.apex.data.model.AITool
import com.apex.data.model.FunctionType
import com.apex.data.model.ModelParameter
import com.apex.data.model.ToolParameter
import com.apex.data.model.ToolResult
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.apex.agent.util.HttpMultiPartDownloader
import com.apex.agent.util.FFmpegUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.content.FileProvider
import android.webkit.MimeTypeMap
import com.apex.agent.api.chat.enhance.FileBindingService
import com.apex.agent.core.config.FunctionalPrompts
import com.apex.agent.data.preferences.ApiPreferences
import com.apex.agent.data.preferences.FunctionalConfigManager
import com.apex.agent.data.preferences.ModelConfigManager
import com.apex.agent.terminal.data.PackageManagerType
import com.apex.agent.terminal.TerminalManager
import com.apex.agent.terminal.provider.filesystem.FileSystemProvider
import com.apex.agent.terminal.provider.type.HiddenExecResult
import com.apex.agent.terminal.utils.SSHFileConnectionManager
import com.apex.agent.terminal.utils.SourceManager
import com.apex.agent.core.tools.defaultTool.PathValidator
import com.apex.agent.core.tools.system.Terminal
import com.apex.agent.util.LocaleUtils
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

open class FileSystemAdvancedTools(protected val context: Context) {
    companion object {
        protected const val TAG = "FileSystemAdvancedTools"
        private const val RIPGREP_EXECUTOR_POOL_SIZE = 4
        private const val RIPGREP_COMMAND_TIMEOUT_MS = 10_000L
        private val ripgrepInstallMutex = Mutex()
        @Volatile
        private var ripgrepAvailabilityVerified = false
    }

    // ApiPreferences 实例，用于动态获取配�?    protected val apiPreferences: ApiPreferences by lazy {
        ApiPreferences.getInstance(context)
    }

    // SSH文件管理器（单例，懒加载�?    private val sshFileManager by lazy {
        SSHFileConnectionManager.getInstance(context)
    }

    // TerminalManager（单例，懒加载）
    private val terminalManager by lazy {
        TerminalManager.getInstance(context)
    }

    private val terminalSourceManager by lazy {
        SourceManager(context)
    }

    private var lastLinuxFileSystemProviderLabel: String? = null

    // Linux文件系统提供者，优先使用SSH连接，否则从TerminalManager获取
    protected fun getLinuxFileSystem(): FileSystemProvider {
        // 先尝试获取SSH连接的文件系�?        val sshProvider = sshFileManager.getFileSystemProvider()
        
        // 如果SSH已登录，使用SSH文件系统
        if (sshProvider != null) {
            if (lastLinuxFileSystemProviderLabel != "ssh") {
                AppLogger.d(TAG, "Using SSH file system provider")
                lastLinuxFileSystemProviderLabel = "ssh"
            }
            return sshProvider
        }
        
        // 否则使用本地Terminal的文件系�?        if (lastLinuxFileSystemProviderLabel != "local") {
            AppLogger.d(TAG, "Using local terminal file system provider")
            lastLinuxFileSystemProviderLabel = "local"
        }
        return terminalManager.getFileSystemProvider()
    }

    // Linux文件系统工具实例
    protected val linuxTools: LinuxFileSystemTools by lazy {
        LinuxFileSystemTools(context)
    }

    private val safTools: SafFileSystemTools by lazy {
        SafFileSystemTools(context, apiPreferences)
    }

    protected fun isSafEnvironment(environment: String): Boolean {
        return environment?.startsWith("repo:", ignoreCase = true) == true
    }

    /** 检查是否是Linux环境 */
    protected fun isLinuxEnvironment(environment: String): Boolean {
        return environment?.lowercase() == "linux"
    }

    protected data class GrepContextCandidate(
        val filePath: String,
        val lineNumber: Int,
        val lineContent: String,
        val matchContext: String?,
        val query: String,
        val round: Int
    )

    protected data class RipgrepBlockLine(
        val lineNumber: Int,
        val text: String,
        val isMatch: Boolean
    )

    protected data class RipgrepBlock(
        val filePath: String,
        val firstMatchLine: Int,
        val lineContent: String,
        val matchContext: String,
        val matchCount: Int
    )

    private data class RipgrepQueryExecution(
        val index: Int,
        val query: String,
        val commandResult: HiddenExecResult,
        val parsedBlocks: List<RipgrepBlock>
    )

    protected suspend fun getGrepService(): AIService {
        return EnhancedAIService.getAIServiceForFunction(context, FunctionType.GREP)
    }

    protected suspend fun getGrepModelParameters(): List<ModelParameter<*>> {
        val functionalConfigManager = FunctionalConfigManager(context)
        functionalConfigManager.initializeIfNeeded()
        val modelConfigManager = ModelConfigManager(context)
        val mapping = functionalConfigManager.getConfigMappingForFunction(FunctionType.GREP)
        return modelConfigManager.getModelParametersForConfig(mapping.configId)
    }

    protected suspend fun runGrepModel(prompt: String): String {
        val service = getGrepService()
        val modelParameters = getGrepModelParameters()
        val sb = StringBuilder()
        val stream = service.sendMessage(
            context = context,
            chatHistory = listOf(PromptTurn(kind = PromptTurnKind.USER, content = prompt)),
            modelParameters = modelParameters,
            enableThinking = false,
            stream = false,
            availableTools = null
        )
        stream.collect { chunk -> sb.append(chunk) }
        return sb.toString().trim()
    }

    protected fun extractFirstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(text.substring(start, end + 1))
        } catch (_: Exception) {
            null
        }
    }

    protected fun parseQueryListFromModelOutput(text: String, fallback: List<String>): List<String> {
        val obj = extractFirstJsonObject(text) ?: return fallback
        val arr = obj.optJSONArray("queries") ?: return fallback
        val queries = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val q = arr.optString(i, "").trim()
            if (q.isNotBlank()) queries.add(q)
        }
        return if (queries.isEmpty()) fallback else queries
    }

    protected fun parseSelectedIdsFromModelOutput(text: String): List<Int> {
        val obj = extractFirstJsonObject(text) ?: return emptyList()
        val arr = obj.optJSONArray("selected") ?: return emptyList()
        val ids = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v >= 0) ids.add(v)
        }
        return ids
    }

    protected fun parseReadIdsFromModelOutput(text: String): List<Int> {
        val obj = extractFirstJsonObject(text) ?: return emptyList()
        val arr = obj.optJSONArray("read") ?: return emptyList()
        val ids = mutableListOf<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.optInt(i, -1)
            if (v >= 0) ids.add(v)
        }
        return ids
    }

    protected fun normalizeQueries(queries: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (q in queries) {
            val trimmed = q.trim()
            if (trimmed.isNotBlank()) {
                val isDotPlaceholder = trimmed.length >= 3 && trimmed.all { it == '.' }
                val isEllipsisPlaceholder = trimmed.all { it == '�?}
                if (isDotPlaceholder || isEllipsisPlaceholder) continue
                seen.add(trimmed)
            }
        }
        return seen.toList()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun clipGrepText(raw: String, maxChars: Int): String {
        val trimmed = raw.trim()
        if (trimmed.length <= maxChars) return trimmed
        return trimmed.take(maxChars) + "...(truncated)"
    }

    private fun buildRipgrepCommand(args: List<String>): String {
        val quotedArgs = args.joinToString(" ") { shellQuote(it) }
        return "rg ${quotedArgs}"
    }

    private fun buildRipgrepCodeCommand(
        path: String,
        pattern: String,
        filePattern: String,
        caseInsensitive: Boolean,
        contextLines: Int
    ): String {
        val args = mutableListOf(
            "--json",
            "--line-number",
            "--column",
            "--color",
            "never",
            "--hidden",
            "-C",
            contextLines.coerceAtLeast(0).toString()
        )
        if (caseInsensitive) {
            args.add("-i")
        }
        if (filePattern.isNotBlank() && filePattern != "*") {
            args.add("-g")
            args.add(filePattern)
        }
        args.add("--")
        args.add(pattern)
        args.add(path)
        return buildRipgrepCommand(args)
    }

    private fun buildRipgrepContextCommand(
        path: String,
        queries: List<String>,
        filePattern: String,
        contextLines: Int
    ): String {
        val args = mutableListOf(
            "--json",
            "--line-number",
            "--column",
            "--color",
            "never",
            "--hidden",
            "-F",
            "-i",
            "-C",
            contextLines.coerceAtLeast(0).toString()
        )
        if (filePattern.isNotBlank() && filePattern != "*") {
            args.add("-g")
            args.add(filePattern)
        }
        queries.forEach { query ->
            args.add("-e")
            args.add(query)
        }
        args.add("--")
        args.add(path)
        return buildRipgrepCommand(args)
    }

    private fun extractRipgrepPath(json: JSONObject): String? {
        return json.optJSONObject("path")?.optString("text")?.takeIf { it.isNotBlank() }
    }

    private fun finalizeRipgrepBlock(
        filePath: String,
        lines: List<RipgrepBlockLine>
    ): RipgrepBlock? {
        if (lines.isEmpty()) return null
        val matchLines = lines.filter { it.isMatch }
        if (matchLines.isEmpty()) return null

        val lineContent = if (matchLines.size == 1) {
            clipGrepText(matchLines.first().text, 300)
        } else {
            val digest = matchLines
                .take(5)
                .joinToString(" | ") { clipGrepText(it.text, 80) }
            "${matchLines.size} matches: ${digest.take(200)}..."
        }

        val matchContext = clipGrepText(
            lines.joinToString("\n") { clipGrepText(it.text, 400) },
            4000
        )

        return RipgrepBlock(
            filePath = filePath,
            firstMatchLine = matchLines.first().lineNumber,
            lineContent = lineContent,
            matchContext = matchContext,
            matchCount = matchLines.size
        )
    }

    private fun parseRipgrepBlocks(output: String): Pair<List<RipgrepBlock>, Int> {
        val blocks = mutableListOf<RipgrepBlock>()
        val currentBlocks = LinkedHashMap<String, MutableList<RipgrepBlockLine>>()
        val seenFiles = LinkedHashSet<String>()
        var summarySearches: Int? = null

        fun flushBlock(filePath: String) {
            val current = currentBlocks.remove(filePath) ?: return
            finalizeRipgrepBlock(filePath, current)?.let { blocks.add(it) }
        }

        output.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (!trimmed.startsWith("{")) return@forEach

            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return@forEach
            when (json.optString("type")) {
                "begin" -> {
                    val data = json.optJSONObject("data") ?: return@forEach
                    extractRipgrepPath(data)?.let { seenFiles.add(it) }
                }
                "match", "context" -> {
                    val data = json.optJSONObject("data") ?: return@forEach
                    val filePath = extractRipgrepPath(data) ?: return@forEach
                    val lineNumber = data.optInt("line_number", -1)
                    if (lineNumber < 1) return@forEach
                    val text = data.optJSONObject("lines")?.optString("text")?.trimEnd('\n', '\r') ?: return@forEach

                    seenFiles.add(filePath)
                    val current = currentBlocks[filePath]
                    if (current == null) {
                        currentBlocks[filePath] = mutableListOf(
                            RipgrepBlockLine(
                                lineNumber = lineNumber,
                                text = text,
                                isMatch = json.optString("type") == "match"
                            )
                        )
                    } else {
                        val previousLine = current.lastOrNull()?.lineNumber ?: -1
                        if (previousLine >= 0 && lineNumber > previousLine + 1) {
                            flushBlock(filePath)
                            currentBlocks[filePath] = mutableListOf(
                                RipgrepBlockLine(
                                    lineNumber = lineNumber,
                                    text = text,
                                    isMatch = json.optString("type") == "match"
                                )
                            )
                        } else {
                            current.add(
                                RipgrepBlockLine(
                                    lineNumber = lineNumber,
                                    text = text,
                                    isMatch = json.optString("type") == "match"
                                )
                            )
                        }
                    }
                }
                "end" -> {
                    val data = json.optJSONObject("data") ?: return@forEach
                    val filePath = extractRipgrepPath(data)
                    if (!filePath.isNullOrBlank()) {
                        flushBlock(filePath)
                    }
                }
                "summary" -> {
                    summarySearches = json.optJSONObject("data")?.optJSONObject("stats")?.optInt("searches")
                    currentBlocks.keys.toList().forEach { flushBlock(it) }
                }
            }
        }

        currentBlocks.keys.toList().forEach { flushBlock(it) }
        return Pair(blocks, summarySearches ?: seenFiles.size)
    }

    private fun extractRipgrepNonJsonLines(output: String): List<String> {
        return output.lineSequence()
            .map { it.trim() }
            .filter { line -> line.isNotBlank() && !line.startsWith("{") }
            .distinct()
            .toList()
    }

    private fun buildRipgrepAvailabilityCheckCommand(): String {
        return "command -v rg >/dev/null 2>&1 && printf '__APEX_RG_READY__\n' || printf '__APEX_RG_MISSING__\n'"
    }

    private fun buildRipgrepInstallCommand(): String {
        val aptSource = terminalSourceManager.getSelectedSource(PackageManagerType.APT)
        val aptSourceCommand = terminalSourceManager.getAptSourceChangeCommand(aptSource)
        return buildString {
            appendLine(aptSourceCommand)
            appendLine("export DEBIAN_FRONTEND=noninteractive")
            appendLine("apt update")
            appendLine("apt install -y ripgrep")
        }.trim()
    }

    private fun buildRipgrepInstallFailureMessage(output: String): String {
        val lines = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (lines.any { it.contains("Unable to locate package ripgrep", ignoreCase = true) }) {
            return "Failed to install ripgrep automatically: package ripgrep was not found in apt sources"
        }

        if (lines.any { it.contains("Could not get lock", ignoreCase = true) }) {
            return "Failed to install ripgrep automatically: apt is locked by another process"
        }

        if (lines.any { it.contains("apt: command not found", ignoreCase = true) || it.contains("command not found: apt", ignoreCase = true) }) {
            return "Failed to install ripgrep automatically: apt is not available in the terminal environment"
        }

        val tail = lines.takeLast(12).joinToString("\n")
        return if (tail.isNotBlank()) {
            "Failed to install ripgrep automatically:\n${tail}"
        } else {
            "Failed to install ripgrep automatically"
        }
    }

    private suspend fun executeInRipgrepExecutor(
        command: String,
        executorKey: String,
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        val terminal = Terminal.getInstance(context)
        return terminal.executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    private fun buildHiddenCommandFailureMessage(
        action: String,
        result: HiddenExecResult
    ): String {
        val reason = result.error.ifBlank { "Hidden terminal command failed with state ${result.state}" }
        val preview = result.rawOutputPreview.trim()
        return buildString {
            append(action)
            append(": ")
            append(reason)
            append(" [state=")
            append(result.state)
            append("]")
            if (result.exitCode >= 0) {
                append(" (exit code ")
                append(result.exitCode)
                append(")")
            }
            if (preview.isNotBlank()) {
                append("\nRaw terminal output tail:\n")
                append(preview)
            }
        }
    }

    private fun requireHiddenCommandSuccess(
        action: String,
        result: HiddenExecResult
    ): HiddenExecResult {
        if (result.isOk) {
            return result
        }
        throw IllegalStateException(buildHiddenCommandFailureMessage(action, result))
    }

    private suspend fun ensureRipgrepAvailable(toolName: String) {
        if (ripgrepAvailabilityVerified) {
            return
        }

        ripgrepInstallMutex.withLock {
            if (ripgrepAvailabilityVerified) {
                return
            }

            val checkResult = requireHiddenCommandSuccess(
                action = "Failed to check ripgrep availability",
                result = executeInRipgrepExecutor(
                    command = buildRipgrepAvailabilityCheckCommand(),
                    executorKey = "rg-setup"
                )
            )
            if (checkResult.output.contains("__APEX_RG_READY__")) {
                ripgrepAvailabilityVerified = true
                return
            }

            ToolProgressBus.update(toolName, 0.08f, "Installing ripgrep...")
            val installResult = requireHiddenCommandSuccess(
                action = "Failed to install ripgrep",
                result = executeInRipgrepExecutor(
                    command = buildRipgrepInstallCommand(),
                    executorKey = "rg-setup",
                    timeoutMs = 600000L
                )
            )
            val verifyResult = requireHiddenCommandSuccess(
                action = "Failed to verify ripgrep installation",
                result = executeInRipgrepExecutor(
                    command = buildRipgrepAvailabilityCheckCommand(),
                    executorKey = "rg-setup"
                )
            )
            if (!verifyResult.output.contains("__APEX_RG_READY__")) {
                throw IllegalStateException(
                    buildRipgrepInstallFailureMessage(
                        if (installResult.output.isNotBlank()) installResult.output else verifyResult.output
                    )
                )
            }
            ripgrepAvailabilityVerified = true
        }
    }

    private suspend fun executeRipgrepCommand(
        toolName: String,
        command: String,
        executorKey: String
    ): HiddenExecResult {
        ensureRipgrepAvailable(toolName)
        return requireHiddenCommandSuccess(
            action = "Failed to capture ripgrep output",
            result = executeInRipgrepExecutor(
                command = command,
                executorKey = executorKey,
                timeoutMs = RIPGREP_COMMAND_TIMEOUT_MS
            )
        )
    }

    private fun buildRipgrepFailureMessage(output: String, exitCode: Int? = null): String {
        val nonJsonLines = extractRipgrepNonJsonLines(output)

        if (exitCode == 127) {
            return "ripgrep (rg) is not available in the terminal environment"
        }

        return nonJsonLines.joinToString("\n").ifBlank { 
            if (exitCode != null) {
                "ripgrep command failed with exit code ${exitCode}"
            } else {
                "ripgrep command failed"
            }
        }
    }

    protected fun buildCandidateDigestForModel(
        candidates: List<GrepContextCandidate>,
        maxCharsPerItem: Int
    ): String {
        if (candidates.isEmpty()) return "(no matches)"
        val sb = StringBuilder()
        candidates.forEachIndexed { idx, c ->
            sb.append("#").append(idx)
                .append(" file=").append(c.filePath)
                .append(" line=").append(c.lineNumber)
                .append(" round=").append(c.round)
                .append(" query=\"").append(c.query.replace("\"", "'"))
                .append("\"\n")

            val ctx = (c.matchContext ?: c.lineContent).trim()
            val limited = if (ctx.length > maxCharsPerItem) ctx.take(maxCharsPerItem) else ctx
            sb.append(limited).append("\n\n")
        }
        return sb.toString().trim()
    }

    protected suspend fun enrichCandidatesWithReadContext(
        candidates: List<GrepContextCandidate>,
        environment: String?,
        readContextLines: Int,
        maxCandidatesToRead: Int,
        selectedCandidateIndexes: List<Int>? = null,
        readFilePartFunc: (AITool) -> suspend ToolResult
    ): List<GrepContextCandidate> {
        if (candidates.isEmpty()) return candidates

        val indexesToRead = if (selectedCandidateIndexes == null) {
            (0 until minOf(maxCandidatesToRead, candidates.size)).toList()
        } else {
            selectedCandidateIndexes
                .asSequence()
                .distinct()
                .filter { it >= 0 && it < candidates.size }
                .take(maxCandidatesToRead)
                .toList()
        }

        if (indexesToRead.isEmpty()) return candidates

        val indexSet = indexesToRead.toHashSet()
        val enriched = ArrayList<GrepContextCandidate>(candidates.size)

        for ((idx, c) in candidates.withIndex()) {
            if (!indexSet.contains(idx)) {
                enriched.add(c)
                continue
            }

            val startLine = maxOf(1, c.lineNumber - readContextLines)
            val endLine = c.lineNumber + readContextLines
            val params = mutableListOf(
                ToolParameter("path", c.filePath),
                ToolParameter("start_line", startLine.toString()),
                ToolParameter("end_line", endLine.toString())
            )
            if (!environment.isNullOrBlank()) {
                params.add(ToolParameter("environment", environment))
            }

            val readRes = readFilePartFunc(AITool(name = "read_file_part", parameters = params))
            val snippet = (readRes.result as? FilePartContentData)?.content

            if (readRes.success && !snippet.isNullOrBlank()) {
                enriched.add(c.copy(matchContext = snippet))
            } else {
                enriched.add(c)
            }
        }

        return enriched
    }

    protected suspend fun runGrepCodeBatch(
        searchPath: String,
        environment: String?,
        filePattern: String,
        queries: List<String>,
        perQueryMaxResults: Int,
        round: Int,
        prefetchedFiles: List<String>? = null,
        toolNameForProgress: String? = null,
        progressBase: Float = 0f,
        progressSpan: Float = 0f,
        progressMessage: String = ""
    ): Pair<List<GrepContextCandidate>, Int> {
        val limitedQueries = normalizeQueries(queries).take(8)
        val candidates = mutableListOf<GrepContextCandidate>()
        val dedup = HashSet<String>()

        if (limitedQueries.isEmpty()) {
            return Pair(emptyList(), 0)
        }

        if (prefetchedFiles != null && prefetchedFiles.isEmpty()) {
            return Pair(emptyList(), 0)
        }

        val indexedQueries = limitedQueries.mapIndexedNotNull { index, query ->
            if (runCatching { Regex(query, RegexOption.IGNORE_CASE) }.isSuccess) {
                index to query
            } else {
                null
            }
        }

        if (indexedQueries.isEmpty()) {
            return Pair(emptyList(), 0)
        }

        val completedQueries = AtomicInteger(0)
        val executions = coroutineScope {
            indexedQueries.map { (index, query) ->
                async {
                    val command = buildRipgrepCodeCommand(
                        path = searchPath,
                        pattern = query,
                        filePattern = filePattern,
                        caseInsensitive = true,
                        contextLines = 3
                    )
                    val commandResult = executeRipgrepCommand(
                        toolName = toolNameForProgress ?: "grep_context",
                        command = command,
                        executorKey = "rg-${index % RIPGREP_EXECUTOR_POOL_SIZE}"
                    )
                    val output = commandResult.output
                    val (parsedBlocks, _) = parseRipgrepBlocks(output)

                    if (toolNameForProgress != null && progressSpan > 0f) {
                        val completed = completedQueries.incrementAndGet()
                        val fraction = (completed.toFloat() / indexedQueries.size.toFloat()).coerceIn(0f, 1f)
                        val msg = if (progressMessage.isNotBlank()) progressMessage else "Searching..."
                        ToolProgressBus.update(
                            toolNameForProgress,
                            (progressBase + progressSpan * fraction).coerceIn(0f, 0.99f),
                            "${msg} (query ${completed}/${indexedQueries.size})"
                        )
                    }

                    RipgrepQueryExecution(
                        index = index,
                        query = query,
                        commandResult = commandResult,
                        parsedBlocks = parsedBlocks
                    )
                }
            }.awaitAll()
        }.sortedBy { it.index }

        executions.forEach { execution ->
            if (execution.parsedBlocks.isEmpty()) {
                if (execution.commandResult.exitCode > 1) {
                    throw IllegalStateException(
                        buildRipgrepFailureMessage(
                            execution.commandResult.output,
                            execution.commandResult.exitCode
                        )
                    )
                }
                return@forEach
            }

            var remaining = perQueryMaxResults
            execution.parsedBlocks.forEach { block ->
                if (remaining <= 0) return@forEach
                val candidate = GrepContextCandidate(
                    filePath = block.filePath,
                    lineNumber = block.firstMatchLine,
                    lineContent = block.lineContent,
                    matchContext = block.matchContext,
                    query = execution.query,
                    round = round
                )
                val key = "${candidate.filePath}#${candidate.lineNumber}#${(candidate.matchContext ?: "").take(120)}"
                if (!dedup.add(key)) return@forEach
                candidates.add(candidate)
                remaining--
            }
        }

        if (toolNameForProgress != null && progressSpan > 0f) {
            val msg = if (progressMessage.isNotBlank()) progressMessage else "Searching..."
            ToolProgressBus.update(
                toolNameForProgress,
                (progressBase + progressSpan).coerceIn(0f, 0.99f),
                "${msg} (query ${indexedQueries.size}/${indexedQueries.size})"
            )
        }

        return Pair(candidates, 0)
    }

    private fun groupRipgrepBlocks(
        blocks: List<RipgrepBlock>
    ): List<GrepResultData.FileMatch> {
        val grouped = LinkedHashMap<String, MutableList<GrepResultData.LineMatch>>()
        blocks.forEach { block ->
            val lineMatches = grouped.getOrPut(block.filePath) { mutableListOf() }
            lineMatches.add(
                GrepResultData.LineMatch(
                    lineNumber = block.firstMatchLine,
                    lineContent = block.lineContent,
                    matchContext = block.matchContext
                )
            )
        }

        return grouped.map { (filePath, lineMatches) ->
            GrepResultData.FileMatch(
                filePath = filePath,
                lineMatches = lineMatches
            )
        }
    }

    protected suspend fun grepCodeWithRipgrep(
        toolName: String,
        path: String,
        pattern: String,
        filePattern: String,
        caseInsensitive: Boolean,
        contextLines: Int,
        maxResults: Int,
        envLabel: String
    ): ToolResult {
        return try {
            ToolProgressBus.update(toolName, 0.05f, "Running ripgrep...")
            val command = buildRipgrepCodeCommand(
                path = path,
                pattern = pattern,
                filePattern = filePattern,
                caseInsensitive = caseInsensitive,
                contextLines = contextLines
            )
            val commandResult = executeRipgrepCommand(
                toolName = toolName,
                command = command,
                executorKey = "rg-0"
            )
            val output = commandResult.output

            ToolProgressBus.update(toolName, 0.7f, "Parsing ripgrep results...")
            val (parsedBlocks, filesSearched) = parseRipgrepBlocks(output)
            if (commandResult.exitCode > 1) {
                return ToolResult(
                    toolName = toolName,
                    success = false,
                    result = StringResultData(""),
                    error = buildRipgrepFailureMessage(output, commandResult.exitCode)
                )
            }
            val limitedBlocks = parsedBlocks.take(maxResults.coerceAtLeast(0))
            val fileMatches = groupRipgrepBlocks(limitedBlocks)
            ToolProgressBus.update(toolName, 1f, "Search completed")

            ToolResult(
                toolName = toolName,
                success = true,
                result = GrepResultData(
                    searchPath = path,
                    pattern = pattern,
                    matches = fileMatches.take(20),
                    totalMatches = limitedBlocks.size,
                    filesSearched = filesSearched,
                    env = envLabel
                ),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing ripgrep code search", e)
            ToolProgressBus.update(toolName, 1f, "Search failed")
            ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "Error performing grep search: ${e.message}"
            )
        }
    }

    protected suspend fun grepContextAgentic(
        toolName: String,
        displayPath: String,
        searchPath: String,
        environment: String?,
        intent: String,
        filePattern: String,
        maxResults: Int,
        envLabel: String
    ): ToolResult {
        return try {
            val overallStartTime = System.currentTimeMillis()
            ToolProgressBus.update(toolName, 0f, "Preparing search...")

            val useEnglish = LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")

            val fallback = listOf(intent.take(60)).filter { it.isNotBlank() }
            var queries = normalizeQueries(fallback).take(8)
            if (queries.isEmpty()) queries = fallback
            ToolProgressBus.update(toolName, 0.05f, "Starting search rounds...")

            val allCandidates = mutableListOf<GrepContextCandidate>()
            val overallDedup = HashSet<String>()

            val perRoundSearchSpan = 0.2f
            val perRoundRefineSpan = 0.05f

            for (round in 1..3) {
                val roundBase = 0.1f + (round - 1) * (perRoundSearchSpan + perRoundRefineSpan)
                AppLogger.d(TAG, "grep_context: Starting search round ${round}/3. queries=${queries.joinToString(" | ") { it.take(60) }}")
                val (batchCandidates, _) = runGrepCodeBatch(
                    searchPath = searchPath,
                    environment = environment,
                    filePattern = filePattern,
                    queries = queries,
                    perQueryMaxResults = 30,
                    round = round,
                    toolNameForProgress = toolName,
                    progressBase = roundBase,
                    progressSpan = perRoundSearchSpan,
                    progressMessage = "Searching (round ${round}/3)"
                )

                var storedBatchCandidates = batchCandidates

                val digestCandidates = storedBatchCandidates.take(24)
                val digest = buildCandidateDigestForModel(digestCandidates, 800)

                val planPrompt = FunctionalPrompts.grepContextRefineWithReadPrompt(
                    intent = intent,
                    displayPath = displayPath,
                    filePattern = filePattern,
                    lastRoundDigest = digest,
                    maxRead = 8,
                    useEnglish = useEnglish
                )

                ToolProgressBus.update(toolName, roundBase + perRoundSearchSpan, "Planning next steps (round ${round}/3)...")
                val planStart = System.currentTimeMillis()
                val planRaw = runGrepModel(planPrompt)
                val plannedQueries = normalizeQueries(parseQueryListFromModelOutput(planRaw, queries)).take(8)
                val readIds = parseReadIdsFromModelOutput(planRaw)
                    .distinct()
                    .filter { it >= 0 && it < digestCandidates.size }
                    .take(8)

                if (readIds.isNotEmpty()) {
                    ToolProgressBus.update(toolName, roundBase + perRoundSearchSpan, "Reading selected snippets (round ${round}/3)...")
                    // 由于我们在FileSystemAdvancedTools中没有直接访问readFilePart的权限，
                    // 这里我们需要使用一个默认实现，或者在StandardFileSystemTools中重写此方法
                    val enrichedDigestCandidates = digestCandidates

                    val contextByKey = HashMap<String, String>()
                    for (id in readIds) {
                        val c = enrichedDigestCandidates.getOrNull(id) ?: continue
                        val ctx = c.matchContext ?: continue
                        contextByKey["${c.filePath}#${c.lineNumber}"] = ctx
                    }

                    storedBatchCandidates = storedBatchCandidates.map { c ->
                        val key = "${c.filePath}#${c.lineNumber}"
                        val ctx = contextByKey[key]
                        if (!ctx.isNullOrBlank()) c.copy(matchContext = ctx) else c
                    }
                }

                val planElapsed = System.currentTimeMillis() - planStart
                if (round < 3 && plannedQueries.isNotEmpty()) {
                    queries = plannedQueries
                }
                ToolProgressBus.update(toolName, roundBase + perRoundSearchSpan + perRoundRefineSpan, "Prepared next steps")
                AppLogger.d(
                    TAG,
                    "grep_context: Plan after round ${round}/3 completed in ${planElapsed}ms. nextQueries=${plannedQueries.joinToString(" | ") { it.take(60) }} readIds=${readIds.joinToString(",") }"
                )

                storedBatchCandidates.forEach { c ->
                    val key = "${c.filePath}#${c.lineNumber}"
                    if (overallDedup.add(key)) {
                        allCandidates.add(c)
                    }
                }
            }

            if (allCandidates.isEmpty()) {
                ToolProgressBus.update(toolName, 1f, "Search completed, found 0")
                return ToolResult(
                    toolName = toolName,
                    success = true,
                    result = GrepResultData(
                        searchPath = displayPath,
                        pattern = intent,
                        matches = emptyList(),
                        totalMatches = 0,
                        filesSearched = 0,
                        env = envLabel
                    ),
                    error = ""
                )
            }

            val selectionDigest = buildCandidateDigestForModel(allCandidates.take(60), 1000)
            val selectPrompt = FunctionalPrompts.grepContextSelectPrompt(
                intent = intent,
                displayPath = displayPath,
                candidatesDigest = selectionDigest,
                maxResults = maxResults,
                useEnglish = useEnglish
            )

            ToolProgressBus.update(toolName, 0.85f, "Selecting most relevant matches...")
            val selectedIds = parseSelectedIdsFromModelOutput(runGrepModel(selectPrompt))
            val selectedCandidates = if (selectedIds.isNotEmpty()) {
                selectedIds.mapNotNull { id -> allCandidates.getOrNull(id) }.take(maxResults)
            } else {
                allCandidates.take(maxResults)
            }

            val fileOrder = selectedCandidates.map { it.filePath }.distinct()
            val fileMatches = fileOrder.map { filePath ->
                val lineMatches = selectedCandidates
                    .filter { it.filePath == filePath }
                    .map {
                        GrepResultData.LineMatch(
                            lineNumber = it.lineNumber,
                            lineContent = it.lineContent,
                            matchContext = it.matchContext
                        )
                    }
                GrepResultData.FileMatch(filePath = filePath, lineMatches = lineMatches)
            }

            ToolProgressBus.update(toolName, 1f, "Search completed, found ${selectedCandidates.size}")
            val overallElapsed = System.currentTimeMillis() - overallStartTime
            AppLogger.d(
                TAG,
                "grep_context: Completed in ${overallElapsed}ms. selected=${selectedCandidates.size} candidates=${allCandidates.size}"
            )
            ToolResult(
                toolName = toolName,
                success = true,
                result = GrepResultData(
                    searchPath = displayPath,
                    pattern = intent,
                    matches = fileMatches,
                    totalMatches = selectedCandidates.size,
                    filesSearched = 0,
                    env = envLabel
                ),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error performing context search", e)
            ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error performing context search: ${e.message}"
            )
        }
    }

    /** Search for files by pattern */
    open suspend fun findFiles(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val pattern = tool.parameters.find { it.name == "pattern" }?.value ?: "*"
        val recursive = tool.parameters.find { it.name == "recursive" }?.value?.toBoolean() ?: true
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.findFiles(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.findFiles(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        return try {
            val directory = File(path)

            if (!directory.exists()) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Directory does not exist: ${path}"
                )
            }

            if (!directory.isDirectory) {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Path is not a directory: ${path}"
                )
            }

            val foundFiles = mutableListOf<String>()
            val patternRegex = Regex(pattern.replace("*", ".*"))

            fun searchFiles(currentDir: File) {
                val files = currentDir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory) {
                        if (recursive) {
                            searchFiles(file)
                        }
                    } else {
                        if (patternRegex.matches(file.name)) {
                            foundFiles.add(file.absolutePath)
                        }
                    }
                }
            }

            searchFiles(directory)
            AppLogger.d(TAG, "Found ${foundFiles.size} files matching pattern '${pattern}' in ${path}")

            return ToolResult(
                toolName = tool.name,
                success = true,
                result = FindFilesResultData(path, pattern, foundFiles),
                error = ""
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error finding files", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Error finding files: ${e.message}"
            )
        }
    }

    /** Search for text in files */
    open suspend fun grepCode(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val pattern = tool.parameters.find { it.name == "pattern" }?.value ?: ""
        val filePattern = tool.parameters.find { it.name == "file_pattern" }?.value ?: "*"
        val caseInsensitive = tool.parameters.find { it.name == "case_insensitive" }?.value?.toBoolean() ?: true
        val contextLines = tool.parameters.find { it.name == "context_lines" }?.value?.toIntOrNull() ?: 3
        val maxResults = tool.parameters.find { it.name == "max_results" }?.value?.toIntOrNull() ?: 50
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.grepCode(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.grepCode(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        if (pattern.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Pattern parameter is required"
            )
        }

        return grepCodeWithRipgrep(
            toolName = tool.name,
            path = path,
            pattern = pattern,
            filePattern = filePattern,
            caseInsensitive = caseInsensitive,
            contextLines = contextLines,
            maxResults = maxResults,
            envLabel = "android"
        )
    }

    /** Search for context in files */
    open suspend fun grepContext(tool: AITool): ToolResult {
        val path = tool.parameters.find { it.name == "path" }?.value ?: ""
        val intent = tool.parameters.find { it.name == "intent" }?.value ?: ""
        val filePattern = tool.parameters.find { it.name == "file_pattern" }?.value ?: "*"
        val maxResults = tool.parameters.find { it.name == "max_results" }?.value?.toIntOrNull() ?: 20
        val environment = tool.parameters.find { it.name == "environment" }?.value

        if (isLinuxEnvironment(environment)) {
            return linuxTools.grepContext(tool)
        }
        if (isSafEnvironment(environment)) {
            return safTools.grepContext(tool)
        }
        PathValidator.validateAndroidPath(path, tool.name)?.let { return it }

        if (path.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Path parameter is required"
            )
        }

        if (intent.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "Intent parameter is required"
            )
        }

        return grepContextAgentic(
            toolName = tool.name,
            displayPath = path,
            searchPath = path,
            environment = environment,
            intent = intent,
            filePattern = filePattern,
            maxResults = maxResults,
            envLabel = "android"
        )
    }
}

