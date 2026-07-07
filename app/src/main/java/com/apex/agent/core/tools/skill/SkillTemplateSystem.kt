package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.core.tools.PackagePermission
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Skill 模板系统 - 快速创�?Skill 的模板引�?
 *
 * 提供多种预设模板，用户可以基于模板快速创建新 Skill
 */
class SkillTemplateSystem private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillTemplateSystem"
        private const val TEMPLATES_DIR = "skill_templates"
        private const val USER_TEMPLATES_DIR = "user_templates"

        @Volatile private var INSTANCE: SkillTemplateSystem? = null

        fun getInstance(context: Context): SkillTemplateSystem {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillTemplateSystem(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    @Serializable
    data class SkillTemplate(
        val id: String,
        val name: String,
        val description: String,
        val category: TemplateCategory,
        val tags: List<String>,
        val version: String = "1.0.0",
        val author: String = "System",
        val variables: List<TemplateVariable>,
        val workflow: TemplateWorkflow?,
        val files: List<TemplateFile>,
        val permissions: List<String> = emptyList(),
        val icon: String? = null,
        val difficulty: Difficulty = Difficulty.BEGINNER,
        val estimatedTime: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    @Serializable
    data class TemplateVariable(
        val name: String,
        val displayName: String,
        val description: String,
        val type: VariableType,
        val required: Boolean = true,
        val defaultValue: String? = null,
        val options: List<String>? = null,
        val min: Int? = null,
        val max: Int? = null,
        val validation: String? = null
    )

    @Serializable
    enum class VariableType {
        STRING, NUMBER, BOOLEAN, SELECT, MULTI_SELECT, FILE_PATH, DIRECTORY_PATH
    }

    @Serializable
    enum class Difficulty {
        BEGINNER, INTERMEDIATE, ADVANCED, EXPERT
    }

    @Serializable
    data class TemplateWorkflow(
        val triggerType: TriggerType,
        val nodes: List<TemplateNode>,
        val connections: List<TemplateConnection>
    )

    @Serializable
    data class TemplateNode(
        val name: String,
        val type: NodeType,
        val configTemplate: Map<String, String>,
        val positionX: Float = 0f,
        val positionY: Float = 0f
    )

    @Serializable
    data class TemplateConnection(
        val fromNode: Int,
        val toNode: Int,
        val condition: String = "ON_SUCCESS"
    )

    @Serializable
    data class TemplateFile(
        val path: String,
        val content: String,
        val isExecutable: Boolean = false
    )

    @Serializable
    enum class TemplateCategory {
        UTILITY,
        AUTOMATION,
        DATA_PROCESSING,
        NETWORK,
        FILE_MANAGEMENT,
        SYSTEM,
        COMMUNICATION,
        MEDIA,
        DEVELOPMENT,
        CUSTOM
    }

    // ========== 状�?==========

    private val _builtInTemplates = MutableStateFlow<List<SkillTemplate>>(emptyList())
    val builtInTemplates: StateFlow<List<SkillTemplate>> = _builtInTemplates.asStateFlow()

    private val _userTemplates = MutableStateFlow<List<SkillTemplate>>(emptyList())
    val userTemplates: StateFlow<List<SkillTemplate>> = _userTemplates.asStateFlow()

    private val _allTemplates = MutableStateFlow<List<SkillTemplate>>(emptyList())
    val allTemplates: StateFlow<List<SkillTemplate>> = _allTemplates.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // ========== 内置模板 ==========

    private val builtInTemplateList = listOf(
        // 自动化类模板
        createBatchOperationTemplate(),
        createScheduledTaskTemplate(),
        createEventTriggerTemplate(),

        // 数据处理类模�?
        createDataTransformTemplate(),
        createFileProcessorTemplate(),
        createJsonParserTemplate(),

        // 网络类模�?
        createHttpRequestTemplate(),
        createWebhookTemplate(),
        createApiClientTemplate(),

        // 文件管理类模�?
        createBackupTemplate(),
        createFileOrganizerTemplate(),
        createBatchRenameTemplate(),

        // 系统类模�?
        createSystemMonitorTemplate(),
        createLogAnalyzerTemplate(),

        // 开发类模板
        createCodeGeneratorTemplate(),
        createGitHelperTemplate(),
        createBuildAutomationTemplate(),

        // 通信类模�?
        createNotificationTemplate(),
        createEmailHelperTemplate()
    )

    init {
        _builtInTemplates.value = builtInTemplateList
        updateAllTemplates()
    }

    private fun updateAllTemplates() {
        _allTemplates.value = builtInTemplateList + _userTemplates.value
    }

    // ========== 模板创建方法 ==========

    private fun createBatchOperationTemplate() = SkillTemplate(
        id = "template_batch_operation",
        name = "批量操作",
        description = "对多个文件或项目执行批量操作的模�?,
        category = TemplateCategory.AUTOMATION,
        tags = listOf("batch", "automation", "files"),
        variables = listOf(
            TemplateVariable("inputPath", "输入路径", "要处理的文件或目录路�?, VariableType.DIRECTORY_PATH),
            TemplateVariable("operation", "操作类型", "要执行的批量操作", VariableType.SELECT, options = listOf("copy", "move", "delete", "rename")),
            TemplateVariable("pattern", "文件匹配", "文件名称模式（支持通配符）", VariableType.STRING, required = false, defaultValue = "*"),
            TemplateVariable("recursive", "递归处理", "是否递归处理子目�?, VariableType.BOOLEAN, required = false, defaultValue = "false")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("开�?, NodeType.TRIGGER, mapOf("triggerType" to "MANUAL")),
                TemplateNode("获取文件列表", NodeType.EXECUTE, mapOf("actionType" to "list_files")),
                TemplateNode("循环处理", NodeType.LOGIC, mapOf("operator" to "AND")),
                TemplateNode("执行操作", NodeType.EXECUTE, mapOf("actionType" to "file_operation")),
                TemplateNode("记录结果", NodeType.EXECUTE, mapOf("actionType" to "log"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3),
                TemplateConnection(3, 4)
            )
        ),
        files = listOf(
            TemplateFile("workflow.json", "{}"),
            TemplateFile("config.json", "{\"operation\": \"{{operation}}\", \"pattern\": \"{{pattern}}\"}")
        ),
        icon = "📦",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "10分钟"
    )

    private fun createScheduledTaskTemplate() = SkillTemplate(
        id = "template_scheduled_task",
        name = "定时任务",
        description = "按照设定的时间间隔或特定时间执行的任务模�?,
        category = TemplateCategory.AUTOMATION,
        tags = listOf("schedule", "automation", "task"),
        variables = listOf(
            TemplateVariable("scheduleType", "调度类型", "定时器类�?, VariableType.SELECT, options = listOf("interval", "specific_time", "cron")),
            TemplateVariable("intervalMs", "间隔时间(毫秒�?, "重复执行的间�?, VariableType.NUMBER, required = false, min = 1000, max = 86400000),
            TemplateVariable("specificTime", "特定时间", "每天执行的时间，格式 HH:mm", VariableType.STRING, required = false),
            TemplateVariable("cronExpression", "Cron表达�?, "高级 cron 表达�?, VariableType.STRING, required = false),
            TemplateVariable("taskCommand", "任务命令", "要执行的命令或脚�?, VariableType.STRING)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.SCHEDULE,
            nodes = listOf(
                TemplateNode("定时触发", NodeType.TRIGGER, mapOf("triggerType" to "SCHEDULE")),
                TemplateNode("执行任务", NodeType.EXECUTE, mapOf("actionType" to "execute_command")),
                TemplateNode("记录日志", NodeType.EXECUTE, mapOf("actionType" to "log_result"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2)
            )
        ),
        icon = "�?,
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createEventTriggerTemplate() = SkillTemplate(
        id = "template_event_trigger",
        name = "事件触发",
        description = "当特定事件发生时自动执行相应操作",
        category = TemplateCategory.AUTOMATION,
        tags = listOf("event", "trigger", "automation", "intent"),
        variables = listOf(
            TemplateVariable("eventType", "事件类型", "触发的事件类�?, VariableType.SELECT, options = listOf("intent", "speech", "tasker")),
            TemplateVariable("intentAction", "Intent Action", "要监听的 Intent Action", VariableType.STRING, required = false),
            TemplateVariable("speechPattern", "语音模式", "匹配的语音命�?, VariableType.STRING, required = false),
            TemplateVariable("responseAction", "响应动作", "事件触发后执行的动作", VariableType.SELECT, options = listOf("notify", "execute", "log"))
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.INTENT,
            nodes = listOf(
                TemplateNode("事件触发�?, NodeType.TRIGGER, mapOf("triggerType" to "INTENT")),
                TemplateNode("条件判断", NodeType.CONDITION, mapOf("left" to "{{eventType}}", "operator" to "EQ", "right" to "{{eventType}}")),
                TemplateNode("执行动作", NodeType.EXECUTE, mapOf("actionType" to "{{responseAction}}")),
                TemplateNode("反馈", NodeType.EXECUTE, mapOf("actionType" to "notify"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2, "TRUE"),
                TemplateConnection(2, 3)
            )
        ),
        icon = "�?,
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "15分钟"
    )

    private fun createDataTransformTemplate() = SkillTemplate(
        id = "template_data_transform",
        name = "数据转换",
        description = "对数据进行提取、转换、处理的模板",
        category = TemplateCategory.DATA_PROCESSING,
        tags = listOf("data", "transform", "extract", "json"),
        variables = listOf(
            TemplateVariable("inputData", "输入数据", "要处理的数据�?, VariableType.STRING),
            TemplateVariable("transformMode", "转换模式", "数据转换方式", VariableType.SELECT, options = listOf("regex", "json", "substring", "concat")),
            TemplateVariable("expression", "表达�?, "转换表达�?, VariableType.STRING),
            TemplateVariable("outputFormat", "输出格式", "输出数据格式", VariableType.SELECT, options = listOf("text", "json", "csv"), required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("输入", NodeType.TRIGGER, mapOf("triggerType" to "MANUAL")),
                TemplateNode("提取数据", NodeType.EXECUTE, mapOf("actionType" to "get_data")),
                TemplateNode("转换", NodeType.EXTRACT, mapOf("mode" to "{{transformMode}}")),
                TemplateNode("格式化输�?, NodeType.EXECUTE, mapOf("actionType" to "format_output"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3)
            )
        ),
        icon = "🔄",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "10分钟"
    )

    private fun createFileProcessorTemplate() = SkillTemplate(
        id = "template_file_processor",
        name = "文件处理�?,
        description = "读取、处理和写入文件的模�?,
        category = TemplateCategory.FILE_MANAGEMENT,
        tags = listOf("file", "read", "write", "process"),
        variables = listOf(
            TemplateVariable("sourceFile", "源文�?, "要处理的源文件路�?, VariableType.FILE_PATH),
            TemplateVariable("destFile", "目标文件", "输出文件路径", VariableType.FILE_PATH),
            TemplateVariable("processMode", "处理模式", "文件处理方式", VariableType.SELECT, options = listOf("copy", "transform", "encrypt", "compress")),
            TemplateVariable("encoding", "编码", "文件编码", VariableType.STRING, required = false, defaultValue = "UTF-8")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("读取文件", NodeType.EXECUTE, mapOf("actionType" to "read_file")),
                TemplateNode("处理", NodeType.EXECUTE, mapOf("actionType" to "process_content")),
                TemplateNode("写入文件", NodeType.EXECUTE, mapOf("actionType" to "write_file"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2)
            )
        ),
        icon = "📄",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createJsonParserTemplate() = SkillTemplate(
        id = "template_json_parser",
        name = "JSON 解析�?,
        description = "解析和提�?JSON 数据",
        category = TemplateCategory.DATA_PROCESSING,
        tags = listOf("json", "parser", "extract", "api"),
        variables = listOf(
            TemplateVariable("jsonInput", "JSON 输入", "要解析的 JSON 字符串或文件路径", VariableType.STRING),
            TemplateVariable("jsonPath", "JSON Path", "用于提取数据�?JSONPath 表达�?, VariableType.STRING),
            TemplateVariable("defaultValue", "默认�?, "提取失败时的默认�?, VariableType.STRING, required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("输入JSON", NodeType.EXECUTE, mapOf("actionType" to "get_json_data")),
                TemplateNode("解析", NodeType.EXTRACT, mapOf("mode" to "JSON")),
                TemplateNode("验证", NodeType.CONDITION, mapOf("left" to "{{result}}", "operator" to "NE", "right" to "null")),
                TemplateNode("输出", NodeType.EXECUTE, mapOf("actionType" to "output_result"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3, "TRUE")
            )
        ),
        icon = "📋",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createHttpRequestTemplate() = SkillTemplate(
        id = "template_http_request",
        name = "HTTP 请求",
        description = "发�?HTTP 请求并处理响�?,
        category = TemplateCategory.NETWORK,
        tags = listOf("http", "request", "api", "network"),
        variables = listOf(
            TemplateVariable("url", "URL", "请求的完�?URL 地址", VariableType.STRING),
            TemplateVariable("method", "方法", "HTTP 请求方法", VariableType.SELECT, options = listOf("GET", "POST", "PUT", "DELETE", "PATCH")),
            TemplateVariable("headers", "请求�?, "HTTP 请求�?(JSON 格式�?, VariableType.STRING, required = false),
            TemplateVariable("body", "请求�?, "HTTP 请求体内�?, VariableType.STRING, required = false),
            TemplateVariable("timeout", "超时时间", "请求超时时间(毫秒�?, VariableType.NUMBER, required = false, defaultValue = "30000")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("发送请�?, NodeType.EXECUTE, mapOf("actionType" to "http_request")),
                TemplateNode("检查状�?, NodeType.CONDITION, mapOf("left" to "{{statusCode}}", "operator" to "GTE", "right" to "200")),
                TemplateNode("解析响应", NodeType.EXECUTE, mapOf("actionType" to "parse_response")),
                TemplateNode("错误处理", NodeType.EXECUTE, mapOf("actionType" to "handle_error"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2, "TRUE"),
                TemplateConnection(1, 3, "FALSE")
            )
        ),
        icon = "🌐",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "10分钟"
    )

    private fun createWebhookTemplate() = SkillTemplate(
        id = "template_webhook",
        name = "Webhook 处理",
        description = "接收和处�?Webhook 请求的模�?,
        category = TemplateCategory.NETWORK,
        tags = listOf("webhook", "callback", "api"),
        variables = listOf(
            TemplateVariable("webhookPath", "Webhook 路径", "Webhook 端点路径", VariableType.STRING),
            TemplateVariable("allowedMethods", "允许的方�?, "接受�?HTTP 方法", VariableType.MULTI_SELECT, options = listOf("GET", "POST", "PUT", "DELETE")),
            TemplateVariable("verifyToken", "验证令牌", "用于验证请求的令�?可选）", VariableType.STRING, required = false),
            TemplateVariable("responseType", "响应类型", "返回的响应类�?, VariableType.SELECT, options = listOf("json", "text", "redirect"), required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.INTENT,
            nodes = listOf(
                TemplateNode("接收请求", NodeType.TRIGGER, mapOf("triggerType" to "INTENT")),
                TemplateNode("验证", NodeType.CONDITION, mapOf("left" to "{{token}}", "operator" to "EQ", "right" to "{{verifyToken}}")),
                TemplateNode("处理数据", NodeType.EXECUTE, mapOf("actionType" to "process_webhook_data")),
                TemplateNode("返回响应", NodeType.EXECUTE, mapOf("actionType" to "send_response"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2, "TRUE"),
                TemplateConnection(2, 3)
            )
        ),
        icon = "🪝",
        difficulty = Difficulty.ADVANCED,
        estimatedTime = "20分钟"
    )

    private fun createApiClientTemplate() = SkillTemplate(
        id = "template_api_client",
        name = "API 客户�?,
        description = "构建 API 客户端的模板，支持认证和错误重试",
        category = TemplateCategory.NETWORK,
        tags = listOf("api", "client", "auth", "retry"),
        variables = listOf(
            TemplateVariable("baseUrl", "基础 URL", "API 基础地址", VariableType.STRING),
            TemplateVariable("authType", "认证类型", "API 认证方式", VariableType.SELECT, options = listOf("none", "bearer", "basic", "api_key", "oauth2")),
            TemplateVariable("authToken", "认证令牌", "认证令牌或密�?, VariableType.STRING, required = false),
            TemplateVariable("retryCount", "重试次数", "请求失败时的重试次数", VariableType.NUMBER, required = false, defaultValue = "3"),
            TemplateVariable("endpoints", "端点列表", "要使用的 API 端点", VariableType.STRING)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("初始�?, NodeType.EXECUTE, mapOf("actionType" to "init_client")),
                TemplateNode("认证", NodeType.EXECUTE, mapOf("actionType" to "authenticate")),
                TemplateNode("请求", NodeType.EXECUTE, mapOf("actionType" to "api_request")),
                TemplateNode("重试逻辑", NodeType.LOGIC, mapOf("operator" to "OR")),
                TemplateNode("处理响应", NodeType.EXECUTE, mapOf("actionType" to "handle_response"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3),
                TemplateConnection(3, 4, "TRUE"),
                TemplateConnection(2, 4, "FALSE")
            )
        ),
        icon = "🔌",
        difficulty = Difficulty.ADVANCED,
        estimatedTime = "30分钟"
    )

    private fun createBackupTemplate() = SkillTemplate(
        id = "template_backup",
        name = "备份工具",
        description = "对文件或目录进行备份的模�?,
        category = TemplateCategory.FILE_MANAGEMENT,
        tags = listOf("backup", "copy", "restore", "files"),
        variables = listOf(
            TemplateVariable("sourcePath", "源路�?, "要备份的文件或目�?, VariableType.DIRECTORY_PATH),
            TemplateVariable("backupDir", "备份目录", "备份文件存放位置", VariableType.DIRECTORY_PATH),
            TemplateVariable("compression", "压缩", "是否压缩备份文件", VariableType.BOOLEAN, required = false, defaultValue = "true"),
            TemplateVariable("includeTimestamp", "添加时间�?, "备份文件名是否包含时间戳", VariableType.BOOLEAN, required = false, defaultValue = "true"),
            TemplateVariable("retentionDays", "保留天数", "备份文件保留天数", VariableType.NUMBER, required = false, defaultValue = "30")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("检查源", NodeType.EXECUTE, mapOf("actionType" to "check_source")),
                TemplateNode("创建备份", NodeType.EXECUTE, mapOf("actionType" to "create_backup")),
                TemplateNode("压缩", NodeType.EXECUTE, mapOf("actionType" to "compress_backup")),
                TemplateNode("清理旧备�?, NodeType.EXECUTE, mapOf("actionType" to "cleanup_old_backups")),
                TemplateNode("记录日志", NodeType.EXECUTE, mapOf("actionType" to "log"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3),
                TemplateConnection(3, 4)
            )
        ),
        icon = "💾",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "15分钟"
    )

    private fun createFileOrganizerTemplate() = SkillTemplate(
        id = "template_file_organizer",
        name = "文件整理",
        description = "根据规则自动整理文件的模�?,
        category = TemplateCategory.FILE_MANAGEMENT,
        tags = listOf("organize", "files", "sort", "automation"),
        variables = listOf(
            TemplateVariable("sourceDir", "源目�?, "要整理的目录", VariableType.DIRECTORY_PATH),
            TemplateVariable("ruleType", "整理规则", "文件整理方式", VariableType.SELECT, options = listOf("extension", "date", "size", "name_pattern")),
            TemplateVariable("targetDir", "目标目录", "整理后的目标目录", VariableType.DIRECTORY_PATH),
            TemplateVariable("createSubdirs", "创建子目�?, "是否按规则创建子目录", VariableType.BOOLEAN, required = false, defaultValue = "true")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("扫描文件", NodeType.EXECUTE, mapOf("actionType" to "scan_files")),
                TemplateNode("分类", NodeType.EXECUTE, mapOf("actionType" to "classify_files")),
                TemplateNode("移动文件", NodeType.EXECUTE, mapOf("actionType" to "move_files")),
                TemplateNode("生成报告", NodeType.EXECUTE, mapOf("actionType" to "generate_report"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3)
            )
        ),
        icon = "📁",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "10分钟"
    )

    private fun createBatchRenameTemplate() = SkillTemplate(
        id = "template_batch_rename",
        name = "批量重命�?,
        description = "批量重命名文件的模板",
        category = TemplateCategory.FILE_MANAGEMENT,
        tags = listOf("rename", "batch", "files"),
        variables = listOf(
            TemplateVariable("directory", "目录", "要重命名文件所在的目录", VariableType.DIRECTORY_PATH),
            TemplateVariable("pattern", "文件模式", "要匹配的文件名模�?, VariableType.STRING, defaultValue = "*"),
            TemplateVariable("renameMode", "重命名模�?, "重命名方�?, VariableType.SELECT, options = listOf("prefix", "suffix", "replace", "sequence", "date")),
            TemplateVariable("newValue", "新�?, "前缀/后缀/替换内容", VariableType.STRING),
            TemplateVariable("preview", "预览模式", "仅预览不实际重命�?, VariableType.BOOLEAN, required = false, defaultValue = "true")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("预览", NodeType.EXECUTE, mapOf("actionType" to "preview_rename")),
                TemplateNode("确认", NodeType.CONDITION, mapOf("left" to "{{preview}}", "operator" to "EQ", "right" to "false")),
                TemplateNode("执行重命�?, NodeType.EXECUTE, mapOf("actionType" to "execute_rename")),
                TemplateNode("验证", NodeType.EXECUTE, mapOf("actionType" to "verify_rename"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2, "FALSE"),
                TemplateConnection(2, 3)
            )
        ),
        icon = "✏️",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createSystemMonitorTemplate() = SkillTemplate(
        id = "template_system_monitor",
        name = "系统监控",
        description = "监控系统状态并在异常时通知",
        category = TemplateCategory.SYSTEM,
        tags = listOf("monitor", "system", "cpu", "memory", "notification"),
        variables = listOf(
            TemplateVariable("monitorInterval", "监控间隔", "状态检查间�?秒）", VariableType.NUMBER, defaultValue = "60"),
            TemplateVariable("cpuThreshold", "CPU 阈�?, "CPU 使用率告警阈�?%)", VariableType.NUMBER, defaultValue = "80"),
            TemplateVariable("memoryThreshold", "内存阈�?, "内存使用率告警阈�?%)", VariableType.NUMBER, defaultValue = "85"),
            TemplateVariable("batteryThreshold", "电池阈�?, "电池电量告警阈�?%)", VariableType.NUMBER, required = false),
            TemplateVariable("notifyOnAlert", "异常通知", "异常时发送通知", VariableType.BOOLEAN, required = false, defaultValue = "true")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.SCHEDULE,
            nodes = listOf(
                TemplateNode("定时检�?, NodeType.TRIGGER, mapOf("triggerType" to "SCHEDULE")),
                TemplateNode("获取系统信息", NodeType.EXECUTE, mapOf("actionType" to "get_system_info")),
                TemplateNode("检查阈�?, NodeType.CONDITION, mapOf("left" to "{{cpuUsage}}", "operator" to "GT", "right" to "{{cpuThreshold}}")),
                TemplateNode("发送通知", NodeType.EXECUTE, mapOf("actionType" to "send_notification")),
                TemplateNode("记录日志", NodeType.EXECUTE, mapOf("actionType" to "log"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3, "TRUE"),
                TemplateConnection(2, 4, "FALSE")
            )
        ),
        icon = "📊",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "15分钟"
    )

    private fun createLogAnalyzerTemplate() = SkillTemplate(
        id = "template_log_analyzer",
        name = "日志分析",
        description = "分析日志文件并提取关键信�?,
        category = TemplateCategory.SYSTEM,
        tags = listOf("log", "analyze", "error", "pattern"),
        variables = listOf(
            TemplateVariable("logFile", "日志文件", "要分析的日志文件路径", VariableType.FILE_PATH),
            TemplateVariable("errorPattern", "错误模式", "要匹配的的错误正则表达式", VariableType.STRING, defaultValue = "ERROR|FATAL|EXCEPTION"),
            TemplateVariable("timeRange", "时间范围", "分析的时间范�?, VariableType.STRING, required = false),
            TemplateVariable("outputFormat", "输出格式", "分析结果输出格式", VariableType.SELECT, options = listOf("summary", "detailed", "json"), required = false, defaultValue = "summary")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("读取日志", NodeType.EXECUTE, mapOf("actionType" to "read_log_file")),
                TemplateNode("过滤错误", NodeType.EXECUTE, mapOf("actionType" to "filter_errors")),
                TemplateNode("统计分析", NodeType.EXECUTE, mapOf("actionType" to "analyze_patterns")),
                TemplateNode("生成报告", NodeType.EXECUTE, mapOf("actionType" to "generate_report"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3)
            )
        ),
        icon = "📝",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "20分钟"
    )

    private fun createCodeGeneratorTemplate() = SkillTemplate(
        id = "template_code_generator",
        name = "代码生成�?,
        description = "根据模板生成代码文件",
        category = TemplateCategory.DEVELOPMENT,
        tags = listOf("code", "generator", "template", "development"),
        variables = listOf(
            TemplateVariable("templateFile", "代码模板", "代码模板文件路径", VariableType.FILE_PATH),
            TemplateVariable("outputFile", "输出文件", "生成的代码文件路�?, VariableType.FILE_PATH),
            TemplateVariable("language", "语言", "目标编程语言", VariableType.SELECT, options = listOf("kotlin", "java", "python", "javascript", "typescript", "go")),
            TemplateVariable("className", "类名", "要生成的类名", VariableType.STRING),
            TemplateVariable("packageName", "包名", "包名(用于 Java/Kotlin)", VariableType.STRING, required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("加载模板", NodeType.EXECUTE, mapOf("actionType" to "load_template")),
                TemplateNode("替换变量", NodeType.EXECUTE, mapOf("actionType" to "replace_variables")),
                TemplateNode("格式化代�?, NodeType.EXECUTE, mapOf("actionType" to "format_code")),
                TemplateNode("写入文件", NodeType.EXECUTE, mapOf("actionType" to "write_file"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3)
            )
        ),
        icon = "⚙️",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "10分钟"
    )

    private fun createGitHelperTemplate() = SkillTemplate(
        id = "template_git_helper",
        name = "Git 助手",
        description = "常用 Git 操作辅助工具",
        category = TemplateCategory.DEVELOPMENT,
        tags = listOf("git", "version", "control", "commit"),
        variables = listOf(
            TemplateVariable("repoPath", "仓库路径", "Git 仓库本地路径", VariableType.DIRECTORY_PATH),
            TemplateVariable("operation", "操作", "要执行的 Git 操作", VariableType.SELECT, options = listOf("status", "commit", "push", "pull", "branch", "log")),
            TemplateVariable("commitMessage", "提交信息", "Git 提交信息", VariableType.STRING, required = false),
            TemplateVariable("branchName", "分支�?, "目标分支名称", VariableType.STRING, required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("检查状�?, NodeType.EXECUTE, mapOf("actionType" to "git_status")),
                TemplateNode("执行操作", NodeType.EXECUTE, mapOf("actionType" to "git_operation")),
                TemplateNode("显示结果", NodeType.EXECUTE, mapOf("actionType" to "display_output"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2)
            )
        ),
        icon = "🔀",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createBuildAutomationTemplate() = SkillTemplate(
        id = "template_build_automation",
        name = "构建自动�?,
        description = "自动化项目构建流�?,
        category = TemplateCategory.DEVELOPMENT,
        tags = listOf("build", "automation", "gradle", "compile"),
        variables = listOf(
            TemplateVariable("projectPath", "项目路径", "项目根目录路�?, VariableType.DIRECTORY_PATH),
            TemplateVariable("buildType", "构建类型", "构建类型", VariableType.SELECT, options = listOf("debug", "release", "clean", "assemble")),
            TemplateVariable("parallelBuild", "并行构建", "是否启用并行构建", VariableType.BOOLEAN, required = false, defaultValue = "true"),
            TemplateVariable("runTests", "运行测试", "构建后是否运行测�?, VariableType.BOOLEAN, required = false, defaultValue = "false")
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("准备环境", NodeType.EXECUTE, mapOf("actionType" to "prepare_environment")),
                TemplateNode("执行构建", NodeType.EXECUTE, mapOf("actionType" to "execute_build")),
                TemplateNode("运行测试", NodeType.CONDITION, mapOf("left" to "{{runTests}}", "operator" to "EQ", "right" to "true")),
                TemplateNode("验证结果", NodeType.EXECUTE, mapOf("actionType" to "verify_build")),
                TemplateNode("输出产物", NodeType.EXECUTE, mapOf("actionType" to "output_artifacts"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3, "TRUE"),
                TemplateConnection(2, 4, "FALSE"),
                TemplateConnection(3, 4),
                TemplateConnection(4, 5, "TRUE")
            )
        ),
        icon = "🔨",
        difficulty = Difficulty.ADVANCED,
        estimatedTime = "30分钟"
    )

    private fun createNotificationTemplate() = SkillTemplate(
        id = "template_notification",
        name = "通知发�?,
        description = "发送各种类型通知的模�?,
        category = TemplateCategory.COMMUNICATION,
        tags = listOf("notification", "alert", "message"),
        variables = listOf(
            TemplateVariable("channelId", "渠道ID", "通知渠道标识�?, VariableType.STRING),
            TemplateVariable("title", "标题", "通知标题", VariableType.STRING),
            TemplateVariable("content", "内容", "通知内容", VariableType.STRING),
            TemplateVariable("priority", "优先�?, "通知优先�?, VariableType.SELECT, options = listOf("low", "normal", "high", "max"), required = false, defaultValue = "normal"),
            TemplateVariable("actions", "操作", "通知操作按钮(JSON数组�?, VariableType.STRING, required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("构建通知", NodeType.EXECUTE, mapOf("actionType" to "build_notification")),
                TemplateNode("验证内容", NodeType.CONDITION, mapOf("left" to "{{title}}", "operator" to "NE", "right" to "\"\"")),
                TemplateNode("发�?, NodeType.EXECUTE, mapOf("actionType" to "send_notification")),
                TemplateNode("记录", NodeType.EXECUTE, mapOf("actionType" to "log"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2, "TRUE"),
                TemplateConnection(1, 3, "FALSE"),
                TemplateConnection(2, 3)
            )
        ),
        icon = "🔔",
        difficulty = Difficulty.BEGINNER,
        estimatedTime = "5分钟"
    )

    private fun createEmailHelperTemplate() = SkillTemplate(
        id = "template_email_helper",
        name = "邮件助手",
        description = "发送和管理邮件的模�?,
        category = TemplateCategory.COMMUNICATION,
        tags = listOf("email", "mail", "smtp", "send"),
        variables = listOf(
            TemplateVariable("smtpHost", "SMTP 主机", "SMTP 服务器地址", VariableType.STRING),
            TemplateVariable("smtpPort", "SMTP 端口", "SMTP 服务器端�?, VariableType.NUMBER, defaultValue = "587"),
            TemplateVariable("username", "用户�?, "邮箱用户�?, VariableType.STRING),
            TemplateVariable("password", "密码", "邮箱密码或授权码", VariableType.STRING),
            TemplateVariable("to", "收件�?, "收件人邮箱地址", VariableType.STRING),
            TemplateVariable("subject", "主题", "邮件主题", VariableType.STRING),
            TemplateVariable("body", "正文", "邮件正文内容", VariableType.STRING),
            TemplateVariable("attachments", "附件", "附件文件路径(逗号分隔�?, VariableType.STRING, required = false)
        ),
        workflow = TemplateWorkflow(
            triggerType = TriggerType.MANUAL,
            nodes = listOf(
                TemplateNode("连接服务�?, NodeType.EXECUTE, mapOf("actionType" to "smtp_connect")),
                TemplateNode("构建邮件", NodeType.EXECUTE, mapOf("actionType" to "build_email")),
                TemplateNode("发�?, NodeType.EXECUTE, mapOf("actionType" to "smtp_send")),
                TemplateNode("断开连接", NodeType.EXECUTE, mapOf("actionType" to "smtp_disconnect")),
                TemplateNode("记录结果", NodeType.EXECUTE, mapOf("actionType" to "log"))
            ),
            connections = listOf(
                TemplateConnection(0, 1),
                TemplateConnection(1, 2),
                TemplateConnection(2, 3),
                TemplateConnection(3, 4)
            )
        ),
        icon = "📧",
        difficulty = Difficulty.INTERMEDIATE,
        estimatedTime = "15分钟"
    )

    // ========== 公开 API ==========

    suspend fun loadUserTemplates() = withContext(Dispatchers.IO) {
        _isLoading.value = true
        try {
            val templatesDir = File(context.filesDir, USER_TEMPLATES_DIR)
            if (!templatesDir.exists()) {
                templatesDir.mkdirs()
            }

            val templates = mutableListOf<SkillTemplate>()
            templatesDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
                try {
                    val json = file.readText()
                    val template = Json.decodeFromString<SkillTemplate>(json)
                    templates.add(template)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to load user template: ${file.name}", e)
                }
            }

            _userTemplates.value = templates
            updateAllTemplates()
        } finally {
            _isLoading.value = false
        }
    }

    fun getTemplateById(id: String): SkillTemplate? {
        return _allTemplates.value.find { it.id == id }
    }

    fun getTemplatesByCategory(category: TemplateCategory): List<SkillTemplate> {
        return _allTemplates.value.filter { it.category == category }
    }

    fun searchTemplates(query: String): List<SkillTemplate> {
        val lowerQuery = query.lowercase()
        return _allTemplates.value.filter { template ->
            template.name.lowercase().contains(lowerQuery) ||
            template.description.lowercase().contains(lowerQuery) ||
            template.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    suspend fun saveUserTemplate(template: SkillTemplate): Result<SkillTemplate> = withContext(Dispatchers.IO) {
        try {
            val templatesDir = File(context.filesDir, USER_TEMPLATES_DIR)
            if (!templatesDir.exists()) {
                templatesDir.mkdirs()
            }

            val fileName = "${template.id}.json"
            val file = File(templatesDir, fileName)
            val json = Json.encodeToString(template)
            file.writeText(json)

            loadUserTemplates()
            Result.success(template)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save user template", e)
            Result.failure(e)
        }
    }

    suspend fun deleteUserTemplate(templateId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val templatesDir = File(context.filesDir, USER_TEMPLATES_DIR)
            val file = File(templatesDir, "${templateId}.json")
            val result = file.delete()

            if (result) {
                loadUserTemplates()
            }
            result
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete user template: ${templateId}", e)
            false
        }
    }

    /**
     * 从模板创建新�?Skill
     */
    suspend fun createSkillFromTemplate(
        template: SkillTemplate,
        variables: Map<String, String>,
        skillName: String? = null,
        skillDir: File? = null
    ): Result<SkillCreationResult> = withContext(Dispatchers.IO) {
        try {
            val finalSkillName = skillName ?: template.name
            val skillsDir = skillDir ?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "logistra/skills"
            )

            if (!skillsDir.exists()) {
                skillsDir.mkdirs()
            }

            val skillDirectory = File(skillsDir, finalSkillName)
            if (skillDirectory.exists()) {
                return@withContext Result.failure(Exception("Skill directory already exists: ${finalSkillName}"))
            }
            skillDirectory.mkdirs()

            // 替换变量
            val replacedContent = replaceVariables(template, variables)

            // 生成 SKILL.md
            val skillMdContent = buildSkillMd(template, finalSkillName, replacedContent)
            File(skillDirectory, "SKILL.md").writeText(skillMdContent)

            // 生成工作流文�?
            if (template.workflow != null) {
                val workflowContent = buildWorkflowJson(template.workflow, replacedContent)
                File(skillDirectory, "workflow.json").writeText(workflowContent)
            }

            // 生成配置文件
            template.files.forEach { templateFile ->
                val filePath = templateFile.path.replace("{{skillName}}", finalSkillName)
                val file = File(skillDirectory, filePath)
                file.parentFile?.mkdirs()
                file.writeText(templateFile.content)
                if (templateFile.isExecutable) {
                    file.setExecutable(true)
                }
            }

            // 生成 README.md
            val readmeContent = buildReadme(template, finalSkillName)
            File(skillDirectory, "README.md").writeText(readmeContent)

            val result = SkillCreationResult(
                skillName = finalSkillName,
                skillDirectory = skillDirectory,
                templateId = template.id,
                variables = variables
            )

            Result.success(result)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to create skill from template", e)
            Result.failure(e)
        }
    }

    private fun replaceVariables(template: SkillTemplate, variables: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        template.variables.forEach { variable ->
            val value = variables[variable.name] ?: variable.defaultValue ?: ""
            result[variable.name] = value
        }
        return result
    }

    private fun buildSkillMd(template: SkillTemplate, skillName: String, variables: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("---")
        sb.appendLine("name: ${skillName}")
        sb.appendLine("description: ${template.description}")
        sb.appendLine("version: 1.0.0")
        sb.appendLine("author: ${template.author}")
        sb.appendLine("created_from: ${template.id}")

        if (template.permissions.isNotEmpty()) {
            sb.appendLine("permissions:")
            template.permissions.forEach { perm ->
                sb.appendLine("  - ${perm}")
            }
        }

        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("# ${skillName}")
        sb.appendLine()
        sb.appendLine(template.description)
        sb.appendLine()
        sb.appendLine("## 使用说明")
        sb.appendLine()
        sb.appendLine("本技能基于模板�?{template.name}」创建�?)
        sb.appendLine()
        sb.appendLine("### 配置参数")
        sb.appendLine()
        template.variables.forEach { variable ->
            val value = variables[variable.name] ?: variable.defaultValue ?: "(未设置）"
            sb.appendLine("- **${variable.displayName}** (`${variable.name}`): ${variable.description} (当前�? `${value}`)")
        }
        sb.appendLine()
        sb.appendLine("## 工作流程")
        sb.appendLine()
        if (template.workflow != null) {
            template.workflow.nodes.forEachIndexed { index, node ->
                sb.appendLine("${index + 1}. ${node.name} (${node.type})")
            }
        } else {
            sb.appendLine("此模板不包含预定义工作流程�?)
        }

        return sb.toString()
    }

    private fun buildWorkflowJson(workflow: TemplateWorkflow, variables: Map<String, String>): String {
        val nodes = workflow.nodes.mapIndexed { index, templateNode ->
            val configReplaced = templateNode.configTemplate.mapValues { (_, value) ->
                replaceVariablePlaceholders(value, variables)
            }

            WorkflowNode(
                id = "node_${index}",
                name = templateNode.name,
                type = templateNode.type,
                position = NodePosition(templateNode.positionX, templateNode.positionY),
                config = NodeConfig(
                    triggerConfig = if (templateNode.type == NodeType.TRIGGER) {
                        TriggerConfig(TriggerType.valueOf(configReplaced["triggerType"] ?: "MANUAL"))
                    } else null,
                    actionType = if (templateNode.type == NodeType.EXECUTE) {
                        configReplaced["actionType"]
                    } else null,
                    actionConfig = if (templateNode.type == NodeType.EXECUTE) {
                        configReplaced.filterKeys { it != "actionType" && it != "triggerType" }
                            .mapValues { ParameterValue.StaticValue(it.value) }
                    } else null,
                    mode = if (templateNode.type == NodeType.EXTRACT) {
                        ExtractMode.valueOf(configReplaced["mode"] ?: "REGEX")
                    } else null,
                    operator = if (templateNode.type in listOf(NodeType.CONDITION, NodeType.LOGIC)) {
                        configReplaced["operator"]
                    } else null,
                    left = if (templateNode.type == NodeType.CONDITION) {
                        ParameterValue.fromAny(configReplaced["left"])
                    } else null,
                    right = if (templateNode.type == NodeType.CONDITION) {
                        ParameterValue.fromAny(configReplaced["right"])
                    } else null
                )
            )
        }

        val connections = workflow.connections.map { conn ->
            WorkflowConnection(
                sourceNodeId = "node_${conn.fromNode}",
                targetNodeId = "node_${conn.toNode}",
                condition = ConnectionCondition.fromString(conn.condition)
            )
        }

        val definition = WorkflowDefinition(
            name = "Template Workflow",
            nodes = nodes,
            connections = connections
        )

        return Json.encodeToString(definition)
    }

    private fun replaceVariablePlaceholders(text: String, variables: Map<String, String>): String {
        var result = text
        variables.forEach { (key, value) ->
            result = result.replace("{{${key}}}", value)
        }
        return result
    }

    private fun buildReadme(template: SkillTemplate, skillName: String): String {
        val sb = StringBuilder()
        sb.appendLine("# ${skillName}")
        sb.appendLine()
        sb.appendLine("> ${template.description}")
        sb.appendLine()
        sb.appendLine("**模板来源**: ${template.name} (${template.id})")
        sb.appendLine()
        sb.appendLine("## 概述")
        sb.appendLine()
        sb.appendLine(template.description)
        sb.appendLine()
        sb.appendLine("## 使用方法")
        sb.appendLine()
        sb.appendLine("1. 根据需要修�?`SKILL.md` 中的配置参数")
        sb.appendLine("2. 如有需要，编辑 `workflow.json` 调整工作流程")
        sb.appendLine("3. 测试并使用此技�?)
        sb.appendLine()
        sb.appendLine("## 参数说明")
        sb.appendLine()
        template.variables.forEach { variable ->
            sb.appendLine("| `${variable.name}` | ${variable.displayName} | ${variable.description} |")
        }
        sb.appendLine()
        sb.appendLine("## 难度")
        sb.appendLine()
        sb.appendLine("${template.difficulty.name}")
        if (template.estimatedTime != null) {
            sb.appendLine()
            sb.appendLine("**预计完成时间**: ${template.estimatedTime}")
        }
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("*此文件由 Skill 模板系统自动生成*")

        return sb.toString()
    }

    data class SkillCreationResult(
        val skillName: String,
        val skillDirectory: File,
        val templateId: String,
        val variables: Map<String, String>
    )

    fun getBuiltInTemplateCount(): Int = builtInTemplateList.size

    fun getUserTemplateCount(): Int = _userTemplates.value.size

    fun getCategoryDisplayNames(): Map<TemplateCategory, String> = mapOf(
        TemplateCategory.UTILITY to "工具�?,
        TemplateCategory.AUTOMATION to "自动�?,
        TemplateCategory.DATA_PROCESSING to "数据处理",
        TemplateCategory.NETWORK to "网络",
        TemplateCategory.FILE_MANAGEMENT to "文件管理",
        TemplateCategory.SYSTEM to "系统",
        TemplateCategory.COMMUNICATION to "通信",
        TemplateCategory.MEDIA to "媒体",
        TemplateCategory.DEVELOPMENT to "开�?,
        TemplateCategory.CUSTOM to "自定�?
    )

    fun getDifficultyDisplayNames(): Map<Difficulty, String> = mapOf(
        Difficulty.BEGINNER to "入门",
        Difficulty.INTERMEDIATE to "进阶",
        Difficulty.ADVANCED to "高级",
        Difficulty.EXPERT to "专家"
    )
}
