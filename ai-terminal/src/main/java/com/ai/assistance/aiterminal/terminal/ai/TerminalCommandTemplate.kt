package com.ai.assistance.aiterminal.terminal.ai

data class TerminalCommandTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: TemplateCategory,
    val command: String,
    val requiresRoot: Boolean = false,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val parameters: List<TemplateParameter> = emptyList(),
    val examples: List<String> = emptyList(),
    val relatedCommands: List<String> = emptyList()
)

data class TemplateParameter(
    val name: String,
    val description: String,
    val defaultValue: String? = null,
    val required: Boolean = false
)

enum class TemplateCategory(val displayName: String) {
    FILE_OPERATIONS("文件操作"),
    PACKAGE_MANAGEMENT("包管理"),
    SYSTEM_INFO("系统信息"),
    NETWORK("网络"),
    PROCESS("进程管理"),
    PERMISSIONS("权限管理"),
    DEVICE_CONTROL("设备控制"),
    SHELL("Shell操作"),
    DEVELOPMENT("开发调试"),
    SECURITY("安全相关"),
    BACKUP("备份恢复"),
    AUTOMATION("自动化任务")
}

enum class RiskLevel(val displayName: String, val color: Int) {
    LOW("低风险", 0xFF4CAF50.toInt()),
    MEDIUM("中等风险", 0xFFFFC107.toInt()),
    HIGH("高风险", 0xFFFF5722.toInt()),
    CRITICAL("极高风险", 0xFFF44336.toInt())
}

object TerminalCommandTemplates {

