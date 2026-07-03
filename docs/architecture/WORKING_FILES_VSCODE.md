# Working Files APK — VSCode 式代码浏览器 + Agent 执行流程

> 在 Android 上提供类 VSCode 的代码查看体验 + Agent 执行可视化 + 文件变更回退

---

## 0. 设计参考的顶级开源项目

| 项目 | 借鉴点 |
|------|--------|
| **VSCode Timeline** | 文件本地历史时间线，自动 + 手动快照 |
| **Cline (VSCode AI 插件)** | 每次工具调用前快照，checkpoint 回退 |
| **Aider** | git commit 跟踪 AI 编辑，`/undo` 回退 |
| **JetBrains Local History** | 差异存储，对比任意两版本 |
| **GitHub PR Diff** | unified diff format，hunk + context |
| **Cursor IDE** | checkpoint 系统，AI 操作前自动快照 |

---

## 1. 核心能力

### 1.1 VSCode 式代码浏览
- 📁 **文件树**：左侧资源管理器，目录可展开/折叠，不同语言图标不同颜色
- 📝 **代码查看**：行号 + 等宽字体 + 语法高亮（关键字/字符串/注释/数字）
- 🎨 **暗色主题**：基于 VSCode Dark+ 配色

### 1.2 文件快照与时间线
- 📸 **自动快照**：每次 Agent 写入前自动建立"变更前"+"变更后"两个快照
- ⏰ **时间线**：VSCode Timeline 风格，按时间倒序展示文件所有版本
- 🏷️ **来源标识**：🤖 Agent / 👤 用户 / 🌐 外部 / ✏️ 手动
- 📊 **元数据**：时间、行数、字符数、内容 hash

### 1.3 Diff 视图（行级变更）
- ➕➖ **行级 diff**：基于 Myers 算法（同 git diff）
- 🎨 **颜色编码**：绿色新增、红色删除、灰色上下文
- 📊 **统计**：+X -Y、净增减、修改块数
- 📄 **Unified format**：兼容 git diff 输出格式

### 1.4 Agent 执行流程
- 🤖 **会话列表**：所有 Agent 会话，含任务描述/模式/状态
- 📊 **统计卡片**：步骤数 / 文件变更数 / 快照数 / 错误数 / 总时长
- ⏱️ **时间线视图**：垂直时间线，每步含：
  - 类型图标 + 颜色（思考/执行/工具/文件操作/命令/LLM/搜索/网络/...）
  - 标题 + 描述
  - 时间戳 + 持续时长
  - Agent 名称
  - 影响文件数 + 快照数
  - 成功/失败状态 + 错误信息
- 🔍 **步骤 Diff**：点击步骤查看该步产生的文件 diff

### 1.5 文件变更回退
- ⏪ **任意版本回退**：选时间线任一快照点击「回退」按钮
- ✅ **回退安全**：回退会生成新快照记录此次操作（不破坏历史）
- 🤖 **关联 Agent 步骤**：回退操作自动记录到关联的 Agent 会话

---

## 2. 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                  Working Files APK                           │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              WorkingFilesServiceFacade                  │  │
│  │  （套件对外门面，所有方法返回 BridgeResult）              │  │
│  └─────────────┬──────────────────────────────────────────┘  │
│                │                                              │
│                ▼                                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │              CodeEditorFacade                           │  │
│  │  （统一编排文件浏览/快照/diff/Agent流程/回退）           │  │
│  └─────┬───────────┬───────────┬───────────┬──────────────┘  │
│        │           │           │           │                  │
│        ▼           ▼           ▼           ▼                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────┐    │
│  │Snapshot  │ │  Diff    │ │  Agent   │ │   File Tree  │    │
│  │ Storage  │ │ Computer │ │   Flow   │ │   + Preview  │    │
│  │          │ │(Myers)   │ │ Storage  │ │              │    │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────┘    │
│        │           │           │                              │
│        └───────────┴───────────┘                              │
│                    │                                          │
│                    ▼                                          │
│              JSON 文件持久化                                  │
│   <app_data>/apex-code-editor/                                │
│   ├── snapshots/                                              │
│   │   ├── index.json                                          │
│   │   └── snapshots/snap-xxx.json                             │
│   └── flows/                                                  │
│       ├── active.json                                         │
│       └── sessions/session-xxx.json                           │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心数据模型

### FileSnapshot（文件快照）
```kotlin
data class FileSnapshot(
    val id: String,                    // UUID
    val filePath: String,              // 绝对路径
    val relativePath: String,          // 相对工作区
    val timestamp: Long,               // 时间戳
    val content: String,               // 全文内容
    val contentHash: String,           // SHA-256
    val changeType: ChangeType,        // CREATE / MODIFY / DELETE
    val source: ChangeSource,          // AGENT / USER / EXTERNAL / MANUAL
    val agentId: String? = null,       // Agent ID
    val sessionId: String? = null,     // Agent 会话 ID
    val stepId: String? = null,        // Agent 步骤 ID
    val description: String,           // 人类可读描述
    val lineCount: Int,
    val charCount: Int
)
```

