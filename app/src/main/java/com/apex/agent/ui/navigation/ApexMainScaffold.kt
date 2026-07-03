package com.apex.agent.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/**
 * 主界面脚手架 — Material You 3 NavigationBar（底部 4 Tab）。
 *
 * Tab：Agent / 套件 / 诊断 / 设置
 * 狂暴模式和多 Agent 协作在各自 APK 内使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApexMainScaffold() {
    var currentTab by remember { mutableStateOf(ApexTab.CHAT) }
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ApexTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.description) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                        alwaysShowLabel = true
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        when (currentTab) {
            ApexTab.CHAT -> com.apex.agent.ui.screens.chat.ChatScreen(
                modifier = Modifier.padding(innerPadding)
            )
            ApexTab.SUITE -> com.apex.agent.ui.screens.suite.SuiteScreen(
                modifier = Modifier.padding(innerPadding)
            )
            ApexTab.DIAGNOSTICS -> com.apex.agent.ui.screens.diagnostics.DiagnosticsScreen(
                modifier = Modifier.padding(innerPadding)
            )
            ApexTab.SETTINGS -> com.apex.agent.ui.screens.settings.SettingsScreen(
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}
