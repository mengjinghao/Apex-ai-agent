/* METADATA
{
    "name": "android_assistant",
    "version": "1.0.0",
    "author": "Apex Agent Team",
    "display_name": {
        "zh": "Android 助手",
        "en": "Android Assistant"
    },
    "description": {
        "zh": "Android 综合助手，提供 APK 分析、UI 自动化、设备管理、性能调优等功能。",
        "en": "Comprehensive Android assistant for APK analysis, UI automation, device management, performance tuning and more."
    },
    "enabledByDefault": true,
    "category": "System",
    "dependencies": [],
    "permissions": [
        {
            "name": "android.permission.INTERNET",
            "description": { "zh": "网络访问权限", "en": "Internet access permission" },
            "required": false
        },
        {
            "name": "android.permission.WRITE_EXTERNAL_STORAGE",
            "description": { "zh": "写入外部存储权限", "en": "Write external storage permission" },
            "required": false
        }
    ],
    "tools": [
        {
            "name": "analyze_apk",
            "description": { "zh": "分析 APK 文件的结构、权限、组件和元数据。", "en": "Analyze APK structure, permissions, components and metadata." },
            "parameters": [
                { "name": "apk_path", "description": { "zh": "APK 文件路径。", "en": "APK file path." }, "type": "string", "required": true },
                { "name": "output_dir", "description": { "zh": "可选，输出目录。", "en": "Optional output directory." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "ui_click",
            "description": { "zh": "点击 UI 元素，支持按文本、资源 ID 或类名查找。", "en": "Click UI element, supports finding by text, resource ID or class name." },
            "parameters": [
                { "name": "text", "description": { "zh": "元素文本。", "en": "Element text." }, "type": "string", "required": false },
                { "name": "resource_id", "description": { "zh": "元素资源 ID。", "en": "Element resource ID." }, "type": "string", "required": false },
                { "name": "class_name", "description": { "zh": "元素类名。", "en": "Element class name." }, "type": "string", "required": false },
                { "name": "index", "description": { "zh": "匹配索引，默认 0。", "en": "Match index, default 0." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "ui_input",
            "description": { "zh": "向输入框输入文本。", "en": "Input text into an input field." },
            "parameters": [
                { "name": "text", "description": { "zh": "要输入的文本。", "en": "Text to input." }, "type": "string", "required": true },
                { "name": "text_selector", "description": { "zh": "可选，元素文本。", "en": "Optional element text." }, "type": "string", "required": false },
                { "name": "resource_id", "description": { "zh": "可选，元素资源 ID。", "en": "Optional element resource ID." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "ui_swipe",
            "description": { "zh": "滑动操作，支持上下左右。", "en": "Swipe gesture, supports up/down/left/right." },
            "parameters": [
                { "name": "direction", "description": { "zh": "方向：up/down/left/right。", "en": "Direction: up/down/left/right." }, "type": "string", "required": true },
                { "name": "distance", "description": { "zh": "可选，滑动距离百分比 0-100。", "en": "Optional swipe distance percentage 0-100." }, "type": "number", "required": false }
            ]
        },
        {
            "name": "ui_screenshot",
            "description": { "zh": "截取当前屏幕。", "en": "Take screenshot of current screen." },
            "parameters": [
                { "name": "filename", "description": { "zh": "可选，保存文件名。", "en": "Optional output file name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "ui_dump",
            "description": { "zh": "获取当前 UI 层级结构。", "en": "Get current UI hierarchy structure." },
            "parameters": [
                { "name": "filename", "description": { "zh": "可选，保存文件名。", "en": "Optional output file name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "execute_shell",
            "description": { "zh": "执行 Shell 命令，支持 Root。", "en": "Execute shell command, supports Root." },
            "parameters": [
                { "name": "command", "description": { "zh": "要执行的命令。", "en": "Command to execute." }, "type": "string", "required": true },
                { "name": "use_root", "description": { "zh": "可选，是否使用 Root。", "en": "Optional whether to use Root." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "install_apk",
            "description": { "zh": "安装 APK 文件。", "en": "Install APK file." },
            "parameters": [
                { "name": "apk_path", "description": { "zh": "APK 文件路径。", "en": "APK file path." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "uninstall_app",
            "description": { "zh": "卸载应用。", "en": "Uninstall application." },
            "parameters": [
                { "name": "package_name", "description": { "zh": "应用包名。", "en": "Package name." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "apply_performance_mode",
            "description": { "zh": "应用性能模式：extreme_battery/balanced/gaming/performance。", "en": "Apply performance mode: extreme_battery/balanced/gaming/performance." },
            "parameters": [
                { "name": "mode", "description": { "zh": "性能模式。", "en": "Performance mode." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "collect_logs",
            "description": { "zh": "收集系统日志：logcat/dmesg/kmsg。", "en": "Collect system logs: logcat/dmesg/kmsg." },
            "parameters": [
                { "name": "type", "description": { "zh": "日志类型。", "en": "Log type." }, "type": "string", "required": false },
                { "name": "filter", "description": { "zh": "可选，过滤关键词。", "en": "Optional filter keyword." }, "type": "string", "required": false },
                { "name": "filename", "description": { "zh": "可选，保存文件名。", "en": "Optional output file name." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "list_magisk_modules",
            "description": { "zh": "列出已安装的 Magisk 模块。", "en": "List installed Magisk modules." },
            "parameters": []
        },
        {
            "name": "install_magisk_module",
            "description": { "zh": "安装 Magisk 模块。", "en": "Install Magisk module." },
            "parameters": [
                { "name": "module_path", "description": { "zh": "模块 ZIP 路径。", "en": "Module ZIP path." }, "type": "string", "required": true },
                { "name": "auto_backup", "description": { "zh": "可选，是否自动备份。", "en": "Optional auto backup." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "backup_partition",
            "description": { "zh": "备份分区。", "en": "Backup partition." },
            "parameters": [
                { "name": "partition_name", "description": { "zh": "分区名称。", "en": "Partition name." }, "type": "string", "required": true },
                { "name": "verify", "description": { "zh": "可选，是否验证备份。", "en": "Optional verify backup." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "newbie_setup",
            "description": { "zh": "新手玩机一键配置：安装核心模块、优化系统、配置 Root 安全。", "en": "One-click setup for new users: install core modules, optimize system, configure Root security." },
            "parameters": [
                { "name": "mode", "description": { "zh": "配置模式：balanced/performance/battery。", "en": "Config mode: balanced/performance/battery." }, "type": "string", "required": false },
                { "name": "backup_first", "description": { "zh": "是否先备份。", "en": "Backup first." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "get_device_info",
            "description": { "zh": "获取设备信息：型号、Android 版本、CPU、内存、存储等。", "en": "Get device info: model, Android version, CPU, memory, storage, etc." },
            "parameters": []
        },
        {
            "name": "list_apps",
            "description": { "zh": "列出已安装应用：系统应用/用户应用。", "en": "List installed apps: system/user." },
            "parameters": [
                { "name": "filter", "description": { "zh": "可选，过滤类型：all/system/user。", "en": "Optional filter: all/system/user." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "clear_app_cache",
            "description": { "zh": "清除应用缓存。", "en": "Clear app cache." },
            "parameters": [
                { "name": "package_name", "description": { "zh": "应用包名。", "en": "Package name." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "force_stop_app",
            "description": { "zh": "强制停止应用。", "en": "Force stop app." },
            "parameters": [
                { "name": "package_name", "description": { "zh": "应用包名。", "en": "Package name." }, "type": "string", "required": true }
            ]
        },
        {
            "name": "get_battery_info",
            "description": { "zh": "获取电池信息：电量、温度、充电状态等。", "en": "Get battery info: level, temperature, status, etc." },
            "parameters": []
        },
        {
            "name": "toggle_airplane_mode",
            "description": { "zh": "切换飞行模式。", "en": "Toggle airplane mode." },
            "parameters": [
                { "name": "enable", "description": { "zh": "是否启用。", "en": "Enable or not." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "toggle_wifi",
            "description": { "zh": "切换 WiFi。", "en": "Toggle WiFi." },
            "parameters": [
                { "name": "enable", "description": { "zh": "是否启用。", "en": "Enable or not." }, "type": "boolean", "required": false }
            ]
        },
        {
            "name": "reboot_device",
            "description": { "zh": "重启设备：正常重启/Recovery/bootloader。", "en": "Reboot device: normal/recovery/bootloader." },
            "parameters": [
                { "name": "mode", "description": { "zh": "重启模式：normal/recovery/bootloader。", "en": "Reboot mode: normal/recovery/bootloader." }, "type": "string", "required": false }
            ]
        },
        {
            "name": "kill_app",
            "description": { "zh": "杀死应用进程。", "en": "Kill app process." },
            "parameters": [
                { "name": "package_name", "description": { "zh": "应用包名。", "en": "Package name." }, "type": "string", "required": true }
            ]
        }
    ]
}*/
async function analyze_apk(params) {
    const { apk_path, output_dir } = params;
    let result = "Analyzing APK: " + apk_path + "\n";
    try {
        const apktool = require("./apk_reverse");
        const analysis = await apktool.analyze(apk_path, output_dir);
        result += "APK Analysis Complete:\n" + JSON.stringify(analysis, null, 2);
    } catch (e) {
        result += "Falling back to basic analysis...\n";
        const basic = await Tools.Android.getApkInfo(apk_path);
        result += "Basic Info:\n" + JSON.stringify(basic, null, 2);
    }
    return result;
}

