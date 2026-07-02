# ai-terminal 模块

Apex 项目的终端核心模块，提供完整的 Android 终端支持，融合先进的终端交互和 AI 辅助功能。

---

## 功能特性

### 基础功能
- ✅ **AIDL 跨进程通信** - 支持多进程调用
- ✅ **PTY 伪终端** - 原生 Shell 支持
- ✅ **JNI Native 实现** - 高性能终端命令执行
- ✅ **真正的进程管理** - fork/exec 实现
- ✅ **stderr 捕获** - 完整的输出流处理
- ✅ **超时控制** - 防止命令永久阻塞
- ✅ **线程池管理** - 高效的线程资源利用
- ✅ **Session 管理** - 线程安全的会话管理
- ✅ **环境变量** - Android 文件系统适配
- ✅ **独立进程** - 避免阻塞主进程
- ✅ **进程管理** - 完整的进程生命周期管理
- ✅ **Shell 扩展** - 管道、重定向、环境变量
- ✅ **性能监控** - 实时性能和资源监控

### AI 增强功能
- ✅ **智能命令搜索** - 支持模糊匹配、正则表达式、优先级排序的命令历史搜索
- ✅ **命令别名系统** - 自定义命令快捷方式（如 `l` → `ls -la`）
- ✅ **工作流录制与回放** - 记录和重放命令序列
- ✅ **AI 命令生成** - 自然语言转终端命令
- ✅ **代码解释** - 基于 AI 的代码理解能力
- ✅ **命令优化建议** - 分析执行结果并提供优化建议
- ✅ **脚本生成** - 根据需求自动生成 Shell 脚本
- ✅ **多步骤任务规划** - 将复杂任务分解为命令序列

### 高级交互功能
- ✅ **命令语法高亮** - 实时高亮显示关键字、内置命令、运算符、字符串、变量
- ✅ **智能自动补全** - Tab 键触发命令和参数补全，支持 40+ 常用命令
- ✅ **多标签页管理** - 支持多会话标签页，独立历史记录
- ✅ **命令书签收藏** - 收藏常用命令，支持标签分类和搜索
- ✅ **执行统计分析** - 命令使用频率、执行时间统计
- ✅ **终端输出搜索** - 实时搜索和高亮终端输出内容
- ✅ **命令分享** - 一键分享命令到剪贴板或系统分享

---

## 模块结构

```
ai-terminal/
├── src/
│   └── main/
│       ├── aidl/com/ai/assistance/Apex/terminal/    # AIDL 接口
│       │   ├── CommandExecutionEvent.aidl
│       │   ├── SessionDirectoryEvent.aidl
│       │   ├── ITerminalCallback.aidl
│       │   └── ITerminalService.aidl
│       ├── java/com/ai/assistance/Apex/terminal/    # 核心类
│       │   ├── CommandExecutionEvent.kt
│       │   ├── CommandResult.kt
│       │   ├── SessionDirectoryEvent.kt
│       │   ├── Pty.kt
│       │   ├── SessionManager.kt
│       │   ├── TerminalSession.kt
│       │   ├── TerminalEnv.kt
│       │   ├── TerminalManager.kt
│       │   ├── TerminalJni.kt
│       │   ├── ProcessManager.kt                       # 新增：进程管理
│       │   ├── ShellExtension.kt                      # 新增：Shell 扩展
│       │   ├── PerformanceMonitor.kt                   # 新增：性能监控
│       │   └── service/TerminalService.kt
│       ├── cpp/                                        # JNI Native 实现
│       │   ├── CMakeLists.txt
│       │   └── terminal_jni.cpp
│       └── AndroidManifest.xml
├── build.gradle.kts
├── proguard-rules.pro
└── consumer-rules.pro
```

---

## 一、配置说明

### build.gradle.kts

```kotlin
android {
    namespace = "com.ai.assistance.Apex.terminal"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-fexceptions", "-frtti")
                abiFilters("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
        ndkVersion = "25.2.9519653"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

---

## 二、核心功能使用

### 2.1 进程管理 (ProcessManager)

```kotlin
val processManager = ProcessManager.getInstance()

// 添加进程
val info = processManager.addProcess(pid, "ls -l")

// 获取进程信息
val process = processManager.getProcess(pid)
process?.let {
    println("PID: ${it.pid}, Command: ${it.command}, Status: ${it.status}")
}

// 终止进程
processManager.killProcess(pid)

// 获取所有运行中的进程
val running = processManager.getRunningProcesses()

// 注册监听器
processManager.registerListener(object : ProcessManager.ProcessListener {
    override fun onProcessStarted(info: ProcessManager.ProcessInfo) {
        println("Process started: ${info.pid}")
    }
    override fun onProcessTerminated(info: ProcessManager.ProcessInfo) {
        println("Process terminated: ${info.pid}, Exit: ${info.exitCode}")
    }
    override fun onProcessOutput(pid: Int, output: String) {
        println("Output from $pid: $output")
    }
})
```

### 2.2 Shell 扩展 (ShellExtension)

```kotlin
val shellExt = ShellExtension()

