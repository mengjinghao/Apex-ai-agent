# Rage Mode APK — 完整能力清单（增强版）

> 修复 1 个 BUG + 暴露 95% BurstMode/BurstKernel 能力 + 新增事件桥接 + 30+ 新方法

---

## 0. 修复的问题

### BUG #1: loadSkill/unloadSkill Bridge 路由缺失
**原代码**：`RageBridgeImpl` 注释声称支持 `rage/loadSkill` / `rage/unloadSkill`，但 `when` 块没有这两个分支 → 调用方收到 `unknown method` 错误。
**修复**：现在两个方法都有路由分支。

### BUG #2: stopSession 假停止
**原代码**：`stopSession` 仅从 `sessions` map 移除，不取消任何底层任务。
**修复**：现在调 `asyncTasks.remove(taskId)?.cancel()` + `burstMode.taskQueue.cancel(taskId)` + 发布事件。

### BUG #3: executeTask 进度是 fake
**原代码**：`executeTask` 硬编码 0.1/0.3/1.0 三个进度点。
**修复**：现在通过 `RageEventBridge` 注册真实进度回调，订阅 `BurstModeListener.onTaskProgress`。

### BUG #4: 断点续传只读不写
**原代码**：只有 `listCheckpoints()`，没有 save/load/resume/delete/clear。
**修复**：现在暴露完整 8 个断点方法 + 业务级 `resumeFromCheckpoint`。

---

## 1. 4 种执行模式（P0 增强）

### 单任务执行
```kotlin
val sessionId = ApexClient.rage.startSession("分析代码", "reasoning.react").getOrNull()
val result = ApexClient.rage.executeTask(sessionId).getOrNull()
```

### 批量并发执行
```kotlin
val results = ApexClient.rage.executeBatch(
    taskDescriptions = listOf("任务1", "任务2", "任务3"),
    skillId = "reasoning.react"
).getOrNull()
```

### DAG 依赖执行（分层并行）
```kotlin
val results = ApexClient.rage.executeWithDependencyGraph(
    tasks = listOf(
        "task1" to "下载数据",
        "task2" to "解析数据",
        "task3" to "生成报告"
    ),
    dependencies = listOf(
        "task2" to "task1",  // task2 依赖 task1
        "task3" to "task2"   // task3 依赖 task2
    ),
    strategy = "SKIP_ON_FAILURE"  // 或 CONTINUE_ON_FAILURE / ABORT_ON_FAILURE
).getOrNull()
```

### 链式管道执行
```kotlin
val result = ApexClient.rage.executeWithChain(
    initialTask = "处理用户请求",
    steps = listOf(
        "理解意图" to "分析用户输入",
        "检索信息" to "搜索相关知识",
        "生成回答" to "组织最终回答"
    )
).getOrNull()
```

### 异步执行（可取消/超时）
```kotlin
val taskId = ApexClient.rage.executeAsync("长时间任务", "extreme_reasoning").getOrNull()
// 等待完成
val result = ApexClient.rage.awaitAsyncTask(taskId, timeoutMs = 120_000).getOrNull()
// 或取消
ApexClient.rage.cancelAsyncTask(taskId)
```

---

## 2. 任务队列管理（P1 增强）

```kotlin
// 入队（带优先级）
val taskId = ApexClient.rage.enqueueTask(
    taskDescription = "低优先级任务",
    priority = "LOW"  // LOWEST/LOW/NORMAL/HIGH/HIGHEST/URGENT
).getOrNull()

// 队列操作
ApexClient.rage.peekQueue()           // 查看队首
ApexClient.rage.pendingTaskCount()    // 待处理数
ApexClient.rage.cancelQueuedTask(taskId)
ApexClient.rage.clearQueue()

// 队列快照
val snap = ApexClient.rage.getQueueSnapshot().getOrNull()
// {pendingCount: 5, completedCount: 12, failedCount: 2, cancelledCount: 1}
```

---

## 3. 完整断点续传（P0 增强）

