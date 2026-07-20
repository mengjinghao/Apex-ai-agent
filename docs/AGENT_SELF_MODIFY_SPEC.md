# Agent 自改源码架构规范 (SPEC v1.0)

> 本规范定义 Apex AI Agent **自我修改源代码** 的完整架构。
> 实施必须严格遵循本 spec。任何偏离需在此文档中记录并更新版本号。

---

## 1. 目标与约束

### 1.1 目标
让软件内的 Agent 能够:
1. **读取**自身源代码(全部模块:Kotlin/C++/XML/Gradle)
2. **修改**源代码(编辑文件、新增文件、删除文件)
3. **编译验证**修改是否破坏构建
4. **热重载**运行中的代码(无需重启 App)
5. **回滚**失败的修改(安全网)
6. **索引**代码库(快速定位符号、引用、定义)

### 1.2 硬约束(不可违反)
| 约束 | 理由 |
|------|------|
| **无混淆** | Agent 必须能读懂自己的代码(R8 已关闭) |
| **沙盒** | Agent 只能改 `workspace/` 目录,不能改系统文件 |
| **编译门控** | 任何修改必须通过编译才能应用 |
| **原子性** | 修改要么全部应用,要么全部回滚 |
| **审计** | 每次修改记录:谁、何时、改了什么、结果 |
| **人可审核** | 高风险修改需用户确认 |

### 1.3 非目标
- 不支持修改其他 App 的代码
- 不支持运行时修改 C++ 已编译的 .so(需重新编译)
- 不支持绕过 Android 安全模型(无 root 提权)

---

## 2. 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  Agent (LLM 决策层)                                          │
│  ├── 代码索引查询 (CodeIndex)                                │
│  ├── 修改规划 (ModificationPlan)                             │
│  └── 提交修改 (SelfModifyService)                            │
├──────────────────────────────────────────────────────────────┤
│  Self-Modify Service Layer                                   │
│  ├── WorkspaceManager    — 沙盒目录管理 + 备份               │
│  ├── FileWatcher         — 文件变更监听 (inotify/FSEvents)   │
│  ├── CodeIndexer         — 符号/引用索引 (ctags-like)        │
│  ├── CompileGate         — 编译验证 (gradle compile)         │
│  ├── HotReloader         — 热重载 (DexClassLoader / Compose) │
│  ├── RollbackManager     — 回滚 (git-based)                 │
│  └── AuditLog            — 审计日志 (tamper-evident)         │
├──────────────────────────────────────────────────────────────┤
│  Storage Layer                                               │
│  ├── workspace/          — Agent 可改的源码副本              │
│  ├── .snapshots/         — git 快照(每次修改前)             │
│  ├── .index/             — 代码索引(SQLite/内存)           │
│  └── .audit/             — 审计日志(append-only)           │
└──────────────────────────────────────────────────────────────┘
```

---

## 3. 模块定义

### 3.1 新增 Gradle 模块: `:self-modify`

```
self-modify/
├── build.gradle.kts
├── src/main/kotlin/com/apex/selfmodify/
│   ├── SelfModifyService.kt       # 对外门面
│   ├── workspace/
│   │   ├── WorkspaceManager.kt    # 沙盒目录 + 快照
│   │   └── WorkspaceModels.kt     # 数据类
│   ├── watch/
│   │   ├── FileWatcher.kt         # 文件监听接口
│   │   └── AndroidFileWatcher.kt  # Android 实现 (FileObserver)
│   ├── index/
│   │   ├── CodeIndexer.kt         # 索引构建
│   │   ├── CodeIndex.kt           # 索引查询
│   │   └── SymbolParser.kt        # Kotlin/C++ 符号解析
│   ├── compile/
│   │   ├── CompileGate.kt         # 编译验证
│   │   └── CompileResult.kt
│   ├── reload/
│   │   ├── HotReloader.kt         # 热重载接口
│   │   ├── DexHotReloader.kt      # DexClassLoader 实现
│   │   └── ComposeReloader.kt     # Compose 重组触发
│   ├── rollback/
│   │   └── RollbackManager.kt     # git-based 回滚
│   ├── audit/
│   │   └── AuditLog.kt            # 审计日志
│   └── plan/
│       ├── ModificationPlan.kt    # 修改计划
│       └── PlanExecutor.kt        # 计划执行器
└── src/main/AndroidManifest.xml
```

### 3.2 模块依赖
```
:self-modify
 ├── :sdk:common-core       (日志/Trace)
 ├── :sdk:process-bridge    (ApexClient 跨模块调用)
 ├── :lib:working-files     (已有的文件监听/diff 能力复用)
 └── :domain                (模型)

:app
 └── implementation(project(":self-modify"))
