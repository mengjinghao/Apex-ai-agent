package com.apex.agent.mts.bridge

import android.content.Context
import com.apex.agent.core.tools.AIToolHandler
import com.apex.agent.mts.MtsEngine
import com.apex.agent.mts.executor.ExecutionConfig
import com.apex.agent.mts.executor.ToolInvoker
import com.apex.agent.mts.schema.*

class LegacyToolBridge(context: Context) {
    private val toolHandler = AIToolHandler.getInstance(context)

    /** 根据工具名称和分类自动推断其应在的Agent模式 */
    companion object {
        /** 仅在普通模式下可用的工具（基础安全操作） */
        private val normalOnlyTools = setOf(
            "read_file", "read_file_part", "read_file_full", "read_file_binary",
            "list_files", "file_exists", "file_info", "find_files",
            "grep_code", "grep_context", "download_file",
            "visit_web", "close_session", "http_request", "multipart_request", "manage_cookies",
            "query_memory", "get_memory_by_title", "calculate", "sleep",
            "device_info", "capture_screenshot", "get_page_info",
            "toast", "send_notification", "get_notifications",
            "get_system_setting",
            "start_chat_service", "stop_chat_service",
            "list_chats", "find_chat", "get_chat_messages",
            "list_character_cards",
            "browser_snapshot", "browser_take_screenshot",
            "ffmpeg_info"
        )

        /** 多Agent模式下额外可用的工具（含协作/管理工作流） */
        private val multiAgentExtra = setOf(
            "write_file", "write_file_binary", "apply_file",
            "copy_file", "move_file", "make_directory", "zip_files", "unzip_files",
            "open_file", "share_file",
            "create_memory", "update_memory", "delete_memory", "move_memory",
            "link_memories", "query_memory_links", "update_memory_link", "delete_memory_link",
            "update_user_preferences",
            "get_all_workflows", "create_workflow", "get_workflow",
            "update_workflow", "patch_workflow", "enable_workflow",
            "disable_workflow", "delete_workflow", "trigger_workflow",
            "create_new_chat", "switch_chat", "update_chat_title", "delete_chat",
            "send_message_to_ai", "send_message_to_ai_advanced",
            "agent_status",
            "browser_click", "browser_navigate", "browser_navigate_back",
            "browser_type", "browser_select_option", "browser_hover",
            "browser_fill_form", "browser_evaluate", "browser_run_code",
            "browser_tabs", "browser_close", "browser_press_key",
            "browser_resize", "browser_wait_for", "browser_handle_dialog",
            "browser_console_messages", "browser_network_requests",
            "browser_drag", "browser_file_upload",
            "ffmpeg_execute", "ffmpeg_convert",
            "trigger_tasker_event"
        )

        /** 狂暴模式下额外可用的工具（高危/破坏性操作） */
        private val berserkExtra = setOf(
            "delete_file", "execute_shell", "execute_hidden_terminal_command",
            "create_terminal_session", "execute_in_terminal_session",
            "close_terminal_session", "input_in_terminal_session",
            "get_terminal_session_screen",
            "install_app", "uninstall_app", "start_app", "stop_app",
            "list_installed_apps", "get_app_usage_time",
            "modify_system_setting", "get_device_location",
            "click_element", "tap", "long_press", "swipe",
            "set_input_text", "press_key", "run_ui_subagent",
            "execute_intent", "send_broadcast",
            "close_all_virtual_displays",
            "use_package", "package_proxy",
            "read_environment_variable", "write_environment_variable",
            "list_sandbox_packages", "set_sandbox_package_enabled",
            "execute_sandbox_script_direct",
            "restart_mcp_with_logs",
            "get_speech_services_config", "set_speech_services_config",
            "test_tts_playback",
            "list_model_configs", "create_model_config", "update_model_config",
            "delete_model_config", "list_function_model_configs",
            "get_function_model_config", "set_function_model_config",
            "test_model_config_connection",
            "find_duplicate_memories", "merge_duplicate_memories",
            "recover_deleted_memory", "rollback_memory_to_time",
            "query_operation_logs", "find_path_between_memories",
            "find_graph_related_memories", "export_memories_to_markdown",
            "export_graph_to_opml", "chain_of_thought_search",
            "battery_info", "network_info", "system_settings"
        )

        fun inferModeConfig(toolName: String): AgentModeConfig {
            val modes = mutableSetOf<AgentMode>()
            if (toolName in normalOnlyTools || toolName in multiAgentExtra || toolName in berserkExtra) {
                modes.add(AgentMode.NORMAL)
            }
            if (toolName in multiAgentExtra || toolName in berserkExtra) {
                modes.add(AgentMode.MULTI_AGENT)
            }
            if (toolName in berserkExtra) {
                modes.add(AgentMode.BERSERK)
            }
            if (modes.isEmpty()) {
                return AgentModeConfig(allowedModes = setOf(AgentMode.NORMAL, AgentMode.MULTI_AGENT))
            }
            return AgentModeConfig(allowedModes = modes)
        }

        fun applyModeConfig(spec: ToolSpec): ToolSpec {
            val config = inferModeConfig(spec.name)
            return spec.copy(modeConfig = config)
        }
    }

