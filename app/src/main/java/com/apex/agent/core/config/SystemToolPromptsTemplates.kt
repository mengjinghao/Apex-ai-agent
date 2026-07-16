package com.apex.core.config

import com.apex.data.model.SystemToolPromptCategory
import com.apex.data.model.ToolPrompt
import com.apex.data.model.ToolParameterSchema

object SystemToolPromptsTemplates {

    val internalToolCategoriesEn: List<SystemToolPromptCategory> =
        listOf(
            SystemToolPromptCategory(
                categoryName = "Internal Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "execute_shell",
                            description = "Execute a device shell command.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "shell command to execute",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "create_terminal_session",
                            description = "Create or get a terminal session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_name",
                                        type = "string",
                                        description = "terminal session name",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_in_terminal_session",
                            description = "Execute a command in a terminal session and collect full output.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "command to execute",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, command timeout in milliseconds",
                                        required = false,
                                        default = "1800000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_hidden_terminal_command",
                            description = "Execute a command in a hidden non-PTY terminal executor. Commands using the same executor_key reuse the same hidden login context and are not shown in the visible terminal UI.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "command",
                                        type = "string",
                                        description = "command to execute",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "executor_key",
                                        type = "string",
                                        description = "optional, hidden executor key used to reuse the same background shell context",
                                        required = false,
                                        default = "default"
                                    ),
                                    ToolParameterSchema(
                                        name = "timeout_ms",
                                        type = "integer",
                                        description = "optional, command timeout in milliseconds",
                                        required = false,
                                        default = "120000"
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "input_in_terminal_session",
                            description = "Write input to a terminal session. At least one of input or control is required. Typical usage is sending input first, then control=enter to submit.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "input",
                                        type = "string",
                                        description = "text to write to the terminal (can include newlines)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "control",
                                        type = "string",
                                        description = "control key or modifier (e.g. enter/tab/esc/up/down/left/right/home/end/pageup/pagedown, or ctrl with input=c for Ctrl+C)",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "close_terminal_session",
                            description = "Close a terminal session.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "get_terminal_session_screen",
                            description = "Get only the current visible PTY screen content for a terminal session (single screen, no scrollback/history).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "session_id",
                                        type = "string",
                                        description = "terminal session id",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "browser_click",
                            description = "Click an element on the current page by browser_snapshot ref, including refs inside same-origin iframes.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "ref", type = "string", description = "target element ref from browser_snapshot output; provide ref or selector", required = false),
                                    ToolParameterSchema(name = "selector", type = "string", description = "optional CSS selector fallback when ref is not available", required = false),
                                    ToolParameterSchema(name = "element", type = "string", description = "optional, human-readable element description", required = false),
                                    ToolParameterSchema(name = "doubleClick", type = "boolean", description = "optional, perform a double click instead of a single click", required = false, default = "false"),
                                    ToolParameterSchema(name = "button", type = "string", description = "optional mouse button: left/right/middle", required = false, default = "left"),
                                    ToolParameterSchema(name = "modifiers", type = "array", description = "optional modifier keys array: Alt/Control/ControlOrMeta/Meta/Shift", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_close",
                            description = "Close the current browser tab. Closing the last tab also closes the browser overlay.",
                            parametersStructured = emptyList()
                        ),
                        ToolPrompt(
                            name = "browser_console_messages",
                            description = "Read browser console messages for the current page.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "level", type = "string", description = "optional console level: error/warning/info/debug", required = false, default = "info"),
                                    ToolParameterSchema(name = "filename", type = "string", description = "optional output file name for large results", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_drag",
                            description = "Perform drag and drop between two page elements.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "startElement", type = "string", description = "human-readable source element description", required = true),
                                    ToolParameterSchema(name = "startRef", type = "string", description = "source element ref from browser_snapshot output", required = true),
                                    ToolParameterSchema(name = "endElement", type = "string", description = "human-readable target element description", required = true),
                                    ToolParameterSchema(name = "endRef", type = "string", description = "target element ref from browser_snapshot output", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_evaluate",
                            description = "Evaluate a JavaScript function on the page or on a target element.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "function", type = "string", description = "() => { ... } or (element) => { ... }", required = true),
                                    ToolParameterSchema(name = "element", type = "string", description = "optional, human-readable element description", required = false),
                                    ToolParameterSchema(name = "ref", type = "string", description = "optional target element ref; required when element is provided", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_file_upload",
                            description = "Upload one or multiple files to the active file chooser. Omit paths to cancel the chooser.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "paths", type = "array", description = "optional absolute file paths", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_fill_form",
                            description = "Fill multiple form fields on the current page.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "fields", type = "array", description = "array of field objects with name/type/value plus ref or selector", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_handle_dialog",
                            description = "Accept or dismiss the currently open dialog.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "accept", type = "boolean", description = "true to accept, false to dismiss", required = true),
                                    ToolParameterSchema(name = "promptText", type = "string", description = "optional prompt text when handling a prompt dialog", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_hover",
                            description = "Hover over an element on the current page.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "element", type = "string", description = "optional, human-readable element description", required = false),
                                    ToolParameterSchema(name = "ref", type = "string", description = "target element ref from browser_snapshot output", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_navigate",
                            description = "Navigate the active browser tab to a URL. If no tab exists yet, the first tab is created automatically.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "url", type = "string", description = "target URL", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_navigate_back",
                            description = "Go back in the current tab history.",
                            parametersStructured = emptyList()
                        ),
                        ToolPrompt(
                            name = "browser_network_requests",
                            description = "Read network requests recorded for the current page.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "includeStatic", type = "boolean", description = "optional, include static asset requests", required = false, default = "false"),
                                    ToolParameterSchema(name = "filename", type = "string", description = "optional output file name for large results", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_press_key",
                            description = "Press a keyboard key in the current page.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "key", type = "string", description = "key name, for example ArrowLeft or a", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_resize",
                            description = "Resize the browser viewport.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "width", type = "number", description = "viewport width", required = true),
                                    ToolParameterSchema(name = "height", type = "number", description = "viewport height", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_run_code",
                            description = "Run a Playwright-style code snippet against the current tab.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "code", type = "string", description = "Playwright-style JavaScript snippet", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_select_option",
                            description = "Select option values in a dropdown element.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "element", type = "string", description = "optional, human-readable element description", required = false),
                                    ToolParameterSchema(name = "ref", type = "string", description = "target select element ref from browser_snapshot output", required = true),
                                    ToolParameterSchema(name = "values", type = "array", description = "option values or visible texts to select", required = true)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_snapshot",
                            description = "Capture a structured accessibility-style snapshot of the current page, including same-origin iframe content.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "filename", type = "string", description = "optional output snapshot file name", required = false),
                                    ToolParameterSchema(name = "selector", type = "string", description = "optional root element selector for a partial snapshot", required = false),
                                    ToolParameterSchema(name = "depth", type = "integer", description = "optional snapshot tree depth limit", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_take_screenshot",
                            description = "Take a screenshot of the current page or of a specific element.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "type", type = "string", description = "optional image type: png or jpeg", required = false, default = "png"),
                                    ToolParameterSchema(name = "filename", type = "string", description = "optional output file name", required = false),
                                    ToolParameterSchema(name = "element", type = "string", description = "optional element description; when present ref is required", required = false),
                                    ToolParameterSchema(name = "ref", type = "string", description = "optional element ref; when present element is required", required = false),
                                    ToolParameterSchema(name = "fullPage", type = "boolean", description = "optional full-page capture; cannot be used with element screenshots", required = false, default = "false")
                                )
                        ),
                        ToolPrompt(
                            name = "browser_type",
                            description = "Type text into an editable element.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "element", type = "string", description = "optional, human-readable element description", required = false),
                                    ToolParameterSchema(name = "ref", type = "string", description = "target element ref from browser_snapshot output", required = true),
                                    ToolParameterSchema(name = "text", type = "string", description = "text to type", required = true),
                                    ToolParameterSchema(name = "submit", type = "boolean", description = "optional, press Enter after typing", required = false, default = "false"),
                                    ToolParameterSchema(name = "slowly", type = "boolean", description = "optional, type character by character", required = false, default = "false")
                                )
                        ),
                        ToolPrompt(
                            name = "browser_wait_for",
                            description = "Wait for text to appear, disappear, or for a duration to pass.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "time", type = "number", description = "optional wait duration in seconds", required = false),
                                    ToolParameterSchema(name = "text", type = "string", description = "optional text that must appear", required = false),
                                    ToolParameterSchema(name = "textGone", type = "string", description = "optional text that must disappear", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "browser_tabs",
                            description = "List, create, select, or close browser tabs using 0-based indexes.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(name = "action", type = "string", description = "one of: list, create, select, close", required = true),
                                    ToolParameterSchema(name = "index", type = "integer", description = "optional tab index used by select or close", required = false)
                                )
                        ),
                        ToolPrompt(
                            name = "calculate",
                            description = "Evaluate a math expression.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "expression",
                                        type = "string",
                                        description = "math expression, e.g. \"(1+2)*3\"",
                                        required = true
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "execute_intent",
                            description = "Execute an Android Intent (activity/broadcast/service).",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "optional, intent action",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "optional, data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "optional, package name",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "optional, component in \"package/class\" format",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "type",
                                        type = "string",
                                        description = "optional, one of activity/broadcast/service",
                                        required = false,
                                        default = "activity"
                                    ),
                                    ToolParameterSchema(
                                        name = "flags",
                                        type = "string",
                                        description = "optional, JSON array string of int flags (or a single int)",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "optional, JSON object string for extras",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "send_broadcast",
                            description = "Send a broadcast intent.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "action",
                                        type = "string",
                                        description = "required, broadcast action",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "uri",
                                        type = "string",
                                        description = "optional, data URI",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "package",
                                        type = "string",
                                        description = "optional, package name",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "component",
                                        type = "string",
                                        description = "optional, component in \"package/class\" format",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extras",
                                        type = "string",
                                        description = "optional, JSON object string for extras",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key",
                                        type = "string",
                                        description = "optional, a single string extra key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value",
                                        type = "string",
                                        description = "optional, a single string extra value",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_key2",
                                        type = "string",
                                        description = "optional, second string extra key",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "extra_value2",
                                        type = "string",
                                        description = "optional, second string extra value",
                                        required = false
                                    )
                                )
                        ),
                        ToolPrompt(
                            name = "device_info",
                            description = "Get device information.",
                            parametersStructured = listOf()
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended Memory Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "create_memory",
                            description = "Creates a new memory node in the library. Use this when you want to save important information for future reference.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "required, string", required = true),
                                ToolParameterSchema(name = "content", type = "string", description = "required, string", required = true),
                                ToolParameterSchema(name = "content_type", type = "string", description = "optional", required = false, default = "\"text/plain\""),
                                ToolParameterSchema(name = "source", type = "string", description = "optional", required = false, default = "\"ai_created\""),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "optional", required = false, default = "\"\""),
                                ToolParameterSchema(name = "tags", type = "string", description = "optional, comma-separated string", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "update_memory",
                            description = "Updates an existing memory node by title. Use this to modify an existing memory's content or metadata.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "old_title", type = "string", description = "required, string to identify the memory", required = true),
                                ToolParameterSchema(name = "new_title", type = "string", description = "optional, string, new title if renaming", required = false),
                                ToolParameterSchema(name = "content", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "content_type", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "source", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "credibility", type = "number", description = "optional, float 0-1", required = false),
                                ToolParameterSchema(name = "importance", type = "number", description = "optional, float 0-1", required = false),
                                ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "tags", type = "string", description = "optional, comma-separated string", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "delete_memory",
                            description = "Deletes a memory node from the library by title. Use with caution as this operation is irreversible.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "title", type = "string", description = "required, string to identify the memory", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "link_memories",
                            description = "Creates a semantic link between two memories in the library. Use this to establish relationships between related concepts, facts, or pieces of information. This helps build a knowledge graph structure for better memory retrieval and understanding.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source_title", type = "string", description = "required, string, the title of the source memory", required = true),
                                ToolParameterSchema(name = "target_title", type = "string", description = "required, string, the title of the target memory", required = true),
                                ToolParameterSchema(name = "link_type", type = "string", description = "optional, string, the type of relationship such as \"related\", \"causes\", \"explains\", \"part_of\", \"contradicts\", etc.", required = false, default = "\"related\""),
                                ToolParameterSchema(name = "weight", type = "number", description = "optional, float 0.0-1.0, the strength of the link with 1.0 being strongest", required = false, default = "0.7"),
                                ToolParameterSchema(name = "description", type = "string", description = "optional, string, additional context about the relationship", required = false, default = "\"\""
                            )
                        ),
                        ToolPrompt(
                            name = "query_memory_links",
                            description = "Queries links in the memory graph. Supports filtering by link_id, source_title, target_title, and link_type. Use this before updating/deleting links to precisely identify targets.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "link_id", type = "integer", description = "optional, exact link id", required = false),
                                ToolParameterSchema(name = "source_title", type = "string", description = "optional, exact source memory title", required = false),
                                ToolParameterSchema(name = "target_title", type = "string", description = "optional, exact target memory title", required = false),
                                ToolParameterSchema(name = "link_type", type = "string", description = "optional, relation type filter", required = false),
                                ToolParameterSchema(name = "limit", type = "integer", description = "optional, int 1-200, maximum links to return", required = false, default = "20")
                            )
                        ),
                        ToolPrompt(
                            name = "update_user_preferences",
                            description = "Updates user preference information directly. Use this when you learn new information about the user that should be remembered (e.g., their birthday, gender, personality traits, identity, occupation, or preferred AI interaction style). This allows immediate updates without waiting for the automatic system.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "birth_date", type = "integer", description = "optional, Unix timestamp in milliseconds", required = false),
                                ToolParameterSchema(name = "gender", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "personality", type = "string", description = "optional, string describing personality traits", required = false),
                                ToolParameterSchema(name = "identity", type = "string", description = "optional, string describing identity/role", required = false),
                                ToolParameterSchema(name = "occupation", type = "string", description = "optional, string", required = false),
                                ToolParameterSchema(name = "ai_style", type = "string", description = "optional, string describing preferred AI interaction style. At least one parameter must be provided", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended HTTP Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "http_request",
                            description = "Send HTTP request.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "GET/POST/PUT/DELETE", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "body", type = "string", description = "body", required = false),
                                ToolParameterSchema(name = "body_type", type = "string", description = "json/form/text/xml", required = false),
                                ToolParameterSchema(name = "ignore_ssl", type = "boolean", description = "ignore https certificate verification, true/false", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "multipart_request",
                            description = "Upload files.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "url", type = "string", description = "url", required = true),
                                ToolParameterSchema(name = "method", type = "string", description = "POST/PUT", required = true),
                                ToolParameterSchema(name = "headers", type = "string", description = "headers", required = false),
                                ToolParameterSchema(name = "form_data", type = "string", description = "form_data", required = false),
                                ToolParameterSchema(name = "files", type = "string", description = "JSON array string. Each item is an object: {\"field_name\": string, \"file_path\": string, \"content_type\"?: string, \"file_name\"?: string}", required = false),
                                ToolParameterSchema(name = "ignore_ssl", type = "boolean", description = "ignore https certificate verification, true/false", required = false)
                            )
                        ),
                        ToolPrompt(
                            name = "manage_cookies",
                            description = "Manage cookies.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "action", type = "string", description = "get/set/clear", required = true),
                                ToolParameterSchema(name = "domain", type = "string", description = "domain", required = false),
                                ToolParameterSchema(name = "cookies", type = "string", description = "cookies", required = false)
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Extended File Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "file_exists",
                            description = "Check if a file or directory exists.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "target path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "move_file",
                            description = "Move or rename a file or directory.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "source path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "destination path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "copy_file",
                            description = "Copy a file or directory. Supports cross-environment copying between Android and Linux.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "source path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "destination path", required = true),
                                ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false"),
                                ToolParameterSchema(name = "source_environment", type = "string", description = "optional, \"android\" or \"linux\"", required = false, default = "\"android\""),
                                ToolParameterSchema(name = "dest_environment", type = "string", description = "optional, \"android\" or \"linux\". For cross-environment copy (e.g., Android ?Linux or Linux ?Android), specify both source_environment and dest_environment", required = false, default = "\"android\""
                            )
                        ),
                        ToolPrompt(
                            name = "file_info",
                            description = "Get detailed information about a file or directory including type, size, permissions, owner, group, and last modified time.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "target path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "zip_files",
                            description = "Compress files or directories.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "path to compress", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "output zip file", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "unzip_files",
                            description = "Extract a zip file.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "source", type = "string", description = "zip file path", required = true),
                                ToolParameterSchema(name = "destination", type = "string", description = "extract path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "open_file",
                            description = "Open a file using the system's default application.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "file path", required = true)
                            )
                        ),
                        ToolPrompt(
                            name = "share_file",
                            description = "Share a file with other applications.",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                                ToolParameterSchema(name = "title", type = "string", description = "optional share title", required = false, default = "\"Share File\""
                            )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "Tasker Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "trigger_tasker_event",
                            description = "Trigger a Tasker event.",
                            parametersStructured =
                                listOf(
                                    ToolParameterSchema(
                                        name = "task_type",
                                        type = "string",
                                        description = "Tasker event type",
                                        required = true
                                    ),
                                    ToolParameterSchema(
                                        name = "arg1",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg2",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg3",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg4",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "arg5",
                                        type = "string",
                                        description = "optional",
                                        required = false
                                    ),
                                    ToolParameterSchema(
                                        name = "args_json",
                                        type = "string",
                                        description = "optional, JSON object string",
                                        required = false
                                    )
                                )
                        )
                    )
            ),
            SystemToolPromptCategory(
                categoryName = "System Management Tools",
                tools =
                    listOf(
                        ToolPrompt(
                            name = "battery_info",
                            description = "获取电池状态、电量、健康度等详细信?,
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "network_info",
                            description = "获取网络连接状态、Wi-Fi信息、移动数据信?,
                            parametersStructured = listOf()
                        ),
                        ToolPrompt(
                            name = "system_settings",
                            description = "读取或修改系统设置（如亮度、音量、飞行模式等）。注意：修改某些系统设置可能需要root权限",
                            parametersStructured = listOf(
                                ToolParameterSchema(name = "action", type = "string", description = "get/set", required = true),
                                ToolParameterSchema(name = "setting", type = "string", description = "setting name", required = true),
                                ToolParameterSchema(name = "value", type = "string", description = "setting value", required = false)
                            )
                        )
                    )
            )
        )

    // 其他语言的工具类别定义将在此处添?
    val internalToolCategoriesZh: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEs: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesJp: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesKo: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesFr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesDe: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesIt: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesPt: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesRu: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesAr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesHi: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesTh: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesVi: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesId: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesMs: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesTr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesPl: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesUk: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesFa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesHe: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesJa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesZhTw: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnGb: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnUs: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesFrCa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesPtBr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEsMx: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesDeAt: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesItCh: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesPlPl: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesUkUa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesFaIr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesHeIl: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesJaJp: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesZhCn: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesZhHk: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesZhMo: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnAu: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnCa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnNz: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEnZa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesFrFr: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesDeDe: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesItIt: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesPtPt: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesEsEs: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesRuRu: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesArSa: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesHiIn: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesThTh: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesViVn: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesIdId: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesMsMy: List<SystemToolPromptCategory> = internalToolCategoriesEn
    val internalToolCategoriesTrTr: List<SystemToolPromptCategory> = internalToolCategoriesEn
}
