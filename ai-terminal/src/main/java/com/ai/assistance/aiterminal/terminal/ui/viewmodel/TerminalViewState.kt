package com.ai.assistance.aiterminal.terminal.ui.viewmodel

import com.ai.assistance.aiterminal.terminal.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 终端视图状态。
 *
 * 持有终端 UI 的完整状态，包括输入栏、消息流、侧边栏、工具栏等。
 * UI 层（Compose/View）观察这些 StateFlow 来渲染界面。
 *
 * # 状态组成
 *
 * - **输入栏状态**: 当前输入文本、光标位置、补全建议、输入模式
 * - **消息流状态**: 消息列表、滚动位置、自动滚动
 * - **侧边栏状态**: 会话列表、展开/折叠
 * - **工具栏状态**: 模式切换按钮、Agent 列表按钮、宏按钮
 * - **吉祥物状态**: 当前形态、显示/隐藏
 * - **面板布局**: 单面板/分屏/标签页
 */
class TerminalViewState {

    // ===== 输入栏 =====

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    private val _inputMode = MutableStateFlow(TerminalInputMode.SHELL)
    val inputMode: StateFlow<TerminalInputMode> = _inputMode.asStateFlow()

    private val _completions = MutableStateFlow<List<String>>(emptyList())
    val completions: StateFlow<List<String>> = _completions.asStateFlow()

    private val _showCompletions = MutableStateFlow(false)
    val showCompletions: StateFlow<Boolean> = _showCompletions.asStateFlow()

    private val _selectedCompletionIndex = MutableStateFlow(0)
    val selectedCompletionIndex: StateFlow<Int> = _selectedCompletionIndex.asStateFlow()

    private val _inputHistory = MutableStateFlow<List<String>>(emptyList())
    val inputHistory: StateFlow<List<String>> = _inputHistory.asStateFlow()

    private val _historyIndex = MutableStateFlow(-1)
    val historyIndex: StateFlow<Int> = _historyIndex.asStateFlow()

    // ===== 消息流 =====

    private val _autoScroll = MutableStateFlow(true)
    val autoScroll: StateFlow<Boolean> = _autoScroll.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _showTimestamps = MutableStateFlow(true)
    val showTimestamps: StateFlow<Boolean> = _showTimestamps.asStateFlow()

    private val _showAgentLabels = MutableStateFlow(true)
    val showAgentLabels: StateFlow<Boolean> = _showAgentLabels.asStateFlow()

    private val _maxVisibleMessages = MutableStateFlow(200)
    val maxVisibleMessages: StateFlow<Int> = _maxVisibleMessages.asStateFlow()

    // ===== 侧边栏 =====

    private val _sidebarOpen = MutableStateFlow(false)
    val sidebarOpen: StateFlow<Boolean> = _sidebarOpen.asStateFlow()

    private val _sidebarTab = MutableStateFlow(SidebarTab.SESSIONS)
    val sidebarTab: StateFlow<SidebarTab> = _sidebarTab.asStateFlow()

    // ===== 工具栏 =====

    private val _toolbarVisible = MutableStateFlow(true)
    val toolbarVisible: StateFlow<Boolean> = _toolbarVisible.asStateFlow()

    private val _modeSelectorOpen = MutableStateFlow(false)
    val modeSelectorOpen: StateFlow<Boolean> = _modeSelectorOpen.asStateFlow()

    // ===== 吉祥物 =====

    private val _mascotVisible = MutableStateFlow(true)
    val mascotVisible: StateFlow<Boolean> = _mascotVisible.asStateFlow()

    private val _mascotPosition = MutableStateFlow(MascotPosition.BOTTOM_RIGHT)
    val mascotPosition: StateFlow<MascotPosition> = _mascotPosition.asStateFlow()

    private val _mascotSize = MutableStateFlow(MascotSize.MEDIUM)
    val mascotSize: StateFlow<MascotSize> = _mascotSize.asStateFlow()

    // ===== 面板布局 =====

    private val _panelLayout = MutableStateFlow(PanelLayout.SINGLE)
    val panelLayout: StateFlow<PanelLayout> = _panelLayout.asStateFlow()

    private val _activePanelIndex = MutableStateFlow(0)
    val activePanelIndex: StateFlow<Int> = _activePanelIndex.asStateFlow()

    private val _splitRatio = MutableStateFlow(0.5f)
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    // ===== 搜索 =====

    private val _searchMode = MutableStateFlow(false)
    val searchMode: StateFlow<Boolean> = _searchMode.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TerminalMessage>>(emptyList())
    val searchResults: StateFlow<List<TerminalMessage>> = _searchResults.asStateFlow()

    // ===== 输入栏操作 =====

    fun updateInput(text: String) {
        _inputText.value = text
        if (text.isBlank()) {
            _showCompletions.value = false
        }
    }

    fun appendInput(text: String) {
        _inputText.value = _inputText.value + text
    }

    fun clearInput() {
        _inputText.value = ""
        _showCompletions.value = false
        _completions.value = emptyList()
    }

    fun setInputMode(mode: TerminalInputMode) {
        _inputMode.value = mode
    }