    val defaultInvoker: ToolInvoker = ToolInvoker { spec, args ->
        val aiTool = com.apex.agent.data.model.AITool(
            name = spec.name,
            parameters = args.map { (k, v) ->
                com.apex.agent.data.model.ToolParameter(name = k, value = v?.toString() ?: "")
            }
        )
        val result = toolHandler.executeTool(aiTool)
        if (result.success) {
            ToolOutcome.Success(
                data = (result.result as? com.apex.agent.core.tools.StringResultData)?.value
                    ?: result.result.toString(),
                metadata = mapOf("raw" to result.result.toString())
            )
        } else {
            ToolOutcome.Failure(
                error = result.error ?: "Unknown error",
                code = "TOOL_ERROR"
            )
        }
    }

    fun buildEngine(config: ExecutionConfig = ExecutionConfig()): MtsEngine {
        return MtsEngine.create(defaultInvoker, emptyList(), config)
    }

    fun scanAndRegisterAll(engine: MtsEngine) {
        val toolSpecs = buildToolSpecsFromLegacy()
        engine.registerTools(toolSpecs)
    }

    private fun buildToolSpecsFromLegacy(): List<ToolSpec> {
        val specs = mutableListOf<ToolSpec>()
        for ((name, _) in toolHandler.getAllTools()) {
            val spec = inferToolSpec(name)
            if (spec != null) specs.add(applyModeConfig(spec))
        }
        specs.addAll(buildKnownToolSpecs().map { applyModeConfig(it) })
        return specs.distinctBy { it.name }
    }

