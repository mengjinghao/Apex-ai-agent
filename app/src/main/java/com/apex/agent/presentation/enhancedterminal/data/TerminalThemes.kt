package com.apex.agent.presentation.enhancedterminal.data

import androidx.compose.ui.graphics.Color

data class TerminalTheme(
    val id: String, val name: String,
    val backgroundColor: Color, val foregroundColor: Color,
    val promptColor: Color, val outputColor: Color, val systemColor: Color,
    val errorColor: Color, val successColor: Color, val warningColor: Color,
    val infoColor: Color, val commentColor: Color,
) {
    fun colorFor(kind: LineKind): Color = when (kind) {
        LineKind.PROMPT -> promptColor; LineKind.OUTPUT -> outputColor; LineKind.SYSTEM -> systemColor
        LineKind.ERROR -> errorColor; LineKind.SUCCESS -> successColor; LineKind.WARNING -> warningColor
        LineKind.INFO -> infoColor; LineKind.COMMENT -> commentColor
    }
}

object TerminalThemes {
    val all = listOf(
        TerminalTheme("apex_dark","Apex Dark",Color(0xFF060912),Color(0xFFCBD5E1),Color(0xFF00E5FF),Color(0xFFCBD5E1),Color(0xFF60A5FA),Color(0xFFEF4444),Color(0xFF4ADE80),Color(0xFFFBBF24),Color(0xFF818CF8),Color(0xFF64748B)),
        TerminalTheme("dracula","Dracula",Color(0xFF282A36),Color(0xFFF8F8F2),Color(0xFFFF79C6),Color(0xFFF8F8F2),Color(0xFFBD93F9),Color(0xFFFF5555),Color(0xFF50FA7B),Color(0xFFF1FA8C),Color(0xFF8BE9FD),Color(0xFF6272A4)),
        TerminalTheme("solarized_dark","Solarized Dark",Color(0xFF002B36),Color(0xFF839496),Color(0xFF268BD2),Color(0xFF93A1A1),Color(0xFF268BD2),Color(0xFFDC322F),Color(0xFF859900),Color(0xFFB58900),Color(0xFF2AA198),Color(0xFF586E75)),
        TerminalTheme("monokai","Monokai",Color(0xFF272822),Color(0xFFF8F8F2),Color(0xFFF92672),Color(0xFFF8F8F2),Color(0xFF66D9EF),Color(0xFFF92672),Color(0xFFA6E22E),Color(0xFFE6DB74),Color(0xFFAE81FF),Color(0xFF75715E)),
        TerminalTheme("github_light","GitHub Light",Color(0xFFFFFFFF),Color(0xFF24292E),Color(0xFF0366D6),Color(0xFF24292E),Color(0xFF0366D6),Color(0xFFCB2431),Color(0xFF28A745),Color(0xFFD1A904),Color(0xFF6F42C1),Color(0xFF6A737D)),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
}
