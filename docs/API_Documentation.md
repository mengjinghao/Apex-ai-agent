# 权限模式系统 - API文档

## 目录

1. [快速开始](#快速开始)
2. [核心组件](#核心组件)
3. [API参考](#api参考)
4. [数据模型](#数据模型)

---

## 快速开始

### 初始化系统

```kotlin
// 在 Application.onCreate() 中调用
import com.apex.core.tools.system.PermissionModeIntegration

override fun onCreate() {
    super.onCreate()
    PermissionModeIntegration.initialize(applicationContext)
}
```

### 基本使用

```kotlin
// 获取模式管理器
val modeManager = PermissionModeIntegration.getModeManager()

// 检测所有模式
runBlocking {
    modeManager.checkAllModes(forceRefresh = true)
}

// 获取最佳模式
val bestMode = modeManager.getBestAvailableMode()

// 切换到指定模式
runBlocking {
    modeManager.switchToMode(PermissionMode.ROOT)
}
```

---

## 核心组件

### 1. PermissionModeIntegration - 集成入口

统一的权限模式系统集成入口，负责组件的初始化和管理。

#### 主要方法

```kotlin
// 初始化系统
fun initialize(context: Context)

// 获取系统状态
fun isInitialized(): Boolean

// 获取管理器实例
fun getModeManager(): PermissionModeManager
fun getBackupManager(): PermissionConfigBackupManager
fun getSmartSwitcher(): SmartModeSwitcher
fun getAdvisor(): PermissionModeAdvisor

// 快捷操作
fun getBestAvailableMode(): PermissionMode
suspend fun autoSwitchToBestMode(): Boolean
suspend fun performFullSystemCheck(context: Context): SystemCheckResult
```

---

### 2. PermissionModeManager - 模式管理器

核心的权限模式管理组件，负责模式检测、切换和状态管理。

#### 主要方法

```kotlin
// 获取单例
fun getInstance(context: Context): PermissionModeManager

// 模式检测
suspend fun checkAllModes(forceRefresh: Boolean)
suspend fun checkMode(mode: PermissionMode, forceRefresh: Boolean): PermissionModeState
suspend fun checkRoot(forceRefresh: Boolean): RootDetectionResult
suspend fun checkShizuku(forceRefresh: Boolean): ShizukuDetectionResult

// 模式切换
suspend fun switchToMode(mode: PermissionMode): Boolean
suspend fun autoSelectBestMode(): PermissionMode?

// 查询方法
fun getModeState(mode: PermissionMode): PermissionModeState?
fun getAvailableModes(): List<PermissionModeState>
fun getUsableModes(): List<PermissionModeState>
fun getBestAvailableMode(): PermissionMode

// 状态流
val modeStates: StateFlow<Map<PermissionMode, PermissionModeState>>
val currentMode: StateFlow<PermissionMode?>
val rootResult: StateFlow<RootDetectionResult>
val shizukuResult: StateFlow<ShizukuDetectionResult>
```

---

### 3. RootManager - Root管理器

专门用于Root权限管理，支持多种Root方案。

#### 主要方法

```kotlin
// 初始化
fun initialize(context: Context)

// 状态检测
suspend fun checkRootStatus(context: Context, forceRefresh: Boolean): RootDetectionResult

// 权限请求
fun requestRootPermission(onResult: (Boolean) -> Unit)

// 执行模式设置
suspend fun setExecutionMode(mode: RootExecutionMode)

// 命令执行
suspend fun executeRootCommand(
    command: String,
    context: Context,
    identity: ShellIdentity = ShellIdentity.ROOT
): Pair<Boolean, String>

// 状态流
val isRooted: StateFlow<Boolean>
val hasRootAccess: StateFlow<Boolean>
val rootScheme: StateFlow<RootScheme>
val seLinuxStatus: StateFlow<SELinuxStatus>
val rootExecutionMode: StateFlow<RootExecutionMode>
```

---

### 4. ShizukuManager - Shizuku管理器

管理Shizuku/Sui权限和服务。

#### 主要方法

```kotlin
// 初始化
fun initialize()

// 状态检查
fun checkStatus(): ShizukuDetectionResult
fun isShizukuServiceRunning(): Boolean
fun hasShizukuPermission(): Boolean

// 权限请求
fun requestShizukuPermission(onResult: (Boolean) -> Unit)

// 获取状态
fun getStatus(): ShizukuDetectionResult
fun isShizukuInstalled(context: Context): Boolean

// 状态流
val isShizukuInstalled: StateFlow<Boolean>
val isServiceAvailable: StateFlow<Boolean>
val isPermissionGranted: StateFlow<Boolean>
val isSuiBackend: StateFlow<Boolean>
val currentUid: StateFlow<Int>
```

---

### 5. PermissionModeExecutorFactory - 执行器工厂

根据权限模式创建对应的命令执行器。

#### 主要方法

```kotlin
// 获取单例
fun getInstance(context: Context): PermissionModeExecutorFactory

// 获取执行器
suspend fun getExecutor(mode: PermissionMode): ShellExecutor
suspend fun getCurrentModeExecutor(): ShellExecutor
suspend fun getBestAvailableExecutor(): Pair<ShellExecutor, PermissionMode>
suspend fun getUserPreferredExecutor(): ShellExecutor
suspend fun tryGetExecutor(
    preferredModes: List<PermissionMode> = PermissionMode.sortedByLevelDesc()
): ShellExecutor

// 预加载
suspend fun preloadExecutors(modes: List<PermissionMode>)

// 缓存管理
fun clearCache(mode: PermissionMode? = null)
suspend fun refreshAll()

// 查询
suspend fun getAvailableExecutors(): Map<PermissionMode, Pair<ShellExecutor, ShellExecutor.PermissionStatus>>

// 状态流
val executorCache: StateFlow<Map<PermissionMode, ShellExecutor>>
val currentExecutor: StateFlow<ShellExecutor?>
val isInitializing: StateFlow<Boolean>
```

---

### 6. EnhancedPermissionPreferences - 增强偏好管理

管理权限相关的用户偏好设置。

#### 主要方法

```kotlin
// 初始化
fun initEnhancedPermissionPreferences(context: Context)

// 首选模式
suspend fun savePreferredMode(mode: PermissionMode)
fun getPreferredMode(): PermissionMode?
val preferredModeFlow: Flow<PermissionMode?>

// Root执行模式
suspend fun saveRootExecutionMode(mode: RootExecutionMode)
fun getRootExecutionMode(): RootExecutionMode
val rootExecutionModeFlow: Flow<RootExecutionMode>

// 自定义su命令
suspend fun saveCustomSuCommand(command: String)
fun getCustomSuCommand(): String
val customSuCommandFlow: Flow<String>

// 自动切换
suspend fun saveAutoSwitchEnabled(enabled: Boolean)
fun isAutoSwitchEnabled(): Boolean

// 记住最后模式
suspend fun saveRememberLastMode(remember: Boolean)
fun shouldRememberLastMode(): Boolean
suspend fun saveLastUsedMode(mode: PermissionMode)
fun getLastUsedMode(): PermissionMode?

// 配置管理
fun exportConfigToJson(): String
suspend fun importConfigFromJson(json: String): Boolean
suspend fun exportConfigToFile(file: File): Boolean
suspend fun importConfigFromFile(file: File): Boolean
suspend fun resetAll()

// 状态流
val config: StateFlow<PermissionConfig?>
val isLoading: StateFlow<Boolean>
```

---

### 7. PermissionConfigBackupManager - 配置备份管理器

处理权限配置的备份和恢复。

#### 主要方法

```kotlin
// 构造
val backupManager = PermissionConfigBackupManager(context)

// 备份操作
suspend fun backupConfig(): BackupResult
fun hasBackup(): Boolean
fun deleteBackup(): Boolean
fun getBackupFile(): File
fun exportConfigToJson(): String

// 恢复操作
suspend fun restoreConfig(): RestoreResult
suspend fun importConfigFromJson(jsonString: String): RestoreResult

// 状态流
val isBackingUp: StateFlow<Boolean>
val isRestoring: StateFlow<Boolean>
```

---

### 8. SmartModeSwitcher - 智能模式切换器

提供智能的权限模式切换功能。

#### 主要方法

```kotlin
// 构造
val smartSwitcher = SmartModeSwitcher(modeManager)

// 自动切换
fun enableAutoSwitch()
fun disableAutoSwitch()
suspend fun smartSwitchToBestMode(): Boolean

// 场景选择
fun selectModeForScenario(scenario: UsageScenario): PermissionMode

// 历史记录
fun getSwitchHistory(): List<SwitchHistoryItem>
fun clearHistory()

// 状态流
val autoSwitchEnabled: StateFlow<Boolean>
val switchHistory: StateFlow<List<SwitchHistoryItem>>
```

---

### 9. PermissionModeAdvisor - 模式建议器

提供权限模式的智能建议。

#### 主要方法

```kotlin
// 构造
val advisor = PermissionModeAdvisor(modeManager)

// 建议生成
fun getRecommendedMode(): PermissionMode
fun getModeSuggestions(): List<ModeSuggestion>
fun getModeDetails(mode: PermissionMode): ModeDetails
```

---

## API参考

### 权限模式枚举

```kotlin
enum class PermissionMode(
    val id: String,
    val displayName: String,
    val description: String,
    val level: Int,
    val requiresRoot: Boolean = false,
    val requiresShizuku: Boolean = false,
    val requiresAccessibility: Boolean = false,
    val requiresAdmin: Boolean = false
) {
    STANDARD("standard", "标准模式", "使用普通应用权限", level = 0),
    ACCESSIBILITY("accessibility", "无障碍模式", "使用无障碍服务权限", level = 1, requiresAccessibility = true),
    DEBUGGER("debugger", "调试模式", "使用调试桥接权限", level = 2),
    ADMIN("admin", "管理员模式", "使用设备管理员权限", level = 3, requiresAdmin = true),
    SHIZUKU("shizuku", "Shizuku模式", "使用Shizuku/Sui服务", level = 4, requiresShizuku = true),
    ROOT("root", "Root模式", "使用完全Root权限", level = 5, requiresRoot = true)
}

// 辅助方法
fun PermissionMode.fromId(id: String): PermissionMode
fun PermissionMode.sortedByLevel(): List<PermissionMode>
fun PermissionMode.sortedByLevelDesc(): List<PermissionMode>
```

---

### Root执行模式枚举

```kotlin
enum class RootExecutionMode(
    val id: String,
    val displayName: String,
    val description: String
) {
    AUTO("auto", "自动模式", "自动选择最佳的Root执行方式"),
    FORCE_LIBSU("force_libsu", "Libsu模式", "强制使用Libsu库"),
    FORCE_EXEC("force_exec", "Exec模式", "强制使用Runtime.exec()"),
    FORCE_KERNELSU("force_kernelsu", "KernelSU模式", "强制使用KernelSU方式"),
    FORCE_MAGISK("force_magisk", "Magisk模式", "强制使用Magisk方式")
}
```

---

### Root方案枚举

```kotlin
enum class RootScheme(
    val displayName: String,
    val packageName: String? = null
) {
    UNKNOWN("未知方案"),
    MAGISK("Magisk", "com.topjohnwu.magisk"),
    KERNELSU("KernelSU", "me.weishu.kernelsu"),
    APATCH("APatch", "me.bmax.apatch"),
    SUPERSU("SuperSU", "eu.chainfire.supersu"),
    KINGROOT("KingRoot", "com.kingroot.kinguser"),
    SUI("Sui (Shizuku)", "rikka.sui"),
    OTHER("其他Root方案")
}
```

---

### SELinux状态枚举

```kotlin
enum class SELinuxStatus(val displayName: String) {
    ENFORCING("强制模式"),
    PERMISSIVE("宽容模式"),
    DISABLED("已禁用"),
    UNKNOWN("未知状态")
}
```

---

## 数据模型

### PermissionModeState

权限模式状态。

```kotlin
@Parcelize
data class PermissionModeState(
    val mode: PermissionMode,
    val isAvailable: Boolean = false,
    val isGranted: Boolean = false,
    val isPreferred: Boolean = false,
    val checkTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable {
    val isUsable: Boolean = isAvailable && isGranted
}
```

---

### RootDetectionResult

Root检测结果。

```kotlin
@Parcelize
data class RootDetectionResult(
    val isRooted: Boolean = false,
    val hasRootAccess: Boolean = false,
    val rootScheme: RootScheme = RootScheme.UNKNOWN,
    val suPath: String? = null,
    val suVersion: String? = null,
    val seLinuxStatus: SELinuxStatus = SELinuxStatus.UNKNOWN,
    val detectionTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable
```

---

### ShizukuDetectionResult

Shizuku检测结果。

```kotlin
@Parcelize
data class ShizukuDetectionResult(
    val isAvailable: Boolean = false,
    val isGranted: Boolean = false,
    val isSuiBackend: Boolean = false,
    val isShizukuInstalled: Boolean = false,
    val shizukuPackageName: String? = null,
    val shizukuVersion: String? = null,
    val uid: Int = -1,
    val detectionTimestamp: Long = 0L,
    val errorMessage: String? = null
) : Parcelable {
    val isUsable: Boolean = isAvailable && isGranted
}
```

---

### PermissionConfig

权限配置数据。

```kotlin
@Serializable
data class PermissionConfig(
    val preferredMode: String? = null,
    val rootExecutionMode: String = RootExecutionMode.AUTO.id,
    val customSuCommand: String = AndroidPermissionPreferences.DEFAULT_SU_COMMAND,
    val autoSwitchEnabled: Boolean = true,
    val rememberLastMode: Boolean = true,
    val lastUsedMode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun getPreferredPermissionMode(): PermissionMode?
    fun getRootExecutionMode(): RootExecutionMode
}
```

---

### SystemCheckResult

系统检查结果。

```kotlin
data class SystemCheckResult(
    val duration: Long,
    val modeStates: Map<PermissionMode, PermissionModeState>,
    val rootResult: RootDetectionResult,
    val shizukuResult: ShizukuDetectionResult
)
```

---

### ModeSuggestion

模式建议。

```kotlin
data class ModeSuggestion(
    val mode: PermissionMode,
    val type: SuggestionType,
    val reason: String
)

enum class SuggestionType {
    RECOMMENDED,
    CURRENT,
    AVAILABLE
}
```

---

### ModeDetails

模式详细信息。

```kotlin
data class ModeDetails(
    val mode: PermissionMode,
    val state: PermissionModeState?,
    val features: List<String>,
    val limitations: List<String>
)
```

---

### SwitchHistoryItem

切换历史记录项。

```kotlin
data class SwitchHistoryItem(
    val mode: PermissionMode,
    val timestamp: Long,
    val reason: String? = null
) {
    val formattedTime: String
}
```

---

### UsageScenario

使用场景枚举。

```kotlin
enum class UsageScenario {
    STANDARD,        // 标准使用
    AUTOMATION,      // 自动化任务
    DEBUG,           // 调试开发
    SYSTEM_ADMIN     // 系统管理
}
```

---

## ShellExecutor接口

所有执行器都实现此接口。

```kotlin
interface ShellExecutor {
    // 基础信息
    fun getPermissionLevel(): AndroidPermissionLevel
    fun isAvailable(): Boolean
    fun hasPermission(): PermissionStatus
    
    // 初始化
    fun initialize()
    fun requestPermission(onResult: (Boolean) -> Unit)
    
    // 命令执行
    suspend fun executeCommand(
        command: String,
        identity: ShellIdentity
    ): CommandResult
    
    // 进程启动
    suspend fun startProcess(command: String): ShellProcess
    
    // 结果类
    data class PermissionStatus(
        val granted: Boolean,
        val reason: String = ""
    )
    
    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
}
```

---

## 使用流程示例

### 完整初始化流程

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 1. 初始化权限模式系统
        PermissionModeIntegration.initialize(this)
        
        // 2. 等待系统就绪
        lifecycleScope.launch {
            while (!PermissionModeIntegration.isInitialized()) {
                delay(100)
            }
            
            // 3. 执行初始检测
            val modeManager = PermissionModeIntegration.getModeManager()
            modeManager.checkAllModes(forceRefresh = true)
            
            // 4. 打印系统状态
            val result = PermissionModeIntegration.performFullSystemCheck(this@MyApplication)
            Log.d("Init", "系统检查完成，耗时: ${result.duration}ms")
        }
    }
}
```

### 执行命令的完整流程

```kotlin
suspend fun executeWithBestMode(context: Context, command: String): String {
    // 1. 获取执行器工厂
    val factory = PermissionModeExecutorFactory.getInstance(context)
    
    // 2. 获取最佳执行器
    val (executor, mode) = factory.getBestAvailableExecutor()
    
    // 3. 检查权限
    val permissionStatus = executor.hasPermission()
    if (!permissionStatus.granted) {
        return "权限不足: ${permissionStatus.reason}"
    }
    
    // 4. 执行命令
    val result = executor.executeCommand(command, ShellIdentity.CURRENT)
    
    // 5. 返回结果
    return if (result.success) {
        result.stdout
    } else {
        "执行失败: ${result.stderr}"
    }
}
```

---

## 注意事项

1. **协程使用**: 大部分API使用suspend函数，需要在协程中调用
2. **线程安全**: 所有管理器都是线程安全的，可以在多线程环境使用
3. **缓存机制**: 检测结果有缓存，避免频繁检测
4. **回调处理**: 权限请求等异步操作，使用回调处理结果
5. **状态观察**: 使用StateFlow观察状态变化，更新UI
6. **向后兼容**: 新系统保留了旧API的兼容性

---

## 错误处理

所有API都可能抛出异常，建议进行异常处理：

```kotlin
try {
    // 执行操作
    modeManager.checkAllModes()
} catch (e: Exception) {
    // 处理错误
    Log.e("PermissionMode", "检测失败", e)
    // 显示错误信息
}
```

---

## 更新日志

### v2.0 (当前版本)
- ✨ 全新的权限模式管理系统
- 🎨 液态玻璃风格UI
- 🚀 性能优化和缓存机制
- 📋 支持6种权限模式
- 🔄 智能自动切换
- 💾 配置备份恢复
- 📊 详细的诊断工具

### v1.x (旧版)
- 基础的Root/Shizuku支持
- 简单的权限级别管理