### FileDiff（差异）
```kotlin
data class FileDiff(
    val oldFilePath: String,
    val newFilePath: String,
    val oldHash: String,
    val newHash: String,
    val hunks: List<DiffHunk>,         // 多个差异块
    val summary: DiffSummary           // +X -Y 统计
)

data class DiffHunk(
    val oldStart: Int, val oldEnd: Int,
    val newStart: Int, val newEnd: Int,
    val lines: List<DiffLine>
)

data class DiffLine(
    val type: DiffLineType,            // CONTEXT / ADDED / REMOVED / EMPTY
    val content: String,
    val oldLineNumber: Int?,
    val newLineNumber: Int?
)
```

### AgentFlow（Agent 执行流程）
```kotlin
data class AgentSession(
    val id: String,
    val agentId: String,
    val agentName: String,
    val startTime: Long,
    val endTime: Long,
    val taskDescription: String,
    val mode: AgentMode,               // NORMAL / MULTI_AGENT / BURST
    val stepCount: Int,
    val fileCount: Int,
    val status: AgentSessionStatus,    // RUNNING / COMPLETED / FAILED / CANCELLED / PAUSED
    val finalResult: String?
)

data class AgentStep(
    val id: String,
    val sessionId: String,
    val agentId: String,
    val agentName: String,
    val type: AgentStepType,           // 19 种类型（THOUGHT/ACTION/TOOL_CALL/...）
    val order: Int,                    // 会话中第几步
    val timestamp: Long,
    val durationMs: Long,
    val title: String,
    val description: String,
    val thought: String?,              // Agent 思考内容
    val action: String?,               // 执行动作
    val result: String?,               // 执行结果
    val isSuccess: Boolean,
    val errorMessage: String?,
    val affectedFiles: List<String>,
    val snapshotIds: List<String>
)
```

---

## 4. 业务侧使用方式

### 4.1 Agent 集成（在 Agent 写文件时自动快照）

```kotlin
// 在 Agent 模块中（如 Rage APK / Multi-Agent APK）：

// 1. 启动会话
val session = ApexClient.workingFiles.startAgentSession(
    agentId = "rage-agent-1",
    agentName = "狂暴 Agent",
    taskDescription = "修复 MainActivity.kt 的 NPE",
    mode = "BURST"
).getOrNull() ?: return

// 2. 记录思考步骤
ApexClient.workingFiles.recordAgentStep(
    sessionId = session.sessionId,
    agentId = "rage-agent-1",
    agentName = "狂暴 Agent",
    type = "THOUGHT",
    title = "分析问题",
    description = "用户报告 NPE，可能是空指针未检查"
)

// 3. 记录工具调用
ApexClient.workingFiles.recordAgentStep(
    sessionId = session.sessionId,
    agentId = "rage-agent-1",
    agentName = "狂暴 Agent",
    type = "TOOL_CALL",
    title = "读取文件",
    description = "读取 MainActivity.kt",
    affectedFiles = listOf("/sdcard/proj/MainActivity.kt")
)

// 4. 写入文件（自动快照 + 记录步骤）
ApexClient.workingFiles.writeWithSnapshot(
    filePath = "/sdcard/proj/MainActivity.kt",
    rootPath = "/sdcard/proj",
    content = newContent,
    agentId = "rage-agent-1",
    agentName = "狂暴 Agent",
    sessionId = session.sessionId,
    description = "添加空指针检查"
)

// 5. 结束会话
ApexClient.workingFiles.finishAgentSession(
    sessionId = session.sessionId,
    finalResult = "已修复 NPE，添加了 null 检查",
    status = "COMPLETED"
)
```

### 4.2 UI 集成（在任意 APK 中查看代码/diff/流程）

```kotlin
// 查看文件树
val tree = ApexClient.workingFiles.getFileTree("/sdcard/proj").getOrNull()

// 查看文件快照历史
val snapshots = ApexClient.workingFiles.listSnapshots("/sdcard/proj/MainActivity.kt").getOrNull()

// 查看两个快照的 diff
val diff = ApexClient.workingFiles.diffSnapshots(
    beforeId = snapshots[0].id,
    afterId = snapshots[1].id
).getOrNull()

// 查看某快照与当前文件的 diff
val diff = ApexClient.workingFiles.diffWithCurrent(snapshotId).getOrNull()

// 回退文件
ApexClient.workingFiles.restoreSnapshot(snapshotId)

// 查看 Agent 执行流程
val sessions = ApexClient.workingFiles.listAgentSessions().getOrNull()
val flow = ApexClient.workingFiles.getAgentFlow(sessionId).getOrNull()

// 查看某步骤的 diff
val diff = ApexClient.workingFiles.diffForStep(stepId).getOrNull()
```

