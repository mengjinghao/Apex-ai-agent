package com.ai.assistance.aiterminal.terminal.ui.viewmodel

import com.ai.assistance.aiterminal.terminal.burst.BurstTerminalIntegration
import com.ai.assistance.aiterminal.terminal.bridge.TerminalBridge
import com.ai.assistance.aiterminal.terminal.bridge.MultiAgentTerminalCoordinator
import com.ai.assistance.aiterminal.terminal.mascot.MascotTerminalIntegration
import com.ai.assistance.aiterminal.terminal.multiagent.MultiAgentTerminalAdapter
import com.ai.assistance.aiterminal.terminal.multiagent.TerminalAgentRole
import com.ai.assistance.aiterminal.terminal.ui.*
import com.apex.agent.presentation.multiagent.state.MultiAgentPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 终端交互控制器。
 *
 * 处理用户输入、快捷键、命令补全触发、模式切换等交互逻辑。
 * 连接 [TerminalViewState]（UI 状态）和 [ApexTerminal]（终端核心）。
 *
 * # 职责
 *
 * - 输入提交：用户按回车时提交命令
 * - 快捷键处理：Tab 补全、上下历史、Ctrl+L 清屏等
 * - 模式切换：Shell → Agent → Multi → Burst
 * - 补全触发：输入变化时触发补全
 * - 搜索：Ctrl+R 进入搜索模式
 * - 面板切换：分屏/标签页
 *
 * # 使用示例
 *
 * ```
 * val controller = TerminalInteractionController(
 *     terminal, viewState, sessionManager,
 *     completer, macroRecorder, mascotIntegration
 * )
 *
 * // 用户输入
 * controller.onInputChange("ls")
 *
 * // 用户按回车
 * controller.onSubmit()
 *
 * // 用户按 Tab
 * controller.onTabKey()
 *
 * // 用户按上箭头
 * controller.onArrowUp()
 * ```
 */
