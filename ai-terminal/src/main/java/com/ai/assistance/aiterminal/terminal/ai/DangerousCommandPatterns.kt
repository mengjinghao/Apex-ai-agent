package com.ai.assistance.aiterminal.terminal.ai

data class RiskAssessmentResult(
    val level: RiskLevel,
    val score: Int,
    val warnings: List<String>,
    val precautions: List<String>,
    val reversible: Boolean,
    val requiresConfirmation: Boolean,
    val assessmentDetails: Map<String, Any> = emptyMap()
)

data class CommandPattern(
    val pattern: Regex,
    val riskLevel: RiskLevel,
    val description: String,
    val precautions: List<String>,
    val reversible: Boolean = false
)

object DangerousCommandPatterns {

    private val criticalRiskPatterns = listOf(
        CommandPattern(
            pattern = Regex("(rm\\s+-rf\\s+/|rm\\s+-rf\\s+\\*|del\\s+/[fqs]\\s+/\\*)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "递归删除系统根目录或所有文件",
            precautions = listOf(
                "此操作不可逆！",
                "将导致系统完全损坏",
                "永远不要执行此命令"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(format|mkfs)\\s+.*(/dev|/system|/data)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "格式化系统分区",
            precautions = listOf(
                "格式化将清除所有数据",
                "系统将无法启动"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("dd\\s+.*of=/dev/", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "直接写入设备可能损坏系统",
            precautions = listOf(
                "错误的设备选择会导致系统损坏",
                "确保目标设备正确"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(reboot\\s+-f|busybox\\s+reboot\\s+-f|fastboot\\s+oem\\s+unlock)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "强制重启或解锁引导程序",
            precautions = listOf(
                "可能导致设备变砖",
                "解锁bootloader会清除所有数据"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(wipe|reset)\\s+.*(data|all|factory)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "恢复出厂设置或清除所有数据",
            precautions = listOf(
                "所有用户数据将被删除",
                "所有设置将被重置",
                "此操作不可逆"
            ),
            reversible = false
        )
    )

    private val highRiskPatterns = listOf(
        CommandPattern(
            pattern = Regex("(rm\\s+-r|/bin/rm\\s+.*-r|/system/bin/rm\\s+.*-r)\\s+/?(data|system|vendor|boot)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "删除系统关键目录",
            precautions = listOf(
                "系统关键目录被删除后可能无法启动",
                "建议先备份数据"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(chmod|chown)\\s+777\\s+(/system|/data|/vendor|/boot)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "修改系统目录权限为完全访问",
            precautions = listOf(
                "降低系统安全性",
                "可能导致安全问题"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(mv|cp)\\s+.*\\s+/dev/null", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "将文件移动或复制到黑洞设备",
            precautions = listOf(
                "文件将被永久删除",
                "无法恢复"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("kill\\s+-9\\s+(1|init|PID\\s+1)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "终止系统关键进程",
            precautions = listOf(
                "可能导统系统崩溃",
                "PID 1 是 init 进程，终止会导致系统重启"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(su\\s+-c|su\\s+-m).*\\|\\s*sh", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "通过管道执行未转义命令",
            precautions = listOf(
                "可能存在命令注入风险",
                "建议分开执行命令"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(echo|printf)\\s+.*\\|\\s*(sh|bash|zsh)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "通过管道执行动态生成的命令",
            precautions = listOf(
                "如果内容来自用户输入，存在安全风险",
                "建议验证输入内容"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(cat|dd)\\s+.*\\|\\s*(chmod|chown|mount)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "通过管道修改权限或挂载",
            precautions = listOf(
                "可能存在竞态条件",
                "建议直接执行目标命令"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(mount|umount)\\s+.*(rw|ro)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "修改系统分区挂载状态",
            precautions = listOf(
                "错误的挂载状态可能导致系统异常",
                "修改 /system 只读状态可能影响系统更新"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(pm\\s+hide|pm\\s+disable)\\s+.*(com\\.android|com\\.google|com\\.sec)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "禁用系统关键应用",
            precautions = listOf(
                "禁用关键系统应用可能导致系统不稳定",
                "某些应用可能是其他功能的基础"
            ),
            reversible = true
        )
    )

    private val mediumRiskPatterns = listOf(
        CommandPattern(
            pattern = Regex("(rm|del)\\s+.*\\.(apk|jar|dex|so)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "删除应用或库文件",
            precautions = listOf(
                "相关应用可能无法运行",
                "可能影响系统功能"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(setprop|getprop)\\s+.*(debug|log|sys)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "修改系统属性",
            precautions = listOf(
                "部分属性修改需要重启生效",
                "错误的属性值可能导致异常"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(stop|start)\\s+(surfaceflinger|zygote|system_server)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "停止或启动系统关键服务",
            precautions = listOf(
                "可能影响系统稳定性",
                "部分服务停止后可能需要手动启动"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(input\\s+keyevent\\s+|input\\s+text).*", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "模拟输入事件",
            precautions = listOf(
                "在自动化场景中可能产生意外操作",
                "确保在安全的环境中执行"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(logcat|bugreport)\\s+.*\\s*>\\s*.*", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "将日志输出到文件",
            precautions = listOf(
                "大文件输出可能占用大量存储空间",
                "日志可能包含敏感信息"
            ),
            reversible = true
        ),
        CommandPattern(
            pattern = Regex("(tcpdump|wireshark|nmap)\\s+.*", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "网络抓包或扫描",
            precautions = listOf(
                "未经授权的网络嗅探可能违法",
                "确保有适当权限"
            ),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(sqlite3|dumpsys)\\s+.*\\s+(dump|backup|export)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "导出系统数据库",
            precautions = listOf(
                "导出的数据可能包含敏感信息",
                "确保存储位置安全"
            ),
            reversible = false
        )
    )

    private val lowRiskPatterns = listOf(
        CommandPattern(
            pattern = Regex("(ls|ls\\s+-l|pwd|whoami|id)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.LOW,
            description = "查看文件或用户信息",
            precautions = emptyList(),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(ps|top|free|df|df\\s+-h)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.LOW,
            description = "查看系统状态",
            precautions = emptyList(),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(pm\\s+list\\s+packages|dumpsys\\s+battery|dumpsys\\s+meminfo)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.LOW,
            description = "查询系统信息",
            precautions = emptyList(),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(getprop|cat\\s+/proc/.*)", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.LOW,
            description = "读取系统属性",
            precautions = emptyList(),
            reversible = false
        ),
        CommandPattern(
            pattern = Regex("(screenrecord|screencap|input\\s+tap|input\\s+swipe)\\s+", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.LOW,
            description = "屏幕录制或模拟触摸",
            precautions = emptyList(),
            reversible = false
        )
    )

    val allPatterns = criticalRiskPatterns + highRiskPatterns + mediumRiskPatterns + lowRiskPatterns

    fun matchPattern(command: String): CommandPattern? {
        for (pattern in allPatterns) {
            if (pattern.pattern.containsMatchIn(command)) {
                return pattern
            }
        }
        return null
    }

    fun getPatternsByLevel(level: RiskLevel): List<CommandPattern> {
        return allPatterns.filter { it.riskLevel == level }
    }

    fun isHighRisk(command: String): Boolean {
        val matched = matchPattern(command)
        return matched?.riskLevel?.let { it.ordinal >= RiskLevel.HIGH.ordinal } ?: false
    }

    fun isCriticalRisk(command: String): Boolean {
        val matched = matchPattern(command)
        return matched?.riskLevel == RiskLevel.CRITICAL
    }
}