---

## 5. UI 组件

### 5.1 FileTreeView（文件树）
- VSCode Explorer 风格
- 目录可展开/折叠
- 文件按语言显示不同图标颜色

### 5.2 CodeEditorView（代码编辑器）
- 行号列（VSCode 灰色）
- 等宽字体
- 行高亮
- 水平 + 垂直滚动

### 5.3 DiffView（差异视图）
- 文件头：旧路径 → 新路径 + 统计
- Hunk header：`@@ -oldStart,oldLen +newStart,newLen @@`
- 行级 diff：绿/红/灰背景
- 双行号列（旧/新）

### 5.4 TimelineView（时间线）
- VSCode Timeline 风格
- 圆点 + 时间线
- 来源图标（🤖👤🌐✏️）
- 回退按钮

### 5.5 AgentFlowView（Agent 流程）
- 会话头：Agent 名 + 模式 + 状态 + 任务
- 统计卡片：步骤/文件/快照/错误/时长
- 垂直时间线：每步含图标/类型/标题/描述/元数据

### 5.6 MainActivity（主入口）
- 三个 Tab：文件浏览 / 时间线 / Agent 流程
- 左右分栏：左侧导航，右侧内容
- 回退确认对话框

---

## 6. 19 种 Agent 步骤类型

| 类型 | 图标 | 说明 | 触发场景 |
|------|------|------|----------|
| THOUGHT | 🧠 | 思考 | Agent 推理过程 |
| ACTION | ▶️ | 执行 | 通用动作 |
| TOOL_CALL | 🔧 | 工具调用 | 调用 file/network/... 工具 |
| FILE_READ | 📂 | 读取文件 | 读文件内容 |
| FILE_WRITE | ✏️ | 写入文件 | 创建新文件 |
| FILE_EDIT | ✏️ | 编辑文件 | 修改已有文件 |
| FILE_DELETE | 🗑️ | 删除文件 | 删除文件 |
| COMMAND | 💻 | 执行命令 | shell 命令 |
| LLM_CALL | ☁️ | LLM 调用 | 调用大模型 |
| SEARCH | 🔍 | 搜索 | 搜索代码/网络 |
| WEB | 🌐 | 网络请求 | HTTP 请求 |
| OBSERVATION | 👁️ | 观察 | ReAct 的 Observation |
| REFLECTION | 🔄 | 反思 | Reflexion 自我反思 |
| DECISION | ✅ | 决策 | 关键决策点 |
| ERROR | ❌ | 错误 | 执行失败 |
| CHECKPOINT | 🔖 | 检查点 | 显式 checkpoint |
| ROLLBACK | ⏪ | 回退 | 文件回退操作 |
| USER_INPUT | 👤 | 用户输入 | 用户交互 |
| CUSTOM | ⭐ | 自定义 | 业务自定义 |

---

## 7. 存储策略

### 7.1 快照存储
- **路径**：`<app_data>/apex-code-editor/snapshots/`
- **索引**：`index.json` 路径 → 快照 ID 列表
- **全文**：每个快照一个 `<id>.json`
- **保留**：默认每文件保留 100 个快照，超限自动删最旧

### 7.2 Agent 流程存储
- **路径**：`<app_data>/apex-code-editor/flows/`
- **会话**：每个会话一个 `sessions/<session-id>.json`，含所有步骤
- **活跃列表**：`active.json` 记录 RUNNING 状态的会话

### 7.3 并发安全
- 快照索引用 `ReentrantReadWriteLock` 保护
- 文件写入用原子写（临时文件 + rename）
- Agent 流程用 `ConcurrentHashMap` + `synchronized`

---

## 8. 性能指标

- 快照存储：~10KB/文件（典型代码文件）
- Diff 计算：1万行代码 < 100ms（Myers 算法）
- 时间线加载：100 个快照 < 50ms
- 内存占用：仅加载 summary，全文按需读取

---

## 9. 与顶级项目的对比

| 特性 | Apex Working Files | Cline | Aider | VSCode Timeline |
|------|---------------------|-------|-------|-----------------|
| 自动快照 | ✅ Agent 写入前 | ✅ 工具调用前 | ❌ (git commit) | ✅ 保存时 |
| 手动快照 | ✅ | ✅ | ❌ | ✅ |
| 行级 diff | ✅ Myers | ✅ | ✅ git | ✅ |
| 任意版本回退 | ✅ | ✅ checkpoint | ✅ /undo | ✅ |
| Agent 流程可视化 | ✅ | ✅ webview | ❌ | ❌ |
| 步骤级 diff | ✅ | ✅ | ❌ | ❌ |
| 跨 APK 共享 | ✅ 套件级 | ❌ VSCode 内 | ❌ CLI | ❌ VSCode 内 |
| Android 原生 | ✅ | ❌ 桌面 | ❌ CLI | ❌ 桌面 |
