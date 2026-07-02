package com.ai.assistance.aiterminal.terminal.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * 终端命令补全器。
 *
 * 支持：
 * - 内置命令补全
 * - 命令历史补全
 * - 文件路径补全
 * - 别名补全
 * - Tab 键触发
 *
 * # 使用示例
 *
 * ```
 * val completer = TerminalCommandCompleter()
 * completer.registerBuiltin("help", "clear", "mode", "agents", "burst")
 * completer.registerAlias("ll", "ls -la")
 * completer.registerAlias("gs", "git status")
 *
 * // 补全
 * val suggestions = completer.complete("mo")  // ["mode"]
 * val suggestions = completer.complete("l")   // ["ll", "ls"]
 * ```
 */
class TerminalCommandCompleter {

    /** 内置命令。 */
    private val builtins = mutableSetOf<String>()

    /** 别名。 */
    private val aliases = ConcurrentHashMap<String, String>()

    /** 命令历史（用于补全）。 */
    private val history = mutableListOf<String>()

    /**
     * 注册内置命令。
     */
    fun registerBuiltin(vararg commands: String) {
        builtins.addAll(commands)
    }

    /**
     * 注册别名。
     *
     * @param alias 别名
     * @param command 实际命令
     */
    fun registerAlias(alias: String, command: String) {
        aliases[alias] = command
    }

    /**
     * 批量注册别名。
     */
    fun registerAliases(mapping: Map<String, String>) {
        aliases.putAll(mapping)
    }

    /**
     * 移除别名。
     */
    fun removeAlias(alias: String): Boolean {
        return aliases.remove(alias) != null
    }

    /**
     * 获取所有别名。
     */
    fun getAllAliases(): Map<String, String> = aliases.toMap()

    /**
     * 展开别名。
     *
     * @param input 用户输入
     * @return 展开后的命令（如果是别名），否则原样返回
     */
    fun expandAlias(input: String): String {
        val parts = input.split(" ", limit = 2)
        val cmd = parts[0]
        val args = parts.getOrNull(1) ?: ""
        val expanded = aliases[cmd] ?: return input
        return if (args.isNotBlank()) "$expanded $args" else expanded
    }

    /**
     * 添加命令到历史。
     */
    fun addToHistory(command: String) {
        if (command.isNotBlank()) {
            history.add(command)
            if (history.size > 1000) history.removeAt(0)
        }
    }

    /**
     * 补全。
     *
     * @param input 当前输入
     * @return 匹配的建议列表
     */
    fun complete(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val results = mutableSetOf<String>()

        // 内置命令
        builtins.filter { it.startsWith(input) }.forEach { results.add(it) }

        // 别名
        aliases.keys.filter { it.startsWith(input) }.forEach { results.add(it) }

        // 历史命令（包含输入的）
        history.filter { it.contains(input) }.takeLast(10).forEach { results.add(it) }

        return results.sorted()
    }

    /**
     * 补全并返回最佳匹配。
     *
     * @return 最佳匹配，无匹配返回 null
     */
    fun completeBest(input: String): String? {
        val suggestions = complete(input)
        return suggestions.firstOrNull()
    }

    /**
     * 获取所有可补全的命令。
     */
    fun getAllCommands(): List<String> {
        return (builtins + aliases.keys).sorted()
    }

    /**
     * 清空历史。
     */
    fun clearHistory() {
        history.clear()
    }

    companion object {
        /**
         * 创建带默认内置命令和别名的补全器。
         */
        fun createDefault(): TerminalCommandCompleter {
            return TerminalCommandCompleter().apply {
                registerBuiltin(
                    "help", "clear", "mode", "agents", "burst",
                    "sessions", "session", "theme", "status", "about", "echo",
                    "alias", "unalias", "history", "macro", "run"
                )
                registerAliases(mapOf(
                    "ll" to "ls -la",
                    "la" to "ls -a",
                    "gs" to "git status",
                    "gd" to "git diff",
                    "gc" to "git commit",
                    "gp" to "git push",
                    "gl" to "git log --oneline",
                    "cls" to "clear",
                    "h" to "help",
                    "st" to "status",
                    "ag" to "agents",
                    "br" to "burst"
                ))
            }
        }
    }
}

/**
 * 终端宏录制器。
 *
 * 录制一系列命令，保存为宏，后续可以一键回放。
 *
 * # 使用示例
 *
 * ```
 * val recorder = TerminalMacroRecorder()
 *
 * // 开始录制
 * recorder.startRecording("deploy")
 * recorder.record("git pull")
 * recorder.record("npm install")
 * recorder.record("npm run build")
 * recorder.stopRecording()
 *
 * // 回放
 * recorder.play("deploy") { command ->
 *     processor.process(sessionId, command)
 * }
 * ```
 */
class TerminalMacroRecorder {

