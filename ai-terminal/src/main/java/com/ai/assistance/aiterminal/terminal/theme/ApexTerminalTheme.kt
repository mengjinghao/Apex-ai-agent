package com.ai.assistance.aiterminal.terminal.theme

import androidx.compose.ui.graphics.Color

/**
 * Apex 终端主题。
 *
 * 独特配色方案，灵感来自深海与极光，与 Claude Code（橙色）、
 * Kimi Code（蓝灰）、OpenCode（绿色）完全不同。
 *
 * 主色调：深邃靛蓝 + 电光青 + 珊瑚粉点缀
 * - 背景：深空黑（略带靛蓝）
 * - 前景：柔和白
 * - 主色：电光青（#00E5FF）
 * - 强调：珊瑚粉（#FF6B9D）
 * - 成功：薄荷绿
 * - 警告：琥珀金
 * - 错误：玫瑰红
 */
object ApexTerminalTheme {

    // === 深海极光配色 ===
    val background = Color(0xFF0A0E1A)       // 深空黑（靛蓝调）
    val surface = Color(0xFF111827)          // 面板背景
    val surfaceVariant = Color(0xFF1A2332)   // 次级面板
    val foreground = Color(0xFFE2E8F0)       // 柔和白
    val foregroundMuted = Color(0xFF94A3B8)  // 次要文字
    val foregroundDim = Color(0xFF64748B)    // 暗淡文字

    // === 主色 ===
    val primary = Color(0xFF00E5FF)          // 电光青
    val primaryDim = Color(0xFF00B8D4)       // 暗电光青
    val accent = Color(0xFFFF6B9D)           // 珊瑚粉
    val accentDim = Color(0xFFE91E63)        // 暗珊瑚粉

    // === 语义色 ===
    val success = Color(0xFF4ADE80)          // 薄荷绿
    val warning = Color(0xFFFBBF24)          // 琥珀金
    val error = Color(0xFFEF4444)            // 玫瑰红
    val info = Color(0xFF60A5FA)             // 天空蓝

    // === 终端专用 ===
    val terminalBg = Color(0xFF0A0E1A)       // 终端背景
    val terminalCursor = Color(0xFF00E5FF)   // 光标色
    val terminalSelection = Color(0xFF00B8D4).copy(alpha = 0.3f)  // 选中色

    // === ANSI 16 色映射 ===
    val ansiBlack = Color(0xFF1A2332)
    val ansiRed = Color(0xFFEF4444)
    val ansiGreen = Color(0xFF4ADE80)
    val ansiYellow = Color(0xFFFBBF24)
    val ansiBlue = Color(0xFF60A5FA)
    val ansiMagenta = Color(0xFFFF6B9D)
    val ansiCyan = Color(0xFF00E5FF)
    val ansiWhite = Color(0xFFE2E8F0)
    val ansiBrightBlack = Color(0xFF64748B)
    val ansiBrightRed = Color(0xFFEF4444)
    val ansiBrightGreen = Color(0xFF4ADE80)
    val ansiBrightYellow = Color(0xFFFBBF24)
    val ansiBrightBlue = Color(0xFF60A5FA)
    val ansiBrightMagenta = Color(0xFFFF6B9D)
    val ansiBrightCyan = Color(0xFF00E5FF)
    val ansiBrightWhite = Color(0xFFFFFFFF)

    /**
     * ANSI 颜色码到 Color 的映射表。
     */
    val ansiColorMap: Map<Int, Color> = mapOf(
        0 to ansiBlack, 1 to ansiRed, 2 to ansiGreen, 3 to ansiYellow,
        4 to ansiBlue, 5 to ansiMagenta, 6 to ansiCyan, 7 to ansiWhite,
        8 to ansiBrightBlack, 9 to ansiBrightRed, 10 to ansiBrightGreen,
        11 to ansiBrightYellow, 12 to ansiBrightBlue, 13 to ansiBrightMagenta,
        14 to ansiBrightCyan, 15 to ansiBrightWhite
    )

    /**
     * Agent 角色色。
     * 不同 Agent 使用不同颜色标识，便于在多 Agent 终端中区分。
     */
    object AgentColors {
        val supervisor = Color(0xFF00E5FF)    // 主管 Agent — 电光青
        val worker = Color(0xFF4ADE80)        // 执行 Agent — 薄荷绿
        val reviewer = Color(0xFFFBBF24)      // 审查 Agent — 琥珀金
        val critic = Color(0xFFFF6B9D)        // 批评 Agent — 珊瑚粉
        val observer = Color(0xFF60A5FA)      // 观察 Agent — 天空蓝
        val system = Color(0xFF94A3B8)        // 系统 — 灰色

        /**
         * 按 Agent 角色名获取颜色。
         */
        fun forRole(role: String): Color = when (role.lowercase()) {
            "supervisor", "主管" -> supervisor
            "worker", "执行" -> worker
            "reviewer", "审查" -> reviewer
            "critic", "批评" -> critic
            "observer", "观察" -> observer
            "system", "系统" -> system
            else -> Color(0xFF00E5FF)
        }
    }

    /**
     * 狂暴模式状态色。
     */
    object BurstColors {
        val idle = Color(0xFF64748B)          // 空闲 — 灰
        val thinking = Color(0xFF60A5FA)      // 思考 — 蓝
        val executing = Color(0xFF00E5FF)     // 执行 — 电光青
        val paused = Color(0xFFFBBF24)        // 暂停 — 金
        val failed = Color(0xFFEF4444)        // 失败 — 红
        val berserk = Color(0xFFFF6B9D)       // 狂暴 — 珊瑚粉（脉冲动画）

        fun forState(state: String): Color = when (state.lowercase()) {
            "idle", "stopped" -> idle
            "thinking", "initializing" -> thinking
            "executing", "running", "active" -> executing
            "paused" -> paused
            "failed", "error" -> failed
            "berserk", "extreme" -> berserk
            else -> idle
        }
    }
}