    val allTemplates: List<TerminalCommandTemplate> = listOf(
        TerminalCommandTemplate(
            id = "file_list",
            name = "列出目录文件",
            description = "列出指定目录下的所有文件和文件夹",
            category = TemplateCategory.FILE_OPERATIONS,
            command = "ls -la {path}",
            parameters = listOf(
                TemplateParameter("path", "目录路径", "/sdcard", false)
            ),
            examples = listOf("ls -la /sdcard", "ls -la /data/data"),
            relatedCommands = listOf("file_find", "file_info")
        ),
        TerminalCommandTemplate(
            id = "file_find",
            name = "查找文件",
            description = "在指定目录中查找匹配的文件",
            category = TemplateCategory.FILE_OPERATIONS,
            command = "find {path} -name \"{pattern}\"",
            parameters = listOf(
                TemplateParameter("path", "搜索路径", ".", false),
                TemplateParameter("pattern", "文件名模式", "*.txt", true)
            ),
            examples = listOf("find /sdcard -name \"*.log\"", "find . -name \"test*\""),
            relatedCommands = listOf("file_list", "file_content")
        ),
        TerminalCommandTemplate(
            id = "file_content",
            name = "查看文件内容",
            description = "显示文本文件的内容",
            category = TemplateCategory.FILE_OPERATIONS,
            command = "cat {file}",
            parameters = listOf(
                TemplateParameter("file", "文件路径", "", true)
            ),
            examples = listOf("cat /sdcard/test.txt", "cat build.gradle"),
            relatedCommands = listOf("file_find", "file_edit")
        ),
        TerminalCommandTemplate(
            id = "file_info",
            name = "文件详细信息",
            description = "显示文件的详细信息包括权限、大小等",
            category = TemplateCategory.FILE_OPERATIONS,
            command = "ls -lna {path}",
            parameters = listOf(
                TemplateParameter("path", "文件或目录路径", "", true)
            ),
            examples = listOf("ls -lna /sdcard/file.txt"),
            relatedCommands = listOf("file_list", "perm_view")
        ),
        TerminalCommandTemplate(
            id = "file_size",
            name = "查看目录大小",
            description = "计算并显示目录或文件的大小",
            category = TemplateCategory.FILE_OPERATIONS,
            command = "du -sh {path}",
            parameters = listOf(
                TemplateParameter("path", "目录路径", ".", false)
            ),
            examples = listOf("du -sh /sdcard", "du -sh *"),
            relatedCommands = listOf("file_list", "storage_info")
        ),
        TerminalCommandTemplate(
            id = "pkg_list",
            name = "列出已安装应用",
            description = "显示设备上所有已安装的包",
            category = TemplateCategory.PACKAGE_MANAGEMENT,
            command = "pm list packages",
            examples = listOf("pm list packages", "pm list packages -3"),
            relatedCommands = listOf("pkg_info", "pkg_path")
        ),
        TerminalCommandTemplate(
            id = "pkg_info",
            name = "应用包信息",
            description = "显示指定应用的详细信息",
            category = TemplateCategory.PACKAGE_MANAGEMENT,
            command = "dumpsys package {package}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true)
            ),
            examples = listOf("dumpsys package com.android.chrome"),
            relatedCommands = listOf("pkg_list", "pkg_path")
        ),
        TerminalCommandTemplate(
            id = "pkg_path",
            name = "应用安装路径",
            description = "显示应用APK文件的安装路径",
            category = TemplateCategory.PACKAGE_MANAGEMENT,
            command = "pm path {package}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true)
            ),
            examples = listOf("pm path com.android.chrome"),
            relatedCommands = listOf("pkg_list", "pkg_info")
        ),
        TerminalCommandTemplate(
            id = "pkg_disable",
            name = "禁用应用",
            description = "禁用设备上的指定应用",
            category = TemplateCategory.PACKAGE_MANAGEMENT,
            command = "pm disable-user --user 0 {package}",
            requiresRoot = false,
            riskLevel = RiskLevel.MEDIUM,
            parameters = listOf(
                TemplateParameter("package", "包名", "", true)
            ),
            examples = listOf("pm disable-user --user 0 com.android.provider.apps"),
            relatedCommands = listOf("pkg_enable", "pkg_list")
        ),
        TerminalCommandTemplate(
            id = "pkg_enable",
            name = "启用应用",
            description = "启用之前禁用的应用",
            category = TemplateCategory.PACKAGE_MANAGEMENT,
            command = "pm enable {package}",
            requiresRoot = false,
            riskLevel = RiskLevel.MEDIUM,
            parameters = listOf(
                TemplateParameter("package", "包名", "", true)
            ),
            examples = listOf("pm enable com.android.provider.apps"),
            relatedCommands = listOf("pkg_disable", "pkg_list")
        ),
        TerminalCommandTemplate(
            id = "sys_info",
            name = "系统信息",
            description = "显示Android系统详细信息",
            category = TemplateCategory.SYSTEM_INFO,
            command = "getprop",
            examples = listOf("getprop", "getprop | grep ro.product"),
            relatedCommands = listOf("sys_version", "sys_cpu")
        ),
        TerminalCommandTemplate(
            id = "sys_version",
            name = "系统版本",
            description = "显示Android版本和SDK信息",
            category = TemplateCategory.SYSTEM_INFO,
            command = "getprop ro.build.version.release && getprop ro.build.version.sdk",
            examples = listOf("getprop ro.build.version.release"),
            relatedCommands = listOf("sys_info", "sys_device")
        ),
        TerminalCommandTemplate(
            id = "sys_cpu",
            name = "CPU信息",
            description = "显示CPU架构和核心数",
            category = TemplateCategory.SYSTEM_INFO,
            command = "cat /proc/cpuinfo | grep -E 'Processor|model name|cpu cores'",
            examples = listOf("cat /proc/cpuinfo", "top -b -n 1 | head -20"),
            relatedCommands = listOf("sys_info", "sys_memory")
        ),
        TerminalCommandTemplate(
            id = "sys_memory",
            name = "内存信息",
            description = "显示系统内存使用情况",
            category = TemplateCategory.SYSTEM_INFO,
            command = "cat /proc/meminfo",
            examples = listOf("cat /proc/meminfo", "free -h"),
            relatedCommands = listOf("sys_cpu", "sys_storage")
        ),
        TerminalCommandTemplate(
            id = "sys_storage",
            name = "存储信息",
            description = "显示存储空间使用情况",
            category = TemplateCategory.SYSTEM_INFO,
            command = "df -h",
            examples = listOf("df -h", "df -h /data"),
            relatedCommands = listOf("sys_memory", "file_size")
        ),
        TerminalCommandTemplate(
            id = "net_status",
            name = "网络状态",
            description = "显示网络连接状态",
            category = TemplateCategory.NETWORK,
            command = "ifconfig || ip addr",
            examples = listOf("ifconfig", "ip addr show"),
            relatedCommands = listOf("net_ping", "net_dns")
        ),
        TerminalCommandTemplate(
            id = "net_ping",
            name = "Ping测试",
            description = "测试网络连接延迟",
            category = TemplateCategory.NETWORK,
            command = "ping -c 4 {host}",
            parameters = listOf(
                TemplateParameter("host", "目标主机", "8.8.8.8", false)
            ),
            examples = listOf("ping -c 4 8.8.8.8", "ping -c 4 google.com"),
            relatedCommands = listOf("net_status", "net_dns")
        ),
        TerminalCommandTemplate(
            id = "net_dns",
            name = "DNS查询",
            description = "查看DNS服务器配置",
            category = TemplateCategory.NETWORK,
            command = "getprop net.dns1 && getprop net.dns2",
            examples = listOf("getprop | grep dns", "nslookup google.com"),
            relatedCommands = listOf("net_status", "net_ping")
        ),
        TerminalCommandTemplate(
            id = "ps_list",
            name = "进程列表",
            description = "显示运行中的进程",
            category = TemplateCategory.PROCESS,
            command = "ps -A",
            examples = listOf("ps -A", "ps -A | grep chrome"),
            relatedCommands = listOf("ps_detail", "kill_process")
        ),
        TerminalCommandTemplate(
            id = "ps_detail",
            name = "进程详情",
            description = "显示进程的详细信息",
            category = TemplateCategory.PROCESS,
            command = "ps -A | grep {name}",
            parameters = listOf(
                TemplateParameter("name", "进程名", "", false)
            ),
            examples = listOf("ps -A | grep com.android", "ps -Al"),
            relatedCommands = listOf("ps_list", "top_cpu")
        ),
        TerminalCommandTemplate(
            id = "top_cpu",
            name = "CPU占用排名",
            description = "显示CPU占用最高的进程",
            category = TemplateCategory.PROCESS,
            command = "top -b -n 1 -o %CPU | head -15",
            examples = listOf("top -b -n 1 | head -20", "top -d 2"),
            relatedCommands = listOf("top_mem", "ps_list")
        ),
        TerminalCommandTemplate(
            id = "top_mem",
            name = "内存占用排名",
            description = "显示内存占用最高的进程",
            category = TemplateCategory.PROCESS,
            command = "top -b -n 1 -o %MEM | head -15",
            examples = listOf("top -b -n 1 -o %MEM | head -15"),
            relatedCommands = listOf("top_cpu", "ps_list")
        ),
        TerminalCommandTemplate(
            id = "kill_process",
            name = "结束进程",
            description = "强制结束指定进程",
            category = TemplateCategory.PROCESS,
            riskLevel = RiskLevel.HIGH,
            command = "kill -9 {pid}",
            parameters = listOf(
                TemplateParameter("pid", "进程ID", "", true)
            ),
            examples = listOf("kill -9 1234", "killall com.android.chrome"),
            relatedCommands = listOf("ps_list", "ps_detail")
        ),
        TerminalCommandTemplate(
            id = "perm_view",
            name = "权限列表",
            description = "查看应用被授予的权限",
            category = TemplateCategory.PERMISSIONS,
            command = "dumpsys package {package} | grep permission",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true)
            ),
            examples = listOf("dumpsys package com.android.chrome | grep permission"),
            relatedCommands = listOf("pkg_info", "perm_grant")
        ),
        TerminalCommandTemplate(
            id = "perm_grant",
            name = "授予权限",
            description = "向应用授予指定权限",
            category = TemplateCategory.PERMISSIONS,
            requiresRoot = true,
            riskLevel = RiskLevel.HIGH,
            command = "pm grant {package} {permission}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true),
                TemplateParameter("permission", "权限名", "android.permission.CAMERA", true)
            ),
            examples = listOf("pm grant com.android.chrome android.permission.CAMERA"),
            relatedCommands = listOf("perm_view", "perm_revoke")
        ),
        TerminalCommandTemplate(
            id = "perm_revoke",
            name = "撤销权限",
            description = "撤销应用的指定权限",
            category = TemplateCategory.PERMISSIONS,
            requiresRoot = true,
            riskLevel = RiskLevel.HIGH,
            command = "pm revoke {package} {permission}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true),
                TemplateParameter("permission", "权限名", "android.permission.CAMERA", true)
            ),
            examples = listOf("pm revoke com.android.chrome android.permission.CAMERA"),
            relatedCommands = listOf("perm_view", "perm_grant")
        ),
        TerminalCommandTemplate(
            id = "screen_record",
            name = "屏幕录制",
            description = "开始录制屏幕",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "screenrecord {duration} {output}",
            parameters = listOf(
                TemplateParameter("duration", "时长(秒)", "30", false),
                TemplateParameter("output", "输出文件", "/sdcard/screen.mp4", false)
            ),
            examples = listOf("screenrecord --time-limit 30 /sdcard/screen.mp4"),
            relatedCommands = listOf("screen_screenshot", "sys_storage")
        ),
        TerminalCommandTemplate(
            id = "screen_screenshot",
            name = "截图",
            description = "截取当前屏幕",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "screencap -p {output}",
            parameters = listOf(
                TemplateParameter("output", "输出文件", "/sdcard/screenshot.png", false)
            ),
            examples = listOf("screencap -p /sdcard/screenshot.png"),
            relatedCommands = listOf("screen_record", "file_list")
        ),
        TerminalCommandTemplate(
            id = "input_text",
            name = "输入文本",
            description = "向当前应用输入文本",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "input text \"{text}\"",
            parameters = listOf(
                TemplateParameter("text", "要输入的文本", "", true)
            ),
            examples = listOf("input text \"hello world\"", "input keyevent 66"),
            relatedCommands = listOf("input_tap", "input_swipe")
        ),
        TerminalCommandTemplate(
            id = "input_tap",
            name = "点击屏幕",
            description = "模拟点击屏幕指定位置",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "input tap {x} {y}",
            parameters = listOf(
                TemplateParameter("x", "X坐标", "500", true),
                TemplateParameter("y", "Y坐标", "500", true)
            ),
            examples = listOf("input tap 500 500", "input tap 250 250"),
            relatedCommands = listOf("input_swipe", "input_text")
        ),
        TerminalCommandTemplate(
            id = "input_swipe",
            name = "滑动屏幕",
            description = "模拟滑动屏幕操作",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "input swipe {x1} {y1} {x2} {y2} {duration}",
            parameters = listOf(
                TemplateParameter("x1", "起点X", "500", true),
                TemplateParameter("y1", "起点Y", "500", true),
                TemplateParameter("x2", "终点X", "500", true),
                TemplateParameter("y2", "终点Y", "1000", true),
                TemplateParameter("duration", "时长(ms)", "300", false)
            ),
            examples = listOf("input swipe 500 500 500 1000 300"),
            relatedCommands = listOf("input_tap", "input_text")
        ),
        TerminalCommandTemplate(
            id = "logcat_clear",
            name = "清除日志",
            description = "清除日志缓冲区",
            category = TemplateCategory.DEVELOPMENT,
            command = "logcat -c",
            examples = listOf("logcat -c"),
            relatedCommands = listOf("logcat_dump", "logcat_filter")
        ),
        TerminalCommandTemplate(
            id = "logcat_dump",
            name = "导出日志",
            description = "导出当前日志",
            category = TemplateCategory.DEVELOPMENT,
            command = "logcat -d -f {output}",
            parameters = listOf(
                TemplateParameter("output", "输出文件", "/sdcard/logcat.txt", false)
            ),
            examples = listOf("logcat -d > /sdcard/logcat.txt", "logcat -d -f /sdcard/log.txt"),
            relatedCommands = listOf("logcat_clear", "logcat_filter")
        ),
        TerminalCommandTemplate(
            id = "logcat_filter",
            name = "过滤日志",
            description = "按标签过滤日志",
            category = TemplateCategory.DEVELOPMENT,
            command = "logcat | grep {tag}",
            parameters = listOf(
                TemplateParameter("tag", "日志标签", "ActivityManager", false)
            ),
            examples = listOf("logcat | grep ActivityManager", "logcat -s MyApp:*"),
            relatedCommands = listOf("logcat_dump", "logcat_clear")
        ),
        TerminalCommandTemplate(
            id = "bugreport",
            name = "Bug报告",
            description = "生成完整的bug报告",
            category = TemplateCategory.DEVELOPMENT,
            command = "bugreport > {output}",
            riskLevel = RiskLevel.MEDIUM,
            parameters = listOf(
                TemplateParameter("output", "输出文件", "/sdcard/bugreport.txt", false)
            ),
            examples = listOf("bugreport > /sdcard/bugreport.txt"),
            relatedCommands = listOf("logcat_dump", "dumpsys")
        ),
        TerminalCommandTemplate(
            id = "dumpsys",
            name = "系统服务信息",
            description = "显示系统服务的详细信息",
            category = TemplateCategory.DEVELOPMENT,
            command = "dumpsys {service}",
            parameters = listOf(
                TemplateParameter("service", "服务名", "meminfo", false)
            ),
            examples = listOf("dumpsys meminfo", "dumpsys activity"),
            relatedCommands = listOf("sys_info", "ps_list")
        ),
        TerminalCommandTemplate(
            id = "sha256_check",
            name = "校验文件SHA256",
            description = "计算并显示文件的SHA256值",
            category = TemplateCategory.SECURITY,
            command = "sha256sum {file}",
            parameters = listOf(
                TemplateParameter("file", "文件路径", "", true)
            ),
            examples = listOf("sha256sum /sdcard/app.apk"),
            relatedCommands = listOf("file_info", "md5_check")
        ),
        TerminalCommandTemplate(
            id = "md5_check",
            name = "校验文件MD5",
            description = "计算并显示文件的MD5值",
            category = TemplateCategory.SECURITY,
            command = "md5sum {file}",
            parameters = listOf(
                TemplateParameter("file", "文件路径", "", true)
            ),
            examples = listOf("md5sum /sdcard/app.apk"),
            relatedCommands = listOf("sha256_check", "file_info")
        ),
        TerminalCommandTemplate(
            id = "backup_apk",
            name = "备份应用APK",
            description = "提取并备份指定应用的APK文件",
            category = TemplateCategory.BACKUP,
            command = "cp \$(pm path {package} | cut -d: -f2) {output}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true),
                TemplateParameter("output", "输出路径", "/sdcard/backup.apk", false)
            ),
            examples = listOf("pm path com.android.chrome"),
            relatedCommands = listOf("pkg_info", "restore_apk")
        ),
        TerminalCommandTemplate(
            id = "backup_data",
            name = "备份应用数据",
            description = "备份指定应用的数据目录",
            category = TemplateCategory.BACKUP,
            requiresRoot = true,
            riskLevel = RiskLevel.HIGH,
            command = "cp -r /data/data/{package} {output}",
            parameters = listOf(
                TemplateParameter("package", "包名", "", true),
                TemplateParameter("output", "输出目录", "/sdcard/backup", false)
            ),
            examples = listOf("cp -r /data/data/com.android.chrome /sdcard/chrome_data"),
            relatedCommands = listOf("backup_apk", "restore_data")
        ),
        TerminalCommandTemplate(
            id = "sched_task",
            name = "定时任务",
            description = "创建定时执行的任务",
            category = TemplateCategory.AUTOMATION,
            command = "at {time} <<EOF\n{command}\nEOF",
            parameters = listOf(
                TemplateParameter("time", "执行时间", "now", false),
                TemplateParameter("command", "要执行的命令", "", true)
            ),
            examples = listOf("echo 'reboot' | at now + 1 minute"),
            relatedCommands = listOf("cron_list", "sched_service")
        ),
        TerminalCommandTemplate(
            id = "battery_stats",
            name = "电池统计",
            description = "显示电池使用统计",
            category = TemplateCategory.SYSTEM_INFO,
            command = "dumpsys battery",
            examples = listOf("dumpsys battery", "dumpsys battery | grep level"),
            relatedCommands = listOf("sys_info", "top_cpu")
        ),
        TerminalCommandTemplate(
            id = "wifi_on",
            name = "开启WiFi",
            description = "开启WiFi网络",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "svc wifi enable",
            requiresRoot = true,
            riskLevel = RiskLevel.MEDIUM,
            examples = listOf("svc wifi enable"),
            relatedCommands = listOf("wifi_off", "net_status")
        ),
        TerminalCommandTemplate(
            id = "wifi_off",
            name = "关闭WiFi",
            description = "关闭WiFi网络",
            category = TemplateCategory.DEVICE_CONTROL,
            command = "svc wifi disable",
            requiresRoot = true,
            riskLevel = RiskLevel.MEDIUM,
            examples = listOf("svc wifi disable"),
            relatedCommands = listOf("wifi_on", "net_status")
        )
    )

    fun getTemplatesByCategory(category: TemplateCategory): List<TerminalCommandTemplate> {
        return allTemplates.filter { it.category == category }
    }

    fun searchTemplates(query: String): List<TerminalCommandTemplate> {
        val lowerQuery = query.lowercase()
        return allTemplates.filter {
            it.name.contains(lowerQuery, ignoreCase = true) ||
            it.description.contains(lowerQuery, ignoreCase = true) ||
            it.command.contains(lowerQuery, ignoreCase = true) ||
            it.category.displayName.contains(lowerQuery, ignoreCase = true)
        }
    }

    fun getTemplateById(id: String): TerminalCommandTemplate? {
        return allTemplates.find { it.id == id }
    }

    fun getTemplatesByIds(ids: List<String>): List<TerminalCommandTemplate> {
        return ids.mapNotNull { getTemplateById(it) }
    }

    fun getRelatedTemplates(templateId: String): List<TerminalCommandTemplate> {
        val template = getTemplateById(templateId) ?: return emptyList()
        return getTemplatesByIds(template.relatedCommands)
    }

    fun fillTemplate(template: TerminalCommandTemplate, params: Map<String, String>): String {
        var command = template.command
        template.parameters.forEach { param ->
            val value = params[param.name] ?: param.defaultValue ?: ""
            command = command.replace("{${param.name}}", value)
        }
        return command
    }
}