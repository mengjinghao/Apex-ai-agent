package com.apex.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.apex.agent.kernel.burst.ui.BurstModeScreen
import com.apex.agent.kernel.burst.KernelState
import com.apex.agent.kernel.burst.ui.BurstLogEntry
import com.apex.agent.presentation.multiagent.ui.MultiAgentScreen
import com.apex.agent.presentation.multiagent.state.MultiAgentPageState
import com.apex.ui.terminal.TerminalScreen
import com.apex.ui.terminal.TerminalLine
import com.apex.ui.terminal.TerminalLineKind
import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot

/**
 * 主导航界面(Android)。
 *
 * 三个独立界面,底部导航切换:
 * - 终端界面(普通命令 + 水母)
 * - 狂暴模式界面(技能链 + 日志)
 * - 多 Agent 界面(拓扑图 + 协作)
 */
@Composable
fun MainNavigation(
    multiAgentState: MultiAgentPageState,
    modifier: Modifier = Modifier,
) {
    var currentTab by remember { mutableStateOf(NavTab.TERMINAL) }

    // 终端状态(mock,实际接入真实状态)
    var terminalLines by remember { mutableStateOf(listOf(TerminalLine("Apex Terminal v2.4.0 — Deep Sea Aurora", TerminalLineKind.SYSTEM))) }
    var inputText by remember { mutableStateOf("") }
    val terminalForm = AuraMascot.AuraForm.IDLE

    // 狂暴模式状态(mock)
    var kernelState by remember { mutableStateOf(KernelState.STOPPED) }
    val loadedSkills = remember { mutableStateOf(listOf("ReAct", "ToT", "Racing", "RedBlueAdversarial", "SelfCorrection")) }
    val burstLogs = remember { mutableStateOf(listOf<BurstLogEntry>()) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111827)) {
                NavTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Text(tab.icon, fontSize = 18.sp) },
                        label = { Text(tab.displayName, color = if (currentTab == tab) Color(0xFF00E5FF) else Color(0xFF94A3B8), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00E5FF),
                            indicatorColor = Color(0xFF1A2332),
                        ),
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0A0E1A)).padding(padding)) {
            when (currentTab) {
                NavTab.TERMINAL -> com.apex.agent.presentation.enhancedterminal.ui.EnhancedTerminalScreen()
                NavTab.BURST -> BurstModeScreen(
                    kernelState = kernelState,
                    loadedSkills = loadedSkills.value,
                    logs = burstLogs.value,
                    onStart = {
                        kernelState = KernelState.RUNNING
                        burstLogs.value = burstLogs.value + BurstLogEntry(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()), com.apex.agent.kernel.burst.ui.BurstLogLevel.INFO, "狂暴内核启动")
                    },
                    onPause = { kernelState = KernelState.PAUSED },
                    onStop = { kernelState = KernelState.STOPPED },
                    onBerserk = {
                        kernelState = KernelState.RUNNING
                        burstLogs.value = burstLogs.value + BurstLogEntry(java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date()), com.apex.agent.kernel.burst.ui.BurstLogLevel.BERSERK, "🔥 进入狂暴模式!")
                    },
                )
                NavTab.MULTI_AGENT -> MultiAgentScreen(state = multiAgentState)
            }
        }
    }
}

/** 导航 Tab */
enum class NavTab(val displayName: String, val icon: String) {
    TERMINAL("终端", "⌨️"),
    BURST("狂暴", "🔥"),
    MULTI_AGENT("协作", "🎭"),
}
