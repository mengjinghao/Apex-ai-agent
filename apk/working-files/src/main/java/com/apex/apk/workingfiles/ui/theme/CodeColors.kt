package com.apex.apk.workingfiles.ui.theme

import androidx.compose.ui.graphics.Color

/** VSCode Dark+ 配色（参考官方 dark_vs.json） */
object CodeColors {
    // 背景
    val EditorBackground = Color(0xFF1E1E1E)
    val EditorForeground = Color(0xFFD4D4D4)
    val LineNumberBackground = Color(0xFF1E1E1E)
    val LineNumberForeground = Color(0xFF858585)
    val ActiveLineBackground = Color(0xFF2A2D2E)
    val SelectionBackground = Color(0xFF264F78)
    val GutterBackground = Color(0xFF1E1E1E)

    // 语法高亮（Dark+ 主题）
    val Keyword = Color(0xFF569CD6)        // blue
    val String = Color(0xFFCE9178)         // orange
    val Comment = Color(0xFF6A9955)        // green
    val Number = Color(0xFFB5CEA8)         // light green
    val Operator = Color(0xFFD4D4D4)       // default
    val Identifier = Color(0xFF9CDCFE)     // light blue
    val Function = Color(0xFFDCDCAA)       // yellow
    val Type = Color(0xFF4EC9B0)           // teal
    val Variable = Color(0xFF9CDCFE)       // light blue

    // Diff
    val DiffAdded = Color(0xFF2EA043)       // green bg
    val DiffAddedFg = Color(0xFF7EE787)
    val DiffRemoved = Color(0xFFDA3633)     // red bg
    val DiffRemovedFg = Color(0xFFFF7B72)
    val DiffContext = Color(0xFFD4D4D4)
    val DiffHeader = Color(0xFF858585)

    // 文件树
    val TreeBackground = Color(0xFF252526)
    val TreeItemHover = Color(0xFF2A2D2E)
    val TreeItemSelected = Color(0xFF04395E)
    val TreeDirectory = Color(0xFFDCDCAA)   // 黄色目录名
    val TreeFile = Color(0xFFD4D4D4)

    // 时间线
    val TimelineLine = Color(0xFF3C3C3C)
    val TimelineDot = Color(0xFF007ACC)
    val TimelineDotError = Color(0xFFFF5252)
    val TimelineDotSuccess = Color(0xFF4EC9B0)

    // 通用
    val Surface = Color(0xFF252526)
    val SurfaceVariant = Color(0xFF2D2D2D)
    val Primary = Color(0xFF007ACC)
    val OnPrimary = Color(0xFFFFFFFF)
    val Error = Color(0xFFFF5252)
    val Warning = Color(0xFFFFA726)
    val Success = Color(0xFF4EC9B0)
    val Disabled = Color(0xFF5A5A5A)
}