```

### 3.3 与现有模块的关系
| 现有模块 | 复用方式 |
|---------|---------|
| `:lib:working-files` | 复用 `WorkingFilesWatcher`(文件监听)、`DiffComputer`(diff)、`TimeMachine`(快照)、`BranchManager`(虚拟分支) |
| `:lib:engine` | 复用 `ToolCatalog`(自改工具注册) |
| `:sdk:process-bridge` | Agent 通过 `ApexClient.selfModify.*` 调用(未来拆 APK 时跨进程) |
| `:ai-terminal` | Agent 的执行环境(编译命令走终端) |

---

## 4. 核心流程

### 4.1 Agent 自改源码完整流程

```
Agent 收到"优化 X 模块性能"指令
    │
    ▼
1. 查询代码索引
   CodeIndex.findSymbol("XModule") → 返回文件:行 + 引用列表
   CodeIndex.findReferences("XModule.execute") → 所有调用点
    │
    ▼
2. 读取相关源码
   WorkspaceManager.read(file) → 源码文本
    │
    ▼
3. 规划修改
   ModificationPlan(
     changes = [
       FileEdit(path, oldText, newText),
       FileCreate(path, content),
       FileDelete(path)
     ],
     reason = "优化: 用 StringBuilder 替代字符串拼接",
     riskLevel = MEDIUM,
     requiresUserConfirm = false
   )
    │
    ▼
4. 创建快照(原子性保证)
   RollbackManager.snapshot("before-optimization") → git commit
    │
    ▼
5. 应用修改到 workspace/
   PlanExecutor.apply(plan) → 写文件
   FileWatcher 推送变更事件
    │
    ▼
6. 编译验证
   CompileGate.compile() → ./gradlew :module:compileDebugKotlin
   ├─ 成功 → 继续
   └─ 失败 → RollbackManager.rollback() → 恢复快照 → 报错
    │
    ▼
7. 热重载(如果可能)
   HotReloader.reload(changedFiles)
   ├─ Kotlin class → DexClassLoader 加载新 dex
   └─ Compose → 触发重组(state变更)
    │
    ▼
8. 审计记录
   AuditLog.record(plan, compileResult, reloadResult)
    │
    ▼
9. 更新索引
   CodeIndexer.reindex(changedFiles)
    │
    ▼
完成,Agent 可继续迭代
```

### 4.2 编译验证流程(详细)

```
CompileGate.compile(module: String): CompileResult
    │
    ├── 1. 构建命令: ./gradlew :{module}:compileDebugKotlin --no-daemon --offline
    │      (offline 避免网络依赖;no-daemon 避免状态泄露)
    │
    ├── 2. 超时: 120s(可配置)
    │
    ├── 3. 捕获 stdout/stderr
    │
    ├── 4. 解析结果:
    │      ├─ exitCode == 0 → CompileResult.Success
    │      ├─ exitCode != 0 → CompileResult.Failure(errors: List<CompileError>)
    │      │   CompileError(file, line, message)
    │      └─ timeout → CompileResult.Timeout
    │
    └── 5. 返回结果给 PlanExecutor
```

**关键约束**:
- 编译在**独立进程**运行(避免 Gradle daemon 污染主进程)
- 编译用**沙盒 Gradle**(指向 workspace/ 的副本,不碰真实项目)
- 编译结果**不可缓存**(每次修改都重新编译)

### 4.3 热重载策略

| 代码类型 | 热重载方式 | 限制 |
|---------|-----------|------|
| **Kotlin 类(无新签名)** | DexClassLoader 加载新 dex,替换实例 | 已运行的对象不替换;需重启相关组件 |
| **Compose UI** | 触发 state 变更 → 重组 | 函数签名不能变 |
| **C++ (.so)** | ❌ 不支持热重载 | 需重新编译 + 重启进程 |
| **资源(XML)** | ResourceKey 重载(有限) | 已缓存的资源不更新 |
| **Gradle 配置** | ❌ 不支持 | 需重新同步 |

**HotReloader 接口**:
```kotlin
interface HotReloader {
    suspend fun reload(files: List<FileChange>): ReloadResult
    fun canHotReload(file: File): Boolean
}

