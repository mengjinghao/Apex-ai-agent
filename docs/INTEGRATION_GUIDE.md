# 权限模式系统 - 集成指南

## 目录

1. [概述](#概述)
2. [快速集成](#快速集成)
3. [逐步集成](#逐步集成)
4. [UI集成](#ui集成)
5. [高级配置](#高级配置)
6. [故障排除](#故障排除)
7. [最佳实践](#最佳实践)

---

## 概述

本文档提供了将新的权限模式管理系统集成到现有Android项目中的完整指南。新系统具有以下优势：

- 📋 6种权限模式的完整支持
- 🔄 智能自动切换功能
- 💾 配置备份和恢复
- 🎨 现代化的液态玻璃UI
- 🚀 性能优化和缓存机制
- ↩️ 完整的向后兼容性

---

## 快速集成

### 1. 添加初始化代码

在您的Application类中添加以下代码：

```kotlin
// 在您的Application类中
import com.apex.core.tools.system.PermissionModeIntegration

class YourApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 原有初始化代码...
        
        // 添加权限模式系统初始化（新代码）
        PermissionModeIntegration.initialize(applicationContext)
    }
}
```

### 2. 在build.gradle中检查依赖

确认以下依赖已添加（通常已存在）：

```groovy
dependencies {
    // 核心依赖（通常已存在）
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0"
    implementation "androidx.datastore:datastore-preferences:1.0.0"
    
    // 编译时序列化（如需要）
    implementation "org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0"
    
    // 如果使用Shizuku
    implementation "dev.rikka.shizuku:api:13.1.5"
    implementation "dev.rikka.shizuku:provider:13.1.5"
    
    // 如果使用libsu
    implementation "com.github.topjohnwu.libsu:core:5.0.5"
}
```

### 3. 验证集成

启动应用并查看Logcat，确认以下日志输出：

```
PermissionModeIntegration: 开始初始化权限模式系统...
PermissionModeIntegration: ✅ 增强偏好管理初始化完成
PermissionModeIntegration: ✅ RootManager 初始化完成
PermissionModeIntegration: ✅ ShizukuManager 初始化完成
PermissionModeIntegration: ✅ PermissionModeManager 初始化完成
PermissionModeIntegration: ✅ 权限模式系统初始化成功
```

---

## 逐步集成

### 第一步：集成到Application类

#### 1.1 添加导入语句

在您的Application类顶部添加：

```kotlin
import com.apex.core.tools.system.PermissionModeIntegration
```

#### 1.2 在onCreate中添加初始化

找到合适的位置添加初始化代码（建议在其他偏好管理初始化之后）：

```kotlin
override fun onCreate() {
    super.onCreate()
    
    // ... 现有初始化代码 ...
    
    // 初始化用户偏好管理器（可能已存在）
    val defaultProfileName = applicationContext.getString(R.string.default_profile)
    initUserPreferencesManager(applicationContext, defaultProfileName)
    
    // 初始化Android权限偏好管理器（可能已存在）
    initAndroidPermissionPreferences(applicationContext)
    
    // 👇 新增：初始化权限模式集成系统
    PermissionModeIntegration.initialize(applicationContext)
    AppLogger.d(TAG, "权限模式集成系统初始化完成")
    
    // ... 其余初始化代码 ...
}
```

#### 1.3 完整示例

```kotlin
class YourApplication : Application() {
    companion object {
        private const val TAG = "YourApplication"
        lateinit var instance: YourApplication
    }
    
    override fun onCreate() {
        super.onCreate()
        val startTime = System.currentTimeMillis()
        
        instance = this
        
        // 初始化主题
        ThemeManager.init(this)
        
        // 初始化异常处理器
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(this))
        
        // 初始化JSON序列化
        initJsonSerializer()
        
        // 初始化语言设置
        initAppLanguage()
        
        // 初始化用户偏好
        val defaultProfileName = getString(R.string.default_profile)
        initUserPreferencesManager(applicationContext, defaultProfileName)
        
        // 初始化权限偏好
        initAndroidPermissionPreferences(applicationContext)
        
        // ✨ 新：初始化权限模式系统
        PermissionModeIntegration.initialize(applicationContext)
        AppLogger.d(TAG, "权限模式集成系统初始化完成")
        
        // 初始化其他系统...
        initAndroidShellExecutor()
        initShowerEnvironment()
        
        // 记录启动时间
        val totalTime = System.currentTimeMillis() - startTime
        AppLogger.d(TAG, "应用启动完成，总耗时: ${totalTime}ms")
    }
}
```

---

### 第二步：集成到MainActivity

#### 2.1 添加权限模式设置入口

在设置菜单或导航中添加一个权限模式设置项：

```kotlin
// 在您的设置界面或导航中
val navItems = listOf(
    NavItem("权限模式", R.drawable.ic_permission, PermissionModeActivity::class),
    // ... 其他项目
)
```

#### 2.2 创建跳转方法

```kotlin
fun openPermissionModeSettings(context: Context) {
    val intent = Intent(context, PermissionModeActivity::class.java)
    context.startActivity(intent)
}
```

---

### 第三步：AndroidManifest配置

#### 3.1 注册新Activity

```xml
<activity
    android:name=".ui.features.permission.PermissionModeActivity"
    android:theme="@style/Theme.YourApp"
    android:exported="false" />
```

#### 3.2 确保必要权限已声明

```xml
<!-- 如果需要Root功能 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- 如果需要无障碍功能 -->
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />

<!-- 如果需要设备管理员 -->
<uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />
```

---

## UI集成

### 选项1：使用新的完整界面

新系统提供了完整的权限模式设置界面，直接在您的应用中启动即可：

```kotlin
// 从任何Activity或Fragment中启动
val intent = Intent(context, PermissionModeActivity::class.java)
startActivity(intent)
```

### 选项2：将新UI集成到现有设置界面

如果您希望将权限模式选择集成到现有的设置界面，可以使用Composable组件：

```kotlin
@Composable
fun YourSettingsScreen() {
    // ... 其他设置项
    
    // 权限模式设置项
    PermissionModeSettingItem()
}

@Composable
fun PermissionModeSettingItem() {
    val context = LocalContext.current
    
    SettingItem(
        icon = Icons.Default.PermIdentity,
        title = "权限模式",
        subtitle = "管理Root/Shizuku等权限模式",
        onClick = {
            val intent = Intent(context, PermissionModeActivity::class.java)
            context.startActivity(intent)
        }
    )
}
```

### 选项3：在现有代码中使用权限模式API

在需要执行命令的地方，使用新的执行器工厂：

```kotlin
// 替换旧的执行代码
import com.apex.core.tools.system.shell.PermissionModeExecutorFactory

class YourCommandExecutor {
    suspend fun execute(command: String, context: Context): CommandResult {
        // 旧代码（可保留作为后备）
        // val executor = AndroidShellExecutor()
        
        // ✨ 新代码：使用最佳可用执行器
        val factory = PermissionModeExecutorFactory.getInstance(context)
        val executor = factory.getCurrentModeExecutor()
        
        return executor.executeCommand(command, ShellIdentity.CURRENT)
    }
}
```

---

## 高级配置

### 1. 自定义初始化顺序

如果您需要更精细地控制初始化顺序：

```kotlin
fun customInitialize(context: Context) {
    // 初始化增强偏好
    initEnhancedPermissionPreferences(context)
    
    // 初始化Root管理器
    RootManager.initialize(context)
    
    // 初始化Shizuku管理器
    ShizukuManager.initialize()
    
    // 初始化权限模式管理器
    val modeManager = PermissionModeManager.getInstance(context)
    
    // 预加载执行器
    lifecycleScope.launch {
        val factory = PermissionModeExecutorFactory.getInstance(context)
        factory.preloadExecutors()
    }
}
```

### 2. 配置自动切换策略

```kotlin
fun configureSmartSwitch(context: Context) {
    val preferences = enhancedPermissionPreferences
    
    lifecycleScope.launch {
        // 启用自动切换
        preferences.saveAutoSwitchEnabled(true)
        
        // 启用记住最后模式
        preferences.saveRememberLastMode(true)
        
        // 保存首选模式
        preferences.savePreferredMode(PermissionMode.SHIZUKU)
    }
}
```

### 3. 设置Root执行模式偏好

```kotlin
fun configureRootExecution(context: Context) {
    // 根据设备选择合适的执行模式
    val preferredMode = when {
        isKernelSU() -> RootExecutionMode.FORCE_KERNELSU
        isMagisk() -> RootExecutionMode.FORCE_LIBSU
        else -> RootExecutionMode.AUTO
    }
    
    lifecycleScope.launch {
        RootManager.setExecutionMode(preferredMode)
    }
}
```

### 4. 监听模式变化

```kotlin
fun observeModeChanges(context: Context) {
    val modeManager = PermissionModeIntegration.getModeManager()
    
    // 使用协程监听当前模式变化
    lifecycleScope.launch {
        modeManager.currentMode.collect { mode ->
            onModeChanged(mode)
        }
    }
    
    // 使用回调监听状态变化
    modeManager.addStateChangeListener { state ->
        onStateUpdated(state)
    }
}

private fun onModeChanged(newMode: PermissionMode?) {
    AppLogger.d("PermissionMode", "模式已切换: ${newMode?.displayName}")
    // 更新UI、重新初始化执行器等
}

private fun onStateUpdated(state: PermissionModeState) {
    AppLogger.d("PermissionMode", "${state.mode.displayName} 状态更新: ${state.isUsable}")
}
```

---

## 故障排除

### 问题1：初始化失败

**症状**:
```
E/PermissionModeIntegration: 权限模式系统初始化失败
```

**解决方案**:
1. 检查是否已正确调用`initAndroidPermissionPreferences`
2. 确认DataStore依赖已添加
3. 查看更详细的错误日志

```kotlin
try {
    PermissionModeIntegration.initialize(context)
} catch (e: Exception) {
    Log.e("Init", "初始化失败", e)
    // 回退到旧系统
    initFallbackToOldSystem()
}
```

### 问题2：权限检测返回错误状态

**症状**:
- 明明已Root，但检测显示未Root
- Shizuku已启动，但检测显示不可用

**解决方案**:
```kotlin
// 强制刷新检测缓存
lifecycleScope.launch {
    val modeManager = PermissionModeIntegration.getModeManager()
    modeManager.checkAllModes(forceRefresh = true)
}

// 清除单个管理器缓存
RootManager.clearCache()
ShizukuManager.clearError()
```

### 问题3：执行器不可用

**症状**:
```
E/ShellExecutor: 执行器不可用，执行命令失败
```

**解决方案**:
```kotlin
fun safeExecute(context: Context, command: String): CommandResult {
    return runBlocking {
        try {
            val factory = PermissionModeExecutorFactory.getInstance(context)
            // 尝试获取执行器，会自动降级
            val executor = factory.tryGetExecutor()
            executor.executeCommand(command, ShellIdentity.CURRENT)
        } catch (e: Exception) {
            // 回退到标准执行器
            AndroidShellExecutor.executeCommand(command)
        }
    }
}
```

### 问题4：UI不显示

**症状**:
- 启动PermissionModeActivity但显示空白
- 组件渲染异常

**解决方案**:
1. 确认Activity已在AndroidManifest中注册
2. 检查是否正确使用了`ApexTheme`
3. 查看Logcat中的Compose渲染错误

### 问题5：性能问题

**症状**:
- 检测速度慢
- 界面卡顿

**解决方案**:
```kotlin
// 使用缓存避免频繁检测
val modeManager = PermissionModeIntegration.getModeManager()

// 只在必要时强制刷新
if (needRefresh) {
    modeManager.checkAllModes(forceRefresh = true)
} else {
    // 使用缓存（默认）
    modeManager.checkAllModes(forceRefresh = false)
}
```

---

## 最佳实践

### 1. 初始化策略

**推荐做法**:
```kotlin
// 在Application中初始化
class YourApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // 立即初始化核心组件
        initCoreComponents()
        
        // 延迟初始化权限模式系统（可选，用于加速启动）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val executor = ContextCompat.getMainExecutor(this)
            executor.execute {
                initPermissionModeSystem()
            }
        } else {
            initPermissionModeSystem()
        }
    }
}
```

### 2. 错误处理和降级

**推荐做法**:
```kotlin
class RobustExecutor(private val context: Context) {
    
    suspend fun executeCommandSafe(command: String): CommandResult {
        return try {
            // 尝试使用新系统
            executeWithNewSystem(command)
        } catch (e: Exception) {
            AppLogger.w("Executor", "新系统执行失败，回退到旧系统", e)
            // 回退到旧系统
            executeWithOldSystem(command)
        }
    }
    
    private suspend fun executeWithNewSystem(command: String): CommandResult {
        val factory = PermissionModeExecutorFactory.getInstance(context)
        val executor = factory.getBestAvailableExecutor()
        return executor.executeCommand(command, ShellIdentity.CURRENT)
    }
    
    private fun executeWithOldSystem(command: String): CommandResult {
        // ... 旧系统代码
    }
}
```

### 3. 配置管理

**推荐做法**:
```kotlin
fun manageConfiguration(context: Context) {
    // 设置合理的默认值
    setDefaultConfiguration(context)
    
    // 定期备份配置
    scheduleConfigBackup(context)
    
    // 监听配置变化
    observeConfigChanges(context)
}

suspend fun scheduleConfigBackup(context: Context) {
    val backupManager = PermissionModeIntegration.getBackupManager()
    
    // 每周备份一次
    // 或者在每次配置更改后备份
    if (!backupManager.hasBackup()) {
        backupManager.backupConfig()
    }
}
```

### 4. 用户体验优化

**推荐做法**:
```kotlin
@Composable
fun OptimizedPermissionModeUI() {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsState()
    
    // 显示加载状态
    if (isLoading) {
        LoadingIndicator()
        return
    }
    
    // 显示主要内容
    PermissionModeScreen()
}

// 在ViewModel中
class PermissionViewModel : ViewModel() {
    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 加载数据
            } finally {
                _isLoading.value = false
            }
        }
    }
}
```

### 5. 性能优化

**推荐做法**:
```kotlin
fun optimizePerformance(context: Context) {
    // 1. 合理使用缓存
    val modeManager = PermissionModeIntegration.getModeManager()
    
    // 2. 预加载常用执行器
    lifecycleScope.launch {
        val factory = PermissionModeExecutorFactory.getInstance(context)
        factory.preloadExecutors(
            listOf(PermissionMode.STANDARD, PermissionMode.ROOT)
        )
    }
    
    // 3. 使用状态流而非频繁查询
    lifecycleScope.launch {
        modeManager.currentMode.collect { mode ->
            updateUIFromState(mode)
        }
    }
}
```

### 6. 测试策略

```kotlin
class PermissionModeIntegrationTest {
    
    @Test
    fun testInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        
        // 测试初始化
        PermissionModeIntegration.initialize(context)
        assertTrue(PermissionModeIntegration.isInitialized())
    }
    
    @Test
    fun testModeDetection() {
        val modeManager = PermissionModeIntegration.getModeManager()
        
        // 测试检测
        runBlocking {
            modeManager.checkAllModes(forceRefresh = true)
            val states = modeManager.modeStates.value
            assertTrue(states.isNotEmpty())
        }
    }
}
```

---

## 迁移从旧版本

### 如果您使用旧的AndroidPermissionPreferences

新系统完全兼容旧的API，您可以无缝过渡：

```kotlin
// 旧代码仍然可以工作
val oldPreferences = androidPermissionPreferences

// 但建议逐步迁移到新的API
val newPreferences = enhancedPermissionPreferences
```

### 如果您有自定义的权限管理代码

```kotlin
// 逐步替换
fun migratePermissionCode(context: Context) {
    // 1. 获取新的管理器
    val modeManager = PermissionModeIntegration.getModeManager()
    
    // 2. 逐步替换调用
    // 旧：
    // checkRoot()
    
    // 新：
    modeManager.checkRoot()
    
    // 3. 使用新的API
    val bestMode = modeManager.getBestAvailableMode()
}
```

---

## 总结

完成以上步骤后，您的应用将拥有：

✅ 完整的权限模式管理系统
✅ 智能的Root/Shizuku检测
✅ 现代化的液态玻璃UI
✅ 配置备份和恢复功能
✅ 完整的向后兼容性
✅ 性能优化和缓存机制

如需进一步的帮助，请参考：
- [API文档](./API_Documentation.md) - 完整的API参考
- [使用示例](./PermissionModeExamples.kt) - 详细的使用示例
- [重构总结](./REFACTOR_SUMMARY.md) - 本次重构的详细说明
