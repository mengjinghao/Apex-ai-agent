package com.apex.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class ApexRoute(val path: String, val title: String, val icon: ImageVector) {
    object Home : ApexRoute("home", "首页", Icons.Default.Home)
    object Chat : ApexRoute("chat", "对话", Icons.Default.Chat)
    object Settings : ApexRoute("settings", "设置", Icons.Default.Settings)

    companion object {
        val bottomNavItems = listOf(Home, Chat, Settings)
    }
}
