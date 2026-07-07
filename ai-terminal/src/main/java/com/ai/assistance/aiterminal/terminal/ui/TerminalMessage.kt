package com.ai.assistance.aiterminal.terminal.ui

import com.ai.assistance.aiterminal.terminal.theme.ApexTerminalTheme
import java.util.UUID

/**
 * 终端消息类型。
 *
 * 每种类型在 UI 中有不同的颜色、图标和样式。
 */
enum class TerminalMessageType(val displayName: String) {
    COMMAND("命令"),        // 用户输入的命令
    OUTPUT("输出"),         // 命令输出
    ERROR("错误"),          // 错误输出
    SYSTEM("系统"),         // 系统消息
    AGENT("Agent"),        // Agent 消息
    BURST("狂暴"),         // 狂暴模式消息
    SUCCESS("成功"),        // 成功结果
    WARNING("警告"),        // 警告信息
    INFO("信息"),           // 提示信息
    DIVIDER("分隔线")       // 视觉分隔
}

/**
 * 终端消息来源。
 */
enum class MessageSource {
    USER,           // 用户输入
    SHELL,          // Shell 输出
    SYSTEM,         // 系统消息
    AGENT,          // Agent 消息
    BURST_MODE,     // 狂暴模式
    MULTI_AGENT     // 多 Agent 协作
}

/**
 * 终端消息。
 *
 * 终端中显示的每一条消息（命令/输出/Agent 消息/狂暴模式状态等）。
 *
 * @property id 消息 ID
 * @property type 消息类型
 * @property source 消息来源
 * @property content 内容
 * @property timestamp 时间戳
 * @property agentId 发送方 Agent ID（仅 Agent 消息）
 * @property agentRole Agent 角色（仅 Agent 消息）
 * @property metadata 附加元数据
 */
data class TerminalMessage(
    val id: String = UUID.randomUUID().toString(),
    val type: TerminalMessageType,
    val source: MessageSource = MessageSource.SYSTEM,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val agentId: String? = null,
    val agentRole: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 格式化时间（HH:mm:ss）。
     */
    val formattedTime: String
        get() {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(timestamp))
        }

    /**
     * 生成显示前缀（如 "[14:30:25] ▌ USER ▸"）。
     */
    val prefix: String
        get() = when (source) {
            MessageSource.USER -> "[$formattedTime] ▌"
            MessageSource.SHELL -> "[$formattedTime] ▸"
            MessageSource.SYSTEM -> "[$formattedTime] ⚡"
            MessageSource.AGENT -> "[$formattedTime] 🤖"
            MessageSource.BURST_MODE -> "[$formattedTime] 🔥"
            MessageSource.MULTI_AGENT -> "[$formattedTime] 🎭"
        }

    companion object {
        /** 创建用户命令消息。 */
        fun command(text: String) = TerminalMessage(
            type = TerminalMessageType.COMMAND,
            source = MessageSource.USER,
            content = text
        )

        /** 创建 Shell 输出消息。 */
        fun output(text: String) = TerminalMessage(
            type = TerminalMessageType.OUTPUT,
            source = MessageSource.SHELL,
            content = text
        )

        /** 创建错误消息。 */
        fun error(text: String) = TerminalMessage(
            type = TerminalMessageType.ERROR,
            source = MessageSource.SHELL,
            content = text
        )

        /** 创建系统消息。 */
        fun system(text: String) = TerminalMessage(
            type = TerminalMessageType.SYSTEM,
            source = MessageSource.SYSTEM,
            content = text
        )

        /** 创建 Agent 消息。 */
        fun agent(text: String, agentId: String, agentRole: String) = TerminalMessage(
            type = TerminalMessageType.AGENT,
            source = MessageSource.AGENT,
            content = text,
            agentId = agentId,
            agentRole = agentRole
        )

        /** 创建狂暴模式消息。 */
        fun burst(text: String) = TerminalMessage(
            type = TerminalMessageType.BURST,
            source = MessageSource.BURST_MODE,
            content = text
        )

        /** 创建多 Agent 消息。 */
        fun multiAgent(text: String, agentId: String, agentRole: String) = TerminalMessage(
            type = TerminalMessageType.AGENT,
            source = MessageSource.MULTI_AGENT,
            content = text,
            agentId = agentId,
            agentRole = agentRole
        )

        /** 创建分隔线。 */
        fun divider(title: String = "") = TerminalMessage(
            type = TerminalMessageType.DIVIDER,
            source = MessageSource.SYSTEM,
            content = title
        )

        /** 创建成功消息。 */
        fun success(text: String) = TerminalMessage(
            type = TerminalMessageType.SUCCESS,
            source = MessageSource.SYSTEM,
            content = text
        )

        /** 创建警告消息。 */
        fun warning(text: String) = TerminalMessage(
            type = TerminalMessageType.WARNING,
            source = MessageSource.SYSTEM,
            content = text
        )
    }
}

/**
 * 终端会话状态栏信息。
 *
 * 显示在终端底部的状态栏内容。
 *
 * @property currentDir 当前目录
 * @property sessionName 会话名
 * @property agentMode Agent 模式（none/single/multi/burst）
 * @property burstState 狂暴模式状态
 * @property activeAgentCount 活跃 Agent 数
 * @property cpuUsage CPU 使用率（0..100，-1 表示未知）
 * @property memUsage 内存使用率（0..100，-1 表示未知）
 */
data class TerminalStatusBar(
    val currentDir: String = "~",
    val sessionName: String = "apex",
    val agentMode: AgentMode = AgentMode.NONE,
    val burstState: String = "idle",
    val activeAgentCount: Int = 0,
    val cpuUsage: Int = -1,
    val memUsage: Int = -1
) {
    /**
     * 格式化状态栏文本。
     */
    val statusText: String
        get() = buildString {
            append(sessionName)
            append(":")
            append(currentDir.takeLast(20))
            append(" | ")
            append(agentMode.displayName)
            if (agentMode != AgentMode.NONE) {
                append("($activeAgentCount)")
            }
            if (agentMode == AgentMode.BURST) {
                append(" | BURST: $burstState")
            }
            if (cpuUsage >= 0) {
                append(" | CPU: ${cpuUsage}%")
            }
            if (memUsage >= 0) {
                append(" | MEM: ${memUsage}%")
            }
        }
}

/**
 * Agent 模式。
 */
enum class AgentMode(val displayName: String) {
    NONE("标准"),          // 标准终端
    SINGLE("单 Agent"),   // 单 Agent 模式
    MULTI("多 Agent"),    // 多 Agent 协作
    BURST("狂暴模式")     // 狂暴模式
}

/**
 * 终端输入模式。
 */
enum class TerminalInputMode(val displayName: String, val placeholder: String) {
    SHELL("Shell", "输入命令..."),
    AGENT("Agent", "向 Agent 提问..."),
    BURST("狂暴", "描述任务，狂暴模式执行...")
}