```kotlin
// 保存断点
ApexClient.rage.saveCheckpoint(
    taskId = "task-1",
    completedSteps = listOf("step1", "step2"),
    totalSteps = 5,
    intermediateResult = "部分结果"
)

// 加载断点
val checkpoint = ApexClient.rage.loadCheckpoint("task-1").getOrNull()
// {taskId: "task-1", completedSteps: 2, totalSteps: 5, progress: 0.4, isComplete: false}

// 从断点恢复执行
val result = ApexClient.rage.resumeFromCheckpoint("task-1").getOrNull()

// 查询
ApexClient.rage.canResume("task-1")           // 是否可恢复
ApexClient.rage.getResumePoint("task-1")      // 下一步步骤名
ApexClient.rage.listIncompleteTasks()         // 未完成任务列表

// 管理
ApexClient.rage.deleteCheckpoint("task-1")
ApexClient.rage.clearCheckpoints()
```

---

## 4. 事件流（P0 增强 — RageEventBridge）

**14 种 BurstModeEvent 桥接到 SuiteEventBus**：

```kotlin
// 订阅狂暴模式事件（任意 APK）
SuiteEventBus.events
    .filter { it.type.startsWith("burst.") }
    .onEach { event ->
        when (event.type) {
            "burst.task_progress" -> {
                val taskId = event.payload["taskId"] as String
                val progress = event.payload["progress"] as Float
                updateProgressBar(taskId, progress)
            }
            "burst.task_succeeded" -> showSuccess(event.payload["taskId"])
            "burst.task_failed" -> showError(event.payload["error"])
            "burst.metrics_updated" -> updateMetrics(event.payload)
            "burst.state_changed" -> updateState(event.payload["to"])
        }
    }
    .launchIn(scope)
```

**事件类型**：
- `burst.state_changed` — 内核状态变化
- `burst.task_enqueued` — 任务入队
- `burst.task_started` — 任务开始
- `burst.task_progress` — 任务进度（含 progress: Float）
- `burst.task_succeeded` — 任务成功
- `burst.task_failed` — 任务失败
- `burst.task_cancelled` — 任务取消
- `burst.config_updated` — 配置更新
- `burst.preset_switched` — 预设切换
- `burst.skill_registered` — 技能注册
- `burst.skill_unregistered` — 技能注销
- `burst.metrics_updated` — 指标更新
- `burst.health_checked` — 健康检查
- `burst.shutdown` — 内核关闭

---

## 5. 指标与状态（P1 增强）

```kotlin
// 一次性快照
val metrics = ApexClient.rage.getMetrics().getOrNull()
// {totalTasks: 50, successfulTasks: 45, failedTasks: 3, successRate: 0.94, ...}

// 等待下一次指标更新
val next = ApexClient.rage.observeNextMetrics().getOrNull()

// 重置指标
ApexClient.rage.resetMetrics()

// 内核状态
val state = ApexClient.rage.getKernelState().getOrNull()
// "RUNNING" / "PAUSED" / "STOPPED" / "STARTING" / "ERROR"

// 健康检查
val health = ApexClient.rage.getHealthStatus().getOrNull()
// {healthy: true, usedMemoryMb: 256, currentConcurrency: 4, maxConcurrency: 8, shouldDegrade: false}
```

---

## 6. 技能管理（P2 增强 — 多维查询）

```kotlin
// 列出所有 31 个内置技能
val skills = ApexClient.rage.listSkills().getOrNull()

// 按标签查询
val reasoningSkills = ApexClient.rage.getSkillsByTag("reasoning").getOrNull()

// 按能力查询
val parallelSkills = ApexClient.rage.getSkillsByCapability("parallel_racing").getOrNull()

// 技能数量
val count = ApexClient.rage.getSkillCount().getOrNull()  // 31

// 是否已加载
val loaded = ApexClient.rage.isSkillLoaded("reasoning.react").getOrNull()

// 卸载技能
ApexClient.rage.unloadSkill("reasoning.react")
```

---

## 7. 配置管理（P2 增强 — 细粒度热更新）

