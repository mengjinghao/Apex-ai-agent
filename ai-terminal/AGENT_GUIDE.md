# Terminal Agent - 完整闭环 AI 助手

## 概述

Terminal Agent 不再是简单的「自然语言→命令」工具调用，而是具备 **感知 - 决策 - 执行 - 排错 - 反馈** 完整闭环的真正 Agent。

## 核心架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                        用户自然语言请求                                │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────┐
│ 1. 感知阶段 (SystemProbe)                                           │
│    - 静默执行预设命令                                                │
│    - 获取系统信息、硬件状态、软件状态                                  │
│    - 结构化探测数据                                                  │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────┐
│ 2. 决策阶段 (LLM Thinking)                                          │
│    - 将探测数据作为上下文                                            │
│    - AI 分析需求并生成执行计划                                        │
│    - 包含命令列表、风险评估、预期结果                                  │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
┌─────────────────────────────────▼───────────────────────────────────┐
│ 3. 执行阶段 (Command Execution)                                     │
│    - 按计划执行命令                                                  │
│    - 实时监控输出                                                    │
│    - 支持 Root/Non-Root 模式                                        │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                       ┌──────────┴──────────┐
                       │ 执行成功            │ 执行失败
                       │                     │
┌──────────────────────▼──────────────┐  ┌─▼─────────────────────────────┐
│ 5. 反馈阶段 (Report Generation)    │  │ 4. 排错阶段 (Auto Fix)         │
│    - 生成完整报告                   │  │    - 分析错误原因               │
│    - 总结执行效果                   │  │    - 尝试自动修复               │
│    - 给出优化建议                   │  │    - 重新执行计划               │
└─────────────────────────────────────┘  └───────────┬───────────────────┘
                                                           │
                                                           ▼
                                                 ┌───────────────────┐
                                                 │  回到反馈阶段      │
                                                 └───────────────────┘
```

## 快速开始

### 1. 初始化 Agent

```kotlin
import com.ai.assistance.aiterminal.terminal.agent.TerminalAgent
import com.ai.assistance.aiterminal.terminal.agent.SystemProbe
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import com.ai.assistance.aiterminal.terminal.ai.LLMApiImpl

// 初始化依赖
val rootTerminalManager = RootTerminalManager()
val systemProbe = SystemProbe(rootTerminalManager)
val llmApi = LLMApiImpl()

// 创建 Agent
val terminalAgent = TerminalAgent(
    rootTerminalManager = rootTerminalManager,
    llmApi = llmApi,
    systemProbe = systemProbe
)
```

### 2. 执行用户请求

```kotlin
// 监听状态变化
lifecycleScope.launch {
    terminalAgent.state.collect { state ->
        when (state) {
            is AgentState.Idle -> updateUI("就绪")
            is AgentState.Probing -> updateUI("正在探测系统...")
            is AgentState.Thinking -> updateUI("AI 正在思考...")
            is AgentState.Executing -> updateUI("正在执行...")
            is AgentState.Fixing -> updateUI("正在修复...")
            is AgentState.Done -> updateUI("完成！")
            is AgentState.Error -> updateUI("错误: ${state.message}")
        }
    }
}

// 监听执行步骤
lifecycleScope.launch {
    terminalAgent.executionSteps.collect { steps ->
        updateStepsUI(steps)
    }
}

// 执行请求
lifecycleScope.launch {
    val result = terminalAgent.executeRequest(
        userRequest = "优化手机续航",
        useRoot = true
    )
    
    // 显示最终结果
    showFinalResult(result)
}
```

### 3. 使用系统探测（单独使用）

```kotlin
// 获取系统探测数据
val probeResult = systemProbe.probeSystem()

