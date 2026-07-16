package com.apex.agent.presentation.enhancedterminal.data

import androidx.compose.ui.graphics.Color

object AnsiParser {
    data class Segment(
        val text: String,
        val color: Color? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
    )

    private val ansiRegex = Regex("\u001B\\[([0-9;]*)m")
    private val basicColors = mapOf(
        0 to Color(0xFF000000), 1 to Color(0xFFCC0000), 2 to Color(0xFF4E9A06),
        3 to Color(0xFFC4A000), 4 to Color(0xFF3465A4), 5 to Color(0xFF75507B),
        6 to Color(0xFF06989A), 7 to Color(0xFFD3D7CF),
    )
    private val brightColors = mapOf(
        0 to Color(0xFF555753), 1 to Color(0xFFEF2929), 2 to Color(0xFF8AE234),
        3 to Color(0xFFFCE94F), 4 to Color(0xFF729FCF), 5 to Color(0xFFAD7FA8),
        6 to Color(0xFF34E2E2), 7 to Color(0xFFFFFFFF),
    )

    fun parse(text: String): List<Segment> {
        if (!text.contains("\u001B[")) return listOf(Segment(text = text))
        val segments = mutableListOf<Segment>()
        var pos = 0
        var currentColor: Color? = null
        var bold = false; var italic = false; var underline = false
        ansiRegex.findAll(text).forEach { match ->
            if (match.range.first > pos) {
                val plain = text.substring(pos, match.range.first)
                if (plain.isNotEmpty()) segments.add(Segment(plain, currentColor, bold, italic, underline))
            }
            val codes = match.groupValues[1].split(";").filter { it.isNotEmpty() }
            var i = 0
            while (i < codes.size) {
                val code = codes[i].toIntOrNull() ?: 0
                when {
                    code == 0 -> { currentColor = null; bold = false; italic = false; underline = false }
                    code == 1 -> bold = true; code == 3 -> italic = true; code == 4 -> underline = true
                    code == 22 -> bold = false; code == 23 -> italic = false; code == 24 -> underline = false
                    code in 30..37 -> currentColor = basicColors[code - 30]
                    code == 38 -> { val n = codes.getOrNull(i+2)?.toIntOrNull(); if (n != null) { currentColor = color256(n); i += 2 } }
                    code == 39 -> currentColor = null
                    code in 90..97 -> currentColor = brightColors[code - 90]
                }
                i++
            }
            pos = match.range.last + 1
        }
        if (pos < text.length) segments.add(Segment(text.substring(pos), currentColor, bold, italic, underline))
        return segments
    }

    private fun color256(n: Int): Color = when {
        n < 8 -> basicColors[n] ?: Color.White
        n < 16 -> brightColors[n - 8] ?: Color.White
        n < 232 -> { val idx = n - 16; val r = idx / 36; val g = (idx % 36) / 6; val b = idx % 6
            Color(if (r==0) 0 else 40+r*40, if (g==0) 0 else 40+g*40, if (b==0) 0 else 40+b*40) }
        else -> { val v = 8 + (n - 232) * 10; Color(v, v, v) }
    }
}