    /** 录制中的宏名。 */
    private var recordingName: String? = null

    /** 录制中的命令列表。 */
    private val recordingCommands = mutableListOf<String>()

    /** 已保存的宏。 */
    private val macros = ConcurrentHashMap<String, List<String>>()

    /**
     * 开始录制宏。
     *
     * @param name 宏名
     * @return true 开始成功，false 已在录制中
     */
    fun startRecording(name: String): Boolean {
        if (recordingName != null) return false
        recordingName = name
        recordingCommands.clear()
        return true
    }

    /**
     * 录制一条命令。
     */
    fun record(command: String) {
        if (recordingName != null && command.isNotBlank()) {
            recordingCommands.add(command)
        }
    }

    /**
     * 停止录制并保存。
     *
     * @return 录制的命令数，-1 表示未在录制
     */
    fun stopRecording(): Int {
        val name = recordingName ?: return -1
        macros[name] = recordingCommands.toList()
        recordingName = null
        recordingCommands.clear()
        return macros[name]?.size ?: 0
    }

    /**
     * 取消录制。
     */
    fun cancelRecording() {
        recordingName = null
        recordingCommands.clear()
    }

    /**
     * 是否正在录制。
     */
    fun isRecording(): Boolean = recordingName != null

    /**
     * 获取录制中的宏名。
     */
    fun getRecordingName(): String? = recordingName

    /**
     * 回放宏。
     *
     * @param name 宏名
     * @param executor 命令执行器
     * @return true 回放成功，false 宏不存在
     */
    fun play(name: String, executor: (String) -> Unit): Boolean {
        val commands = macros[name] ?: return false
        for (cmd in commands) {
            executor(cmd)
        }
        return true
    }

    /**
     * 删除宏。
     */
    fun deleteMacro(name: String): Boolean {
        return macros.remove(name) != null
    }

    /**
     * 获取所有宏。
     */
    fun getAllMacros(): Map<String, List<String>> = macros.toMap()

    /**
     * 获取宏的命令数。
     */
    fun getMacroCommandCount(name: String): Int {
        return macros[name]?.size ?: 0
    }

    /**
     * 导出宏为 JSON。
     */
    fun exportMacros(): String {
        val sb = StringBuilder("{")
        macros.entries.forEachIndexed { index, (name, commands) ->
            if (index > 0) sb.append(",")
            sb.append("\"$name\":[")
            commands.forEachIndexed { i, cmd ->
                if (i > 0) sb.append(",")
                sb.append("\"${cmd.replace("\"", "\\\"")}\"")
            }
            sb.append("]")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 导入宏。
     *
     * @param json JSON 字符串
     * @return 导入的宏数
     */
    fun importMacros(json: String): Int {
        return try {
            val obj = org.json.JSONObject(json)
            var count = 0
            for (key in obj.keys()) {
                val arr = obj.getJSONArray(key)
                val commands = (0 until arr.length()).map { arr.getString(it) }
                macros[key] = commands
                count++
            }
            count
        } catch (_: Exception) {
            0
        }
    }
}

/**
 * 终端快捷键定义。
 */
enum class TerminalShortcut(
    val displayName: String,
    val description: String,
    val keySequence: String
) {
    TAB_COMPLETE("Tab", "命令补全", "Tab"),
    HISTORY_PREV("↑", "上一条命令", "ArrowUp"),
    HISTORY_NEXT("↓", "下一条命令", "ArrowDown"),
    CLEAR_SCREEN("Ctrl+L", "清屏", "Ctrl+L"),
    KILL_LINE("Ctrl+K", "删除到行尾", "Ctrl+K"),
    BACKWARD_KILL("Ctrl+U", "删除到行首", "Ctrl+U"),
    FORWARD_WORD("Alt+F", "前进一个词", "Alt+F"),
    BACKWARD_WORD("Alt+B", "后退一个词", "Alt+B"),
    SEARCH_HISTORY("Ctrl+R", "搜索历史", "Ctrl+R"),
    INTERRUPT("Ctrl+C", "中断", "Ctrl+C"),
    EOF("Ctrl+D", "退出", "Ctrl+D"),
    SUSPEND("Ctrl+Z", "挂起", "Ctrl+Z"),
    NEW_SESSION("Ctrl+N", "新建会话", "Ctrl+N"),
    SWITCH_SESSION("Ctrl+Tab", "切换会话", "Ctrl+Tab"),
    TOGGLE_MODE("Ctrl+M", "切换模式", "Ctrl+M"),
    START_BURST("Ctrl+B", "启动狂暴模式", "Ctrl+B"),
    RECORD_MACRO("Ctrl+R", "录制宏", "Ctrl+R"),
    PLAY_MACRO("Ctrl+P", "回放宏", "Ctrl+P"),
    SHOW_AGENTS("Ctrl+A", "显示 Agent 列表", "Ctrl+A")
}
