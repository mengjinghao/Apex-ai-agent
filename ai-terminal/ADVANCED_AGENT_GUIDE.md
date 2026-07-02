# 高级终端 Agent - 完整使用指南

## 📋 概述

**高级终端 Agent (AdvancedTerminalAgent)** 是一个功能完整的智能执行系统，具备：

✅ 系统状态感知 - 静默探测完整的手机状态  
✅ 多步任务规划 - 将自然语言需求拆解为结构化任务  
✅ 顺序执行引擎 - 严格校验每一步的执行结果  
✅ 自动错误修复 - 分析错误原因并智能重试  
✅ 断点续执行 - 任务中断后从断点恢复  
✅ 后台定时任务 - WorkManager/AlarmManager 集成  
✅ 系统通知 - 任务状态实时推送  

---

## 🏗️ 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                         AdvancedTerminalAgent                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ SystemProbe  │  │ TaskPlanner  │  │    TaskExecutor      │  │
│  │  系统探测    │  │  任务规划    │  │   执行引擎          │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│         │                  │                    │               │
│         └──────────────────┼────────────────────┘               │
│                            │                                    │
│  ┌─────────────────────────┼────────────────────────────────┐  │
│  │  ScheduledTaskManager   │  │       ErrorAnalyzer         │  │
│  │  定时任务管理           │  │  错误分析修复               │  │
│  └─────────────────────────┴────────────────────────────────┘  │
│                              │                                    │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                TaskPersistence                            │  │
│  │                 任务持久化/断点                           │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🚀 快速开始

### 1. 初始化

```kotlin
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import com.ai.assistance.aiterminal.terminal.ai.LLMApiImpl
import com.ai.assistance.aiterminal.terminal.agent.AdvancedTerminalAgent

// 初始化基础组件
val rootTerminalManager = RootTerminalManager()
val llmApi = LLMApiImpl()

// 创建高级 Agent
val agent = AdvancedTerminalAgent(
    context = context,
    rootTerminalManager = rootTerminalManager,
    llmApi = llmApi
)
```

### 2. 执行完整任务（最简单的方式）

```kotlin
// 一行代码完成所有事情
val result = agent.executeFullTask(
    userRequest = "备份 boot 分区，刷入 Magisk 模块，验证兼容性，失败则自动还原",
    useRoot = true
)

// 处理结果
if (result.isSuccess) {
    println("✅ 任务完成！")
} else {
    println("❌ 任务失败: ${result.errorMessage}")
}
```

### 3. 监听执行状态

```kotlin
lifecycleScope.launch {
    agent.agentState.collect { state ->
        when (state) {
            is AgentState.IDLE -> updateUI("就绪")
            is AgentState.PROBING -> updateUI("🔍 探测系统状态...")
            is AgentState.PLANNING -> updateUI("🤔 规划任务...")
            is AgentState.EXECUTING -> updateUI("⚙️ 执行任务...")
            is AgentState.PAUSED -> updateUI("⏸️ 已暂停")
            is AgentState.COMPLETED -> updateUI("✅ 完成！")
            is AgentState.CANCELLED -> updateUI("❌ 已取消")
            is AgentState.ERROR -> updateUI("❌ 错误: ${state.message}")
        }
    }
}
```

---

## 📖 核心功能详解

### 1. 系统状态感知 (SystemProbe)

Agent 在执行任务前，会静默探测手机的完整状态：

```kotlin
// 手动触发探测
val probeData = agent.probeSystem(forceRefresh = true)

// 探测数据包含：
probeData?.systemInfo?.androidVersion  // Android 版本
probeData?.systemInfo?.kernelVersion   // 内核版本
probeData?.systemInfo?.rootScheme      // Root 方案 (Magisk/KernelSU)
probeData?.hardwareState?.cpuGovernor  // CPU 调度器
probeData?.hardwareState?.wakeLocks    // 唤醒锁列表
probeData?.softwareState?.runningProcesses  // 运行中的进程
```

**探测的价值示例**：

用户说："优化手机续航"

| 之前（简单模式） | 现在（Agent 模式） |
|-----------------|------------------|
| 执行固定脚本："pm disable 几个应用" | 先探测，发现社交 App 持有 15 分钟唤醒锁，CPU 调度器是 performance |
| 通用优化，效果不确定 | 针对性方案：冻结该 App + 调整 CPU 调度器 + 打开电池优化 |

---

### 2. 多步任务规划 (TaskPlanner)

TaskPlanner 将自然语言需求拆解为结构化的 TaskPlan：

```kotlin
// 只规划不执行
val taskPlan = agent.planTask(
    userRequest = "备份 boot 分区，刷入 Magisk 模块，验证兼容性"
)

// TaskPlan 包含：
taskPlan.name           // 任务名称
taskPlan.description    // 任务描述
taskPlan.steps.forEach { step ->
    step.name           // 步骤名称
    step.command        // 具体命令
    step.requiresRoot   // 是否需要 Root
    step.validationRules  // 成功校验规则
    step.failureHandler   // 失败处理方案
    step.requiresConfirmation  // 是否需要用户确认
}
```