class TerminalInteractionController(
    private val terminal: ApexTerminal,
    private val viewState: TerminalViewState,
    private val sessionManager: TerminalSessionManager,
    private val completer: TerminalCommandCompleter,
    private val macroRecorder: TerminalMacroRecorder,
    private val mascotIntegration: MascotTerminalIntegration
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 当前活跃会话 ID。
     */
    private val currentSessionId: String?
        get() = sessionManager.getActiveSession()?.id

    /**
     * 输入变化时触发。
     *
     * - 更新输入文本
     * - 触发命令补全
     */
    fun onInputChange(text: String) {
        viewState.updateInput(text)

        // 触发补全
        if (text.isNotBlank() && viewState.inputMode.value == TerminalInputMode.SHELL) {
            val suggestions = completer.complete(text)
            if (suggestions.isNotEmpty()) {
                viewState.showCompletions(suggestions)
            } else {
                viewState.hideCompletions()
            }
        } else {
            viewState.hideCompletions()
        }
    }

    /**
     * 用户提交输入（按回车）。
     */
    fun onSubmit() {
        val text = viewState.inputText.value
        if (text.isBlank()) return

        val sessionId = currentSessionId ?: return

        // 展开别名
        val expanded = completer.expandAlias(text)

        // 添加到历史
        completer.addToHistory(expanded)
        viewState.setInputHistory(completer.getAllCommands().takeLast(50))

        // 如果在录制宏，记录命令
        if (macroRecorder.isRecording()) {
            macroRecorder.record(expanded)
        }

        // 清空输入
        viewState.clearInput()

        // 通过终端处理命令
        scope.launch {
            terminal.input(sessionId, expanded)
        }

        // 吉祥物反应
        val mode = sessionManager.getSession(sessionId)?.inputMode
        when (mode) {
            TerminalInputMode.SHELL -> mascotIntegration.backToIdle(sessionId)
            TerminalInputMode.AGENT -> mascotIntegration.playThinking(sessionId)
            TerminalInputMode.BURST -> mascotIntegration.onBurstPhaseChanged(sessionId, "executing")
            else -> {}
        }
    }

    /**
     * Tab 键 — 补全。
     */
    fun onTabKey() {
        if (viewState.showCompletions.value && viewState.completions.value.isNotEmpty()) {
            // 选择当前补全项
            val selected = viewState.getSelectedCompletion()
            if (selected != null) {
                viewState.updateInput(selected + " ")
                viewState.hideCompletions()
            }
        } else {
            // 触发补全
            val text = viewState.inputText.value
            if (text.isNotBlank()) {
                val suggestions = completer.complete(text)
                if (suggestions.isNotEmpty()) {
                    viewState.showCompletions(suggestions)
                }
            }
        }
    }

    /**
     * 上箭头 — 历史导航。
     */
    fun onArrowUp() {
        if (viewState.searchMode.value) return
        val cmd = viewState.navigateHistoryUp()
        if (cmd != null) {
            viewState.updateInput(cmd)
        }
    }

    /**
     * 下箭头 — 历史导航。
     */
    fun onArrowDown() {
        if (viewState.searchMode.value) return
        val cmd = viewState.navigateHistoryDown()
        if (cmd != null) {
            viewState.updateInput(cmd)
        }
    }

    /**
     * Ctrl+L — 清屏。
     */
    fun onCtrlL() {
        val sessionId = currentSessionId ?: return
        sessionManager.clearMessages(sessionId)
        sessionManager.sendMessage(sessionId, TerminalMessage.system("Screen cleared"))
    }

    /**
     * Ctrl+K — 删除到行尾。
     */
    fun onCtrlK() {
        // 简化实现：清空输入
        viewState.clearInput()
    }

    /**
     * Ctrl+U — 删除到行首。
     */
    fun onCtrlU() {
        viewState.clearInput()
    }

    /**
     * Ctrl+R — 搜索历史 / 录制宏。
     */
    fun onCtrlR() {
        if (macroRecorder.isRecording()) {
            // 如果在录制中，Ctrl+R 停止录制
            val count = macroRecorder.stopRecording()
            val sessionId = currentSessionId ?: return
            sessionManager.sendMessage(sessionId, TerminalMessage.success(
                "Macro recorded: ${macroRecorder.getRecordingName()} ($count commands)"
            ))
        } else {
            // 进入搜索模式
            viewState.enterSearchMode()
        }
    }

    /**
     * Ctrl+C — 中断。
     */
    fun onCtrlC() {
        viewState.clearInput()
        viewState.hideCompletions()
        val sessionId = currentSessionId ?: return
        sessionManager.sendMessage(sessionId, TerminalMessage.warning("^C"))
    }

    /**
     * Ctrl+N — 新建会话。
     */
    fun onCtrlN() {
        val session = terminal.createSession()
        viewState.setInputHistory(emptyList())
    }

    /**
     * Ctrl+M — 切换模式。
     */
    fun onCtrlM() {
        val sessionId = currentSessionId ?: return
        val session = sessionManager.getSession(sessionId) ?: return
        val nextMode = when (session.agentMode) {
            AgentMode.NONE -> AgentMode.SINGLE
            AgentMode.SINGLE -> AgentMode.MULTI
            AgentMode.MULTI -> AgentMode.BURST
            AgentMode.BURST -> AgentMode.NONE
        }
        sessionManager.setAgentMode(sessionId, nextMode)
        viewState.setInputMode(when (nextMode) {
            AgentMode.NONE -> TerminalInputMode.SHELL
            AgentMode.SINGLE -> TerminalInputMode.AGENT
            AgentMode.MULTI -> TerminalInputMode.AGENT
            AgentMode.BURST -> TerminalInputMode.BURST
        })
        mascotIntegration.onModeChanged(sessionId, nextMode)
    }

    /**
     * Ctrl+B — 启动狂暴模式。
     */
    fun onCtrlB() {
        val sessionId = currentSessionId ?: return
        val session = sessionManager.getSession(sessionId) ?: return
        if (session.agentMode != AgentMode.BURST) {
            sessionManager.setAgentMode(sessionId, AgentMode.BURST)
            viewState.setInputMode(TerminalInputMode.BURST)
            mascotIntegration.onModeChanged(sessionId, AgentMode.BURST)
        }
    }

    /**
     * Ctrl+P — 回放宏。
     */
    fun onCtrlP() {
        val macros = macroRecorder.getAllMacros()
        if (macros.isEmpty()) {
            val sessionId = currentSessionId ?: return
            sessionManager.sendMessage(sessionId, TerminalMessage.warning("No macros recorded"))
            return
        }
        // 回放第一个宏
        val firstName = macros.keys.first()
        val sessionId = currentSessionId ?: return
        sessionManager.sendMessage(sessionId, TerminalMessage.system("Playing macro: $firstName"))
        macroRecorder.play(firstName) { cmd ->
            scope.launch { terminal.input(sessionId, cmd) }
        }
    }

    /**
     * Ctrl+A — 显示 Agent 列表。
     */
    fun onCtrlA() {
        viewState.openSidebar(SidebarTab.AGENTS)
    }

    /**
     * Escape — 取消补全/搜索/模式选择。
     */
    fun onEscape() {
        viewState.hideCompletions()
        viewState.exitSearchMode()
        viewState.closeModeSelector()
    }

    /**
     * 搜索查询变化。
     */
    fun onSearchQueryChange(query: String) {
        viewState.updateSearchQuery(query)
        val sessionId = currentSessionId ?: return

        if (query.isBlank()) {
            viewState.setSearchResults(emptyList())
            return
        }

        // 搜索命令历史
        val historyResults = completer.search(query)
        val messages = historyResults.map { TerminalMessage.command(it) }
        viewState.setSearchResults(messages)
    }

    /**
     * 切换侧边栏。
     */
    fun toggleSidebar() {
        viewState.toggleSidebar()
    }

    /**
     * 切换面板布局。
     */
    fun cyclePanelLayout() {
        val next = when (viewState.panelLayout.value) {
            PanelLayout.SINGLE -> PanelLayout.HORIZONTAL_SPLIT
            PanelLayout.HORIZONTAL_SPLIT -> PanelLayout.VERTICAL_SPLIT
            PanelLayout.VERTICAL_SPLIT -> PanelLayout.TABS
            PanelLayout.TABS -> PanelLayout.SINGLE
        }
        viewState.setPanelLayout(next)
    }

    /**
     * 切换吉祥物显示。
     */
    fun toggleMascot() {
        viewState.toggleMascot()
    }

    /**
     * 处理快捷键。
     *
     * @param shortcut 快捷键
     * @return true 已处理
     */
    fun handleShortcut(shortcut: TerminalShortcut): Boolean {
        when (shortcut) {
            TerminalShortcut.TAB_COMPLETE -> onTabKey()
            TerminalShortcut.HISTORY_PREV -> onArrowUp()
            TerminalShortcut.HISTORY_NEXT -> onArrowDown()
            TerminalShortcut.CLEAR_SCREEN -> onCtrlL()
            TerminalShortcut.KILL_LINE -> onCtrlK()
            TerminalShortcut.BACKWARD_KILL -> onCtrlU()
            TerminalShortcut.SEARCH_HISTORY -> onCtrlR()
            TerminalShortcut.INTERRUPT -> onCtrlC()
            TerminalShortcut.NEW_SESSION -> onCtrlN()
            TerminalShortcut.TOGGLE_MODE -> onCtrlM()
            TerminalShortcut.START_BURST -> onCtrlB()
            TerminalShortcut.PLAY_MACRO -> onCtrlP()
            TerminalShortcut.SHOW_AGENTS -> onCtrlA()
            TerminalShortcut.EOF -> { /* 退出 */ }
            TerminalShortcut.SUSPEND -> { /* 挂起 */ }
            TerminalShortcut.SWITCH_SESSION -> { /* 切换会话 */ }
            TerminalShortcut.FORWARD_WORD -> { /* 前进词 */ }
            TerminalShortcut.BACKWARD_WORD -> { /* 后退词 */ }
            TerminalShortcut.RECORD_MACRO -> onCtrlR()
        }
        return true
    }
}
