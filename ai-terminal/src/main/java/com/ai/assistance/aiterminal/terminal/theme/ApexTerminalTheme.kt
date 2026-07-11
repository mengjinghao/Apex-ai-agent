package com.ai.assistance.aiterminal.terminal.theme

import androidx.compose.ui.graphics.Color

object ApexTerminalTheme {
    val bgColor = Color(0xFF0D1117)
    val fgColor = Color(0xFFC9D1D9)
    val accentColor = Color(0xFF58A6FF)
    val successColor = Color(0xFF3FB950)
    val errorColor = Color(0xFFF85149)
    val warningColor = Color(0xFFD29922)
    val infoColor = Color(0xFF79C0FF)
    val dimColor = Color(0xFF484F58)
    fun colorForType(type: String): Color = when (type) { "COMMAND"->accentColor; "OUTPUT"->fgColor; "ERROR"->errorColor; "SYSTEM"->dimColor; "AGENT"->successColor; "BURST"->warningColor; "SUCCESS"->successColor; "WARNING"->warningColor; "INFO"->infoColor; "DIVIDER"->dimColor; else->fgColor }
}
