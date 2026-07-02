# AI Terminal - Root/Non-Root 双模切换指南

## 概述

AI Terminal 现在支持 Root/Non-Root 双模切换，通过一个简单的开关来决定启动普通的 sh 还是通过 su 二进制文件启动 Root Shell。

## 硬核核心实现

### root_terminal_core.cpp - 核心 Native 层实现

这是最关键的部分。我们使用 `fork()` + `execve()` + `ptsname()` 来创建真正的 PTY。

**核心特点：**
- ✅ 使用 `posix_openpt()` 打开 PTY Master
- ✅ 调用 `grantpt()` 和 `unlockpt()` 配置 PTY
- ✅ 使用 `ptsname()` 获取 PTY Slave 路径
- ✅ 使用 `ioctl(TIOCSWINSZ)` 设置窗口大小
- ✅ 使用 `fork()` 启动子进程
- ✅ 使用 `setsid()` 创建新的 Session
- ✅ 使用 `TIOCSCTTY` 设置控制终端
- ✅ 重定向 `stdin/stdout/stderr` 到 PTY
- ✅ 使用 `execve()` 执行 shell
- ✅ 智能查找 su/sh 二进制文件位置

**技术实现：**
```cpp
// 1. 打开 PTY Master
int master_fd = posix_openpt(O_RDWR | O_CLOEXEC);

// 2. Grant/Unlock PTY
grantpt(master_fd);
unlockpt(master_fd);

// 3. 获取 PTY Slave 路径
const char* slave_name = ptsname(master_fd);

// 4. 设置窗口大小
set_window_size(master_fd, cols, rows);

// 5. Fork 进程
pid_t pid = fork();

if (pid == 0) {
    // 子进程：配置并启动 Shell
    setsid();  // 创建新 Session
    int slave_fd = open(slave_name, O_RDWR);
    ioctl(slave_fd, TIOCSCTTY, 0);  // 设置控制终端
    
    // 重定向标准流
    dup2(slave_fd, STDIN_FILENO);
    dup2(slave_fd, STDOUT_FILENO);
    dup2(slave_fd, STDERR_FILENO);
    
    // 根据 use_root 参数选择启动 su 或 sh
    if (use_root) {
        // 启动 su（通常 su 不带参数会直接启动 shell）
    } else {
        // 启动普通 sh
    }
    
    execve(shell_path, argv, c_env);
}
```

### 核心文件

| 文件 | 说明 |
|------|------|
| `root_terminal_core.cpp` | 核心 Native 实现，完整的 PTY 管理 |
| `RootTerminalManager.kt` | Kotlin 层 API 封装 |
| `CMakeLists.txt` | 编译配置，包含新文件 |

## 核心特性对比

| 特性 | 普通 Shell | Root Shell |
|------|-----------|-----------|
| 启动方式 | execve("/system/bin/sh") | execve("/system/xbin/su") 或 execve("/system/bin/su") |
| UID/GID | AID_APP (10000+) | AID_ROOT (0) |
| PTY 配置 | 标准 | 标准 (但需要处理部分 su 管理器的弹窗) |
| 访问权限 | 应用沙箱、公共文件、自己的数据目录 | 完整系统访问权限 |
| 环境变量 | PATH=/system/bin:/system/xbin | PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin |

## 快速开始

### 1. 基本使用示例

```kotlin
import com.ai.assistance.aiterminal.terminal.RootTerminalManager

// 初始化管理器
val rootManager = RootTerminalManager()

// 检查 Root 支持
val hasRoot = rootManager.checkRootAccess()
println("Root 支持: $hasRoot")

// 启动普通会话
val normalSession = rootManager.createNormalSession(
    size = RootTerminalManager.TerminalSize(rows = 30, cols = 100)
)

// 或启动 Root 会话（如果有 Root 权限）
if (hasRoot) {
    val rootSession = rootManager.createRootSession(
        size = RootTerminalManager.TerminalSize(rows = 30, cols = 100)
    )
}
```

### 2. 自动检测模式

```kotlin
// 自动选择最佳模式（优先 Root）
val session = rootManager.createSession(
    size = RootTerminalManager.TerminalSize(rows = 30, cols = 100),
    preferRoot = true // 如果有 Root 权限则使用 Root
)

// 获取当前模式
val currentMode = rootManager.getCurrentMode()
when (currentMode) {
    RootTerminalManager.MODE_NORMAL -> println("普通模式")
    RootTerminalManager.MODE_ROOT -> println("Root 模式")
}
```

