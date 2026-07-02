package com.apex.core.tools.javascript

/** JavaScript Tools 对象定义 提供了一组便捷的工具调用方法，用于在JavaScript脚本中调用Android工具 */
fun getJsToolsDefinition(): String {
    return """
        // 工具调用的便捷方�?       var Tools = {
            // 文件系统操作
            Files: {
                list: (path, environment) => {
                    const params = { path };
                    if (environment) params.environment = environment;
                    return toolCall("list_files", params);
                },
                read: (pathOrOptions) => {
                    let params;
                    if (typeof pathOrOptions === 'string') {
                        // Simple form: read(path)
                        params = { path: pathOrOptions };
                    } else if (pathOrOptions && typeof pathOrOptions === 'object') {
                        // Options form: read({ path, environment?, intent? })
                        params = { ...pathOrOptions };
                    } else {
                        params = {};
                    }
                    return toolCall("read_file_full", params);
                },
                readBinary: (path, environment) => {
                    const params = { path };
                    if (environment) params.environment = environment;
                    return toolCall("read_file_binary", params);
                },
                readPart: (path, startLine, endLine, environment) => {
                    const params = { path };
                    if (startLine !== undefined) params.start_line = String(startLine);
                    if (endLine !== undefined) params.end_line = String(endLine);
                    if (environment) params.environment = environment;
                    return toolCall("read_file_part", params);
                },
                write: (path, content, append, environment) => {
                    const params = { path, content };
                    if (append !== undefined) params.append = append ? "true" : "false";
                    if (environment) params.environment = environment;
                    return toolCall("write_file", params);
                },
                writeBinary: (path, base64Content, environment) => {
                    const params = { path, base64Content };
                    if (environment) params.environment = environment;
                    return toolCall("write_file_binary", params);
                },
                deleteFile: (path, recursive, environment) => {
                    const params = { path };
                    if (recursive !== undefined) params.recursive = recursive ? "true" : "false";
                    if (environment) params.environment = environment;
                    return toolCall("delete_file", params);
                },
                exists: (path, environment) => {
                    const params = { path };
                    if (environment) params.environment = environment;
                    return toolCall("file_exists", params);
                },
                move: (source, destination, environment) => {
                    const params = { source, destination };
                    if (environment) params.environment = environment;
                    return toolCall("move_file", params);
                },
                copy: (source, destination, recursive, sourceEnvironment, destEnvironment) => {
                    const params = { source, destination };
                    if (recursive !== undefined) params.recursive = recursive ? "true" : "false";
                    if (sourceEnvironment) params.source_environment = sourceEnvironment;
                    if (destEnvironment) params.dest_environment = destEnvironment;
                    return toolCall("copy_file", params);
                },
                mkdir: (path, create_parents, environment) => {
                    const params = { path };
                    if (create_parents !== undefined) params.create_parents = create_parents ? "true" : "false";
                    if (environment) params.environment = environment;
                    return toolCall("make_directory", params);
                },
                find: (path, pattern, options = {}, environment) => {
                    const params = { path, pattern, ...options };
                    if (environment) params.environment = environment;
                    return toolCall("find_files", params);
                },
                grep: (path, pattern, options = {}) => {
                    const params = { path, pattern, ...options };
                    // environment can be included in options
                    return toolCall("grep_code", params);
                },
                grepContext: (path, intent, options = {}) => {
                    const params = { path, intent, ...options };
                    // environment and file_pattern can be included in options
                    return toolCall("grep_context", params);
                },
                info: (path, environment) => {
                    const params = { path };
                    if (environment) params.environment = environment;
                    return toolCall("file_info", params);
                },
                // 智能应用文件绑定
                apply: (path, type, oldContent, newContent, environment) => {
                    const params = { path, type };
                    if (oldContent !== undefined && oldContent !== null) params.old = String(oldContent);
                    if (newContent !== undefined && newContent !== null) params.new = String(newContent);
                    if (environment) params.environment = environment;
                    return toolCall("apply_file", params);
                },
                zip: (source, destination, environment) => {
                    const params = { source, destination };
                    if (environment) params.environment = environment;
                    return toolCall("zip_files", params);
                },
                unzip: (source, destination, environment) => {
                    const params = { source, destination };
                    if (environment) params.environment = environment;
                    return toolCall("unzip_files", params);
                },
                open: (path, environment) => {
                    const params = { path };
                    if (environment) params.environment = environment;
                    return toolCall("open_file", params);
                },
                share: (path, title, environment) => {
                    const params = { path };
                    if (title) params.title = title;
                    if (environment) params.environment = environment;
                    return toolCall("share_file", params);
                },
                download: (urlOrOptions, destination, environment, headers) => {
                    let params;
                    if (typeof urlOrOptions === 'string') {
                        params = { url: urlOrOptions };
                        if (destination !== undefined && destination !== null) params.destination = destination;
                        if (environment) params.environment = environment;
                        if (headers !== undefined && headers !== null && typeof headers === 'object') {
                            params.headers = JSON.stringify(headers);
                        }
                    } else if (urlOrOptions && typeof urlOrOptions === 'object') {
                        params = { ...urlOrOptions };
                        if (destination !== undefined && destination !== null) params.destination = destination;
                        if (environment) params.environment = environment;
                        if (headers !== undefined && headers !== null && typeof headers === 'object') {
                            params.headers = JSON.stringify(headers);
                        }
                        if (params.headers !== undefined && params.headers !== null && typeof params.headers === 'object') {
                            params.headers = JSON.stringify(params.headers);
                        }
                    } else {
                        params = {};
                    }
                    return toolCall("download_file", params);
                },
            },
            // 网络操作
            Net: {
                httpGet: (url, ignoreSsl) => {
                    const params = { url, method: "GET" };
                    if (ignoreSsl !== undefined) params.ignore_ssl = ignoreSsl ? "true" : "false";
                    return toolCall("http_request", params);
                },
                httpPost: (url, body, ignoreSsl) => {
                    const params = { url, method: "POST", body };
                    if (typeof body === 'object') {
                        params.body = JSON.stringify(body);
                    }
                    if (ignoreSsl !== undefined) params.ignore_ssl = ignoreSsl ? "true" : "false";
                    return toolCall("http_request", params);
                },
                visit: (params) => {
                    if (typeof params === 'string') {
                        // 向后兼容，如果只传入一个字符串，则假定为URL
                        return toolCall("visit_web", { url: params });
                    }
                    // 否则，假定为参数对象
                    if (params && typeof params === 'object' && params.headers !== undefined && typeof params.headers === 'object') {
                        params = { ...params, headers: JSON.stringify(params.headers) };
                    }
                    return toolCall("visit_web", params);
                },
                browserNavigate: (urlOrOptions) => {
                    const params = typeof urlOrOptions === 'string' ? { url: urlOrOptions } : { ...(urlOrOptions || {}) };
                    if (!params.url) {
                        throw new Error("browserNavigate requires url");
                    }
                    if (params.headers !== undefined && typeof params.headers === 'object') {
                        params.headers = JSON.stringify(params.headers);
                    }
                    return toolCall("browser_navigate", params);
                },
                browserNavigateBack: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserNavigateBack only accepts one options object");
                    }
                    return toolCall("browser_navigate_back", options || {});
                },
                browserClick: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserClick only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (params.selector !== undefined && params.selector !== null) {
                        params.selector = String(params.selector).trim();
                        if (!params.selector) {
                            delete params.selector;
                        }
                    }
                    if (!params.ref && !params.selector) {
                        throw new Error("browserClick requires ref or selector");
                    }
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element).trim();
                        if (!params.element) {
                            delete params.element;
                        }
                    }
                    if (params.button !== undefined && params.button !== null) {
                        const button = String(params.button).trim();
                        if (button !== 'left' && button !== 'right' && button !== 'middle') {
                            throw new Error("button must be one of: left, right, middle");
                        }
                        params.button = button;
                    }
                    if (params.modifiers !== undefined) {
                        if (!Array.isArray(params.modifiers)) {
                            throw new Error("modifiers must be an array");
                        }
                        const allowedModifiers = ['Alt', 'Control', 'ControlOrMeta', 'Meta', 'Shift'];
                        const normalized = params.modifiers.map((modifier) => String(modifier).trim());
                        const invalid = normalized.filter((modifier) => !allowedModifiers.includes(modifier));
                        if (invalid.length > 0) {
                            throw new Error("Invalid modifiers: " + invalid.join(', '));
                        }
                        params.modifiers = normalized;
                    }
                    if (params.doubleClick !== undefined) {
                        params.doubleClick = !!params.doubleClick;
                    }
                    return toolCall("browser_click", params);
                },
                browserClose: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserClose only accepts one options object");
                    }
                    return toolCall("browser_close", options || {});
                },
                browserConsoleMessages: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserConsoleMessages only accepts one options object");
                    }
                    const params = { ...(options || {}) };
                    if (params.level !== undefined && params.level !== null) {
                        params.level = String(params.level).trim();
                    }
                    if (!params.level) {
                        params.level = "info";
                    }
                    if (params.filename !== undefined && params.filename !== null) {
                        params.filename = String(params.filename);
                    }
                    return toolCall("browser_console_messages", params);
                },
                browserDrag: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserDrag only accepts one options object");
                    }
                    const params = { ...options };
                    ['startElement', 'startRef', 'endElement', 'endRef'].forEach((key) => {
                        if (params[key] !== undefined && params[key] !== null) {
                            params[key] = String(params[key]).trim();
                        }
                        if (!params[key]) {
                            throw new Error("browserDrag requires " + key);
                        }
                    });
                    return toolCall("browser_drag", params);
                },
                browserEvaluate: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserEvaluate only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.function !== undefined && params.function !== null) {
                        params.function = String(params.function);
                    }
                    if (!params.function) {
                        throw new Error("browserEvaluate requires function");
                    }
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element);
                    }
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (params.element && !params.ref) {
                        throw new Error("ref is required when element is provided");
                    }
                    return toolCall("browser_evaluate", params);
                },
                browserFileUpload: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserFileUpload only accepts one options object");
                    }
                    const params = { ...(options || {}) };
                    if (params.paths !== undefined) {
                        if (!Array.isArray(params.paths)) {
                            throw new Error("paths must be an array");
                        }
                        params.paths = params.paths.map((path) => String(path));
                    }
                    return toolCall("browser_file_upload", params);
                },
                browserFillForm: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserFillForm only accepts one options object");
                    }
                    const params = { ...options };
                    if (!Array.isArray(params.fields) || params.fields.length === 0) {
                        throw new Error("browserFillForm requires a non-empty fields array");
                    }
                    return toolCall("browser_fill_form", params);
                },
                browserHandleDialog: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserHandleDialog only accepts one options object");
                    }
                    const params = { ...options };
                    if (typeof params.accept !== 'boolean') {
                        throw new Error("accept must be a boolean");
                    }
                    if (params.promptText !== undefined && params.promptText !== null) {
                        params.promptText = String(params.promptText);
                    }
                    return toolCall("browser_handle_dialog", params);
                },
                browserHover: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserHover only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (!params.ref) {
                        throw new Error("browserHover requires ref");
                    }
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element);
                    }
                    return toolCall("browser_hover", params);
                },
                browserNetworkRequests: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserNetworkRequests only accepts one options object");
                    }
                    const params = { ...(options || {}) };
                    if (params.includeStatic !== undefined) {
                        params.includeStatic = !!params.includeStatic;
                    }
                    if (params.filename !== undefined && params.filename !== null) {
                        params.filename = String(params.filename);
                    }
                    return toolCall("browser_network_requests", params);
                },
                browserPressKey: (keyOrOptions) => {
                    const params = typeof keyOrOptions === 'string' ? { key: keyOrOptions } : { ...(keyOrOptions || {}) };
                    if (params.key !== undefined && params.key !== null) {
                        params.key = String(params.key).trim();
                    }
                    if (!params.key) {
                        throw new Error("browserPressKey requires key");
                    }
                    return toolCall("browser_press_key", params);
                },
                browserResize: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserResize only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.width === undefined || params.height === undefined) {
                        throw new Error("browserResize requires width and height");
                    }
                    return toolCall("browser_resize", params);
                },
                browserRunCode: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserRunCode only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.code !== undefined && params.code !== null) {
                        params.code = String(params.code);
                    }
                    if (!params.code) {
                        throw new Error("browserRunCode requires code");
                    }
                    return toolCall("browser_run_code", params);
                },
                browserSelectOption: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserSelectOption only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (!params.ref) {
                        throw new Error("browserSelectOption requires ref");
                    }
                    if (!Array.isArray(params.values) || params.values.length === 0) {
                        throw new Error("browserSelectOption requires a non-empty values array");
                    }
                    params.values = params.values.map((value) => String(value));
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element);
                    }
                    return toolCall("browser_select_option", params);
                },
                browserSnapshot: (options) => {
                    if (options !== undefined && (typeof options !== 'object' || Array.isArray(options))) {
                        throw new Error("browserSnapshot only accepts one options object");
                    }
                    const params = { ...(options || {}) };
                    if (params.filename !== undefined && params.filename !== null) {
                        params.filename = String(params.filename).trim();
                        if (!params.filename) {
                            delete params.filename;
                        }
                    }
                    if (params.selector !== undefined && params.selector !== null) {
                        params.selector = String(params.selector).trim();
                        if (!params.selector) {
                            delete params.selector;
                        }
                    }
                    if (params.depth !== undefined && params.depth !== null) {
                        const depth = Number(params.depth);
                        if (!Number.isInteger(depth) || depth < 0) {
                            throw new Error("browserSnapshot depth must be a non-negative integer");
                        }
                        params.depth = depth;
                    }
                    return toolCall("browser_snapshot", params);
                },
                browserTabs: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserTabs only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.action !== undefined && params.action !== null) {
                        params.action = String(params.action).trim();
                    }
                    if (!params.action) {
                        throw new Error("browserTabs requires action");
                    }
                    return toolCall("browser_tabs", params);
                },
                browserTakeScreenshot: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserTakeScreenshot only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.type !== undefined && params.type !== null) {
                        params.type = String(params.type).trim();
                    }
                    if (!params.type) {
                        params.type = "png";
                    }
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element);
                    }
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (params.ref && !params.element) {
                        throw new Error("element is required when ref is provided");
                    }
                    if (params.element && !params.ref) {
                        throw new Error("ref is required when element is provided");
                    }
                    if (params.fullPage !== undefined) {
                        params.fullPage = !!params.fullPage;
                    }
                    return toolCall("browser_take_screenshot", params);
                },
                browserType: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserType only accepts one options object");
                    }
                    const params = { ...options };
                    if (params.ref !== undefined && params.ref !== null) {
                        params.ref = String(params.ref).trim();
                    }
                    if (!params.ref) {
                        throw new Error("browserType requires ref");
                    }
                    if (params.text === undefined || params.text === null) {
                        throw new Error("browserType requires text");
                    }
                    params.text = String(params.text);
                    if (params.element !== undefined && params.element !== null) {
                        params.element = String(params.element);
                    }
                    if (params.submit !== undefined) {
                        params.submit = !!params.submit;
                    }
                    if (params.slowly !== undefined) {
                        params.slowly = !!params.slowly;
                    }
                    return toolCall("browser_type", params);
                },
                browserWaitFor: (options) => {
                    if (!options || typeof options !== 'object' || Array.isArray(options)) {
                        throw new Error("browserWaitFor only accepts one options object");
                    }
                    const params = { ...options };
                    if (
                        params.time === undefined &&
                        params.text === undefined &&
                        params.textGone === undefined
                    ) {
                        throw new Error("browserWaitFor requires one of: time, text, textGone");
                    }
                    if (params.text !== undefined && params.text !== null) {
                        params.text = String(params.text);
                    }
                    if (params.textGone !== undefined && params.textGone !== null) {
                        params.textGone = String(params.textGone);
                    }
                    return toolCall("browser_wait_for", params);
                },
                // 新增增强版HTTP请求
                http: (options) => {
                    const params = { ...options };
                    if (params.body !== undefined && typeof params.body === 'object') {
                        params.body = JSON.stringify(params.body);
                    }
                    if (params.headers !== undefined && typeof params.headers === 'object') {
                        params.headers = JSON.stringify(params.headers);
                    }
                    if (params.ignore_ssl !== undefined && typeof params.ignore_ssl === 'boolean') {
                        params.ignore_ssl = params.ignore_ssl ? "true" : "false";
                    }
                    return toolCall("http_request", params);
                },
                // 新增文件上传
                uploadFile: (options) => {
                    const params = {
                        ...options,
                        files: JSON.stringify(options.files || []),
                        form_data: JSON.stringify(options.form_data || {})
                    };
                    if (options.headers !== undefined && typeof options.headers === 'object') {
                        params.headers = JSON.stringify(options.headers);
                    }
                    if (params.ignore_ssl !== undefined && typeof params.ignore_ssl === 'boolean') {
                        params.ignore_ssl = params.ignore_ssl ? "true" : "false";
                    }
                    return toolCall("multipart_request", params);
                },
                // 新增Cookie管理
                cookies: {
                    get: (domain) => toolCall("manage_cookies", { action: "get", domain }),
                    set: (domain, cookies) => toolCall("manage_cookies", { action: "set", domain, cookies }),
                    clear: (domain) => toolCall("manage_cookies", { action: "clear", domain })
                }
            },
            // 系统操作
            System: {
                sleep: (milliseconds) => toolCall("sleep", { duration_ms: parseInt(milliseconds) }),
                getSetting: (setting, namespace) => toolCall("get_system_setting", { setting, namespace }),
                setSetting: (setting, value, namespace) => toolCall("modify_system_setting", { setting, value, namespace }),
                getDeviceInfo: () => toolCall("device_info"),
                toast: (message) => toolCall("toast", { message: String(message ?? "") }),
                sendNotification: (message, title) => {
                    const params = { message: String(message ?? "") };
                    if (title !== undefined && title !== null && String(title).trim() !== "") {
                        params.title = String(title);
                    }
                    return toolCall("send_notification", params);
                },
                // 使用工具�?               usePackage: (packageName) => toolCall("use_package", { package_name: packageName }),
                // 安装应用
                installApp: (path) => toolCall("install_app", { path }),
                // 卸载应用
                uninstallApp: (packageName) => toolCall("uninstall_app", { package_name: packageName }),
                startApp: (packageName, activity) => {
                    const params = { package_name: packageName };
                    if (activity) params.activity = activity;
                    return toolCall("start_app", params);
                },
                stopApp: (packageName) => toolCall("stop_app", { package_name: packageName }),
                listApps: (includeSystem) => toolCall("list_installed_apps", { include_system: !!includeSystem }),
                // 获取设备通知
                getNotifications: (limit = 10, includeOngoing = false) => 
                    toolCall("get_notifications", { limit: parseInt(limit), include_ongoing: !!includeOngoing }),
                // 获取应用使用时长
                getAppUsageTime: (options = {}) => {
                    const params = { ...(options || {}) };
                    if (params.packageName !== undefined && params.packageName !== null) {
                        params.package_name = String(params.packageName);
                        delete params.packageName;
                    }
                    if (params.sinceHours !== undefined && params.sinceHours !== null) {
                        params.since_hours = parseInt(params.sinceHours);
                        delete params.sinceHours;
                    }
                    if (params.includeSystemApps !== undefined) {
                        params.include_system_apps = !!params.includeSystemApps;
                        delete params.includeSystemApps;
                    }
                    if (params.limit !== undefined && params.limit !== null) {
                        params.limit = parseInt(params.limit);
                    }
                    return toolCall("get_app_usage_time", params);
                },
                // 获取设备位置
                getLocation: (highAccuracy = false, timeout = 10) => 
                    toolCall("get_device_location", { high_accuracy: !!highAccuracy, timeout: parseInt(timeout) }),
                shell: (command) => toolCall("execute_shell", { command }),
                // 执行终端命令 - 一次性收集输�?               terminal: {
                    create: (sessionName) => toolCall("create_terminal_session", { session_name: sessionName }),
                    exec: (sessionId, command, timeoutMs) => {
                        const params = { session_id: sessionId, command };
                        if (timeoutMs !== undefined && timeoutMs !== null) {
                            params.timeout_ms = String(timeoutMs);
                        }
                        return toolCall("execute_in_terminal_session", params);
                    },
                    hiddenExec: (command, options = {}) => {
                        const params = { command };
                        if (options && typeof options === "object") {
                            if (options.executorKey !== undefined && options.executorKey !== null) {
                                params.executor_key = String(options.executorKey);
                            }
                            if (options.timeoutMs !== undefined && options.timeoutMs !== null) {
                                params.timeout_ms = String(options.timeoutMs);
                            }
                        }
                        return toolCall("execute_hidden_terminal_command", params);
                    },
                    screen: (sessionId) => toolCall("get_terminal_session_screen", { session_id: sessionId }),
                    close: (sessionId) => toolCall("close_terminal_session", { session_id: sessionId }),
                    input: (sessionId, options = {}) => {
                        const params = { session_id: sessionId };
                        if (options && typeof options === "object") {
                            if (options.input !== undefined && options.input !== null) {
                                params.input = String(options.input);
                            }
                            if (options.control !== undefined && options.control !== null) {
                                params.control = String(options.control);
                            }
                        }
                        return toolCall("input_in_terminal_session", params);
                    }
                },
                // 执行Intent
                intent: (options = {}) => {
                    return toolCall("execute_intent", options);
                },
                // 发送广播（避免手动拼接 extras JSON 的场景）
                sendBroadcast: (options = {}) => {
                    return toolCall("send_broadcast", options);
                }
            },
            // 软件设置
            SoftwareSettings: {
                readEnvironmentVariable: (key) => {
                    return toolCall("read_environment_variable", { key: String(key ?? "") });
                },
                writeEnvironmentVariable: (key, value) => {
                    const params = { key: String(key ?? "") };
                    if (value !== undefined && value !== null) params.value = String(value);
                    else params.value = "";
                    return toolCall("write_environment_variable", params);
                },
                listSandboxPackages: () => {
                    return toolCall("list_sandbox_packages", {});
                },
                setSandboxPackageEnabled: (packageName, enabled) => {
                    const params = { package_name: String(packageName ?? "") };
                    params.enabled = !!enabled;
                    return toolCall("set_sandbox_package_enabled", params);
                },
                executeSandboxScriptDirect: (options = {}) => {
                    const params = { ...(options || {}) };
                    return toolCall("execute_sandbox_script_direct", params);
                },
                restartMcpWithLogs: (timeoutMs) => {
                    const params = {};
                    if (timeoutMs !== undefined && timeoutMs !== null) params.timeout_ms = String(timeoutMs);
                    return toolCall("restart_mcp_with_logs", params);
                },
                getSpeechServicesConfig: () => {
                    return toolCall("get_speech_services_config", {});
                },
                setSpeechServicesConfig: (updates = {}) => {
                    const params = { ...(updates || {}) };
                    if (params.tts_headers !== undefined && params.tts_headers !== null && typeof params.tts_headers === 'object') {
                        params.tts_headers = JSON.stringify(params.tts_headers);
                    }
                    if (params.tts_response_pipeline !== undefined && params.tts_response_pipeline !== null && Array.isArray(params.tts_response_pipeline)) {
                        params.tts_response_pipeline = JSON.stringify(params.tts_response_pipeline);
                    }
                    if (params.tts_cleaner_regexs !== undefined && params.tts_cleaner_regexs !== null && Array.isArray(params.tts_cleaner_regexs)) {
                        params.tts_cleaner_regexs = JSON.stringify(params.tts_cleaner_regexs);
                    }
                    return toolCall("set_speech_services_config", params);
                },
                testTtsPlayback: (text, options = {}) => {
                    const params = { ...(options || {}), text: String(text ?? "") };
                    if (params.interrupt !== undefined && params.interrupt !== null) {
                        params.interrupt = !!params.interrupt;
                    }
                    return toolCall("test_tts_playback", params);
                },
                listModelConfigs: () => {
                    return toolCall("list_model_configs", {});
                },
                createModelConfig: (options = {}) => {
                    const params = { ...(options || {}) };
                    if (params.custom_parameters !== undefined && params.custom_parameters !== null && typeof params.custom_parameters === 'object') {
                        params.custom_parameters = JSON.stringify(params.custom_parameters);
                    }
                    if (params.custom_headers !== undefined && params.custom_headers !== null && typeof params.custom_headers === 'object') {
                        params.custom_headers = JSON.stringify(params.custom_headers);
                    }
                    return toolCall("create_model_config", params);
                },
                updateModelConfig: (configId, updates = {}) => {
                    const params = { ...(updates || {}), config_id: String(configId ?? "") };
                    if (params.custom_parameters !== undefined && params.custom_parameters !== null && typeof params.custom_parameters === 'object') {
                        params.custom_parameters = JSON.stringify(params.custom_parameters);
                    }
                    if (params.custom_headers !== undefined && params.custom_headers !== null && typeof params.custom_headers === 'object') {
                        params.custom_headers = JSON.stringify(params.custom_headers);
                    }
                    return toolCall("update_model_config", params);
                },
                deleteModelConfig: (configId) => {
                    return toolCall("delete_model_config", { config_id: String(configId ?? "") });
                },
                listFunctionModelConfigs: () => {
                    return toolCall("list_function_model_configs", {});
                },
                getFunctionModelConfig: (functionType) => {
                    return toolCall("get_function_model_config", { function_type: String(functionType ?? "") });
                },
                setFunctionModelConfig: (functionType, configId, modelIndex) => {
                    const params = {
                        function_type: String(functionType ?? ""),
                        config_id: String(configId ?? "")
                    };
                    if (modelIndex !== undefined && modelIndex !== null) params.model_index = String(modelIndex);
                    return toolCall("set_function_model_config", params);
                },
                testModelConfigConnection: (configId, modelIndex) => {
                    const params = { config_id: String(configId ?? "") };
                    if (modelIndex !== undefined && modelIndex !== null) params.model_index = String(modelIndex);
                    return toolCall("test_model_config_connection", params);
                }
            },
            // Tasker event
            Tasker: {
                triggerEvent: (params) => {
                    return toolCall("trigger_tasker_event", params || {});
                }
            },
            // UI操作
            UI: {
                getPageInfo: () => toolCall("get_page_info"),
                tap: (x, y) => toolCall("tap", { x, y }),
                longPress: (x, y) => toolCall("long_press", { x, y }),
                // 增强的clickElement方法，支持多种参数类�?               clickElement: function(param1, param2, param3) {
                    // 根据参数类型和数量判断调用方�?                   if (typeof param1 === 'object') {
                        // 如果第一个参数是对象，直接传递参数对�?                       return toolCall("click_element", param1);
                    } else if (arguments.length === 1) {
                        // 单参数，假定为resourceId
                        if (param1.startsWith('[') && param1.includes('][')) {
                            // 参数看起来像bounds格式 [x,y][x,y]
                            return toolCall("click_element", { bounds: param1 });
                        }
                        return toolCall("click_element", { resourceId: param1 });
                    } else if (arguments.length === 2) {
                        // 两个参数，假定为(resourceId, index)，className, index)
                        if (param1 === 'resourceId') {
                            return toolCall("click_element", { resourceId: param2 });
                        } else if (param1 === 'className') {
                            return toolCall("click_element", { className: param2 });
                        } else if (param1 === 'bounds') {
                            return toolCall("click_element", { bounds: param2 });
                        } else {
                            return toolCall("click_element", { resourceId: param1, index: param2 });
                        }
                    } else if (arguments.length === 3) {
                        // 三个参数，假定为(type, value, index)
                        if (param1 === 'resourceId') {
                            return toolCall("click_element", { resourceId: param2, index: param3 });
                        } else if (param1 === 'className') {
                            return toolCall("click_element", { className: param2, index: param3 });
                        } else {
                            return toolCall("click_element", { resourceId: param1, className: param2, index: param3 });
                        }
                    }
                    // 默认情况
                    return toolCall("click_element", { resourceId: param1 });
                },

                setText: (text, resourceId) => {
                    const params = { text };
                    if (resourceId) params.resourceId = resourceId;
                    return toolCall("set_input_text", params);
                },
                swipe: (startX, startY, endX, endY, duration) => {
                    const params = { 
                        start_x: startX, 
                        start_y: startY, 
                        end_x: endX, 
                        end_y: endY 
                    };
                    if (duration) params.duration = duration;
                    return toolCall("swipe", params);
                },
                pressKey: (keyCode) => toolCall("press_key", { key_code: keyCode }),
                /**
                 * Run the built-in UI automation subagent.
                 * @param intent High-level task description for the subagent.
                 * @param maxSteps Optional maximum number of steps (default 20).
                 * @param agentId Optional agent id to reuse the same virtual screen session.
                 * @param targetApp Optional target app package name.
                 */
                runSubAgent: (intent, maxSteps, agentId, targetApp) => {
                    const params = { intent: String(intent || "") };
                    if (maxSteps !== undefined) params.max_steps = String(maxSteps);
                    if (agentId !== undefined && agentId !== null && String(agentId).length > 0) params.agent_id = String(agentId);
                    if (targetApp !== undefined && targetApp !== null && String(targetApp).length > 0) params.target_app = String(targetApp);
                    return toolCall("run_ui_subagent", params);
                },
            },
            // 记忆管理
            Memory: {
                // 查询记忆�?               query: (query, folderPath, limit, startTime, endTime, snapshotId, threshold) => {
                    const params = { query };
                    if (folderPath) params.folder_path = folderPath;
                    if (startTime !== undefined) params.start_time = startTime;
                    if (endTime !== undefined) params.end_time = endTime;
                    if (limit !== undefined) params.limit = limit;
                    if (snapshotId !== undefined && snapshotId !== null) params.snapshot_id = String(snapshotId);
                    if (threshold !== undefined) params.threshold = threshold;
                    return toolCall("query_memory", params);
                },
                // 通过标题获取记忆
                getByTitle: (title, chunkIndex, chunkRange, query, limit) => {
                    const params = { title };
                    if (chunkIndex !== undefined) params.chunk_index = chunkIndex;
                    if (chunkRange) params.chunk_range = chunkRange;
                    if (query) params.query = query;
                    if (limit !== undefined) params.limit = limit;
                    return toolCall("get_memory_by_title", params);
                },
                // 创建记忆
                create: (title, content, contentType, source, folderPath, tags) => {
                    const params = { title, content };
                    if (contentType) params.content_type = contentType;
                    if (source) params.source = source;
                    if (folderPath) params.folder_path = folderPath;
                    if (tags) params.tags = tags;
                    return toolCall("create_memory", params);
                },
                // 更新记忆
                update: (oldTitle, updates = {}) => {
                    const params = { old_title: oldTitle };
                    if (updates.newTitle) params.new_title = updates.newTitle;
                    if (updates.content) params.content = updates.content;
                    if (updates.contentType) params.content_type = updates.contentType;
                    if (updates.source) params.source = updates.source;
                    if (updates.credibility !== undefined) params.credibility = updates.credibility;
                    if (updates.importance !== undefined) params.importance = updates.importance;
                    if (updates.folderPath) params.folder_path = updates.folderPath;
                    if (updates.tags) params.tags = updates.tags;
                    return toolCall("update_memory", params);
                },
                // 删除记忆
                deleteMemory: (title) => toolCall("delete_memory", { title }),
                // 批量移动记忆（按标题列表，或来源文件夹筛选）
                move: (targetFolderPath, titles, sourceFolderPath) => {
                    const params = { target_folder_path: targetFolderPath };
                    if (titles) {
                        params.titles = Array.isArray(titles) ? titles.join(",") : String(titles);
                    }
                    if (sourceFolderPath !== undefined && sourceFolderPath !== null) params.source_folder_path = String(sourceFolderPath);
                    return toolCall("move_memory", params);
                },
                // 链接记忆
                link: (sourceTitle, targetTitle, linkType, weight, description) => {
                    const params = { source_title: sourceTitle, target_title: targetTitle };
                    if (linkType) params.link_type = linkType;
                    if (weight !== undefined) params.weight = weight;
                    if (description) params.description = description;
                    return toolCall("link_memories", params);
                },
                // 查询记忆链接
                queryLinks: (linkId, sourceTitle, targetTitle, linkType, limit) => {
                    const params = {};
                    if (linkId !== undefined && linkId !== null) params.link_id = linkId;
                    if (sourceTitle) params.source_title = sourceTitle;
                    if (targetTitle) params.target_title = targetTitle;
                    if (linkType) params.link_type = linkType;
                    if (limit !== undefined) params.limit = limit;
                    return toolCall("query_memory_links", params);
                },
                // 更新记忆链接（优先按 linkId�?               updateLink: (linkId, sourceTitle, targetTitle, linkType, newLinkType, weight, description) => {
                    const params = {};
                    if (linkId !== undefined && linkId !== null) params.link_id = linkId;
                    if (sourceTitle) params.source_title = sourceTitle;
                    if (targetTitle) params.target_title = targetTitle;
                    if (linkType) params.link_type = linkType;
                    if (newLinkType) params.new_link_type = newLinkType;
                    if (weight !== undefined) params.weight = weight;
                    if (description !== undefined) params.description = description;
                    return toolCall("update_memory_link", params);
                },
                // 删除记忆链接（优先按 linkId�?               deleteLink: (linkId, sourceTitle, targetTitle, linkType) => {
                    const params = {};
                    if (linkId !== undefined && linkId !== null) params.link_id = linkId;
                    if (sourceTitle) params.source_title = sourceTitle;
                    if (targetTitle) params.target_title = targetTitle;
                    if (linkType) params.link_type = linkType;
                    return toolCall("delete_memory_link", params);
                }
            },
            // 计算功能
            calc: (expression) => toolCall("calculate", { expression }),
            
            // FFmpeg工具
            FFmpeg: {
                // 执行自定义FFmpeg命令
                execute: (command) => toolCall("ffmpeg_execute", { command }),
                
                // 获取FFmpeg系统信息
                info: () => toolCall("ffmpeg_info"),
                
                // 转换视频文件
                convert: (inputPath, outputPath, options = {}) => {
                    const params = {
                        input_path: inputPath,
                        output_path: outputPath,
                        ...options
                    };
                    return toolCall("ffmpeg_convert", params);
                }
            },
            
            // 工作流工�?           Workflow: {
                // 获取所有工作流
                getAll: () => {
                    return toolCall("get_all_workflows", {});
                },
                // 创建新工作流
                create: (name, description = "", nodes = null, connections = null, enabled = true) => {
                    const params = { name, description, enabled: enabled.toString() };
                    if (nodes) params.nodes = typeof nodes === 'string' ? nodes : JSON.stringify(nodes);
                    if (connections) params.connections = typeof connections === 'string' ? connections : JSON.stringify(connections);
                    return toolCall("create_workflow", params);
                },
                // 获取工作流详�?               get: (workflowId) => {
                    const params = { workflow_id: workflowId };
                    return toolCall("get_workflow", params);
                },
                // 更新工作�?               update: (workflowId, updates = {}) => {
                    const params = { workflow_id: workflowId };
                    if (updates.name !== undefined) params.name = updates.name;
                    if (updates.description !== undefined) params.description = updates.description;
                    if (updates.nodes !== undefined) params.nodes = typeof updates.nodes === 'string' ? updates.nodes : JSON.stringify(updates.nodes);
                    if (updates.connections !== undefined) params.connections = typeof updates.connections === 'string' ? updates.connections : JSON.stringify(updates.connections);
                    if (updates.enabled !== undefined) params.enabled = updates.enabled.toString();
                    return toolCall("update_workflow", params);
                },
                // 差异更新工作流（增量 patch�?               patch: (workflowId, patch = {}) => {
                    const params = { workflow_id: workflowId };
                    if (patch.name !== undefined) params.name = patch.name;
                    if (patch.description !== undefined) params.description = patch.description;
                    if (patch.enabled !== undefined) params.enabled = patch.enabled.toString();
                    if (patch.node_patches !== undefined) {
                        params.node_patches = typeof patch.node_patches === 'string'
                            ? patch.node_patches
                            : JSON.stringify(patch.node_patches);
                    }
                    if (patch.connection_patches !== undefined) {
                        params.connection_patches = typeof patch.connection_patches === 'string'
                            ? patch.connection_patches
                            : JSON.stringify(patch.connection_patches);
                    }
                    return toolCall("patch_workflow", params);
                },
                // 设置工作流启用状�?               setEnabled: (workflowId, enabled) => {
                    const params = { workflow_id: workflowId };
                    return toolCall(enabled ? "enable_workflow" : "disable_workflow", params);
                },
                // 启用工作�?               enable: (workflowId) => {
                    const params = { workflow_id: workflowId };
                    return toolCall("enable_workflow", params);
                },
                // 禁用工作�?               disable: (workflowId) => {
                    const params = { workflow_id: workflowId };
                    return toolCall("disable_workflow", params);
                },
                // 删除工作�?               delete: (workflowId) => {
                    const params = { workflow_id: workflowId };
                    return toolCall("delete_workflow", params);
                },
                // 触发工作流执�?               trigger: (workflowId) => {
                    const params = { workflow_id: workflowId };
                    return toolCall("trigger_workflow", params);
                }
            },
            // 对话管理工具
            Chat: {
                // 启动对话服务
                startService: () => toolCall("start_chat_service", {}),
                // 创建新对�?               createNew: (group, setAsCurrentChat, characterCardId) => {
                    const params = {};
                    if (group !== undefined && group !== null && String(group).trim() !== "") {
                        params.group = String(group);
                    }
                    if (setAsCurrentChat !== undefined && setAsCurrentChat !== null) {
                        params.set_as_current_chat = String(setAsCurrentChat);
                    }
                    if (characterCardId !== undefined && characterCardId !== null && String(characterCardId).trim() !== "") {
                        params.character_card_id = String(characterCardId);
                    }
                    return toolCall("create_new_chat", params);
                },
                // 列出所有对�?               listAll: () => toolCall("list_chats", {}),
                listChats: (params = {}) => toolCall("list_chats", params),
                findChat: (params = {}) => toolCall("find_chat", params),
                agentStatus: (chatId) => toolCall("agent_status", { chat_id: chatId }),
                // 切换对话
                switchTo: (chatId) => toolCall("switch_chat", { chat_id: chatId }),
                updateTitle: (chatId, title) => {
                    const params = { chat_id: String(chatId ?? ""), title: String(title ?? "") };
                    return toolCall("update_chat_title", params);
                },
                deleteChat: (chatId) => {
                    return toolCall("delete_chat", { chat_id: String(chatId ?? "") });
                },
                getMessages: (chatId, order, limit) => {
                    const params = { chat_id: chatId };
                    if (order !== undefined && order !== null && String(order).trim() !== "") params.order = String(order);
                    if (limit !== undefined && limit !== null && !isNaN(Number(limit))) params.limit = String(limit);
                    return toolCall("get_chat_messages", params);
                },
                // 发送消息给AI
                sendMessage: (message, chatId, roleCardId, senderName) => {
                    const params = { message };
                    if (chatId) params.chat_id = chatId;
                    if (roleCardId) params.role_card_id = roleCardId;
                    if (senderName) params.sender_name = senderName;
                    return toolCall("send_message_to_ai", params);
                },
                sendMessageAdvanced: (params = {}) => {
                    return toolCall("send_message_to_ai_advanced", params);
                },
                // 列出所有角色卡
                listCharacterCards: () => toolCall("list_character_cards", {})
            }
        };
    """.trimIndent()
}
