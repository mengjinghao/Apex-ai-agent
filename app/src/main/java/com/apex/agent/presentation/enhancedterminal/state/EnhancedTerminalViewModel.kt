package com.apex.agent.presentation.enhancedterminal.state

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.apex.agent.presentation.enhancedterminal.data.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

class EnhancedTerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val prefs = TerminalPreferences(context)

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions

    private val _activeSessionId = MutableStateFlow<String?>(null)
    val activeSessionId: StateFlow<String?> = _activeSessionId

    val activeSession: StateFlow<TerminalSession?> = combine(_sessions, _activeSessionId) { s, id -> s.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _globalHistory = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val globalHistory: StateFlow<List<Pair<String, Long>>> = _globalHistory

    private val _aliases = MutableStateFlow(DefaultAliases.all.associateBy { it.alias }.toMutableMap())
    val aliases: StateFlow<Map<String, CommandAlias>> = _aliases

    private val _quickCommands = MutableStateFlow(DefaultQuickCommands.all)
    val quickCommands: StateFlow<List<QuickCommand>> = _quickCommands

    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    val snippets: StateFlow<List<Snippet>> = _snippets

    private val _paletteOpen = MutableStateFlow(false)
    val paletteOpen: StateFlow<Boolean> = _paletteOpen
    private val _paletteQuery = MutableStateFlow("")
    val paletteQuery: StateFlow<String> = _paletteQuery

    private val _historyIndex = MutableStateFlow(-1)

    private val _reverseSearchOpen = MutableStateFlow(false)
    val reverseSearchOpen: StateFlow<Boolean> = _reverseSearchOpen
    private val _reverseSearchQuery = MutableStateFlow("")
    val reverseSearchQuery: StateFlow<String> = _reverseSearchQuery

    private val _themeId = MutableStateFlow("apex_dark")
    val theme: StateFlow<TerminalTheme> = _themeId.map { TerminalThemes.byId(it) }.stateIn(viewModelScope, SharingStarted.Lazily, TerminalThemes.all.first())

    private val _fontSize = MutableStateFlow(12)
    val fontSize: StateFlow<Int> = _fontSize

    private val _completionResults = MutableStateFlow<List<String>>(emptyList())
    val completionResults: StateFlow<List<String>> = _completionResults
    private val _completionIndex = MutableStateFlow(-1)
    val completionIndex: StateFlow<Int> = _completionIndex

    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val _searchMatches = MutableStateFlow<List<Int>>(emptyList())
    val searchMatches: StateFlow<List<Int>> = _searchMatches
    private val _searchMatchIndex = MutableStateFlow(-1)
    val searchMatchIndex: StateFlow<Int> = _searchMatchIndex

    private val tabCompleter = TabCompleter({ _aliases.value }, { _quickCommands.value }, { _globalHistory.value })

    // === 危险命令确认 ===
    private val _pendingDangerousCommand = MutableStateFlow<DangerousCommandDetector.DetectionResult?>(null)
    val pendingDangerousCommand: StateFlow<DangerousCommandDetector.DetectionResult?> = _pendingDangerousCommand
    private var pendingCommandText: String? = null

    // === 代码段编辑器 ===
    private val _snippetEditorOpen = MutableStateFlow(false)
    val snippetEditorOpen: StateFlow<Boolean> = _snippetEditorOpen
    private val _editingSnippet = MutableStateFlow<Snippet?>(null)
    val editingSnippet: StateFlow<Snippet?> = _editingSnippet

    init {
        createSession()
        loadPersistedState()
        observeAndPersist()
    }

    /** 从 DataStore 加载持久化数据 */
    private fun loadPersistedState() {
        viewModelScope.launch {
            prefs.historyFlow.collect { h -> if (h.isNotEmpty()) _globalHistory.value = h }
        }
        viewModelScope.launch {
            prefs.aliasesFlow.collect { map ->
                if (map.isNotEmpty()) {
                    _aliases.value = DefaultAliases.all.associateBy { it.alias }.toMutableMap().apply {
                        map.forEach { (k, v) -> put(k, CommandAlias(k, v.first, v.second)) }
                    }
                }
            }
        }
        viewModelScope.launch {
            prefs.snippetsFlow.collect { s -> if (s.isNotEmpty()) _snippets.value = s }
        }
        viewModelScope.launch { prefs.themeIdFlow.collect { id -> _themeId.value = id } }
        viewModelScope.launch { prefs.fontSizeFlow.collect { size -> _fontSize.value = size } }
    }

    /** 监听状态变化,自动持久化 */
    private fun observeAndPersist() {
        viewModelScope.launch { _globalHistory.collect { prefs.saveHistory(it) } }
        viewModelScope.launch { _aliases.collect { a -> prefs.saveAliases(a) } }
        viewModelScope.launch { _snippets.collect { s -> prefs.saveSnippets(s) } }
        viewModelScope.launch { _themeId.collect { id -> prefs.saveThemeId(id) } }
        viewModelScope.launch { _fontSize.collect { size -> prefs.saveFontSize(size) } }
    }

    // === 危险命令检测 ===

    /**
     * 执行命令(带危险检测)
     *
     * 如果是危险命令,先存到 pending 状态,UI 弹确认对话框;
     * 用户确认后调 [confirmDangerousCommand] 执行。
     */
    fun executeCommandWithCheck(input: String) {
        val detection = DangerousCommandDetector.check(input)
        if (detection.isDangerous) {
            pendingCommandText = input
            _pendingDangerousCommand.value = detection
        } else {
            executeCommand(input)
        }
    }

    /** 用户确认后执行危险命令 */
    fun confirmDangerousCommand() {
        val cmd = pendingCommandText ?: return
        _pendingDangerousCommand.value = null
        pendingCommandText = null
        executeCommand(cmd)
    }

    /** 用户取消危险命令 */
    fun cancelDangerousCommand() {
        _pendingDangerousCommand.value = null
        pendingCommandText = null
    }

    // === 代码段编辑器 ===

    fun openSnippetEditor(existing: Snippet? = null) {
        _editingSnippet.value = existing
        _snippetEditorOpen.value = true
    }

    fun closeSnippetEditor() {
        _snippetEditorOpen.value = false
        _editingSnippet.value = null
    }

    fun saveSnippet(name: String, content: String, language: String, tags: List<String>) {
        val existing = _editingSnippet.value
        val snippet = if (existing != null) {
            existing.copy(name = name, content = content, language = language, tags = tags)
        } else {
            Snippet(
                id = "snippet_${System.currentTimeMillis()}",
                name = name, content = content, language = language, tags = tags,
            )
        }
        if (existing != null) {
            _snippets.value = _snippets.value.map { if (it.id == existing.id) snippet else it }
        } else {
            _snippets.value = _snippets.value + snippet
        }
        closeSnippetEditor()
    }

    fun createSession(name: String? = null): String {
        val id = "session_${System.currentTimeMillis()}"
        val session = TerminalSession(id = id, name = name ?: "shell-${_sessions.value.size + 1}")
        _sessions.value = _sessions.value + session
        _activeSessionId.value = id
        addSystemLine(id, "✓ Apex Terminal v3.0 — 输入 'help' 查看帮助")
        return id
    }

    fun switchSession(id: String) { if (_sessions.value.any { it.id == id }) _activeSessionId.value = id }
    fun closeSession(id: String) {
        _sessions.value = _sessions.value.filter { it.id != id }
        if (_activeSessionId.value == id) _activeSessionId.value = _sessions.value.firstOrNull()?.id
    }

    fun executeCommand(input: String) {
        val sessionId = _activeSessionId.value ?: return
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        addLine(sessionId, TerminalLine(text = "❯ $trimmed", kind = LineKind.PROMPT))
        _globalHistory.value = (_globalHistory.value + (trimmed to System.currentTimeMillis())).takeLast(500)
        val resolved = resolveAlias(trimmed)
        updateSession(sessionId) { it.copy(isRunning = true, lastActiveAt = System.currentTimeMillis()) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when {
                    resolved.startsWith("#") -> addLine(sessionId, TerminalLine(text = resolved, kind = LineKind.COMMENT))
                    isBuiltIn(resolved) -> executeBuiltIn(sessionId, resolved)
                    else -> executeShell(sessionId, resolved)
                }
            } catch (e: Exception) { addLine(sessionId, TerminalLine(text = "✗ ${e.message}", kind = LineKind.ERROR)) }
            finally { updateSession(sessionId) { it.copy(isRunning = false) }; _historyIndex.value = -1 }
        }
    }

    private fun resolveAlias(input: String): String {
        val parts = input.split(" ", limit = 2)
        val alias = _aliases.value[parts[0]]
        return if (alias != null) { if (parts.size > 1) "${alias.command} ${parts[1]}" else alias.command } else input
    }

    private fun isBuiltIn(input: String): Boolean {
        val cmd = input.split(" ").firstOrNull()?.lowercase() ?: return false
        return cmd in listOf("help","clear","new","close","sessions","history","aliases","snippets","theme","export","cd","echo","pwd","exit")
    }

    private suspend fun executeBuiltIn(sessionId: String, input: String) {
        val parts = input.split(" ", limit = 2)
        val cmd = parts[0].lowercase(); val args = parts.getOrNull(1) ?: ""
        when (cmd) {
            "help" -> showHelp(sessionId)
            "clear" -> updateSession(sessionId) { it.copy(lines = emptyList()) }
            "new" -> { val id = createSession(args.ifBlank { null }); addSystemLine(id, "✓ 新建会话") }
            "close" -> closeSession(sessionId)
            "sessions" -> { addSystemLine(sessionId, "📋 会话 (${_sessions.value.size}):"); _sessions.value.forEach { s -> addLine(sessionId, TerminalLine(text = "  ${if (s.id == _activeSessionId.value) "●" else "○"} ${s.name} ${s.shortDir} ${s.lines.size}行", kind = LineKind.OUTPUT)) } }
            "history" -> { addSystemLine(sessionId, "📜 历史 (最近${_globalHistory.value.size}条):"); _globalHistory.value.takeLast(30).forEach { (c, t) -> addLine(sessionId, TerminalLine(text = "  ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(t))}  $c", kind = LineKind.OUTPUT)) } }
            "aliases" -> { addSystemLine(sessionId, "🔧 别名:"); _aliases.value.values.forEach { a -> addLine(sessionId, TerminalLine(text = "  ${a.alias} → ${a.command}", kind = LineKind.OUTPUT)) } }
            "snippets" -> { if (_snippets.value.isEmpty()) addSystemLine(sessionId, "暂无代码段") else { addSystemLine(sessionId, "📝 代码段:"); _snippets.value.forEach { s -> addLine(sessionId, TerminalLine(text = "  ${s.name} [${s.language}]", kind = LineKind.OUTPUT)) } } }
            "echo" -> addOutput(sessionId, args)
            "pwd" -> addOutput(sessionId, getCurrentDir(sessionId))
            "cd" -> { val newDir = when { args.isEmpty() || args == "~" -> System.getProperty("user.home") ?: "~"; args.startsWith("/") -> args; args == ".." -> getCurrentDir(sessionId).substringBeforeLast('/').ifEmpty { "/" }; else -> "${getCurrentDir(sessionId)}/$args" }; updateSession(sessionId) { it.copy(workingDir = newDir) }; addOutput(sessionId, newDir) }
            "exit" -> closeSession(sessionId)
            "theme" -> { cycleTheme() }
            "export" -> { val s = _sessions.value.firstOrNull { it.id == sessionId } ?: return; addSystemLine(sessionId, "📄 导出 ${s.name}:"); s.lines.forEach { addLine(sessionId, TerminalLine(text = it.text, kind = LineKind.OUTPUT)) } }
        }
    }

    private suspend fun executeShell(sessionId: String, command: String) {
        try {
            val dir = java.io.File(getCurrentDir(sessionId).let { if (it == "~") System.getProperty("user.home") ?: "/" else it })
            val process = ProcessBuilder(listOf("sh", "-c", command)).directory(dir).redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line = reader.readLine()
            while (line != null) { addOutput(sessionId, line); line = reader.readLine() }
            val exit = process.waitFor()
            if (exit != 0) addLine(sessionId, TerminalLine(text = "[exit $exit]", kind = LineKind.COMMENT))
        } catch (e: Exception) { addLine(sessionId, TerminalLine(text = "✗ ${e.message}", kind = LineKind.ERROR)) }
    }

    private fun showHelp(sessionId: String) {
        listOf("Apex Terminal v3.0 — 增强版终端", "", "内置命令:", "  help/clear/new/close/sessions/history/aliases/snippets", "  echo/pwd/cd/export/theme/exit", "", "快捷键:", "  ↑↓ 历史导航 | Ctrl+R 反向搜索 | 命令面板按钮", "  Tab 补全 | / 搜索输出", "", "其他命令通过 sh -c 执行").forEach { addLine(sessionId, TerminalLine(text = it, kind = if (it.isBlank()) LineKind.OUTPUT else LineKind.INFO)) }
    }

    private fun getCurrentDir(sessionId: String) = _sessions.value.firstOrNull { it.id == sessionId }?.workingDir ?: "~"
    private fun addLine(sessionId: String, line: TerminalLine) { updateSession(sessionId) { it.copy(lines = it.lines + line) } }
    private fun addOutput(sessionId: String, text: String) { if (text.isNotBlank()) addLine(sessionId, TerminalLine(text = text, kind = LineKind.OUTPUT)) }
    private fun addSystemLine(sessionId: String, text: String) { addLine(sessionId, TerminalLine(text = text, kind = LineKind.SYSTEM)) }
    private fun updateSession(sessionId: String, update: (TerminalSession) -> TerminalSession) { _sessions.value = _sessions.value.map { if (it.id == sessionId) update(it) else it } }

    fun getPreviousCommand(): String? { val h = _globalHistory.value; if (h.isEmpty()) return null; val idx = if (_historyIndex.value == -1) h.lastIndex else (_historyIndex.value - 1).coerceAtLeast(0); _historyIndex.value = idx; return h.getOrNull(idx)?.first }
    fun getNextCommand(): String? { val h = _globalHistory.value; if (h.isEmpty()) return null; if (_historyIndex.value == -1) return ""; val idx = _historyIndex.value + 1; if (idx >= h.size) { _historyIndex.value = -1; return "" }; _historyIndex.value = idx; return h.getOrNull(idx)?.first }

    fun openReverseSearch() { _reverseSearchOpen.value = true; _reverseSearchQuery.value = "" }
    fun closeReverseSearch() { _reverseSearchOpen.value = false; _reverseSearchQuery.value = "" }
    fun updateReverseSearchQuery(q: String) { _reverseSearchQuery.value = q }
    fun getReverseSearchMatch(): String? { val q = _reverseSearchQuery.value; if (q.isBlank()) return null; return _globalHistory.value.reversed().firstOrNull { it.first.contains(q, true) }?.first }

    fun openPalette() { _paletteOpen.value = true; _paletteQuery.value = "" }
    fun closePalette() { _paletteOpen.value = false; _paletteQuery.value = "" }
    fun updatePaletteQuery(q: String) { _paletteQuery.value = q }

    val paletteResults: StateFlow<List<CommandPaletteItem>> = combine(_paletteQuery, _globalHistory, _quickCommands, _snippets, aliases) { query, history, quickCmds, snippets, aliases ->
        if (query.isBlank()) { history.takeLast(5).reversed().map { CommandPaletteItem.HistoryItem(it.first, it.second) } + quickCmds.take(10).map { CommandPaletteItem.QuickCommandItem(it) } + BuiltInCommands.all.take(5) }
        else { val q = query.lowercase(); val r = mutableListOf<CommandPaletteItem>()
            history.reversed().filter { it.first.contains(q, true) }.take(5).forEach { r.add(CommandPaletteItem.HistoryItem(it.first, it.second)) }
            quickCmds.filter { it.label.contains(q, true) || it.command.contains(q, true) }.take(5).forEach { r.add(CommandPaletteItem.QuickCommandItem(it)) }
            aliases.values.filter { it.alias.contains(q, true) || it.command.contains(q, true) }.take(3).forEach { r.add(CommandPaletteItem.AliasItem(it)) }
            snippets.filter { it.name.contains(q, true) || it.content.contains(q, true) }.take(3).forEach { r.add(CommandPaletteItem.SnippetItem(it)) }
            BuiltInCommands.all.filter { it.name.contains(q, true) || it.description.contains(q, true) }.take(3).forEach { r.add(it) }
            r }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun executePaletteItem(item: CommandPaletteItem): String = when (item) {
        is CommandPaletteItem.HistoryItem -> item.command
        is CommandPaletteItem.QuickCommandItem -> item.cmd.command
        is CommandPaletteItem.SnippetItem -> item.snippet.content
        is CommandPaletteItem.AliasItem -> item.alias.command
        is CommandPaletteItem.BuiltInItem -> item.action
    }

    fun addAlias(alias: CommandAlias) { _aliases.value = _aliases.value.toMutableMap().apply { put(alias.alias, alias) } }
    fun removeAlias(alias: String) { _aliases.value = _aliases.value.toMutableMap().apply { remove(alias) } }
    fun addSnippet(snippet: Snippet) { _snippets.value = _snippets.value + snippet }
    fun removeSnippet(id: String) { _snippets.value = _snippets.value.filter { it.id != id } }

    fun setTheme(id: String) { _themeId.value = id }
    fun cycleTheme() { val idx = TerminalThemes.all.indexOfFirst { it.id == _themeId.value }; setTheme(TerminalThemes.all[(idx + 1) % TerminalThemes.all.size].id) }
    fun setFontSize(size: Int) { _fontSize.value = size.coerceIn(8, 24) }
    fun increaseFontSize() { setFontSize(_fontSize.value + 1) }
    fun decreaseFontSize() { setFontSize(_fontSize.value - 1) }

    fun requestCompletion(input: String) { val results = tabCompleter.complete(input, getCurrentDir(_activeSessionId.value ?: "")); _completionResults.value = results; _completionIndex.value = if (results.isNotEmpty()) 0 else -1 }
    fun clearCompletions() { _completionResults.value = emptyList(); _completionIndex.value = -1 }
    fun nextCompletion(): String? { val r = _completionResults.value; if (r.isEmpty()) return null; val idx = (_completionIndex.value + 1) % r.size; _completionIndex.value = idx; return r[idx] }
    fun previousCompletion(): String? { val r = _completionResults.value; if (r.isEmpty()) return null; val idx = if (_completionIndex.value <= 0) r.lastIndex else _completionIndex.value - 1; _completionIndex.value = idx; return r[idx] }

    fun openSearch() { _searchOpen.value = true; _searchQuery.value = ""; _searchMatches.value = emptyList(); _searchMatchIndex.value = -1 }
    fun closeSearch() { _searchOpen.value = false; _searchQuery.value = ""; _searchMatches.value = emptyList(); _searchMatchIndex.value = -1 }
    fun updateSearchQuery(q: String) { _searchQuery.value = q; if (q.isBlank()) { _searchMatches.value = emptyList(); _searchMatchIndex.value = -1; return }; val s = _sessions.value.firstOrNull { it.id == _activeSessionId.value } ?: return; val m = s.lines.mapIndexedNotNull { i, l -> if (l.text.contains(q, true)) i else null }; _searchMatches.value = m; _searchMatchIndex.value = if (m.isNotEmpty()) m.lastIndex else -1 }
    fun nextSearchMatch(): Int? { val m = _searchMatches.value; if (m.isEmpty()) return null; val idx = (_searchMatchIndex.value + 1) % m.size; _searchMatchIndex.value = idx; return m[idx] }
    fun previousSearchMatch(): Int? { val m = _searchMatches.value; if (m.isEmpty()) return null; val idx = if (_searchMatchIndex.value <= 0) m.lastIndex else _searchMatchIndex.value - 1; _searchMatchIndex.value = idx; return m[idx] }
}