**一个真实的 TaskPlan 示例**：

```json
{
  "taskName": "备份并刷入 Magisk 模块",
  "description": "备份 boot 分区，刷入模块，验证兼容性",
  "steps": [
    {
      "name": "备份 boot 分区",
      "description": "备份到 /sdcard/boot_backup.img",
      "command": "dd if=/dev/block/by-name/boot of=/sdcard/boot_backup.img",
      "requiresRoot": true,
      "validationRules": [
        {"type": "EXIT_CODE_ZERO", "value": ""},
        {"type": "FILE_EXISTS", "value": "/sdcard/boot_backup.img"}
      ],
      "failureHandler": {
        "strategy": "ABORT",
        "description": "备份失败，终止任务"
      },
      "requiresConfirmation": false
    },
    {
      "name": "刷入 Magisk 模块",
      "description": "使用 magisk 模块安装",
      "command": "magisk --install-module /sdcard/module.zip",
      "requiresRoot": true,
      "validationRules": [
        {"type": "EXIT_CODE_ZERO", "value": ""},
        {"type": "OUTPUT_CONTAINS", "value": "Done!"}
      ],
      "failureHandler": {
        "strategy": "ROLLBACK",
        "maxRetries": 2,
        "rollbackCommand": "rm -rf /data/adb/modules/module_name"
      },
      "requiresConfirmation": true
    }
  ]
}
```

---

### 3. 顺序执行引擎 (TaskExecutor)

TaskExecutor 严格按照"上一步成功 → 进入下一步"的逻辑：

```kotlin
val result = agent.executePlannedTask(taskPlan)

// 执行结果包含：
result.status           // 任务状态
result.stepResults      // 每个步骤的执行结果
result.lastCompletedStep // 最后完成的步骤
result.rollbackPerformed // 是否执行了回滚
```

**执行流程图**：

```
开始
  ↓
执行步骤 1
  ↓
校验结果 ✅
  ↓
执行步骤 2
  ↓
校验结果 ❌
  ↓
尝试修复（最多3次）
  ↓
修复成功 → 继续
修复失败 → 回滚/终止
  ↓
所有步骤完成 → 完成
```

---

### 4. 自动错误修复 (ErrorAnalyzer)

当命令执行失败时，Agent 会自动分析错误并尝试修复：

```kotlin
// 示例：pm disable 在高版本 Android 上失败
// 原始命令失败：
pm disable com.example.app
// 错误输出：Error: java.lang.SecurityException: Shell does not have permission to disable

// ErrorAnalyzer 分析：
// 错误类型：VERSION_INCOMPATIBLE
// 根本原因：高版本 Android 需要指定用户 ID
// 修复命令：pm disable-user --user 0 com.example.app

// 自动重试修复后的命令
```

**ErrorAnalyzer 能处理的错误类型**：

| 错误类型 | 识别关键字 | 修复方式 |
|---------|----------|---------|
| PERMISSION_DENIED | Permission denied | 添加 su -c |
| SELINUX_BLOCKED | SELinux | setenforce 0 |
| PARTITION_READ_ONLY | Read-only file system | mount remount,rw |
| VERSION_INCOMPATIBLE | pm disable (无 --user) | pm disable-user --user 0 |
| FILE_EXISTS | File exists | 添加 -f 参数 |
| COMMAND_NOT_FOUND | not found | 尝试备选命令 |

---

### 5. 断点续执行 (TaskPersistence)

任务执行过程中，每完成一步就保存快照。任务中断后可以恢复：

```kotlin
// 获取所有未完成的任务
val pendingTasks = agent.getPendingTasks()

// 恢复某个任务
val result = agent.resumeTask(taskId = "task-123")

// 暂停当前任务
agent.pauseCurrentTask()

// 继续暂停的任务
agent.resumePausedTask()
```

**快照保存的内容**：

```kotlin
data class TaskSnapshot(
    val taskId: String,
    val taskPlan: TaskPlan,        // 完整任务计划
    val currentStep: Int,         // 执行到第几步
    val stepResults: Map<String, StepExecutionResult>, // 已完成步骤的结果
    val status: TaskStatus,
    val savedAt: Long
)
```

---

### 6. 后台定时任务 (ScheduledTaskManager)

Agent 可以调度后台执行的定时任务：

```kotlin
// 示例：凌晨 2 点自动备份所有应用并压缩

// 计算触发时间（凌晨 2 点）
val calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 2)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    if (beforeNow()) add(Calendar.DAY_OF_YEAR, 1)
}

// 调度定时任务
val scheduledConfig = agent.scheduleTask(
    userRequest = "备份所有应用的 APK 到 /sdcard/backup，压缩加密，完成后关机",
    triggerTime = calendar.timeInMillis,
    triggerType = TriggerType.DAILY,  // 每天执行
    useRoot = true
)

// 查看所有已调度的任务
val allTasks = agent.getScheduledTasks()

// 取消任务
agent.cancelScheduledTask(scheduledConfig.taskId)
```

