package com.apex.agent.presentation.enhancedterminal.data

import androidx.compose.ui.graphics.Color

enum class LineKind(val color: Color) {
    PROMPT(Color(0xFF00E5FF)),
    OUTPUT(Color(0xFFCBD5E1)),
    SYSTEM(Color(0xFF60A5FA)),
    ERROR(Color(0xFFEF4444)),
    SUCCESS(Color(0xFF4ADE80)),
    WARNING(Color(0xFFFBBF24)),
    INFO(Color(0xFF818CF8)),
    COMMENT(Color(0xFF64748B)),
}

data class TerminalLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val kind: LineKind = LineKind.OUTPUT,
    val timestamp: Long = System.currentTimeMillis(),
)

data class TerminalSession(
    val id: String,
    val name: String,
    val workingDir: String = "~",
    val lines: List<TerminalLine> = emptyList(),
    val history: List<String> = emptyList(),
    val isRunning: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
) {
    val shortDir: String get() = workingDir.substringAfterLast('/').ifEmpty { workingDir }
}

data class QuickCommand(
    val id: String,
    val label: String,
    val command: String,
    val icon: String,
    val category: QuickCommandCategory = QuickCommandCategory.GENERAL,
    val description: String? = null,
)

enum class QuickCommandCategory(val label: String, val color: Color) {
    GENERAL("通用", Color(0xFF60A5FA)),
    FILE("文件", Color(0xFF4ADE80)),
    NETWORK("网络", Color(0xFFFBBF24)),
    PROCESS("进程", Color(0xFFEF4444)),
    SYSTEM("系统", Color(0xFF818CF8)),
    DEV("开发", Color(0xFF06B6D4)),
}

data class CommandAlias(val alias: String, val command: String, val description: String? = null)

data class Snippet(
    val id: String,
    val name: String,
    val content: String,
    val language: String = "bash",
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)

sealed class CommandPaletteItem {
    data class HistoryItem(val command: String, val timestamp: Long) : CommandPaletteItem()
    data class QuickCommandItem(val cmd: QuickCommand) : CommandPaletteItem()
    data class SnippetItem(val snippet: Snippet) : CommandPaletteItem()
    data class AliasItem(val alias: CommandAlias) : CommandPaletteItem()
    data class BuiltInItem(val name: String, val description: String, val action: String) : CommandPaletteItem()
}

object DefaultQuickCommands {
    val all: List<QuickCommand> = listOf(
        QuickCommand("ls", "列出文件", "ls -lah", "📁", QuickCommandCategory.FILE),
        QuickCommand("tree", "目录树", "find . -type f | head -50", "🌳", QuickCommandCategory.FILE),
        QuickCommand("df", "磁盘使用", "df -h", "💾", QuickCommandCategory.FILE),
        QuickCommand("du", "目录大小", "du -sh *", "📊", QuickCommandCategory.FILE),
        QuickCommand("find_big", "找大文件", "find . -type f -size +10M -exec ls -lh {} \\;", "🐘", QuickCommandCategory.FILE),
        QuickCommand("ps", "进程列表", "ps aux | head -20", "⚙️", QuickCommandCategory.PROCESS),
        QuickCommand("top", "TOP 进程", "top -n 1 | head -20", "🔝", QuickCommandCategory.PROCESS),
        QuickCommand("ports", "端口占用", "netstat -tlnp 2>/dev/null || ss -tlnp", "🔌", QuickCommandCategory.NETWORK),
        QuickCommand("ip", "IP 地址", "ip addr show | grep inet", "🌐", QuickCommandCategory.NETWORK),
        QuickCommand("ping", "Ping 测试", "ping -c 4 8.8.8.8", "📡", QuickCommandCategory.NETWORK),
        QuickCommand("curl", "HTTP 请求", "curl -s ifconfig.me", "🔗", QuickCommandCategory.NETWORK),
        QuickCommand("env", "环境变量", "env | sort", "🔧", QuickCommandCategory.SYSTEM),
        QuickCommand("uptime", "运行时间", "uptime", "⏰", QuickCommandCategory.SYSTEM),
        QuickCommand("mem", "内存使用", "free -h", "🧠", QuickCommandCategory.SYSTEM),
        QuickCommand("git_status", "Git 状态", "git status -sb", "🌿", QuickCommandCategory.DEV),
        QuickCommand("git_log", "Git 日志", "git log --oneline -20 --graph", "📜", QuickCommandCategory.DEV),
        QuickCommand("git_diff", "Git 差异", "git diff", "🔀", QuickCommandCategory.DEV),
        QuickCommand("node_v", "Node 版本", "node --version", "🟢", QuickCommandCategory.DEV),
        QuickCommand("npm_list", "NPM 全局包", "npm list -g --depth=0 2>/dev/null", "📦", QuickCommandCategory.DEV),
        QuickCommand("history", "命令历史", "history 50", "📜", QuickCommandCategory.GENERAL),
    )
}

object DefaultAliases {
    val all: List<CommandAlias> = listOf(
        CommandAlias("ll", "ls -lah", "长格式列出"),
        CommandAlias("la", "ls -A", "列出所有(含隐藏)"),
        CommandAlias("..", "cd ..", "返回上级目录"),
        CommandAlias("...", "cd ../..", "返回上两级目录"),
        CommandAlias("ports", "netstat -tlnp 2>/dev/null || ss -tlnp", "查看端口"),
        CommandAlias("gss", "git status -sb", "git 状态简写"),
        CommandAlias("gll", "git log --oneline -20 --graph", "git 日志简写"),
        CommandAlias("myip", "ip addr show | grep inet", "本机 IP"),
    )
}

object BuiltInCommands {
    val all: List<CommandPaletteItem.BuiltInItem> = listOf(
        CommandPaletteItem.BuiltInItem("help", "显示帮助", "help"),
        CommandPaletteItem.BuiltInItem("clear", "清空当前会话", "clear"),
        CommandPaletteItem.BuiltInItem("new", "新建会话", "new"),
        CommandPaletteItem.BuiltInItem("close", "关闭当前会话", "close"),
        CommandPaletteItem.BuiltInItem("sessions", "列出所有会话", "sessions"),
        CommandPaletteItem.BuiltInItem("history", "显示命令历史", "history"),
        CommandPaletteItem.BuiltInItem("aliases", "显示所有别名", "aliases"),
        CommandPaletteItem.BuiltInItem("snippets", "显示代码段", "snippets"),
        CommandPaletteItem.BuiltInItem("theme", "切换主题", "theme"),
        CommandPaletteItem.BuiltInItem("export", "导出会话为文本", "export"),
    )
}
