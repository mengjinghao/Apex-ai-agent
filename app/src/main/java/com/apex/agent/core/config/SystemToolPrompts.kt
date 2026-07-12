package com.apex.core.config

import com.apex.core.chat.hooks.PromptHookContext
import com.apex.core.chat.hooks.PromptHookRegistry
import com.apex.data.model.SystemToolPromptCategory
import com.apex.data.model.ToolPrompt
import com.apex.data.model.ToolParameterSchema

/**
 * 系统工具提示词管理器
 * 包含所有工具的结构化定义*/
object SystemToolPrompts {

    private fun buildSafBookmarksSectionEn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString(", ") { "repo:${it}" }
        return """

**Attached Local Storage Repository:**
- environment (optional): you can also use `environment="repo:<repositoryName>"` to operate in an attached local storage repository.
- Paths are absolute (e.g., `/`, `/work/index.html`).
- Available repositories: ${listed}
""".trimEnd()
    }

    private fun buildSafBookmarksSectionCn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString("。") { "repo:${it}" }
        return """
**附加本地储存仓库**
- environment（可选）：也可以使用 `environment="repo:<仓库>"` 在附加本地储存仓库中操作。
- 路径使用绝对路径（例如：`/`、`/work/index.html`）。
- 当前可用仓库：${listed}
""".trimEnd()
    }
    
    // ==================== 基础工具 ====================
    val basicTools = SystemToolPromptCategory(
        categoryName = "Available tools",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "Demonstration tool that pauses briefly.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "milliseconds, default 1000, >= 0", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "Activate a package for use in the current session.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "package_name",
                        type = "string",
                        description = "name of the package to activate",
                        required = true
                    )
                )
            )
        )
    )
    
    val basicToolsCn = SystemToolPromptCategory(
        categoryName = "可用工具",
        tools = listOf(
            ToolPrompt(
                name = "sleep",
                description = "演示工具，短暂暂停。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "duration_ms", type = "integer", description = "毫秒，默认1000", required = false, default = "1000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "在当前会话中激活包。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "package_name", type = "string", description = "要激活的包名", required = true)
                )
            )
        )
    )
    
    // ==================== 文件系统工具 ====================
    val fileSystemTools = SystemToolPromptCategory(
        categoryName = "File System Tools",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "List files in a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "e.g. \"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "Read the content of a file. For image files (jpg, jpeg, png, gif, bmp), it automatically extracts text using OCR.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "path",
                        type = "string",
                        description = "file path",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "optional, execution environment. Values: \"android\" (default, Android file system) | \"linux\" (local Ubuntu 24 terminal environment via proot; Linux paths like /home/... /etc/hosts) | \"repo:<repositoryName>\" (attached local storage repository)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "optional, your question about the media/file (used for backend recognition)",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"image\"> tag for models that support vision",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"audio\"> tag for models that support audio",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "optional, when true: return an <link type=\"video\"> tag for models that support video",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "Read file content by line range.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "starting line number, 1-indexed", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "ending line number, 1-indexed, inclusive, optional", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "apply_file",
                description = "Applies edits to a file by finding and replacing/deleting a matched content block.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "type", type = "string", description = "operation type: replace | delete | create", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "the exact content to be matched and replaced/deleted (required for replace/delete)", required = false),
                    ToolParameterSchema(name = "new", type = "string", description = "the new content to insert (required for replace/create)", required = false)
                ),
                details = """
  - **How it works**:
    - The tool finds the best fuzzy match of `old` in the current file content (not by line numbers) and applies the requested operation.
    - You can call this tool multiple times to apply multiple independent edits.

  - **Parameters**:
    - `type`:
      - `replace`: replace the matched `old` content with `new`
      - `delete`: delete the matched `old` content
      - `create`: create the file when it does not exist (write `new` as full file content)
    - `old`: required for `replace` / `delete`
    - `new`: required for `replace` / `create`

  - **CRITICAL RULES**:
    1. **If you need to rewrite a whole existing file**: do **NOT** use apply_file to overwrite it. Instead, call `delete_file` first, then use `apply_file` with `type=create`.2. **If you need to modify an existing file**: you **MUST** use `type=replace` (or `type=delete`) and provide `old` / `new`. Do **NOT** delete the whole file and rewrite it.
"""
            ),
            ToolPrompt(
                name = "delete_file",
                description = "Delete a file or directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "target path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "Create a directory.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "Search for files matching a pattern.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path, for Android use /sdcard/..., for Linux use /home/... or /etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "search pattern, e.g. \"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "optional, controls depth of subdirectory search, -1=unlimited", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "Search code content matching a regex pattern in files. Returns matches with surrounding context lines.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "search path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "regex pattern", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "boolean", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "lines of context before/after match", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "max matches", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "Search for relevant content based on intent/context understanding. Supports two modes: 1) Directory mode: when path is a directory, finds most relevant files. 2) File mode: when path is a file, finds most relevant code segments within that file. Uses semantic relevance scoring.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory or file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "intent or context description string", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter for directory mode", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "maximum items to return", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "Download a file from the internet. Two modes: (1) Provide `url` + `destination`. (2) Provide `visit_key` + (`link_number` or `image_number`) + `destination` to download an item by index from a previous `visit_web` result.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, file URL. If omitted, use visit_key + link_number/image_number to download from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, 1-based link index from Results (use with visit_key)", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "optional, 1-based image index from Images (use with visit_key)", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "save path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    val fileSystemToolsCn = SystemToolPromptCategory(
        categoryName = "文件系统工具",
        tools = listOf(
            ToolPrompt(
                name = "list_files",
                description = "列出目录中的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "例如\"/sdcard/Download\"", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false)
                )
            ),
            ToolPrompt(
                name = "read_file",
                description = "读取文件内容。对于图片文件（jpg, jpeg, png, gif, bmp），自动使用OCR提取文本。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(
                        name = "environment",
                        type = "string",
                        description = "可选，执行环境。取值：\"android\"（默认，Android文件系统）| \"linux\"（本地Ubuntu 24终端环境，通过proot实现；路径用Linux格式，如/home/... 成/etc/hosts）| \"repo:<仓库后\"（附加本地储存仓库"),
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "intent",
                        type = "string",
                        description = "可选，用户对媒作文件的问题（用于后端识别模型，",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_image",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"image\">标签供支持识图的模型直接查看",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_audio",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"audio\">标签供支持音频的模型直接处理",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "direct_video",
                        type = "boolean",
                        description = "可选，为true时：返回<link type=\"video\">标签供支持视频的模型直接处理",
                        required = false
                    )
                )
            ),
            ToolPrompt(
                name = "read_file_part",
                description = "按行号范围读取文件内容。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "start_line", type = "integer", description = "起始行号，从1开始", required = false, default = "1"),
                    ToolParameterSchema(name = "end_line", type = "integer", description = "结束行号，从1开始，包括该行，可通", required = false, default = "start_line + 99")
                )
            ),
            ToolPrompt(
                name = "apply_file",
                description = "通过查找并替据删除匹配的内容块来编辑文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "type", type = "string", description = "操作类型：replace | delete | create", required = true),
                    ToolParameterSchema(name = "old", type = "string", description = "用于匹配/替换/删除的原始内容（replace/delete必填，", required = false),
                    ToolParameterSchema(name = "new", type = "string", description = "要插入的新内容（replace/create必填，", required = false)
                ),
                details = """
  - **工作原理**:
    - 工具会在文件当前内容中对 `old` 做最佳的模糊匹配（不依赖行号），然后执行指定操作。
    - 你可以多次调用本工具，对同一个文件做多处独立修改。
  - **参数**:
    - `type`:
      - `replace`: 用`new` 替换匹配到的 `old`
      - `delete`: 删除匹配到的 `old`
      - `create`: 当文件不存在时创建文件（将`new` 作为完整文件内容，
    - `old`: `replace` / `delete` 必填
    - `new`: `replace` / `create` 必填

  - **关键规则**:
    1. **如果需要重写整个已存在文件**：不要用 apply_file 直接覆盖。请先调用`delete_file`，再使用 `apply_file` 的`type=create`。   2. **如果需要修改已存在文件**：必须用 `type=replace`（或 `type=delete`）并提供 `old/new`（或 `old`）。不要删除整个文件再重写。"""
            ),
            ToolPrompt(
                name = "delete_file",
                description = "删除文件或目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目标路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "recursive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "make_directory",
                description = "创建目录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "create_parents", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "find_files",
                description = "搜索匹配模式的文件。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径，Android 用/sdcard/...，Linux 用/home/... 成/etc/...", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "搜索模式，例如\"*.jpg\"", required = true),
                    ToolParameterSchema(name = "max_depth", type = "integer", description = "可选，控制子目录搜索深度，-1=无限", required = false),
                    ToolParameterSchema(name = "use_path_pattern", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "grep_code",
                description = "在文件中搜索匹配正则表达式的代码内容，返回带上下文的匹配结果。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "搜索路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "pattern", type = "string", description = "正则表达式模式", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "case_insensitive", type = "boolean", description = "布尔值", required = false, default = "false"),
                    ToolParameterSchema(name = "context_lines", type = "integer", description = "匹配行前后的上下文行数", required = false, default = "3"),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "最大匹配数", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "grep_context",
                description = "基于意图/上下文理解搜索相关内容。支持两种模式：1) 目录模式：当path是目录时，找出最相关的文件。) 文件模式：当path是文件时，找出该文件内最相关的代码段。使用语义相关性评分。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录或文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "意图或上下文描述字符为", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "目录模式下的文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "返回的最大项数", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "从互联网下载文件。有两种用法，）提例`url` + `destination` 直接下载。）提例`visit_key` +（`link_number` 成`image_number`， `destination`，从上一为`visit_web` 的Results/Images 编号中按序号下载。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "可选， 文件URL。不传时可使用visit_key + link_number/image_number 从上一为visit_web 结果按编号下载。", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "可选， 上一为visit_web 返回的visitKey", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "可选， 整数, Results 中的链接编号（从1开始，需要配后visit_key，", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "可选， 整数, Images 中的图片编号（从1开始，需要配后visit_key，", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "保存路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，后read_file 的environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "可选：HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    // ==================== HTTP工具 ====================
    val httpTools = SystemToolPromptCategory(
        categoryName = "HTTP Tools",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "Enhanced webpage interaction tool. Core modes: Direct URL visit / Follow link from history / Batch visit. Supports session persistence, page interactions (click/fill), automatic retries, debugging, and caching.",
                details = """
   - **Unified Response Structure (Always Returned)**:
     {
       "success": true/false,
       "code": "SUCCESS/TIMEOUT/HTTP_ERROR/PAGE_ERROR/INVALID_PARAM",
       "message": "Human readable message",
       "data": { "text": "...", "links": [], "images": [], ... },
       "debug": { "request_headers": {}, "response_headers": {}, "http_status": 200, "response_time_ms": 1200, "logs": [] },
       "error_screenshot": "<link type='image'> (if error_screenshot=true and failed)",
       "request_info": { "url": "...", "final_url": "...", "cached": false }
     }

   - **Usage Rules**:
     1. **Mode Selection**: You must choose exactly one mode via visit_mode.
        - direct_url: Requires url
        - follow_link: Requires visit_key + link_number
        - batch_urls: Requires batch_urls (JSON array)
     2. **Session Persistence**: Use session_id to maintain login state across multiple calls.3. **Page Interactions**: Use page_actions for click/fill/select/keyboard operations before extraction.4. **Error Handling**: Use retry_count + debug_mode + error_screenshot for robustness.5. **Caching**: Use cache_control=force_cache for repeated visits to speed up response.
 """,
                parametersStructured = listOf(
                    // ==================== Core Access Mode ====================
                    ToolParameterSchema(
                        name = "visit_mode",
                        type = "string",
                        description = "Visit mode: direct_url (visit new URL) | follow_link (follow from history) | batch_urls (batch visit)",
                        required = true
                    ),
                    
                    // --- direct_url mode parameters ---
                    ToolParameterSchema(
                        name = "url",
                        type = "string",
                        description = "Required if visit_mode=direct_url. Webpage URL to visit",
                        required = false
                    ),
                    
                    // --- follow_link mode parameters ---
                    ToolParameterSchema(
                        name = "visit_key",
                        type = "string",
                        description = "Required if visit_mode=follow_link. visitKey from previous visit_web result",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "link_number",
                        type = "integer",
                        description = "Required if visit_mode=follow_link. 1-based link index to follow",
                        required = false
                    ),
                    
                    // --- batch_urls mode parameters ---
                    ToolParameterSchema(
                        name = "batch_urls",
                        type = "string",
                        description = "Required if visit_mode=batch_urls. JSON array of URLs, e.g. [\"https://a.com\", \"https://b.com\"]",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "batch_concurrency",
                        type = "integer",
                        description = "Optional for batch_urls. Number of concurrent requests, default 3",
                        required = false,
                        default = "3",
                    ),
                    
                    // ==================== Content Extraction ====================
                    ToolParameterSchema(
                        name = "preset",
                        type = "string",
                        description = "Optional preset: article (extract main content) | search (extract search results) | screenshot_full (full page screenshot)",
                        required = false
                    ),
                    ToolParameterSchema(name = "extract_text", type = "boolean", description = "Extract page text", required = false, default = "true"),
                    ToolParameterSchema(name = "extract_links", type = "boolean", description = "Extract page links", required = false, default = "true"),
                    ToolParameterSchema(name = "extract_images", type = "boolean", description = "Extract image links", required = false, default = "false"),
                    ToolParameterSchema(name = "extract_tables", type = "boolean", description = "Extract HTML tables as structured data", required = false, default = "false"),
                    ToolParameterSchema(name = "extract_metadata", type = "boolean", description = "Extract page metadata (title, description)", required = false, default = "true"),
                    ToolParameterSchema(name = "extract_structured_data", type = "boolean", description = "Extract JSON-LD/microdata", required = false, default = "false"),
                    
                    // ==================== Page Interaction ====================
                    ToolParameterSchema(
                        name = "page_actions",
                        type = "string",
                        description = "Optional JSON array of page interaction actions. Example: [{\"action\":\"fill\", \"selector\":\"#username\", \"value\":\"user\"}, {\"action\":\"click\", \"selector\":\"#login\"}, {\"action\":\"select\", \"selector\":\"#country\", \"value\":\"us\"}, {\"action\":\"keyboard\", \"keys\":\"Enter\"}]",
                        required = false
                    ),
                    ToolParameterSchema(name = "scroll_to", type = "string", description = "Scroll to: top (top) | bottom (bottom) | element:CSS selector | JSON {\"x\":0, \"y\":500}", required = false),
                    
                    // ==================== Wait & Timeout ====================
                    ToolParameterSchema(name = "wait_for_type", type = "string", description = "Wait condition type: element (element) | text (text) | timeout (timeout)", required = false),
                    ToolParameterSchema(name = "wait_for_selector", type = "string", description = "CSS selector (for wait_for_type=element)", required = false),
                    ToolParameterSchema(name = "wait_for_text", type = "string", description = "Text to wait for (for wait_for_type=text)", required = false),
                    ToolParameterSchema(name = "wait_for_timeout_ms", type = "integer", description = "Wait timeout in milliseconds, default 5000", required = false, default = "5000"),
                    ToolParameterSchema(name = "timeout_ms", type = "integer", description = "Request timeout in milliseconds, default 30000", required = false, default = "30000"),
                    
                    // ==================== Session Management ====================
                    ToolParameterSchema(
                        name = "session_id",
                        type = "string",
                        description = "Optional session ID for persistence (cookies, localStorage). Reuse to continue a session; omit for temp session",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "anti_detection",
                        type = "boolean",
                        description = "Enable anti-detection (fingerprint spoofing, random delays, UA rotation)",
                        required = false,
                        default = "false",
                    ),
                    
                    // ==================== Reliability ====================
                    ToolParameterSchema(name = "retry_count", type = "integer", description = "Max retries on failure, default 0", required = false, default = "0"),
                    ToolParameterSchema(name = "retry_delay_ms", type = "integer", description = "Delay between retries in ms, default 1000", required = false, default = "1000"),
                    ToolParameterSchema(name = "retry_on_status", type = "string", description = "JSON array of HTTP status codes to retry, default [408,429,500,502,503,504]", required = false, default = "[408,429,500,502,503,504]"),
                    ToolParameterSchema(name = "retry_on_error", type = "string", description = "JSON array of error types to retry, default [\"timeout\",\"network_error\"]", required = false, default = "[\"timeout\",\"network_error\"]"),
                    
                    // ==================== Observability ====================
                    ToolParameterSchema(name = "debug_mode", type = "boolean", description = "Return full debug logs (request/response headers, timings, stacktrace)", required = false, default = "false"),
                    ToolParameterSchema(name = "error_screenshot", type = "boolean", description = "Capture screenshot on failure", required = false, default = "false"),
                    ToolParameterSchema(name = "screenshot", type = "string", description = "Legacy screenshot param: full | visible | JSON {\"selector\":\"...\", \"filename\":\"...\"}", required = false),
                    
                    // ==================== Caching ====================
                    ToolParameterSchema(name = "cache_control", type = "string", description = "Cache policy: default (default) | no_cache (no cache) | force_cache (force cache) | refresh_cache (refresh cache)", required = false, default = "default"),
                    ToolParameterSchema(name = "cache_ttl_ms", type = "integer", description = "Cache TTL in milliseconds, default 300000 (5min)", required = false, default = "300000"),
                    
                    // ==================== Network Configuration ====================
                    ToolParameterSchema(name = "headers", type = "string", description = "Optional HTTP headers as JSON object, e.g. {\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "Quick select UA: desktop/android/iphone/ipad/mobile/tablet", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "Full custom User-Agent", required = false),
                    ToolParameterSchema(name = "proxy", type = "string", description = "Proxy server: http://host:port or socks5://host:port", required = false),
                    ToolParameterSchema(name = "cookies", type = "string", description = "Legacy: Cookies as JSON object (prefer session_id)", required = false),
                    
                    // ==================== Output Format ====================
                    ToolParameterSchema(name = "response_format", type = "string", description = "Response format: structured (default) | plain_text | markdown", required = false, default = "structured"),
                    ToolParameterSchema(name = "include_all_links", type = "boolean", description = "Extract all links vs only relevant", required = false, default = "false"),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "Include image links in result (alias of extract_images)", required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "close_session",
                description = "Destroy a persistent web session and release browser resources. Clear cookies, local storage, page context and kill background browser process.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "session_id",
                        type = "string",
                        description = "The unique sessionId to destroy, consistent with visit_web session_id",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "clear_cache",
                        type = "boolean",
                        description = "Clear all browser cache for this session",
                        required = false,
                        default = "true",
                    )
                ),
                details = """
- Use this tool to manually close long-lived web sessions created by visit_web with session_id.
- Temporary sessions (without session_id) are auto-released and do not need this tool.
- After closing, the session_id cannot be reused; a new session will be created if used again.
"""
            )
        )
    )
    
    val httpToolsCn = SystemToolPromptCategory(
        categoryName = "HTTP工具",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "增强版网页交互工具。核心模式：直接访问URL / 从历史结果跳转/ 批量访问。支持会话持久化、页面交互（点击/填写）、自动重试、调试和缓存。",
                details = """
   - **统一返回结构（始终返回）**:
     {
       "success": true/false,
       "code": "SUCCESS/TIMEOUT/HTTP_ERROR/PAGE_ERROR/INVALID_PARAM",
       "message": "人类可读的消息",
       "data": { "text": "...", "links": [], "images": [], ... },
       "debug": { "request_headers": {}, "response_headers": {}, "http_status": 200, "response_time_ms": 1200, "logs": [] },
       "error_screenshot": "<link type='image'> (如果 error_screenshot=true 且失败"),
       "request_info": { "url": "...", "final_url": "...", "cached": false }
     }

   - **使用规则**:
     1. **模式选择**：必须通过 visit_mode 选择一种模式。
        - direct_url：需要url
        - follow_link：需要visit_key + link_number
        - batch_urls：需要batch_urls（JSON数组，
     2. **会话持久化*：使用session_id 在多次调用间保持登录状态。
     3. **页面交互**：在提取前使用page_actions 进行点击/填写/选择/按键操作。
     4. **错误处理**：使用retry_count + debug_mode + error_screenshot 提升鲁棒性。
     5. **缓存**：对重复访问使用 cache_control=force_cache 加快响应速度。
 """,
                parametersStructured = listOf(
                    // ==================== 核心访问模式 ====================
                    ToolParameterSchema(
                        name = "visit_mode",
                        type = "string",
                        description = "访问模式：direct_url (访问新URL) | follow_link (从历史结果跳转| batch_urls (批量访问，",
                        required = true
                    ),
                    
                    // --- direct_url 模式参数 ---
                    ToolParameterSchema(
                        name = "url",
                        type = "string",
                        description = "visit_mode=direct_url 时必填。要访问的网页URL",
                        required = false
                    ),
                    
                    // --- follow_link 模式参数 ---
                    ToolParameterSchema(
                        name = "visit_key",
                        type = "string",
                        description = "visit_mode=follow_link 时必填。上一为visit_web 返回的visitKey",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "link_number",
                        type = "integer",
                        description = "visit_mode=follow_link 时必填。要跳转的链接编号（件开始"),
                        required = false
                    ),
                    
                    // --- batch_urls 模式参数 ---
                    ToolParameterSchema(
                        name = "batch_urls",
                        type = "string",
                        description = "visit_mode=batch_urls 时必填。URL的JSON数组，例如[\"https://a.com\", \"https://b.com\"]",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "batch_concurrency",
                        type = "integer",
                        description = "批量访问可选。并发请求数，默认",
                        required = false,
                        default = "3",
                    ),
                    
                    // ==================== 内容提取 ====================
                    ToolParameterSchema(
                        name = "preset",
                        type = "string",
                        description = "可选预设：article (提取正文，| search (提取搜索结果，| screenshot_full (全屏截图，",
                        required = false
                    ),
                    ToolParameterSchema(name = "extract_text", type = "boolean", description = "提取页面文本", required = false, default = "true"),
                    ToolParameterSchema(name = "extract_links", type = "boolean", description = "提取页面链接", required = false, default = "true"),
                    ToolParameterSchema(name = "extract_images", type = "boolean", description = "提取图片链接", required = false, default = "false"),
                    ToolParameterSchema(name = "extract_tables", type = "boolean", description = "提取HTML表格为结构化数据", required = false, default = "false"),
                    ToolParameterSchema(name = "extract_metadata", type = "boolean", description = "提取页面元数据（标题、描述"), required = false, default = "true"),
                    ToolParameterSchema(name = "extract_structured_data", type = "boolean", description = "提取JSON-LD/微数据", required = false, default = "false"),
                    
                    // ==================== 页面交互 ====================
                    ToolParameterSchema(
                        name = "page_actions",
                        type = "string",
                        description = "可选页面交互动作JSON数组。示例：[{\"action\":\"fill\", \"selector\":\"#username\", \"value\":\"user\"}, {\"action\":\"click\", \"selector\":\"#login\"}, {\"action\":\"select\", \"selector\":\"#country\", \"value\":\"cn\"}, {\"action\":\"keyboard\", \"keys\":\"Enter\"}]",
                        required = false
                    ),
                    ToolParameterSchema(name = "scroll_to", type = "string", description = "滚动到：top (顶部，| bottom (底部，| element:CSS选择器| JSON {\"x\":0, \"y\":500}", required = false),
                    
                    // ==================== 等待与超时===================
                    ToolParameterSchema(name = "wait_for_type", type = "string", description = "等待条件类型：element|text|timeout", required = false),
                    ToolParameterSchema(name = "wait_for_selector", type = "string", description = "CSS选择器（wait_for_type=element时使用）"), required = false),
                    ToolParameterSchema(name = "wait_for_text", type = "string", description = "要等待的文本（wait_for_type=text时使用）"), required = false),
                    ToolParameterSchema(name = "wait_for_timeout_ms", type = "integer", description = "等待超时时间（毫秒），默认5000", required = false, default = "5000"),
                    ToolParameterSchema(name = "timeout_ms", type = "integer", description = "请求超时时间（毫秒），默认30000", required = false, default = "30000"),
                    
                    // ==================== 会话管理 ====================
                    ToolParameterSchema(
                        name = "session_id",
                        type = "string",
                        description = "可选会话ID，用于持久化（Cookie、本地存储）。复用可继续会话；不传则为临时会话。",
                        required = false
                    ),
                    ToolParameterSchema(
                        name = "anti_detection",
                        type = "boolean",
                        description = "启用反检测（指纹伪装、随机延迟、UA轮换，",
                        required = false,
                        default = "false",
                    ),
                    
                    // ==================== 可靠态===================
                    ToolParameterSchema(name = "retry_count", type = "integer", description = "失败时最大重试次数，默认0", required = false, default = "0"),
                    ToolParameterSchema(name = "retry_delay_ms", type = "integer", description = "重试间隔（毫秒），默认000", required = false, default = "1000"),
                    ToolParameterSchema(name = "retry_on_status", type = "string", description = "触发重试的HTTP状态码JSON数组，默认[408,429,500,502,503,504]", required = false, default = "[408,429,500,502,503,504]"),
                    ToolParameterSchema(name = "retry_on_error", type = "string", description = "触发重试的错误类型JSON数组，默认[\"timeout\",\"network_error\"]", required = false, default = "[\"timeout\",\"network_error\"]"),
                    
                    // ==================== 可观测态===================
                    ToolParameterSchema(name = "debug_mode", type = "boolean", description = "返回完整调试日志（请求响应头、耗时、堆栈"), required = false, default = "false"),
                    ToolParameterSchema(name = "error_screenshot", type = "boolean", description = "失败时捕获截回", required = false, default = "false"),
                    ToolParameterSchema(name = "screenshot", type = "string", description = "旧版截图参数：full | visible | JSON {\"selector\":\"...\", \"filename\":\"...\"}", required = false),
                    
                    // ==================== 缓存 ====================
                    ToolParameterSchema(name = "cache_control", type = "string", description = "缓存策略：default (默认，| no_cache (无缓字| force_cache (强制缓存，| refresh_cache (刷新缓存，", required = false, default = "default"),
                    ToolParameterSchema(name = "cache_ttl_ms", type = "integer", description = "缓存有效期（毫秒），默认300000，分钟，", required = false, default = "300000"),
                    
                    // ==================== 网络配置 ====================
                    ToolParameterSchema(name = "headers", type = "string", description = "可选HTTP请求头，JSON对象，例如{\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "快速选择UA：desktop/android/iphone/ipad/mobile/tablet", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "完整自定义User-Agent", required = false),
                    ToolParameterSchema(name = "proxy", type = "string", description = "代理服务器：http://host:port 成socks5://host:port", required = false),
                    ToolParameterSchema(name = "cookies", type = "string", description = "旧版：Cookie的JSON对象（推荐使用session_id，", required = false),
                    
                    // ==================== 输出格式 ====================
                    ToolParameterSchema(name = "response_format", type = "string", description = "响应格式：structured (默认，| plain_text | markdown", required = false, default = "structured"),
                    ToolParameterSchema(name = "include_all_links", type = "boolean", description = "提取所有链接还是仅相关链接", required = false, default = "false"),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "在结果中包含图片链接（extract_images的别名"), required = false, default = "false")
                )
            ),
            ToolPrompt(
                name = "close_session",
                description = "销毁持久化网页会话并释放浏览器资源。清空Cookie、本地存储、页面上下文，终止后台浏览器进程。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "session_id",
                        type = "string",
                        description = "需要销毁的会话ID，与 visit_web 传入的session_id 保持一致。",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "clear_cache",
                        type = "boolean",
                        description = "是否清空该会话全部浏览器缓存",
                        required = false,
                        default = "true",
                    )
                ),
                details = """,
- 专门用于关闭 visit_web 通过 session_id 创建的长期网页会话。 临时会话（未传session_id）会自动释放，无需调用本工具。 销毁后，session_id 立即失效，重复使用会自动新建空白会话。"""
            )
        )
    )
    
    // ==================== 记忆库工具===================
    val memoryTools = SystemToolPromptCategory(
        categoryName = "Memory and Memory Library Tools",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "Searches the memory library for relevant memories and document chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, the search query. You can pass a natural-language question, a space-separated phrase, or use `|` to separate multiple keywords, for example `network error timeout` or `network|error|timeout`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`; use only `*` to return all memories", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string, the specific folder path to search within", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "optional, string. Omit or pass empty to create a new snapshot automatically. If you pass a non-empty snapshot_id, that exact id will be used; if it does not exist yet, it will be created and can be reused across follow-up or parallel queries to exclude memories already returned by that snapshot", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "optional, number >= 0. Minimum relevance score required for a memory to be returned. Defaults to 0 for query_memory", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of results to return. When > 20, only titles and truncated content are returned", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "Retrieves a memory by exact title, including document content or selected chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "required, string, the exact title of the memory", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "optional, int, read a specific chunk by its number, e.g., 3 for the 3rd chunk", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "optional, string, read a range of chunks in \"start-end\" format, e.g., \"3-7\" for chunks 3 through 7", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "optional, string, search inside the document by natural-language question or keywords. You can pass a short question, a space-separated phrase, or use `|` to separate multiple keywords, for example `error log timeout` or `error|timeout|retry`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of document chunks to return when using query. Default 20", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "find_duplicate_memories",
                description = "Detect duplicate/similar memories in the library. Returns groups of highly similar memories.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "similarity_threshold",
                        type = "number",
                        description = "Similarity threshold (0.0-1.0), default 0.92. Higher = stricter matching.",
                        required = false,
                        default = "0.92",
                    )
                )
            ),
            ToolPrompt(
                name = "merge_duplicate_memories",
                description = "Merge multiple duplicate memories into a single new memory. Preserves all tags and links by default.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "source_titles",
                        type = "string",
                        description = "Comma-separated list of source memory titles to merge",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_title",
                        type = "string",
                        description = "Title of the new merged memory",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_content",
                        type = "string",
                        description = "Full content of the new merged memory",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "keep_tags",
                        type = "boolean",
                        description = "Preserve all tags from source memories, default true",
                        required = false,
                        default = "true",
                    ),
                    ToolParameterSchema(
                        name = "keep_links",
                        type = "boolean",
                        description = "Preserve all links from source memories, default true",
                        required = false,
                        default = "true",
                    )
                )
            ),
            ToolPrompt(
                name = "recover_deleted_memory",
                description = "Recover a deleted memory by its UUID.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "memory_uuid",
                        type = "string",
                        description = "UUID of the deleted memory to recover",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "rollback_memory_to_time",
                description = "Rollback the entire memory library to a specific time point.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "target_time",
                        type = "string",
                        description = "Target time point, format: YYYY-MM-DD HH:mm",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "query_operation_logs",
                description = "Query memory operation logs (WAL) to view history changes.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "operation_type", type = "string", description = "Filter by operation type: create/update/delete/merge", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "Start time, format: YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "End time, format: YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "Max number of logs to return, default 100", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "find_path_between_memories",
                description = "Find all connection paths between two memories in the knowledge graph (multi-hop reasoning).",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "source_title",
                        type = "string",
                        description = "Title of the source memory",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_title",
                        type = "string",
                        description = "Title of the target memory",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_hops",
                        type = "integer",
                        description = "Maximum number of hops to search, default 3",
                        required = false,
                        default = "3",
                    ),
                    ToolParameterSchema(
                        name = "max_paths",
                        type = "integer",
                        description = "Maximum number of paths to return, default 10",
                        required = false,
                        default = "10",
                    )
                )
            ),
            ToolPrompt(
                name = "find_graph_related_memories",
                description = "Find memories strongly related to the query memory via knowledge graph connections.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "query_title",
                        type = "string",
                        description = "Title of the query memory",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_hops",
                        type = "integer",
                        description = "Maximum number of hops to search, default 2",
                        required = false,
                        default = "2",
                    ),
                    ToolParameterSchema(
                        name = "min_weight",
                        type = "number",
                        description = "Minimum edge weight threshold (0.0-1.0), default 0.5",
                        required = false,
                        default = "0.5",
                    )
                )
            ),
            ToolPrompt(
                name = "export_memories_to_markdown",
                description = "Export all memories to Markdown files (Obsidian/Notion compatible).",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "output_directory",
                        type = "string",
                        description = "Directory path to save Markdown files",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "include_metadata",
                        type = "boolean",
                        description = "Include YAML front matter with metadata, default true",
                        required = false,
                        default = "true",
                    )
                )
            ),
            ToolPrompt(
                name = "export_graph_to_opml",
                description = "Export the knowledge graph to OPML format (compatible with mind mapping tools like XMind, MindNode).",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "output_file",
                        type = "string",
                        description = "File path to save the OPML file",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "chain_of_thought_search",
                description = "Perform Chain-of-Thought (CoT) search: simulate human reasoning to find relevant memories step by step. Especially useful for complex queries.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "query",
                        type = "string",
                        description = "The original user query",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_steps",
                        type = "integer",
                        description = "Maximum number of reasoning steps, default 3",
                        required = false,
                        default = "3",
                    )
                )
            )
        ),
        categoryFooter = "\nNote: The memory library and user personality profile are automatically updated by a separate system after you output the task completion marker. However, if you need to manage memories immediately or update user preferences, use the appropriate tools directly.",
    )
    
    val memoryToolsCn = SystemToolPromptCategory(
        categoryName = "记忆与记忆库工具",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "从记忆库中搜索相关记忆和文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, 搜索查询。可以传自然语言问题、空格分隔的短语，或使用 `|` 分隔多个关键词，例如 `network error timeout` 成`network|error|timeout`。在单个关键词内部，`*` 可作为模糊通配占位符，例如 `error*timeout`；仅会`*` 时返回所有记忆。", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "可选， string, 要搜索的特定文件夹路径", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "可选， 本地时间字符串，格式支持 `YYYY-MM-DD` 成`YYYY-MM-DD HH:mm`。按创建时间过滤 createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "可选， 本地时间字符串，格式支持 `YYYY-MM-DD` 成`YYYY-MM-DD HH:mm`。按创建时间过滤 createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "可选， 字符串。不传或传空时会自动创建新快照；传入任意非空 snapshot_id 时会直接使用这个 id，不存在则按正id 创建。后续串行或并发查询复用同一为snapshot_id 时，会排除该快照里已经返回过的记忆。", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "可选， number >= 0。返回记忆所需的最小相关度分数。query_memory 默认值为 0", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "可选， int >= 1, 返回结果的最大数量。当 > 20 时，只返回标题和截断内容", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "通过精确标题检索记忆，可读取完整内容或文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "必需，字符串，记忆的精确标题", required = true),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "可选， 整数, 读取特定编号的分块例如3表示符块", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "可选，字符串，读取分块范围，格式为\"起始-结束\"，例如\"3-7\"表示符到第7块", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "可选，字符串，在文档内部搜索匹配分块。可以传自然语言问题、空格分隔的短语，或使用 `|` 分隔多个关键词，例如 `error log timeout` 成`error|timeout|retry`。在单个关键词内部，`*` 可作为模糊通配占位符，例如 `error*timeout`", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "可选， int >= 1, 使用 query 时最多返回多少个文档分块，默认0", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "find_duplicate_memories",
                description = "检测记忆库中的重复/相似记忆，返回高度相似的记忆分组。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "similarity_threshold",
                        type = "number",
                        description = "相似度阈值（0.0-1.0），默认0.92，值越高匹配越严格",
                        required = false,
                        default = "0.92",
                    )
                )
            ),
            ToolPrompt(
                name = "merge_duplicate_memories",
                description = "将多条重复记忆合并为一条新记忆，默认保留所有标签和关联链接。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "source_titles",
                        type = "string",
                        description = "要合并的源记忆标题，逗号分隔",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_title",
                        type = "string",
                        description = "合并后新记忆的标题",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_content",
                        type = "string",
                        description = "合并后新记忆的完整内定",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "keep_tags",
                        type = "boolean",
                        description = "是否保留源记忆的所有标签，默认true",
                        required = false,
                        default = "true",
                    ),
                    ToolParameterSchema(
                        name = "keep_links",
                        type = "boolean",
                        description = "是否保留源记忆的所有关联链接，默认true",
                        required = false,
                        default = "true",
                    )
                )
            ),
            ToolPrompt(
                name = "recover_deleted_memory",
                description = "通过UUID恢复误删的记忆。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "memory_uuid",
                        type = "string",
                        description = "要恢复的已删除记忆的UUID",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "rollback_memory_to_time",
                description = "将整个记忆库回滚到指定的时间点。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "target_time",
                        type = "string",
                        description = "目标时间点，格式：YYYY-MM-DD HH:mm",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "query_operation_logs",
                description = "查询记忆操作日志（WAL），查看历史变更记录。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "operation_type", type = "string", description = "按操作类型过滤：create/update/delete/merge", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "开始时间，格式：YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "结束时间，格式：YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "返回的最大日志数量，默认100", required = false, default = "100")
                )
            ),
            ToolPrompt(
                name = "find_path_between_memories",
                description = "在知识图谱中查找两个记忆之间的所有连接路径（多跳推理）。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "source_title",
                        type = "string",
                        description = "源记忆的标题",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "target_title",
                        type = "string",
                        description = "目标记忆的标题",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_hops",
                        type = "integer",
                        description = "最大搜索跳数，默认3",
                        required = false,
                        default = "3",
                    ),
                    ToolParameterSchema(
                        name = "max_paths",
                        type = "integer",
                        description = "最大返回路径数，默认0",
                        required = false,
                        default = "10",
                    )
                )
            ),
            ToolPrompt(
                name = "find_graph_related_memories",
                description = "通过知识图谱连接，找到与查询记忆强相关的其他记忆。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "query_title",
                        type = "string",
                        description = "查询记忆的标题",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_hops",
                        type = "integer",
                        description = "最大搜索跳数，默认2",
                        required = false,
                        default = "2",
                    ),
                    ToolParameterSchema(
                        name = "min_weight",
                        type = "number",
                        description = "最小边权重阈值（0.0-1.0），默认0.5",
                        required = false,
                        default = "0.5",
                    )
                )
            ),
            ToolPrompt(
                name = "export_memories_to_markdown",
                description = "将所有记忆导出为Markdown文件（兼容Obsidian/Notion）。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "output_directory",
                        type = "string",
                        description = "保存Markdown文件的目录路径",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "include_metadata",
                        type = "boolean",
                        description = "是否包含YAML元数据头，默认true",
                        required = false,
                        default = "true",
                    )
                )
            ),
            ToolPrompt(
                name = "export_graph_to_opml",
                description = "将知识图谱导出为OPML格式（兼容XMind、MindNode等思维导图软件）。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "output_file",
                        type = "string",
                        description = "保存OPML文件的路径",
                        required = true
                    )
                )
            ),
            ToolPrompt(
                name = "chain_of_thought_search",
                description = "执行思维链（CoT）检索：模拟人类推理过程，分步查找相关记忆。特别适合复杂查询。",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "query",
                        type = "string",
                        description = "原始用户查询",
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "max_steps",
                        type = "integer",
                        description = "最大推理步数，默认3",
                        required = false,
                        default = "3",
                    )
                )
            )
        ),
        categoryFooter = "\n注意：记忆库和用户性格档案会在你输出任务完成标志后由独立的系统自动更新。但是，如果需要立即管理记忆或更新用户偏好，请直接使用相应的工具。",
    )

    private val internalToolCategoriesEn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesEn
    private val internalToolCategoriesCn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesCn
    
    /**
     * 获取所有英文工具分类
    * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能，
    * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）

     */
    fun getAIAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemTools.copy(
            tools = fileSystemTools.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "Read the content of a file. For media files, you can also provide an 'intent' parameter to use a backend recognition model for analysis."
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionEn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicTools,
            adjustedFileSystemTools,
            httpTools,
            memoryTools
        )
    }

    fun getAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesEn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesEn
    }
    
    /**
     * 获取所有中文工具分类
    * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能，
    * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）

     */
    fun getAIAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        val shouldExposeIntent =
            (hasBackendImageRecognition && !chatModelHasDirectImage) ||
                (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
                (hasBackendVideoRecognition && !chatModelHasDirectVideo)

        val adjustedFileSystemTools = fileSystemToolsCn.copy(
            tools = fileSystemToolsCn.tools.map { tool ->
                if (tool.name != "read_file") return@map tool

                val filteredParams = (tool.parametersStructured ?: emptyList()).filter { param ->
                    when (param.name) {
                        "direct_image" -> false
                        "direct_audio" -> false
                        "direct_video" -> false
                        "intent" -> shouldExposeIntent
                        else -> true
                    }
                }

                val adjustedDescription =
                    if (shouldExposeIntent) {
                        "读取文件内容。对于媒体文件，你也可以提供 intent 参数，使用后端识别模型进行分析。"
                    } else {
                        tool.description
                    }

                tool.copy(
                    description = adjustedDescription + buildSafBookmarksSectionCn(safBookmarkNames),
                    parametersStructured = filteredParams
                )
            }
        )

        return listOf(
            basicToolsCn,
            adjustedFileSystemTools,
            httpToolsCn,
            memoryToolsCn
        )
    }

    fun getAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesCn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesCn
    }

    data class ManageableToolPrompt(
        val categoryName: String,
        val name: String,
        val description: String
    )

    private fun applyToolVisibility(
        categories: List<SystemToolPromptCategory>,
        toolVisibility: Map<String, Boolean>
    ): List<SystemToolPromptCategory> {
        if (toolVisibility.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val visibleTools = category.tools.filter { tool ->
                toolVisibility[tool.name] ?: true
            }
            if (visibleTools.isEmpty()) {
                null
            } else {
                category.copy(tools = visibleTools)
            }
        }
    }

    fun getManageableToolPrompts(useEnglish: Boolean): List<ManageableToolPrompt> {
        val baseCategories = if (useEnglish) {
            listOf(basicTools, fileSystemTools, httpTools, memoryTools)
        } else {
            listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn)
        }

        return baseCategories
            .flatMap { category ->
                category.tools.map { tool ->
                    ManageableToolPrompt(
                        categoryName = category.categoryName,
                        name = tool.name,
                        description = tool.description
                    )
                }
            }
            .distinctBy { it.name }
    }

    fun generateMemoryToolsPromptEn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryTools), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    fun generateMemoryToolsPromptCn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryToolsCn), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    private fun buildToolHookPayload(
        categories: List<SystemToolPromptCategory>
    ): List<Map<String, Any?>> {
        return categories.flatMap { category ->
            category.tools.map { tool ->
                mapOf(
                    "categoryName" to category.categoryName,
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parameters,
                    "details" to tool.details,
                    "notes" to tool.notes,
                    "parametersStructured" to
                        tool.parametersStructured.orEmpty().map { parameter ->
                            mapOf(
                                "name" to parameter.name,
                                "type" to parameter.type,
                                "description" to parameter.description,
                                "required" to parameter.required,
                                "default" to parameter.default
                            )
                        }
                )
            }
        }
    }
    
    /**
     * 生成完整的工具提示词文本（英文）

     */
    fun generateToolsPromptEn(
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "Memory and Memory Library Tools" }
        }
        val availableTools = buildToolHookPayload(categories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    useEnglish = true,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility
                        )
                )
            )
        var prompt = beforeContext.toolPrompt
            ?: applyToolVisibility(categories, toolVisibility).joinToString("\n\n") { it.toString() }
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt
                )
            )
        prompt = filterContext.toolPrompt ?: prompt
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt
                )
            )
        return afterContext.toolPrompt ?: prompt
    }
    
    /**
     * 生成完整的工具提示词文本（中文）

     */
    fun generateToolsPromptCn(
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "记忆与记忆库工具" }
        }
        val availableTools = buildToolHookPayload(categories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    useEnglish = false,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility
                        )
                )
            )
        var prompt = beforeContext.toolPrompt
            ?: applyToolVisibility(categories, toolVisibility).joinToString("\n\n") { it.toString() }
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt
                )
            )
        prompt = filterContext.toolPrompt ?: prompt
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt
                )
            )
        return afterContext.toolPrompt ?: prompt
    }
}
