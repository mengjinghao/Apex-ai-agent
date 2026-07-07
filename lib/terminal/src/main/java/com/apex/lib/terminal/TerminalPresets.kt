package com.apex.lib.terminal

/**
 * 三种终端的默认配置预设。
 *
 * - [NORMAL]      — 普通 Agent 模式：默认 shell，无初始命令，缓冲 5000 行
 * - [MULTI_AGENT] — 多 Agent 模式：缓冲加大到 8000 行（多 Agent 输出量大）
 * - [RAGE]        — 狂暴模式：注入 APEX_RAGE 环境变量 + 启动横幅，缓冲 10000 行
 *
 * APK 可在调用 [TerminalEngine.createSession] 时覆盖工作目录 / 环境变量 / 初始命令；
 * 未覆盖的字段使用 [forType] 返回的预设值。
 */
object TerminalPresets {

    /**
     * 单个终端类型的预设。
     */
    data class Preset(
        val type: TerminalType,
        val defaultWorkingDir: String,
        val shell: String,
        val env: Map<String, String>,
        val initialCommands: List<String>,
        val bufferLineLimit: Int,
        val defaultRows: Int = 24,
        val defaultCols: Int = 80
    )

    /** 普通 Agent 模式预设。 */
    val NORMAL = Preset(
        type = TerminalType.NORMAL,
        defaultWorkingDir = "~",
        shell = "/system/bin/sh",
        env = mapOf(
            "TERM" to "xterm-256color",
            "PS1" to "\\$ ",
            "APEX_TERMINAL" to "1"
        ),
        initialCommands = emptyList(),
        bufferLineLimit = 5000
    )

    /** 多 Agent 模式预设。 */
    val MULTI_AGENT = Preset(
        type = TerminalType.MULTI_AGENT,
        defaultWorkingDir = "~",
        shell = "/system/bin/sh",
        env = mapOf(
            "TERM" to "xterm-256color",
            "PS1" to "[multi] \\$ ",
            "APEX_TERMINAL" to "1",
            "APEX_MULTI_AGENT" to "1"
        ),
        initialCommands = emptyList(),
        bufferLineLimit = 8000
    )

    /** 狂暴模式预设。 */
    val RAGE = Preset(
        type = TerminalType.RAGE,
        defaultWorkingDir = "~",
        shell = "/system/bin/sh",
        env = mapOf(
            "TERM" to "xterm-256color",
            "PS1" to "[rage] \\$ ",
            "APEX_TERMINAL" to "1",
            "APEX_RAGE" to "1"
        ),
        initialCommands = listOf("echo '[Apex Rage] 狂暴模式已启用'"),
        bufferLineLimit = 10000
    )

    /** 按类型取预设。 */
    fun forType(type: TerminalType): Preset = when (type) {
        TerminalType.NORMAL -> NORMAL
        TerminalType.MULTI_AGENT -> MULTI_AGENT
        TerminalType.RAGE -> RAGE
    }

    /** 全部预设（供 UI 展示）。 */
    val ALL: List<Preset> = listOf(NORMAL, MULTI_AGENT, RAGE)
}