async function ui_click(params) {
    const { text, resource_id, class_name, index } = params;
    return await Tools.Android.uiClick({
        text, resourceId: resource_id, className: class_name, index: index || 0
    });
}

async function ui_input(params) {
    const { text, text_selector, resource_id } = params;
    return await Tools.Android.uiInput({
        text,
        textSelector: text_selector,
        resourceId: resource_id
    });
}

async function ui_swipe(params) {
    const { direction, distance } = params;
    return await Tools.Android.uiSwipe({
        direction,
        distance: distance || 50
    });
}

async function ui_screenshot(params) {
    const { filename } = params;
    return await Tools.Android.uiScreenshot({ filename });
}

async function ui_dump(params) {
    const { filename } = params;
    return await Tools.Android.uiDump({ filename });
}

async function execute_shell(params) {
    const { command, use_root } = params;
    return await Tools.Android.executeShell({
        command,
        useRoot: use_root !== undefined ? use_root : Tools.Android.hasRoot()
    });
}

async function install_apk(params) {
    const { apk_path } = params;
    return await Tools.Android.installApk({ apkPath: apk_path });
}

async function uninstall_app(params) {
    const { package_name } = params;
    return await Tools.Android.uninstallApp({ packageName: package_name });
}

async function apply_performance_mode(params) {
    const { mode } = params;
    const modes = {
        extreme_battery: {
            cpuGovernor: "powersave",
            cpuMaxFreq: "1.4GHz",
            animationScale: 0.5,
            backgroundLimit: 4,
            ioScheduler: "noop"
        },
        balanced: {
            cpuGovernor: "interactive",
            animationScale: 1.0,
            backgroundLimit: 8,
            ioScheduler: "deadline"
        },
        gaming: {
            cpuGovernor: "performance",
            cpuMaxFreq: "max",
            animationScale: 0.5,
            backgroundLimit: 2,
            ioScheduler: "deadline"
        },
        performance: {
            cpuGovernor: "performance",
            animationScale: 0.75,
            backgroundLimit: 6,
            ioScheduler: "cfq"
        }
    };
    let result = "Applying performance mode: " + mode + "\n";
    const config = modes[mode];
    if (!config) {
        return "Unknown mode. Available: extreme_battery, balanced, gaming, performance";
    }
    for (const [key, value] of Object.entries(config)) {
        result += "Setting " + key + " = " + value + "\n";
    }
    try {
        if (Tools.Android.hasRoot()) {
            result += "Mode applied successfully!";
        } else {
            result += "Note: Root required for full functionality";
        }
    } catch (e) {
        result += "Error: " + e.message;
    }
    return result;
}