// 解析简单命令
val parsed = shellExt.parseCommand("ls -la")
// Result: CommandType.SIMPLE, components=["ls", "-la"]

// 解析管道
val piped = shellExt.parseCommand("ls -l | grep test")
// Result: CommandType.PIPELINE, components=["ls -l", "grep test"]

// 解析重定向
val redirected = shellExt.parseCommand("echo hello > file.txt")
// Result: CommandType.REDIRECT_STDOUT

// 解析后台执行
val background = shellExt.parseCommand("long-running-command &")
// Result: CommandType.BACKGROUND

// 解析序列命令
val sequence = shellExt.parseCommand("cd / && ls -l && pwd")
// Result: CommandType.SEQUENCE

// 解析环境变量
val expanded = shellExt.expandVariables("echo $HOME", mapOf("HOME" to "/data"))
// Result: "echo /data"

// 检查是否环境变量设置
val isEnv = shellExt.isEnvironmentVariable("PATH=/usr/bin")
// Result: true

// 提取输出重定向目标
val (cmd, file) = shellExt.extractOutputRedirect("ls > output.txt")
// cmd="ls", file="output.txt"
```

### 2.3 性能监控 (PerformanceMonitor)

```kotlin
val perfMonitor = PerformanceMonitor.getInstance(context)

// 开始监控
val sessionId = perfMonitor.startMonitoring("ls -l")
perfMonitor.recordCommandExecution("session1")

// 更新指标
perfMonitor.updateMetrics(sessionId, outputSize = 1024, threadCount = 5)

// 停止监控
perfMonitor.stopMonitoring(sessionId, exitCode = 0)

// 记录指标
perfMonitor.recordCommandDuration("session1", duration = 1500)
perfMonitor.recordPeakMemory("session1", memory = 50 * 1024 * 1024)

// 获取系统内存信息
val memInfo = perfMonitor.getSystemMemoryInfo()
println("Total: ${memInfo.totalMemory}, Used: ${memInfo.usedMemory}")

// 获取系统 CPU 使用
val cpuInfo = perfMonitor.getSystemCpuUsage()
println("Active Threads: ${cpuInfo.threadCount}")

// 生成性能报告
val report = perfMonitor.generateReport()
println(report.formatReport())

// 获取会话指标
val sessionMetrics = perfMonitor.getSessionMetrics("session1")
sessionMetrics?.let {
    println("Commands: ${it.commandCount.get()}")
    println("Errors: ${it.errorCount.get()}")
    println("Peak Memory: ${it.peakMemory.get()}")
}
```

---

## 三、错误码体系

| 错误码 | 常量 | 含义 |
|--------|------|------|
| 0 | EXIT_SUCCESS | 命令执行成功 |
| -1 | EXIT_ERROR | 命令执行失败 |
| -2 | EXIT_TIMEOUT | 命令超时 |

---

## 四、关键注意事项

### NDK/CMake 版本

确保 build.gradle.kts 中配置的 ndkVersion 和 cmake.version 与本地 SDK Manager 中安装的版本一致。

### 权限

终端命令执行可能需要以下权限：

- android.permission.INTERNET
- android.permission.READ_EXTERNAL_STORAGE
- android.permission.WRITE_EXTERNAL_STORAGE
- android.permission.RUN_IN_BACKGROUND

### 安全性

- 命令注入防护：避免执行未校验的用户输入命令
- 权限控制：敏感命令需要权限检查
- 超时控制：防止资源占用

---

## 五、测试与验证方案

### 单元测试

- TerminalEnv 环境变量配置
- Session 增删操作
- CommandResult 错误码处理
- JNI 方法调用
- ProcessManager 进程管理
- ShellExtension 命令解析
- PerformanceMonitor 性能监控

### 集成测试

- 完整命令执行流程
- 并发 Session 测试
- 跨进程通信测试
- 超时机制测试
- 进程生命周期测试
- Shell 扩展解析测试
- 性能监控采样测试

### 边界测试

- 空命令
- 超长命令（>10MB）
- 二进制输出
- 特殊字符命令
- Session 已存在
- 进程僵死
- 内存耗尽

---

## 六、扩展方向

### 短期（1-2周）

1. ✅ 实现完整的 PTY 支持
2. ✅ 增加管道和重定向支持
3. ✅ 添加环境变量导出

### 中期（1个月）

1. ✅ 支持后台进程管理
2. ✅ 添加进程组管理
3. ✅ 实现命令历史记录

### 长期（3个月）

1. ✅ 支持更多 shell 类型
2. ✅ 添加终端多路复用
3. ✅ 完整的性能监控

---

## 作者

Apex 项目团队

---

## 许可证

参见项目根目录的 LICENSE 文件