```kotlin
// 切换预设
ApexClient.rage.switchPreset("PERFORMANCE")

// 动态更新配置（不切预设，改单个参数）
ApexClient.rage.updateConfig(
    maxConcurrency = 16,
    defaultTimeoutMs = 300_000,
    enableAdaptiveOptimization = true,
    memoryBudgetMb = 512
)

// 获取当前配置
val config = ApexClient.rage.getCurrentConfig().getOrNull()
// {maxConcurrency: 16, defaultTimeoutMs: 300000, preset: "BALANCED", ...}
```

---

## 8. 基础设施管理（P2 增强）

```kotlin
// 结果缓存
ApexClient.rage.clearResultCache()  // 清空
ApexClient.rage.clearResultCache(prefix = "task-123")  // 按前缀清
val cacheStats = ApexClient.rage.getResultCacheStats().getOrNull()
// {size: 25, hitCount: 80, missCount: 20, hitRate: 0.8}

// 技能选择策略
ApexClient.rage.setSkillSelectionStrategy("priority")
// 支持：type_matching / keyword_matching / priority / complexity_based
```

---

## 9. AR/VR 可视化

```kotlin
ApexClient.rage.enableSpatialVisualization()
// 启用 AR/VR 空间可视化（ARCore/OpenXR 不可用则静默跳过）
```

---

## 10. 完整 API 速查（Apex 独有增强）

### 4 种执行模式（6 个方法）
```kotlin
executeBatch / executeWithDependencyGraph / executeWithChain
executeAsync / awaitAsyncTask / cancelAsyncTask
```

### 任务队列（6 个方法）
```kotlin
enqueueTask / cancelQueuedTask / peekQueue
pendingTaskCount / clearQueue / getQueueSnapshot
```

### 断点续传（8 个方法）
```kotlin
saveCheckpoint / loadCheckpoint / resumeFromCheckpoint
listIncompleteTasks / canResume / getResumePoint
deleteCheckpoint / clearCheckpoints
```

### 技能管理（5 个方法）
```kotlin
getSkillsByTag / getSkillsByCapability
unloadSkill / getSkillCount / isSkillLoaded
```

### 配置管理（2 个方法）
```kotlin
updateConfig / getCurrentConfig
```

### 指标与状态（4 个方法）
```kotlin
observeNextMetrics / resetMetrics / getKernelState / getHealthStatus
```

### 基础设施（3 个方法）
```kotlin
clearResultCache / getResultCacheStats / setSkillSelectionStrategy
```

### AR/VR（1 个方法）
```kotlin
enableSpatialVisualization
```

**总计 35 个 Apex 独有增强方法**（原 14 个 → 现 49 个）+ 修复 4 个 BUG。

---

## 11. 31 个内置技能速查

### 推理类（8 个）
- `reasoning.react` — ReAct（默认）
- `reasoning.chain-of-thought` — 思维链
- `reasoning.tree-of-thoughts` — 思维树
- `reasoning.self-consistency` — 自一致性
- `reasoning.reflexion` — 反思
- `reasoning.multi-hop` — 多跳推理
- `extreme_reasoning` — 极限推理（红蓝对抗）
- `thinking_agent` — 思考 Agent

### 执行类（7 个）
- `berserk_execution` — 狂暴执行
- `adaptive_execution` — 自适应执行
- `tool_racing` — 工具竞速
- `tool_fusion` — 工具熔断
- `brute_force_ui` — 暴力 UI
- `red_blue_adversarial` — 红蓝对抗
- `tool_recommendation` — 工具推荐

### 基础设施（7 个）
- `api_client` / `memory_storage` / `file_search`
- `infinite_context` / `stream_processor`
- `security_manager` / `execution_logger`

### 知识检索（3 个）
- `knowledge_graph` / `rag_pipeline` / `template_manager`

### 任务管理（4 个）
- `task_scheduler` / `task_graph` / `recovery` / `recovery_chain`

### 自修正（2 个）
- `self_correction` / `code_quality_analyzer`