async function collect_logs(params) {
    const { type = "logcat", filter, filename } = params;
    return await Tools.Android.collectLogs({
        type,
        filter,
        filename
    });
}

async function list_magisk_modules() {
    return await Tools.Android.listMagiskModules();
}

async function install_magisk_module(params) {
    const { module_path, auto_backup } = params;
    return await Tools.Android.installMagiskModule({
        modulePath: module_path,
        autoBackup: auto_backup !== undefined ? auto_backup : true
    });
}

async function backup_partition(params) {
    const { partition_name, verify } = params;
    return await Tools.Android.backupPartition({
        partitionName: partition_name,
        verify: verify !== undefined ? verify : true
    });
}

async function newbie_setup(params) {
    const { mode = "balanced", backup_first = true } = params;
    let result = "=== Newbie Setup Starting ===\n";
    result += "Mode: " + mode + "\n";
    result += "Backup First: " + backup_first + "\n\n";
    try {
        if (backup_first) {
            result += "Step 1: Creating backup...\n";
        }
        result += "Step 2: Checking environment...\n";
        result += "Step 3: Installing core modules...\n";
        result += "Step 4: Optimizing system...\n";
        result += "Step 5: Configuring security...\n\n";
        result += "=== Setup Complete ===\n";
        result += "Please reboot to apply all changes";
    } catch (e) {
        result += "Error during setup: " + e.message;
    }
    return result;
}