    fun showCompletions(suggestions: List<String>) {
        _completions.value = suggestions
        _showCompletions.value = suggestions.isNotEmpty()
        _selectedCompletionIndex.value = 0
    }

    fun hideCompletions() {
        _showCompletions.value = false
        _completions.value = emptyList()
    }

    fun selectNextCompletion() {
        if (_completions.value.isEmpty()) return
        _selectedCompletionIndex.value = (_selectedCompletionIndex.value + 1) % _completions.value.size
    }

    fun selectPrevCompletion() {
        if (_completions.value.isEmpty()) return
        _selectedCompletionIndex.value = if (_selectedCompletionIndex.value <= 0) _completions.value.size - 1 else _selectedCompletionIndex.value - 1
    }

    fun getSelectedCompletion(): String? {
        return _completions.value.getOrNull(_selectedCompletionIndex.value)
    }

    fun setInputHistory(history: List<String>) {
        _inputHistory.value = history
        _historyIndex.value = -1
    }

    fun navigateHistoryUp(): String? {
        val history = _inputHistory.value
        if (history.isEmpty()) return null
        val newIndex = if (_historyIndex.value == -1) history.size - 1 else (_historyIndex.value - 1).coerceAtLeast(0)
        _historyIndex.value = newIndex
        return history.getOrNull(newIndex)
    }

    fun navigateHistoryDown(): String? {
        val history = _inputHistory.value
        if (history.isEmpty()) return null
        if (_historyIndex.value == -1) return null
        val newIndex = _historyIndex.value + 1
        if (newIndex >= history.size) {
            _historyIndex.value = -1
            return ""
        }
        _historyIndex.value = newIndex
        return history.getOrNull(newIndex)
    }

    // ===== 消息流操作 =====

    fun setAutoScroll(enabled: Boolean) {
        _autoScroll.value = enabled
        if (enabled) _unreadCount.value = 0
    }

    fun incrementUnread() {
        if (!_autoScroll.value) _unreadCount.value = _unreadCount.value + 1
    }

    fun clearUnread() {
        _unreadCount.value = 0
    }

    fun toggleTimestamps() {
        _showTimestamps.value = !_showTimestamps.value
    }

    fun toggleAgentLabels() {
        _showAgentLabels.value = !_showAgentLabels.value
    }

    // ===== 侧边栏操作 =====

    fun toggleSidebar() {
        _sidebarOpen.value = !_sidebarOpen.value
    }

    fun openSidebar(tab: SidebarTab = _sidebarTab.value) {
        _sidebarOpen.value = true
        _sidebarTab.value = tab
    }

    fun closeSidebar() {
        _sidebarOpen.value = false
    }

    fun setSidebarTab(tab: SidebarTab) {
        _sidebarTab.value = tab
    }

    // ===== 工具栏操作 =====

    fun toggleToolbar() {
        _toolbarVisible.value = !_toolbarVisible.value
    }

    fun toggleModeSelector() {
        _modeSelectorOpen.value = !_modeSelectorOpen.value
    }

    fun closeModeSelector() {
        _modeSelectorOpen.value = false
    }

    // ===== 吉祥物操作 =====

    fun toggleMascot() {
        _mascotVisible.value = !_mascotVisible.value
    }

    fun setMascotPosition(position: MascotPosition) {
        _mascotPosition.value = position
    }

    fun setMascotSize(size: MascotSize) {
        _mascotSize.value = size
    }

    // ===== 面板操作 =====

    fun setPanelLayout(layout: PanelLayout) {
        _panelLayout.value = layout
    }

    fun setActivePanel(index: Int) {
        _activePanelIndex.value = index
    }

    fun setSplitRatio(ratio: Float) {
        _splitRatio.value = ratio.coerceIn(0.2f, 0.8f)
    }

    // ===== 搜索操作 =====

    fun enterSearchMode() {
        _searchMode.value = true
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun exitSearchMode() {
        _searchMode.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSearchResults(results: List<TerminalMessage>) {
        _searchResults.value = results
    }
}

/**
 * 侧边栏标签页。
 */
enum class SidebarTab(val displayName: String, val icon: String) {
    SESSIONS("会话", "📂"),
    AGENTS("Agent", "🤖"),
    HISTORY("历史", "📜"),
    MACROS("宏", "▶️"),
    SETTINGS("设置", "⚙️")
}

/**
 * 吉祥物位置。
 */
enum class MascotPosition(val displayName: String) {
    BOTTOM_RIGHT("右下"),
    BOTTOM_LEFT("左下"),
    TOP_RIGHT("右上"),
    TOP_LEFT("左上"),
    FLOATING("浮动")
}

/**
 * 吉祥物大小。
 */
enum class MascotSize(val displayName: String, val scale: Float) {
    SMALL("小", 0.6f),
    MEDIUM("中", 1.0f),
    LARGE("大", 1.4f)
}

/**
 * 面板布局。
 */
enum class PanelLayout(val displayName: String, val icon: String) {
    SINGLE("单面板", "▢"),
    HORIZONTAL_SPLIT("水平分屏", "▤"),
    VERTICAL_SPLIT("垂直分屏", "▥"),
    TABS("标签页", "▦")
}