    private fun buildKnownToolSpecs(): List<ToolSpec> {
        return listOf(
            ToolSpec(
                id = "file:read_file", name = "read_file", displayName = "Read File",
                description = "Read the content of a file. Supports text and image files (OCR).",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "read", "text", "ocr"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "File path", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment (android/repo:<name>)")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "read_file"),
                parallelSafe = true,
                outputDescription = "file content, text"
            ),
            ToolSpec(
                id = "file:write_file", name = "write_file", displayName = "Write File",
                description = "Write content to a file, with append or overwrite mode.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "write", "edit", "save"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "File path", required = true),
                    ParameterSpec("content", ParameterType.STRING, "Content to write", required = true),
                    ParameterSpec("append", ParameterType.BOOLEAN, "Append instead of overwrite")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "write_file"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "file:list_files", name = "list_files", displayName = "List Files",
                description = "List files and directories in a given path.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "list", "directory", "ls", "dir"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "Directory path", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "list_files"),
                parallelSafe = true,
                outputDescription = "file list, directory listing"
            ),
            ToolSpec(
                id = "file:find_files", name = "find_files", displayName = "Find Files",
                description = "Search for files matching a pattern in a directory tree.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "search", "find", "glob"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "Search root path", required = true),
                    ParameterSpec("pattern", ParameterType.STRING, "Search pattern (e.g. *.jpg)", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment"),
                    ParameterSpec("max_depth", ParameterType.INTEGER, "Max search depth, -1=unlimited")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "find_files"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "file:grep_code", name = "grep_code", displayName = "Grep Code",
                description = "Search file contents using regex patterns across files.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("search", "grep", "regex", "code", "find"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "Search path", required = true),
                    ParameterSpec("pattern", ParameterType.STRING, "Regex pattern", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment"),
                    ParameterSpec("file_pattern", ParameterType.STRING, "File filter pattern")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "grep_code"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "file:delete_file", name = "delete_file", displayName = "Delete File",
                description = "Delete a file or directory, optionally recursive.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "delete", "remove"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "Target path", required = true),
                    ParameterSpec("recursive", ParameterType.BOOLEAN, "Delete recursively"),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "delete_file"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "file:copy_file", name = "copy_file", displayName = "Copy File",
                description = "Copy a file or directory from source to destination.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "copy", "duplicate"),
                parameters = listOf(
                    ParameterSpec("source", ParameterType.STRING, "Source path", required = true),
                    ParameterSpec("destination", ParameterType.STRING, "Destination path", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "copy_file"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "file:move_file", name = "move_file", displayName = "Move File",
                description = "Move or rename a file or directory.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "move", "rename"),
                parameters = listOf(
                    ParameterSpec("source", ParameterType.STRING, "Source path", required = true),
                    ParameterSpec("destination", ParameterType.STRING, "Destination path", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "move_file"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "file:download_file", name = "download_file", displayName = "Download File",
                description = "Download a file from a URL to a local destination.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "download", "network", "url"),
                parameters = listOf(
                    ParameterSpec("url", ParameterType.STRING, "File URL", required = true),
                    ParameterSpec("destination", ParameterType.STRING, "Save path", required = true),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "download_file"),
                constraints = ToolConstraints(requiresNetwork = true),
                parallelSafe = true
            ),
            ToolSpec(
                id = "file:apply_file", name = "apply_file", displayName = "Apply File Changes",
                description = "Apply targeted edits (replace, delete, create) to a file.",
                category = ToolCategories.FILE_SYSTEM,
                tags = setOf("file", "edit", "patch", "modify"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "File path", required = true),
                    ParameterSpec("type", ParameterType.ENUM, "Operation: replace, delete, create", required = true, enumValues = listOf("replace", "delete", "create")),
                    ParameterSpec("old", ParameterType.STRING, "Text to match (required for replace/delete)"),
                    ParameterSpec("new", ParameterType.STRING, "New text (required for replace/create)"),
                    ParameterSpec("environment", ParameterType.STRING, "Environment")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "apply_file"),
                parallelSafe = false,
                errorRecovery = FailureStrategy.RETRY_ONCE
            ),
            // Network / Web tools
            ToolSpec(
                id = "network:visit_web", name = "visit_web", displayName = "Visit Website",
                description = "Fetch and extract content from a web page URL.",
                category = ToolCategories.NETWORK,
                tags = setOf("web", "http", "url", "scrape", "fetch"),
                parameters = listOf(
                    ParameterSpec("url", ParameterType.STRING, "Page URL to visit", required = true),
                    ParameterSpec("extract_text", ParameterType.BOOLEAN, "Extract page text"),
                    ParameterSpec("extract_links", ParameterType.BOOLEAN, "Extract page links"),
                    ParameterSpec("timeout_ms", ParameterType.INTEGER, "Request timeout")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "visit_web"),
                constraints = ToolConstraints(requiresNetwork = true, timeoutMs = 60000),
                parallelSafe = true,
                outputDescription = "web content, text, links, images"
            ),
            ToolSpec(
                id = "network:http_request", name = "http_request", displayName = "HTTP Request",
                description = "Send a raw HTTP request with custom method, headers, and body.",
                category = ToolCategories.NETWORK,
                tags = setOf("http", "api", "rest", "request"),
                parameters = listOf(
                    ParameterSpec("url", ParameterType.STRING, "Request URL", required = true),
                    ParameterSpec("method", ParameterType.ENUM, "HTTP method", enumValues = listOf("GET", "POST", "PUT", "DELETE", "PATCH")),
                    ParameterSpec("headers", ParameterType.JSON, "Request headers as JSON"),
                    ParameterSpec("body", ParameterType.STRING, "Request body")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "http_request"),
                constraints = ToolConstraints(requiresNetwork = true),
                parallelSafe = true
            ),
            // UI tools
            ToolSpec(
                id = "ui:click_element", name = "click_element", displayName = "Click UI Element",
                description = "Click a UI element identified by resource ID, class name, or bounds.",
                category = ToolCategories.UI,
                tags = setOf("ui", "click", "tap", "element"),
                parameters = listOf(
                    ParameterSpec("resourceId", ParameterType.STRING, "Android resource ID"),
                    ParameterSpec("className", ParameterType.STRING, "Element class name"),
                    ParameterSpec("bounds", ParameterType.STRING, "Element bounds rect"),
                    ParameterSpec("index", ParameterType.INTEGER, "Element index if multiple match")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "click_element"),
                parallelSafe = false,
                errorRecovery = FailureStrategy.FALLBACK_CHAIN
            ),
            ToolSpec(
                id = "ui:tap", name = "tap", displayName = "Tap Screen",
                description = "Tap at specific screen coordinates.",
                category = ToolCategories.UI,
                tags = setOf("ui", "tap", "click", "coordinate"),
                parameters = listOf(
                    ParameterSpec("x", ParameterType.INTEGER, "X coordinate", required = true),
                    ParameterSpec("y", ParameterType.INTEGER, "Y coordinate", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "tap"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "ui:swipe", name = "swipe", displayName = "Swipe",
                description = "Perform a swipe/drag gesture from one point to another.",
                category = ToolCategories.UI,
                tags = setOf("ui", "swipe", "scroll", "drag", "gesture"),
                parameters = listOf(
                    ParameterSpec("start_x", ParameterType.INTEGER, "Start X", required = true),
                    ParameterSpec("start_y", ParameterType.INTEGER, "Start Y", required = true),
                    ParameterSpec("end_x", ParameterType.INTEGER, "End X", required = true),
                    ParameterSpec("end_y", ParameterType.INTEGER, "End Y", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "swipe"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "ui:capture_screenshot", name = "capture_screenshot", displayName = "Capture Screenshot",
                description = "Take a screenshot of the current device screen.",
                category = ToolCategories.UI,
                tags = setOf("ui", "screenshot", "screen", "capture"),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "capture_screenshot"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "ui:set_input_text", name = "set_input_text", displayName = "Set Input Text",
                description = "Set text in an input field on the screen.",
                category = ToolCategories.UI,
                tags = setOf("ui", "input", "type", "text"),
                parameters = listOf(
                    ParameterSpec("text", ParameterType.STRING, "Text to input", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "set_input_text"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "ui:press_key", name = "press_key", displayName = "Press Key",
                description = "Simulate a hardware key press (volume, home, back, etc.).",
                category = ToolCategories.UI,
                tags = setOf("ui", "key", "hardware", "button"),
                parameters = listOf(
                    ParameterSpec("key_code", ParameterType.STRING, "Android key code name or number", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "press_key"),
                parallelSafe = false
            ),
            // Device tools
            ToolSpec(
                id = "device:device_info", name = "device_info", displayName = "Device Info",
                description = "Get detailed device information including hardware, software, and status.",
                category = ToolCategories.DEVICE,
                tags = setOf("device", "info", "hardware", "system"),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "device_info"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "device:install_app", name = "install_app", displayName = "Install App",
                description = "Install an Android application package from a file path.",
                category = ToolCategories.APP,
                tags = setOf("app", "install", "package"),
                parameters = listOf(
                    ParameterSpec("path", ParameterType.STRING, "APK file path", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "install_app"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "device:uninstall_app", name = "uninstall_app", displayName = "Uninstall App",
                description = "Uninstall an Android application by package name.",
                category = ToolCategories.APP,
                tags = setOf("app", "uninstall", "remove"),
                parameters = listOf(
                    ParameterSpec("package_name", ParameterType.STRING, "Package name to uninstall", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "uninstall_app"),
                parallelSafe = false
            ),
            ToolSpec(
                id = "device:start_app", name = "start_app", displayName = "Start App",
                description = "Launch an Android application by package name.",
                category = ToolCategories.APP,
                tags = setOf("app", "launch", "start", "open"),
                parameters = listOf(
                    ParameterSpec("package_name", ParameterType.STRING, "Package name to launch", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "start_app"),
                parallelSafe = false
            ),
            // Browser tools
            ToolSpec(
                id = "browser:browser_snapshot", name = "browser_snapshot", displayName = "Browser Snapshot",
                description = "Capture a full accessibility snapshot of the current browser page.",
                category = ToolCategories.BROWSER,
                tags = setOf("browser", "web", "snapshot", "accessibility"),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "browser_snapshot"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "browser:browser_navigate", name = "browser_navigate", displayName = "Browser Navigate",
                description = "Navigate the browser to a specified URL.",
                category = ToolCategories.BROWSER,
                tags = setOf("browser", "navigate", "url", "web"),
                parameters = listOf(
                    ParameterSpec("url", ParameterType.STRING, "URL to navigate to", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "browser_navigate"),
                parallelSafe = false,
                errorRecovery = FailureStrategy.FALLBACK_CHAIN
            ),
            // Shell / Terminal
            ToolSpec(
                id = "system:execute_shell", name = "execute_shell", displayName = "Execute Shell Command",
                description = "Execute a shell command on the device and return the output.",
                category = ToolCategories.SYSTEM,
                tags = setOf("shell", "command", "terminal", "adb"),
                parameters = listOf(
                    ParameterSpec("command", ParameterType.STRING, "Shell command to execute", required = true),
                    ParameterSpec("timeout_ms", ParameterType.INTEGER, "Command timeout")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "execute_shell"),
                constraints = ToolConstraints(timeoutMs = 30000),
                parallelSafe = false
            ),
            // Memory tools
            ToolSpec(
                id = "memory:query_memory", name = "query_memory", displayName = "Query Memory",
                description = "Search the memory/knowledge base for relevant information.",
                category = ToolCategories.MEMORY,
                tags = setOf("memory", "search", "query", "knowledge", "remember"),
                parameters = listOf(
                    ParameterSpec("query", ParameterType.STRING, "Search query", required = true),
                    ParameterSpec("limit", ParameterType.INTEGER, "Max results"),
                    ParameterSpec("folder_path", ParameterType.STRING, "Search within specific folder")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "query_memory"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "memory:create_memory", name = "create_memory", displayName = "Create Memory",
                description = "Save a new memory entry to the knowledge base.",
                category = ToolCategories.MEMORY,
                tags = setOf("memory", "create", "save", "remember"),
                parameters = listOf(
                    ParameterSpec("title", ParameterType.STRING, "Memory title", required = true),
                    ParameterSpec("content", ParameterType.STRING, "Memory content", required = true),
                    ParameterSpec("folder_path", ParameterType.STRING, "Target folder path")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "create_memory"),
                parallelSafe = false
            ),
            // Calculate
            ToolSpec(
                id = "system:calculate", name = "calculate", displayName = "Calculate",
                description = "Evaluate a mathematical expression and return the result.",
                category = ToolCategories.SYSTEM,
                tags = setOf("math", "calculate", "compute", "expression"),
                parameters = listOf(
                    ParameterSpec("expression", ParameterType.STRING, "Math expression to evaluate", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "calculate"),
                parallelSafe = true
            ),
            // Workflow tools
            ToolSpec(
                id = "workflow:get_all_workflows", name = "get_all_workflows", displayName = "List Workflows",
                description = "Get all available workflow definitions.",
                category = ToolCategories.WORKFLOW,
                tags = setOf("workflow", "automation", "list"),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "get_all_workflows"),
                parallelSafe = true
            ),
            ToolSpec(
                id = "workflow:trigger_workflow", name = "trigger_workflow", displayName = "Trigger Workflow",
                description = "Execute a workflow by its ID.",
                category = ToolCategories.WORKFLOW,
                tags = setOf("workflow", "automation", "trigger", "run"),
                parameters = listOf(
                    ParameterSpec("workflow_id", ParameterType.STRING, "Workflow ID to trigger", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "trigger_workflow"),
                parallelSafe = false
            ),
            // Chat tools
            ToolSpec(
                id = "chat:send_message_to_ai", name = "send_message_to_ai", displayName = "Send Message to AI",
                description = "Send a message to an AI agent in a parallel conversation.",
                category = ToolCategories.CHAT,
                tags = setOf("chat", "message", "ai", "talk"),
                parameters = listOf(
                    ParameterSpec("message", ParameterType.STRING, "Message content", required = true),
                    ParameterSpec("chat_id", ParameterType.STRING, "Target chat ID")
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "send_message_to_ai"),
                parallelSafe = true,
                errorRecovery = FailureStrategy.FALLBACK_CHAIN
            ),
            // Media tools
            ToolSpec(
                id = "media:ffmpeg_execute", name = "ffmpeg_execute", displayName = "FFmpeg Execute",
                description = "Execute FFmpeg commands for media processing.",
                category = ToolCategories.MEDIA,
                tags = setOf("media", "ffmpeg", "video", "audio", "convert"),
                parameters = listOf(
                    ParameterSpec("command", ParameterType.STRING, "FFmpeg command arguments", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "ffmpeg_execute"),
                constraints = ToolConstraints(timeoutMs = 120000),
                parallelSafe = false,
                errorRecovery = FailureStrategy.FALLBACK_CHAIN
            ),
            ToolSpec(
                id = "media:ffmpeg_convert", name = "ffmpeg_convert", displayName = "FFmpeg Convert",
                description = "Simplified media file conversion interface.",
                category = ToolCategories.MEDIA,
                tags = setOf("media", "convert", "ffmpeg", "video", "audio"),
                parameters = listOf(
                    ParameterSpec("input_path", ParameterType.STRING, "Input file path", required = true),
                    ParameterSpec("output_path", ParameterType.STRING, "Output file path", required = true)
                ),
                execMode = ExecutionMode.LOCAL,
                executor = ToolExecutorRef(ExecutionMode.LOCAL, "ffmpeg_convert"),
                constraints = ToolConstraints(timeoutMs = 120000),
                parallelSafe = false
            )
        )
    }

    private fun inferToolSpec(name: String): ToolSpec? {
        val category = when {
            name.startsWith("browser_") -> ToolCategories.BROWSER
            name.startsWith("ffmpeg_") -> ToolCategories.MEDIA
            name.startsWith("workflow") || name.contains("_workflow") -> ToolCategories.WORKFLOW
            name.startsWith("chat_") || name.contains("_chat") || name.startsWith("send_message") -> ToolCategories.CHAT
            name.startsWith("memory_") || name.contains("_memory") || name.startsWith("query_memory") -> ToolCategories.MEMORY
            name.startsWith("list_files") || name.startsWith("read_file") || name.startsWith("write_file") ||
                name.contains("_file") || name.contains("grep_") || name.startsWith("download_") -> ToolCategories.FILE_SYSTEM
            name.startsWith("visit_web") || name.startsWith("http_") || name.startsWith("multipart_") ||
                name.startsWith("manage_cookies") || name.startsWith("close_session") -> ToolCategories.NETWORK
            name.startsWith("click_") || name.startsWith("tap") || name.startsWith("swipe") ||
                name.startsWith("screenshot") || name.startsWith("set_input") || name.startsWith("press_key") ||
                name.startsWith("long_press") || name.startsWith("capture_") || name.startsWith("get_page") ||
                name.startsWith("run_ui") -> ToolCategories.UI
            name.startsWith("execute_shell") || name.startsWith("create_terminal") ||
                name.startsWith("execute_in_terminal") || name.startsWith("close_terminal") ||
                name.startsWith("input_in_terminal") || name.startsWith("get_terminal") ||
                name.startsWith("execute_hidden") -> ToolCategories.TERMINAL
            name == "device_info" || name == "battery_info" || name == "network_info" ||
                name == "system_settings" || name.startsWith("get_device_") -> ToolCategories.DEVICE
            name.startsWith("install_") || name.startsWith("uninstall_") || name.startsWith("start_") ||
                name.startsWith("stop_") || name.startsWith("list_installed_") || name.startsWith("get_app_") -> ToolCategories.APP
            name == "calculate" || name == "sleep" || name.startsWith("modify_system_") ||
                name.startsWith("get_system_") || name.startsWith("toast") || name.startsWith("send_notification") ||
                name.startsWith("get_notifications") || name.startsWith("close_all_") || name.startsWith("trigger_tasker") -> ToolCategories.SYSTEM
            else -> ToolCategories.SYSTEM
        }

        return ToolSpec(
            id = "legacy:$name",
            name = name,
            displayName = name.replace("_", " ").let { it.substring(0, 1).uppercase() + it.substring(1) },
            description = "Legacy tool: $name",
            category = category,
            tags = setOf(name.split("_").firstOrNull()?.lowercase() ?: "tool"),
            execMode = ExecutionMode.LOCAL,
            executor = ToolExecutorRef(ExecutionMode.LOCAL, name),
            parallelSafe = true
        )
    }
}

fun AIToolHandler.getAllTools(): Map<String, Any> {
    val field = AIToolHandler::class.java.getDeclaredField("availableTools")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return field.get(this) as Map<String, Any>
}