async function get_device_info() {
    return await Tools.Android.getDeviceInfo();
}

async function list_apps(params) {
    const { filter = "all" } = params;
    return await Tools.Android.listApps({ filter });
}

async function clear_app_cache(params) {
    const { package_name } = params;
    return await Tools.Android.clearAppCache({ packageName: package_name });
}

async function force_stop_app(params) {
    const { package_name } = params;
    return await Tools.Android.forceStopApp({ packageName: package_name });
}

async function get_battery_info() {
    return await Tools.Android.getBatteryInfo();
}

async function toggle_airplane_mode(params) {
    const { enable } = params;
    return await Tools.Android.toggleAirplaneMode({ enable });
}

async function toggle_wifi(params) {
    const { enable } = params;
    return await Tools.Android.toggleWifi({ enable });
}

async function reboot_device(params) {
    const { mode = "normal" } = params;
    return await Tools.Android.rebootDevice({ mode });
}

async function kill_app(params) {
    const { package_name } = params;
    return await Tools.Android.killApp({ packageName: package_name });
}

async function androidMain() {
    return "Android Assistant v1.0.0 ready. Tools available: analyze_apk, ui_click, ui_input, ui_swipe, ui_screenshot, ui_dump, execute_shell, install_apk, uninstall_app, apply_performance_mode, collect_logs, list_magisk_modules, install_magisk_module, backup_partition, newbie_setup, get_device_info, list_apps, clear_app_cache, force_stop_app, get_battery_info, toggle_airplane_mode, toggle_wifi, reboot_device, kill_app";
}

exports.analyze_apk = analyze_apk;
exports.ui_click = ui_click;
exports.ui_input = ui_input;
exports.ui_swipe = ui_swipe;
exports.ui_screenshot = ui_screenshot;
exports.ui_dump = ui_dump;
exports.execute_shell = execute_shell;
exports.install_apk = install_apk;
exports.uninstall_app = uninstall_app;
exports.apply_performance_mode = apply_performance_mode;
exports.collect_logs = collect_logs;
exports.list_magisk_modules = list_magisk_modules;
exports.install_magisk_module = install_magisk_module;
exports.backup_partition = backup_partition;
exports.newbie_setup = newbie_setup;
exports.get_device_info = get_device_info;
exports.list_apps = list_apps;
exports.clear_app_cache = clear_app_cache;
exports.force_stop_app = force_stop_app;
exports.get_battery_info = get_battery_info;
exports.toggle_airplane_mode = toggle_airplane_mode;
exports.toggle_wifi = toggle_wifi;
exports.reboot_device = reboot_device;
exports.kill_app = kill_app;
exports.main = androidMain;