**定时任务的特点**：

- ✅ 使用 WorkManager/AlarmManager，系统级调度
- ✅ 支持设备唤醒（WakeLock）
- ✅ 支持充电/空闲等约束条件
- ✅ 任务完成后自动发送系统通知
- ✅ App 退出后任务仍会执行

---

## 🎯 实际场景示例

### 场景 1：完整的备份刷入流程

```kotlin
val result = agent.executeFullTask(
    userRequest = """
        备份 boot 分区，刷入 Magisk 模块，验证兼容性，
        失败则自动还原备份
    """,
    useRoot = true
)
```

**Agent 自动执行的流程**：

1. 探测系统：确认是 Magisk Root，SELinux 状态，boot 分区路径
2. 规划任务：拆解为 4 个步骤
3. 执行步骤 1：备份 boot 分区 → 验证备份文件存在
4. 执行步骤 2：刷入模块 → 验证输出包含 "Done!"
5. 执行步骤 3：验证兼容性 → 检查模块是否正常加载
6. 所有步骤通过 → 完成！

**如果刷入失败**：
- 自动重试 2 次（备选命令）
- 都失败 → 执行回滚命令
- 恢复到备份状态
- 发送失败通知

---

### 场景 2：冻结系统浏览器（自动修复）

```kotlin
agent.executeFullTask("冻结系统浏览器")
```

**执行流程**：

1. 执行原始命令：`pm disable com.android.browser` ❌ 失败
2. ErrorAnalyzer 分析：错误是 VERSION_INCOMPATIBLE，需要 --user 0
3. 自动修复：执行 `pm disable-user --user 0 com.android.browser` ✅ 成功
4. 任务完成！

**对用户透明，自动处理兼容性！**

---

### 场景 3：定时自动备份

```kotlin
// 每天凌晨 2 点备份
agent.scheduleTask(
    userRequest = "备份 /data 分区到 /sdcard，压缩，验证完整性",
    triggerTime = tomorrowAt2am,
    triggerType = TriggerType.DAILY,
    useRoot = true
)
```

**不需要 App 保持前台，不需要设备亮屏！**

---

## ⚙️ 高级配置

### 自定义失败策略

```kotlin
// 在规划时自定义每个步骤的失败处理
val customPlan = taskPlan.copy(
    steps = taskPlan.steps.map { step ->
        if (step.name == "危险操作") {
            step.copy(
                failureHandler = FailureHandler(
                    strategy = FailureStrategy.ROLLBACK,
                    maxRetries = 3,
                    rollbackCommand = "echo '执行回滚'"
                )
            )
        } else {
            step
        }
    }
)
```

### 自定义校验规则

```kotlin
val customStep = step.copy(
    validationRules = listOf(
        ValidationRule(
            type = ValidationType.OUTPUT_CONTAINS,
            value = "Success",
            description = "输出必须包含 Success"
        ),
        ValidationRule(
            type = ValidationType.FILE_EXISTS,
            value = "/sdcard/done.txt"
        )
    )
)
```

---

## 📂 完整文件结构

```
ai-terminal/src/main/java/com/ai/assistance/aiterminal/terminal/
│
├── agent/
│   ├── SystemProbeData.kt          # 系统探测数据模型
│   ├── SystemProbe.kt              # 系统探测实现
│   ├── TerminalAgent.kt            # 旧版（保留兼容）
│   └── AdvancedTerminalAgent.kt    # ✨ 高级 Agent（主入口）
│
└── agent/task/
    ├── TaskModels.kt               # 任务相关数据模型
    ├── TaskPlanner.kt              # 任务规划器
    ├── TaskExecutor.kt             # 任务执行引擎
    ├── ErrorAnalyzer.kt            # 错误分析器
    ├── TaskPersistence.kt          # 任务持久化/断点
    └── ScheduledTaskManager.kt     # 定时任务管理
```

---

## 🎉 总结

高级终端 Agent 让你的应用从：

**一个命令生成器** → **一个真正的智能执行系统**

| 特性 | 旧版 | Advanced Agent |
|------|------|----------------|
| 单步执行 | ✅ | ✅ |
| 系统感知 | ❌ | ✅ |
| 多步任务 | ❌ | ✅ |
| 结果校验 | ❌ | ✅ |
| 自动修复 | ❌ | ✅ |
| 断点恢复 | ❌ | ✅ |
| 定时任务 | ❌ | ✅ |
| 系统通知 | ❌ | ✅ |

---

现在你可以构建真正智能的终端 AI 助手了！🚀
