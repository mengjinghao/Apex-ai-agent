package com.ai.assistance.aiterminal.terminal.ai

/**
 * Sanitizes terminal output to neutralize dangerous escape sequences.
 *
 * Strips:
 * - OSC 52 (clipboard read/write): ESC ] 52 ; ... ST  (silent clipboard exfiltration vector)
 * - OSC 8 (hyperlinks): ESC ] 8 ;; URI ST  (can render misleading links / hide destination)
 * - OSC 0/1/2 (title bar set): ESC ] 0/1/2 ; ... ST  (title-bar spoofing, "Don't Trust This Title" attack)
 * - DECRQSS echoback: ESC [ ? ... $ p  (terminal responds with private-mode state, fingerprinting)
 * - DECRQCRA cursor checksum: ESC [ ... * y  (echoback used for fingerprinting / screen scraping)
 * - DCS (Device Control String) payloads: ESC P ... ST  (xterm Sixel/DECRQSS echoback carrier)
 * - ANSI "title report" response ESC ] l ... ST (response-injection / log-spoofing vector)
 *
 * Preserves (safe / required for normal terminal function):
 * - SGR (color/style): ESC [ ... m
 * - Cursor movement: ESC [ ... A/B/C/D/H/f/G/d/e
 * - Erase: ESC [ ... J/K
 * - Scroll: ESC [ ... S/T
 * - Mode set/reset (DECSET/DECRST): ESC [ ? ... h/l  (needed for bracketed paste, mouse, etc.)
 *
 * Security context:
 *   - dgl.cx 2023 terminal security research (10 CVEs in xterm/iTerm2/mintty/Kitty/WezTerm/etc.)
 *     https://dgl.cx/2023/09/ansi-terminal-security
 *   - CyberArk "Don't Trust This Title" (title-bar spoofing via OSC 0)
 *     https://www.cyberark.com/resources/threat-research-blog/dont-trust-this-title-abusing-terminal-emulators-with-ansi-escape-characters
 *   - mintty OSC 52 issue #1264 (clipboard poisoning)
 *     https://github.com/mintty/mintty/issues/1264
 *
 * The sanitizer is intentionally conservative — it strips only sequences that are
 * (a) widely considered dangerous in terminal security literature AND
 * (b) not needed for normal interactive shell / CLI usage.
 *
 * It is applied to:
 *   - Shell output returned to AI agents (prevents prompt injection via escape sequences)
 *   - Shell output stored in command history / summaries (prevents storage of clipboard-exfil
 *     payloads that might be replayed later)
 *
 * NOTE: This is a regex-based sanitizer, NOT a state machine. It is sufficient for the
 * "strip dangerous sequences before display/storage" use case but is NOT a substitute
 * for a proper VT100 state machine when actually rendering terminal output. See
 * terminal-emulator's TerminalEmulator.java for the production renderer.
 */
object TerminalOutputSanitizer {
    private const val ESC = '\u001B'
    private const val BEL = '\u0007'
    private const val ST = "\u001B\\"  // String Terminator: ESC \

    // Dangerous OSC sequences: ESC ] (0|1|2|52|8) ; ... BEL or ST (ESC \)
    // - OSC 0/1/2 = set window/icon title (title-bar spoofing attack vector)
    // - OSC 52    = clipboard set/query (silent exfiltration + poisoning)
    // - OSC 8     = hyperlink (can hide destination URL, social-engineering vector)
    //
    // Match: ESC ] (one of the listed codes) ; (any chars except BEL/ESC) (BEL | ST)
    // The "any chars except BEL/ESC" restriction prevents the regex from running past
    // a missing terminator and consuming unrelated text. If a sequence is malformed
    // (no terminator), we leave it alone rather than risk over-stripping.
    private val dangerousOscPattern = Regex(
        """\u001B\](0|1|2|8|52);[^\u0007\u001B]*(\u0007|\u001B\\)"""
    )

    // DECRQSS echoback response: ESC [ ? <params> $ p
    // The terminal emits this in response to a "Restore Private Mode" query; an attacker
    // who can write to the terminal can craft a query that triggers an echoback containing
    // the terminal's private-mode state (fingerprinting / side-channel).
    private val decrqssPattern = Regex("""\u001B\[\?[0-9;]*\${'$'}p""")

    // DECRQCRA cursor checksum response: ESC [ <params> * y
    // Same idea: an attacker can request a checksum of a screen region and read it back,
    // effectively scraping screen contents (including prompts, secrets typed at the prompt,
    // etc.) even without read access to the PTY.
    private val decrqcraPattern = Regex("""\u001B\[[0-9;]*\*y""")

    // DCS (Device Control String): ESC P ... ST
    // Used to carry Sixel graphics, DECRQSS responses, and xterm's "set terminfo" payload.
    // We strip the entire DCS payload because (a) the AI agent never needs to render Sixel,
    // and (b) DCS-responses are an echoback vector similar to DECRQSS.
    private val dcsPattern = Regex("""\u001BP[^\u0007\u001B]*(\u0007|\u001B\\)""")

    // Title-report response: ESC ] l <title> ST  (response to ESC [ 21 t)
    // Even if we never REQUEST the title (we don't), an attacker can inject a fake response
    // to confuse log parsers. Strip it defensively.
    private val titleReportPattern = Regex("""\u001B\]l[^\u0007\u001B]*(\u0007|\u001B\\)""")

    /**
     * Sanitize terminal output by stripping dangerous escape sequences.
     *
     * @param output raw PTY output (may contain ANSI/VT escape sequences)
     * @return sanitized output safe for display/storage/LLM consumption
     */
    fun sanitize(output: String): String {
        if (output.isEmpty()) return output
        var result = output
        result = result.replace(dangerousOscPattern, "")
        result = result.replace(titleReportPattern, "")
        result = result.replace(decrqssPattern, "")
        result = result.replace(decrqcraPattern, "")
        result = result.replace(dcsPattern, "")
        return result
    }

    /**
     * Check if output contains any dangerous escape sequences.
     * Useful for logging / alerting — does not modify the input.
     */
    fun containsDangerousSequences(output: String): Boolean {
        if (output.isEmpty()) return false
        return dangerousOscPattern.containsMatchIn(output) ||
               titleReportPattern.containsMatchIn(output) ||
               decrqssPattern.containsMatchIn(output) ||
               decrqcraPattern.containsMatchIn(output) ||
               dcsPattern.containsMatchIn(output)
    }
}