### 3. 模式切换

```kotlin
// 假设我们有一个普通会话
val normalSession = rootManager.createNormalSession(...)

// 切换到 Root 模式（或相反）
val newSession = rootManager.switchSessionMode(
    oldSession = normalSession,
    size = RootTerminalManager.TerminalSize(rows = 30, cols = 100)
)
```

## 完整用法示例

### Kotlin 层

```kotlin
class MainActivity : AppCompatActivity() {
    
    private val rootManager = RootTerminalManager()
    private var currentSession: RootTerminalManager.TerminalSession? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // 检查 Root 权限
        checkRootStatus()
        
        // 创建会话
        setupTerminal()
    }
    
    private fun checkRootStatus() {
        val hasRoot = rootManager.checkRootAccess()
        Log.d("Terminal", "Root 权限: $hasRoot")
        
        if (hasRoot) {
            Toast.makeText(this, "已获得 Root 权限", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "当前设备无 Root 权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    private suspend fun setupTerminal() {
        try {
            // 检查是否有 Root，有则用 Root，没有则用普通
            currentSession = if (rootManager.checkRootAccess()) {
                rootManager.createRootSession(
                    size = RootTerminalManager.TerminalSize(rows = 30, cols = 100),
                    env = mapOf(
                        "LANG" to "zh_CN.UTF-8",
                        "PAGER" to "cat"
                    )
                )
            } else {
                rootManager.createNormalSession(
                    size = RootTerminalManager.TerminalSize(rows = 30, cols = 100),
                    env = mapOf(
                        "LANG" to "zh_CN.UTF-8"
                    )
                )
            }
            
            // 使用 ParcelFileDescriptor 进行读写
            val fileDescriptor = currentSession?.fileDescriptor
            
            // 在实际应用中，你会将此 FD 连接到终端模拟器
        } catch (e: Exception) {
            Log.e("Terminal", "初始化失败", e)
        }
    }
    
    // 切换模式按钮
    fun onSwitchModeButtonClick(view: View) {
        lifecycleScope.launch {
            currentSession?.let { session ->
                try {
                    // 销毁旧会话，创建新会话
                    currentSession = rootManager.switchSessionMode(
                        oldSession = session,
                        size = RootTerminalManager.TerminalSize(rows = 30, cols = 100)
                    )
                    
                    val mode = rootManager.getCurrentMode()
                    val modeText = if (mode == RootTerminalManager.MODE_ROOT) "Root" else "Normal"
                    Toast.makeText(this, "已切换到 $modeText 模式", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e("Terminal", "切换模式失败", e)
                }
            }
        }
    }
}
```

## 技术架构

### Native 层 (C++)

Native 层支持通过 `nativeCreatePty` 方法创建 PTY 并启动任意 Shell，包括：

```cpp
// 创建 PTY
int g_masterFd = posix_openpt(O_RDWR | O_NOCTTY);
grantpt(g_masterFd);
unlockpt(g_masterFd);

// 启动 Shell
pid_t pid = fork();
if (pid == 0) {
    // 子进程
    execve(shellPath, argv, envp);
}
```

### JNI 桥接

```cpp
// JNI 方法签名
extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_aiterminal_terminal_RootTerminalManager_nativeCreatePty(
    JNIEnv *env,
    jobject /* this */,
    jint cols,
    jint rows,
    jobjectArray envArray,
    jstring shellPath
);
```

## 🔧 Root 环境关键技术点

既然你决定做 Root 版本，有几个坑必须提前知道：

### 1. Su 二进制的碎片化

不同的 Root 方案（Magisk, SuperSU, KernelSU）的 su 路径和行为略有不同。

| Root 方案 | 特点 |
|----------|------|
| Magisk/KernelSU | 通常非常干净，执行 su 后直接给你一个 uid=0 的 shell |
| 旧版 SuperSU | 可能会弹框，需要在代码中处理 LD_PRELOAD 等 Hook 环境 |