when (probeResult) {
    is ProbeResult.Success -> {
        val data = probeResult.data
        println("Android 版本: ${data.systemInfo.androidVersion}")
        println("Root 方案: ${data.systemInfo.rootScheme}")
        println("CPU 调度器: ${data.hardwareState.cpuGovernor}")
        println("唤醒锁: ${data.hardwareState.wakeLocks.size}")
        
        // 直接用于 LLM 上下文
        val llmContext = data.toPromptString()
    }
    
    is ProbeResult.Error -> {
        println("探测失败: ${probeResult.message}")
    }
    
    ProbeResult.Loading -> {
        // 加载中...
    }
}
```

## 功能对比

| 特性 | 之前（命令执行器） | 现在（Agent） |
|------|------------------|--------------|
| 执行方式 | 单轮工具调用 | 完整闭环（感知-决策-执行-排错-反馈） |
| 系统感知 | ❌ 无 | ✅ SystemProbe 静默探测 |
| 决策能力 | 简单命令生成 | 基于上下文的智能决策 |
| 错误处理 | 直接返回错误 | 自动排错与修复 |
| 结果反馈 | 原始命令输出 | 结构化报告与总结 |
| Root 支持 | 简单切换 | 智能 Root/Non-Root 选择 |

## 实际应用场景

### 场景 1: 优化手机续航

**用户请求**: "优化手机续航"

**Agent 执行流程**:

1. **感知阶段**：SystemProbe 探测到某社交 App 持有 15 分钟的唤醒锁，CPU 调度器设置为 performance，电池健康 85%
2. **决策阶段**：AI 生成计划：
   - 冻结该后台 App
   - 调整 CPU 调度器为 conservative
   - 开启电池优化
3. **执行阶段**：静默执行计划
4. **反馈阶段**：生成报告，预估续航提升 20%

### 场景 2: 清理存储空间

**用户请求**: "帮我清理一下存储空间"

**Agent 执行流程**:

1. **感知阶段**：探测到缓存占用 5GB，卸载残留 2GB，大文件列表
2. **决策阶段**：AI 生成安全清理计划
3. **执行阶段**：清理缓存和残留
4. **反馈阶段**：报告释放 7GB 空间

### 场景 3: 诊断设备问题

**用户请求**: "手机最近很卡，帮我看看"

**Agent 执行流程**:

1. **感知阶段**：探测到内存占用 95%，某进程异常 CPU 占用，SELinux Enforcing
2. **决策阶段**：AI 分析并给出诊断和解决方案
3. **执行阶段**：执行修复
4. **反馈阶段**：问题已解决的报告

## 系统探测内容

### SystemInfo (系统信息)
- Android 版本
- 内核版本
- 机型/制造商
- Root 方案 (Magisk/KernelSU/SuperSU)
- SELinux 状态
- 安全补丁版本
- Build ID

### HardwareState (硬件状态)
- CPU 架构/核心数/频率/调度器
- GPU 信息
- 内存占用（总/可用/已用）
- 存储占用（总/可用/已用）
- 电池电量/健康状态
- 唤醒锁列表

### SoftwareState (软件状态)
- 已安装应用列表
- 运行中进程（前 100）
- 挂载分区
- 系统应用列表

## 高级用法

### 自定义探测

```kotlin
// 强制刷新缓存
val freshData = systemProbe.probeSystem(forceRefresh = true)

// 手动清除缓存
systemProbe.clearCache()
```

### 状态监听

```kotlin
// 监听执行步骤
terminalAgent.executionSteps.collect { steps ->
    steps.forEach { step ->
        println("[${step.order}] ${step.type}: ${step.description}")
        step.result?.let { println("  结果: $it") }
        step.success?.let { println("  成功: $it") }
    }
}
```

### 重置 Agent

```kotlin
// 重置状态
terminalAgent.reset()
```

## 架构设计

### 核心文件

```
ai-terminal/src/main/java/com/ai/assistance/aiterminal/terminal/
├── agent/
│   ├── SystemProbeData.kt      # 数据模型
│   ├── SystemProbe.kt          # 系统探测实现
│   └── TerminalAgent.kt        # Agent 核心闭环
```

### 依赖关系

```
TerminalAgent
├── SystemProbe (系统探测)
├── RootTerminalManager (终端管理)
└── LLMApiImpl (AI 能力)
```

## 注意事项

1. **Root 权限**: 部分探测和操作需要 Root 权限
2. **缓存策略**: SystemProbe 默认缓存 60 秒，可强制刷新
3. **错误恢复**: Agent 具备自动排错能力，但不保证 100% 成功
4. **性能影响**: 完整探测在 1-2 秒内完成，对设备影响很小

## 下一步开发计划

- [ ] 增加更多系统探测维度
- [ ] 优化 LLM Prompt 工程
- [ ] 增加用户确认环节（高风险操作）
- [ ] 支持执行计划的可视化编辑
- [ ] 实现执行历史记录与回放
