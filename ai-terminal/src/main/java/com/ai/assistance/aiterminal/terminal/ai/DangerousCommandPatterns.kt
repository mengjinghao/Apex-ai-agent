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
        ),
        // ===== Defense-in-depth (TERM-FIX-3B / E-3, E-4): expanded pattern set =====
        // Fork bomb — `:(){ :|:& };:` classic Bash fork bomb
        CommandPattern(
            pattern = Regex(""":\s*\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""),
            riskLevel = RiskLevel.CRITICAL,
            description = "Fork bomb — can crash the device",
            precautions = listOf(
                "Fork bomb will exhaust process slots / memory",
                "May require forced reboot to recover"
            ),
            reversible = false
        ),
        // Write directly to block device via dd (more specific than the existing dd pattern)
        CommandPattern(
            pattern = Regex("""dd\s+.*of\s*=\s*/dev/(block/)?(sd|mmcblk|nvme)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Writing directly to block device — can brick device",
            precautions = listOf(
                "Direct writes to block devices bypass filesystem safeguards",
                "Can permanently brick the device — irreversible"
            ),
            reversible = false
        ),
        // cat /dev/urandom|zero > /dev/block/...
        CommandPattern(
            pattern = Regex("""cat\s+/dev/(urandom|zero)\s*>\s*/dev/(block/)?(sd|mmcblk)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Writing random/zeros to block device",
            precautions = listOf(
                "Will overwrite partition table / filesystem metadata",
                "Irreversible device brick"
            ),
            reversible = false
        ),
        // Generic redirect to block device — catches `> /dev/sda`, `> /dev/block/mmcblk0`, etc.
        CommandPattern(
            pattern = Regex(""">\s*/dev/(block/)?(sd|mmcblk|nvme)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Redirecting to block device — can brick device",
            precautions = listOf(
                "Redirecting output to a block device corrupts raw storage",
                "Irreversible"
            ),
            reversible = false
        ),
        // kill -1 / kill -9 -1 — signal every process the user can signal
        CommandPattern(
            pattern = Regex("""kill\s+-9?\s+-1\b""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Killing all processes the user can signal",
            precautions = listOf(
                "Will terminate every running process owned by the user",
                "May crash the system / kill critical services"
            ),
            reversible = false
        ),
        // killall of Android system critical processes
        CommandPattern(
            pattern = Regex("""killall\s+(zygote|system_server|servicemanager|surfaceflinger)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Killing critical system processes",
            precautions = listOf(
                "Killing zygote/system_server will crash the Android framework",
                "Will likely force a system reboot"
            ),
            reversible = false
        ),
        // Disabling SELinux — security suicide
        CommandPattern(
            pattern = Regex("""setenforce\s+(0|permissive)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Disabling SELinux enforcement — security suicide",
            precautions = listOf(
                "Permissive SELinux disables a major exploit-mitigation layer",
                "Should only be done for debugging, never in production"
            ),
            reversible = true
        ),
        // Kernel panic via sysrq-trigger
        CommandPattern(
            pattern = Regex("""echo\s+[cb]\s*>\s*/proc/sysrq-trigger""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Triggering kernel panic via sysrq",
            precautions = listOf(
                "Will immediately panic the kernel",
                "May cause filesystem corruption"
            ),
            reversible = false
        ),
        // find / -delete — recursive delete from root
        CommandPattern(
            pattern = Regex("""find\s+/\s+.*-delete""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "find / -delete — recursive delete from root",
            precautions = listOf(
                "Will delete every file reachable from /",
                "Irreversible — system will be destroyed"
            ),
            reversible = false
        ),
        // find / -exec rm
        CommandPattern(
            pattern = Regex("""find\s+/\s+.*-exec\s+rm""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "find / -exec rm — recursive delete from root",
            precautions = listOf(
                "Will delete every file reachable from /",
                "Irreversible — system will be destroyed"
            ),
            reversible = false
        ),
        // chmod on root directory — breaks permissions system-wide
        CommandPattern(
            pattern = Regex("""chmod\s+(-R\s+)?[0-7]+\s+/\s*$""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "chmod on root directory — breaks permissions",
            precautions = listOf(
                "Changing permissions on / breaks the entire permission model",
                "System services will fail to start"
            ),
            reversible = true
        ),
        // Base64-encoded command execution — classic pattern-matching bypass
        CommandPattern(
            pattern = Regex("""\|\s*base64\s+(-d|--decode)\s*\|\s*(sh|bash)""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.CRITICAL,
            description = "Decoding and executing base64 — bypasses pattern matching",
            precautions = listOf(
                "Base64-piped-to-shell is a common exfiltration / dropper technique",
                "Bypasses static command inspection — block by default"
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
        ),
        // ===== Defense-in-depth (TERM-FIX-3B / E-3, E-4): expanded pattern set =====
        // Enabling sysrq — opens path to kernel panic
        CommandPattern(
            pattern = Regex("""echo\s+[0-9]\s*>\s*/proc/sys/kernel/sysrq""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "Enabling sysrq — can trigger kernel panic",
            precautions = listOf(
                "sysrq allows direct kernel control including immediate crash",
                "Should remain disabled on production devices"
            ),
            reversible = true
        ),
        // Take CPU cores offline — DoS / instability
        CommandPattern(
            pattern = Regex("""echo\s+0\s*>\s*/sys/devices/system/cpu/cpu[0-9]+/online""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "Taking CPU cores offline",
            precautions = listOf(
                "Offlining CPU cores can freeze the system",
                "May require reboot to recover"
            ),
            reversible = true
        ),
        // Flush firewall rules — disables network protection
        CommandPattern(
            pattern = Regex("""(iptables|ip6tables)\s+-F\b""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "Flushing firewall rules — disables network protection",
            precautions = listOf(
                "Flushing iptables removes all inbound/outbound filtering",
                "Exposes services to hostile networks"
            ),
            reversible = true
        ),
        // Mount tmpfs over system partition — overlay attack
        CommandPattern(
            pattern = Regex("""mount\s+.*tmpfs.*(/system|/vendor|/data)\b""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "Mounting tmpfs over system partition",
            precautions = listOf(
                "Overlaying tmpfs on /system hides the real system files",
                "Can be used to inject malicious binaries into PATH"
            ),
            reversible = true
        ),
        // eval of string literal — common obfuscation / bypass technique
        CommandPattern(
            pattern = Regex("""\beval\b\s*["']""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.HIGH,
            description = "eval of string — can bypass pattern matching",
            precautions = listOf(
                "eval executes dynamically-constructed strings",
                "Frequently used to hide the true payload from static inspection"
            ),
            reversible = false
        ),
        // $IFS trick — classic regex-bypass technique (e.g. cat$IFS/etc/passwd)
        CommandPattern(
            pattern = Regex("""\$IFS"""),
            riskLevel = RiskLevel.HIGH,
            description = "\$IFS variable — common pattern-matching bypass technique",
            precautions = listOf(
                "Substituting whitespace with \$IFS defeats naive tokenization",
                "Almost always indicates an obfuscation attempt"
            ),
            reversible = false
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
        ),
        // ===== Defense-in-depth (TERM-FIX-3B / E-4): bypass technique detection =====
        // Base64 decode (no shell pipe yet) — frequently the precursor to | sh
        CommandPattern(
            pattern = Regex("""base64\s+(-d|--decode)\s*""", RegexOption.IGNORE_CASE),
            riskLevel = RiskLevel.MEDIUM,
            description = "Base64 decode — may be used to hide malicious commands",
            precautions = listOf(
                "Base64 decode alone is not malicious, but is a common obfuscation step",
                "Flag for review — combine with downstream shell execution to escalate"
            ),
            reversible = false
        ),
        // $() command substitution with a variable — `$(VAR)` may expand to arbitrary commands
        CommandPattern(
            pattern = Regex("""\$\(\s*[a-zA-Z_]\w*\s*\)"""),
            riskLevel = RiskLevel.MEDIUM,
            description = "Variable command substitution — may bypass pattern matching",
            precautions = listOf(
                "\$(VAR) substitution hides the actual command from static inspection",
                "Inspect the variable's definition before executing"
            ),
            reversible = false
        ),
        // Backtick execution — legacy command substitution
        CommandPattern(
            pattern = Regex("""`[^`]+`"""),
            riskLevel = RiskLevel.MEDIUM,
            description = "Backtick command substitution — may bypass pattern matching",
            precautions = listOf(
                "Backtick substitution hides the actual command from static inspection",
                "Prefer $(...) syntax and review the inner command"
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

    /**
     * Match the command against all known patterns and return the HIGHEST-severity match.
     *
     * Security (TERM-FIX-3B / E-6): previously this returned the FIRST match — which,
     * because `allPatterns` is ordered `critical + high + medium + low`, happened to
     * usually be the highest severity. But that was an implicit dependency on list
     * ordering: callers that filtered `allPatterns` (e.g. via `getPatternsByLevel`)
     * or that added a new low-risk pattern that happened to match a command also
     * matched by a high-risk pattern could silently downgrade the assessment.
     *
     * Now we explicitly iterate ALL patterns and return the one with the highest
     * `RiskLevel` ordinal. RiskLevel ordinal order (see TerminalCommandTemplate.kt:38):
     *   LOW=0 < MEDIUM=1 < HIGH=2 < CRITICAL=3
     * Higher ordinal = higher severity, so we use `>` to replace `best`.
     */
    fun matchPattern(command: String): CommandPattern? {
        var best: CommandPattern? = null
        for (pattern in allPatterns) {
            if (pattern.pattern.containsMatchIn(command)) {
                if (best == null || pattern.riskLevel.ordinal > best.riskLevel.ordinal) {
                    best = pattern
                }
            }
        }
        return best
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