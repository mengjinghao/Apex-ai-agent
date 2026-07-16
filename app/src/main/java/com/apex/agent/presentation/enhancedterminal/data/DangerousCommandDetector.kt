package com.apex.agent.presentation.enhancedterminal.data

/**
 * 危险命令检测器
 *
 * 在执行前检查命令是否危险,如果是则提示用户确认。
 */
object DangerousCommandDetector {

    /** 危险命令模式列表 */
    private val patterns = listOf(
        // 文件删除
        DangerPattern("rm -rf /", "递归删除根目录 — 极其危险!", DangerLevel.CRITICAL),
        DangerPattern("rm -rf ~", "递归删除用户主目录 — 极其危险!", DangerLevel.CRITICAL),
        DangerPattern("rm -rf \\*", "递归删除当前目录所有文件", DangerLevel.CRITICAL),
        DangerPattern("rm -rf \\.", "递归删除当前目录所有文件", DangerLevel.CRITICAL),
        DangerPattern("rm -r", "递归删除目录", DangerLevel.HIGH),
        DangerPattern("rm -f", "强制删除文件", DangerLevel.MEDIUM),
        DangerPattern("rmdir", "删除目录", DangerLevel.LOW),

        // 磁盘操作
        DangerPattern("dd if=", "dd 写入磁盘 — 可能覆盖数据", DangerLevel.CRITICAL),
        DangerPattern("mkfs", "格式化文件系统 — 数据全失", DangerLevel.CRITICAL),
        DangerPattern("fdisk", "磁盘分区操作", DangerLevel.HIGH),

        // 权限修改
        DangerPattern("chmod -R 777", "递归设置 777 权限 — 安全风险", DangerLevel.HIGH),
        DangerPattern("chmod 777", "设置 777 权限", DangerLevel.MEDIUM),
        DangerPattern("chown -R", "递归修改所有者", DangerLevel.MEDIUM),

        // 进程操作
        DangerPattern("kill -9", "强制杀死进程 (SIGKILL)", DangerLevel.MEDIUM),
        DangerPattern("killall", "杀死所有同名进程", DangerLevel.MEDIUM),
        DangerPattern("pkill", "按名称杀进程", DangerLevel.MEDIUM),
        DangerPattern("shutdown", "关机", DangerLevel.HIGH),
        DangerPattern("reboot", "重启", DangerLevel.HIGH),
        DangerPattern("halt", "停止系统", DangerLevel.HIGH),

        // 网络操作
        DangerPattern("iptables -F", "清空防火墙规则", DangerLevel.HIGH),
        DangerPattern("iptables -X", "删除自定义链", DangerLevel.HIGH),

        // 包管理(需 root)
        DangerPattern("apt remove", "卸载软件包", DangerLevel.MEDIUM),
        DangerPattern("apt purge", "清除软件包及配置", DangerLevel.MEDIUM),
        DangerPattern("pip uninstall", "卸载 Python 包", DangerLevel.LOW),

        // Git 操作
        DangerPattern("git push --force", "强制推送 — 覆盖远程历史", DangerLevel.HIGH),
        DangerPattern("git push -f", "强制推送 — 覆盖远程历史", DangerLevel.HIGH),
        DangerPattern("git reset --hard", "硬重置 — 丢弃所有未提交修改", DangerLevel.HIGH),
        DangerPattern("git clean -fd", "删除未跟踪的文件和目录", DangerLevel.MEDIUM),
        DangerPattern("git branch -D", "强制删除分支", DangerLevel.LOW),
    )

    enum class DangerLevel(val label: String, val color: Long) {
        CRITICAL("极其危险", 0xFFEF4444),
        HIGH("高危", 0xFFF97316),
        MEDIUM("中等风险", 0xFFFBBF24),
        LOW("低风险", 0xFF60A5FA),
    }


    /** 检测命令是否危险 */
    fun check(command: String): DetectionResult {
        val lower = command.trim().lowercase()
        for (p in patterns) {
            // 构建正则:允许中间有空格变化
            val regexStr = p.regex
                .replace(" ", "\\s+")
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("~", System.getProperty("user.home") ?: "~")
            try {
                val regex = Regex(regexStr, RegexOption.IGNORE_CASE)
                if (regex.containsMatchIn(lower)) {
                    return DetectionResult(true, p.description, p.level)
                }
            } catch (e: Exception) {
                // 正则构建失败,用简单 contains 匹配
                if (lower.contains(p.regex.lowercase().replace("\\", ""))) {
                    return DetectionResult(true, p.description, p.level)
                }
            }
        }
        return DetectionResult(false, "", DangerLevel.LOW)
    }

    /** 所有危险模式(用于文档/调试) */
    fun allPatterns(): List<DangerPattern> = patterns
}
