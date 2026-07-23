package com.ai.assistance.aiterminal.terminal.ai

/**
 * Shell escaping utilities — single source of truth for safe single-quote escaping.
 *
 * Standard POSIX technique: replace `'` with `'\''` so the string can be safely
 * interpolated inside a single-quoted shell argument.
 *
 * Used by:
 * - [com.ai.assistance.aiterminal.terminal.ai.TerminalToolExecutor.escapeShellCommand]
 * - [com.ai.assistance.aiterminal.terminal.bridge.TerminalBridge.escapeShellSingleQuote]
 */
object ShellEscape {
    /**
     * Escape a string for safe interpolation inside single quotes in a shell command.
     * Example: `it's` → `it'\''s`
     */
    fun singleQuote(input: String): String = input.replace("'", "'\\''")
}