sealed class ReloadResult {
    data class Success(val reloadedClasses: List<String>) : ReloadResult()
    data class Partial(val reloaded: List<String>, val failed: List<String>, val reason: String) : ReloadResult()
    data class Failure(val reason: String) : ReloadResult()
}
```

### 4.4 回滚机制

**基于 git 的快照**:
- workspace/ 目录初始化为 git repo
- 每次 `PlanExecutor.apply` 前,`git add -A && git commit -m "snapshot: {planId}"`
- 回滚: `git checkout HEAD~1 -- .` (恢复到上一个快照)
- 快照保留 7 天,之后 GC

**RollbackManager 接口**:
```kotlin
class RollbackManager(private val workspaceDir: File) {
    fun snapshot(tag: String): String  // returns commit SHA
    suspend fun rollback(toCommit: String): Boolean
    suspend fun rollbackLast(): Boolean
    fun listSnapshots(): List<Snapshot>
    fun cleanupOlderThan(maxAge: Duration)
}
```

---

## 5. 代码索引

### 5.1 索引内容
| 索引类型 | 存储 | 查询 |
|---------|------|------|
| **符号定义**(class/fun/val) | SQLite | `findSymbol(name) → List<SymbolLocation>` |
| **引用**(谁调用了 X) | SQLite | `findReferences(symbol) → List<ReferenceLocation>` |
| **文件树** | 内存 | `listFiles(pattern) → List<Path>` |
| **导入图** | 内存 | `getImportGraph(file) → DependencyGraph` |

### 5.2 符号解析器
```kotlin
interface SymbolParser {
    fun parse(file: File): List<Symbol>
    val supportedExtensions: Set<String>
}

data class Symbol(
    val name: String,
    val kind: SymbolKind,  // CLASS, FUNCTION, PROPERTY, ENUM
    val file: String,
    val line: Int,
    val column: Int,
    val signature: String?,
    val documentation: String?
)
```

**实现**:
- `KotlinSymbolParser`: 正则 + 简单词法(不依赖 Kotlin Compiler,轻量)
- `CppSymbolParser`: ctags 风格(类/函数/宏)
- `XmlSymbolParser`: 元素 + 属性(AndroidManifest/布局)

### 5.3 增量索引
- 首次:全量扫描 workspace/ → 构建索引(~5s for 1000 files)
- 后续:FileWatcher 推送变更 → 只重索引变更文件
- 索引存储: `workspace/.index/codeindex.db` (SQLite)

---

## 6. 文件监听

### 6.1 FileWatcher 接口
```kotlin
interface FileWatcher {
    fun start(watchDir: File, scope: CoroutineScope)
    fun stop()
    val events: SharedFlow<FileChangeEvent>
}

sealed class FileChangeEvent {
    data class Created(val path: String) : FileChangeEvent()
    data class Modified(val path: String) : FileChangeEvent()
    data class Deleted(val path: String) : FileChangeEvent()
    data class Moved(val from: String, val to: String) : FileChangeEvent()
}
```

### 6.2 Android 实现
- 使用 `android.os.FileObserver`(基于 inotify)
- 递归监听:每个子目录一个 FileObserver(受 ~1000 watcher 限制)
- 大目录树(>1000)降级为轮询(5s 扫描 mtime)

### 6.3 与现有 `:lib:working-files` 的关系
- `:lib:working-files` 已有 `WorkingFilesWatcher` + `WorkingFolderManager`
- **复用**: `AndroidFileWatcher` 包装 `WorkingFilesWatcher`,适配 `FileChangeEvent` 接口
- 不重复造轮子

---

## 7. 安全模型

### 7.1 沙盒边界
```
设备文件系统
├── /system/...          ❌ Agent 不可访问
├── /data/other_apps/... ❌ Agent 不可访问
├── /sdcard/...          ⚠️ 只读(可读不可写)
└── app_private/
    ├── files/           ❌ Agent 不可写(运行时数据)
    └── workspace/       ✅ Agent 可读写(沙盒)
        ├── src/         # 源码副本
        ├── .snapshots/  # git 快照
        ├── .index/      # 代码索引
        └── .audit/      # 审计日志
```

### 7.2 修改风险分级
| 级别 | 示例 | 要求 |
|------|------|------|
| **LOW** | 改函数体实现、改常量值 | 自动应用 |
| **MEDIUM** | 新增函数、改私有方法签名 | 自动应用 + 审计 |
| **HIGH** | 改公开 API、改 Gradle 依赖、改 AndroidManifest | **需用户确认** |
| **CRITICAL** | 删除文件、改安全相关代码(Crypto/Auth/RBAC) | **需用户确认 + 二次验证** |

### 7.3 审计日志(tamper-evident)
```kotlin
data class AuditEntry(
    val timestamp: Long,
    val planId: String,
    val agentId: String,
    val filesChanged: List<String>,
    val compileResult: CompileResult,
    val reloadResult: ReloadResult?,
    val previousHash: String,  // 前一条的哈希(链式)
    val thisHash: String       // 本条内容的哈希
)
```
- 存储为 append-only 文件(`workspace/.audit/audit.log`)
- 每条记录包含前一条的 SHA-256(链式哈希,防篡改)
- 定期签名(可选:用设备密钥)

### 7.4 编译门控强制
- **任何文件写入 workspace/src/ 后,必须通过 CompileGate 才能被 HotReloader 加载**
- CompileGate 失败 → RollbackManager 自动回滚
- 无例外

---

## 8. 对外 API(ApexClient 集成)

### 8.1 SelfModifyService 门面
```kotlin
class SelfModifyService(
    private val workspace: WorkspaceManager,
    private val index: CodeIndex,
    private val compiler: CompileGate,
    private val reloader: HotReloader,
    private val rollback: RollbackManager,
    private val audit: AuditLog,
    private val scope: CoroutineScope
) {
    // 读
    suspend fun readFile(path: String): String
    suspend fun listFiles(pattern: String): List<String>
    suspend fun findSymbol(name: String): List<SymbolLocation>
    suspend fun findReferences(symbol: String): List<ReferenceLocation>

    // 改
    suspend fun plan(changes: List<FileChange>, reason: String): ModificationPlan
    suspend fun apply(plan: ModificationPlan, requireUserConfirm: Boolean): ApplyResult

    // 管理
    suspend fun rollback(toCommit: String?): Boolean
    fun listSnapshots(): List<Snapshot>
    suspend fun reindex(): Unit
}
```

### 8.2 ApexClient 集成(未来跨 APK)
```kotlin
// :sdk:process-bridge 的 ApexClient 增加:
object ApexClient {
    val selfModify: SelfModifyClient
}