**代码中的处理：**
在 C++ 中实现了 `find_su_binary()` 函数，遍历常见路径：
```cpp
const char* SU_PATHS[] = {
    "/system/xbin/su",
    "/system/bin/su",
    "/sbin/su",
    "/vendor/bin/su",
    nullptr
};
```

### 2. 进程保活与 AIDL

Root Shell 进程是 fork 出来的，它的生命周期独立于你的 App。

**建议架构：**
将 Terminal 核心（特别是 Root 部分）放在一个独立的 `:remote` 进程中，通过 AIDL 通信。这样即使你的主 App Crash 了，正在运行的 `top` 或 `vim` 不会死。

**复用已有代码：**
- ✅ 使用已有的 "多会话管理"
- ✅ 使用已有的 "响应式状态流"
- ✅ 监控 Root 进程的 SIGCHLD 信号

**当前架构：**
```
Main App 进程
  ↓ AIDL 通信
TerminalService (:terminal_process 进程)
  ↓ JNI
Native Layer (root_terminal_core.cpp)
```

### 3. 权限与 SELinux

在高版本 Android 上，即使有 Root，SELinux 也可能是 Enforcing，这会导致你无法访问某些文件。

**进阶技巧：**
- 在终端里执行命令遇到 Permission denied 时
- 可以尝试在 JNI 启动 su 之前，调用 `setexeccon(NULL)`
- 或者让用户执行 `setenforce 0`（不推荐，但有效）

**建议：**
我们当前的架构已经有 `TerminalService` 在独立进程中，可以作为独立进程运行，这样可以增强稳定性。

---

## 限制和注意事项

### 1. Root 权限
- 需要设备已 Root
- 需要 su 二进制文件存在于标准位置
- 某些 su 管理器可能会弹窗请求权限
- 部分设备或 su 实现可能不兼容

### 2. 安全考虑
- Root Shell 拥有完整系统权限，请谨慎使用
- 不要运行未知来源的脚本或命令
- 建议在完成工作后及时切换回普通模式

### 3. 资源管理
- 使用完毕后记得关闭 ParcelFileDescriptor
- 使用 `switchSessionMode` 会自动关闭旧会话
- 建议在应用销毁或不再需要时清理资源

### 4. 兼容性
- su 二进制文件路径检测支持多个常见位置
- 处理了多个不同的 su 实现（Magisk, SuperSU, KingRoot 等）
- 如果没有 Root 权限，优雅降级到普通模式

## 与现有系统整合

### 与 TerminalManager 配合使用

```kotlin
// 你可以同时使用两个系统
val rootManager = RootTerminalManager()
val terminalManager = TerminalManager.instance

// 简单用例用 TerminalManager
terminalManager.createSession("session1")
terminalManager.executeCommand("session1", "ls -la")

// 需要精细控制或 Root 权限时用 RootTerminalManager
val ptySession = rootManager.createRootSession(...)
```

### 与终端模拟器集成

```kotlin
// 将 ParcelFileDescriptor 连接到你的终端模拟器
val session = rootManager.createNormalSession(...)
val fileDescriptor = session.fileDescriptor

// 将 FD 传递给你的终端视图
terminalView.attachFd(fileDescriptor.detachFd())
```

## 调试和测试

### 1. 验证 Root 检测

```kotlin
// 调试模式：打印所有检查的路径
private fun debugSuCheck() {
    val paths = listOf(
        "/system/xbin/su",
        "/system/bin/su",
        "/sbin/su",
        "/su/bin/su"
    )
    
    for (path in paths) {
        val exists = File(path).exists()
        val executable = File(path).canExecute()
        Log.d("RootCheck", "$path exists: $exists, executable: $executable")
    }
}
```

### 2. 测试命令执行

```kotlin
// 测试简单的 Root 命令（如果在 Root 模式下）
val testCommands = listOf("id", "whoami", "ls -la /")
// 通过 ParcelFileDescriptor 写入并读取输出
```

## 总结

Root/Non-Root 双模切换功能提供了强大的灵活性：

✅ 简单的 API
✅ 智能检测 Root 权限
✅ 自动模式选择
✅ 优雅降级
✅ 安全的 PTY 实现

对于有 Root 权限的设备，用户可以获得完整系统控制；对于没有 Root 权限的设备，依然可以使用普通模式获得基础终端功能。