class SelfModifyClient {
    suspend fun readFile(path: String): BridgeResult<String>
    suspend fun findSymbol(name: String): BridgeResult<String>  // JSON
    suspend fun applyPlan(planJson: String): BridgeResult<String>
    // ... mirrors SelfModifyService
}
```

### 8.3 Agent 工具注册
注册为 Agent 可调用的工具(通过 `:lib:engine` 的 ToolCatalog):
```kotlin
// BuiltInTools 增加:
listOf(
    ReadSourceTool(selfModify),      // 读源码
    SearchCodeTool(selfModify),      // 搜符号
    ModifyCodeTool(selfModify),      // 改代码(触发完整流程)
    CompileCheckTool(selfModify),    // 仅编译验证
    RollbackTool(selfModify)         // 回滚
)
```

---

## 9. 实施计划

### Phase 1: 基础设施(MVP)
- [ ] `:self-modify` 模块骨架(build.gradle.kts + AndroidManifest)
- [ ] `WorkspaceManager`:沙盒目录初始化 + 源码同步
- [ ] `FileWatcher`:基于 FileObserver
- [ ] `AuditLog`:append-only + 链式哈希
- [ ] `SelfModifyService` 门面

### Phase 2: 索引 + 编译
- [ ] `KotlinSymbolParser`:正则解析符号
- [ ] `CppSymbolParser`:ctags 风格
- [ ] `CodeIndexer` + SQLite 存储
- [ ] `CompileGate`:调用 gradle 编译

### Phase 3: 热重载 + 回滚
- [ ] `RollbackManager`:git 快照
- [ ] `DexHotReloader`:DexClassLoader 加载
- [ ] `ComposeReloader`:state 触发重组
- [ ] `PlanExecutor`:原子化应用 + 编译门控

### Phase 4: Agent 集成
- [ ] 工具注册(ReadSource/SearchCode/ModifyCode/CompileCheck/Rollback)
- [ ] `ApexClient.selfModify` 客户端
- [ ] 风险分级 + 用户确认 UI
- [ ] 审计日志查看页

---

## 10. 验收标准

| 标准 | 验证方法 |
|------|---------|
| Agent 能读取任意源码文件 | `readFile("app/src/.../MainActivity.kt")` 返回内容 |
| Agent 能搜索符号 | `findSymbol("RageEngine")` 返回位置 |
| 修改后自动编译验证 | 改文件 → CompileGate 触发 → 成功才应用 |
| 编译失败自动回滚 | 故意引入语法错误 → 快照恢复 |
| 热重载 Kotlin 类 | 改函数体 → DexClassLoader 加载 → 新逻辑生效 |
| 审计日志不可篡改 | 修改日志中间一条 → 链式哈希校验失败 |
| 沙盒边界 | 尝试写 /system/ → 拒绝 |
| 高风险需确认 | 改 AndroidManifest → 弹确认框 |

---

## 11. 风险与缓解

| 风险 | 缓解 |
|------|------|
| Agent 改坏代码导致崩溃 | 编译门控 + 自动回滚 + 快照 |
| 热重载内存泄露 | DexClassLoader 卸载旧 dex + 弱引用 |
| 索引过期 | FileWatcher 增量更新 + 定期全量重建 |
| Agent 恶意修改 | 沙盒 + 风险分级 + 用户确认 + 审计 |
| 编译耗时长 | offline + 增量编译 + 模块级编译(非全量) |
| 并发修改冲突 | 单 Agent 串行修改(互斥锁) |

---

## 12. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0 | 2025-01 | 初版 |

