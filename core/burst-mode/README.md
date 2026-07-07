# burst-mode

> 狂暴模式专属库模块 — 为业务侧提供简洁、类型安全、可观测的狂暴模式 API

## 📋 模块定位

`burst-mode` 是狂暴模式的**对外门面层**，封装了 `burst-kernel` 微内核的复杂性，
提供面向开发者的简洁 API。

### 与其他 burst 模块的关系

```
业务代码
   ↓ 使用
┌─────────────────────────────────┐
│  core/burst-mode  ← 本模块      │  高级 API / 配置 / 预设 / 监控
└────────────┬────────────────────┘
             ↓ 依赖
┌─────────────────────────────────┐
│  core/burst-kernel              │  微内核（执行引擎 / 状态管理 / 插件加载）
└────────────┬────────────────────┘
             ↓ 依赖
┌─────────────────────────────────┐
│  plugins/burst-base             │  插件抽象层（IBurstSkill 等接口）
│  plugins/burst-builtin          │  内置技能实现（ReAct / ToT / Racing）
└─────────────────────────────────┘
```

## 🚀 快速开始

### 基本使用

```kotlin
import com.apex.agent.burstmode.api.BurstMode
import com.apex.agent.burstmode.preset.BurstPreset

// 1. 创建并初始化
val burstMode = BurstMode.create(context)
    .withPreset(BurstPreset.BALANCED)
    .initialize()

// 2. 执行任务
val task = BurstTask(
    id = "task_1",
    description = "分析这段代码的潜在 bug",
    complexity = BurstTask.Complexity.MEDIUM
)
val result = burstMode.execute(task)

// 3. 关闭
burstMode.shutdown()
```

### 自定义配置

```kotlin
val burstMode = BurstMode.create(context)
    .withConfig(
        BurstModeConfig.builder()
            .maxConcurrency(8)
            .timeoutMs(60_000)
            .enableAdaptiveOptimization(true)
            .llmProvider(LlmProvider.DEEPSEEK)
            .llmApiKey("sk-xxx")
            .build()
    )
    .initialize()
```

### 增量自定义预设

```kotlin
val burstMode = BurstMode.create(context)
    .withPreset(BurstPreset.PERFORMANCE)
    .withCustomConfig { builder ->
        builder.maxConcurrency(12)  // 在 PERFORMANCE 基础上调整
    }
    .initialize()
```

### 观察状态和指标

```kotlin
// 观察内核状态
burstMode.state.collect { state ->
    when (state) {
        KernelState.RUNNING -> showRunningIndicator()
        KernelState.PAUSED -> showPausedIndicator()
        KernelState.STOPPED -> hideIndicator()
        else -> {}
    }
}

// 观察指标
burstMode.observeMetrics().collect { metrics ->
    println("成功率: ${metrics.successRate}")
    println("平均耗时: ${metrics.averageExecutionTimeMs}ms")
    println("当前并发: ${metrics.currentConcurrency}")
}
```

### 批量执行

```kotlin
val tasks = listOf(
    BurstTask(id = "t1", description = "任务1"),
    BurstTask(id = "t2", description = "任务2"),
    BurstTask(id = "t3", description = "任务3")
)
val results = burstMode.executeBatch(tasks)
```

### 异步执行（支持取消）

```kotlin
val deferred = burstMode.executeAsync(task)

// 可取消
scope.launch {
    delay(5000)
    deferred.cancel()
}

try {
    val result = deferred.await()
} catch (e: CancellationException) {
    println("任务被取消")
}
```

## 📦 预设

| 预设 | 适用场景 | 并发 | 超时 | LLM |
|------|----------|------|------|-----|
| `BALANCED` | 日常使用（推荐） | 8 | 120s | 默认 |
| `PERFORMANCE` | 批量高吞吐 | 16 | 300s | 默认 |
| `POWER_SAVER` | 后台/省电 | 2 | 30s | 默认 |
| `LOCAL_INFERENCE` | 离线场景 | 4 | 180s | 本地 LLaMA |
| `CLOUD_INFERENCE` | 最高质量 | 8 | 60s | DeepSeek API |
| `STREAMING` | 超大文本流式 | 6 | 600s | 默认 |
| `TEST` | 单元测试 | 2 | 5s | 无 LLM |
| `CUSTOM` | 自定义 | - | - | - |

## ⚙️ 性能档位

| 档位 | 并发 | 超时 | 自适应 | 内存预算 |
|------|------|------|--------|----------|
| `AGGRESSIVE` | 16 | 300s | ✓ | 512MB |
| `BALANCED` | 8 | 120s | ✓ | 256MB |
| `CONSERVATIVE` | 4 | 60s | ✗ | 128MB |
| `POWER_SAVER` | 2 | 30s | ✗ | 64MB |

## 📊 指标

`BurstMetrics` 自动收集：

- 任务统计：总数 / 成功 / 失败 / 取消
- 性能指标：平均耗时 / 成功率 / 峰值并发
- 资源使用：累计 token / 累计内存（MB·秒）
- 实时状态：当前并发数

```kotlin
val snapshot = burstMode.getMetrics()
println("总任务: ${snapshot.totalTasks}")
println("成功率: ${(snapshot.successRate * 100).toInt()}%")
println("平均耗时: ${snapshot.averageExecutionTimeMs.toInt()}ms")
println("峰值并发: ${snapshot.peakConcurrency}")
```

## 🛡️ 异常处理

所有异常继承自 `BurstModeException`：

```kotlin
try {
    val result = burstMode.execute(task)
} catch (e: BurstModeException.NotInitialized) {
    // 未初始化
} catch (e: BurstModeException.Timeout) {
    // 超时
} catch (e: BurstModeException.ExecutionFailed) {
    // 执行失败
} catch (e: BurstModeException.LlmUnavailable) {
    // LLM 不可用
} catch (e: BurstModeException) {
    // 其他狂暴模式异常
}
```

## 🏗️ 架构

```
burst-mode/
├── api/                    # 对外 API
│   ├── BurstMode.kt        # 门面接口
│   ├── BurstModeBuilder.kt # 构建器
│   └── BurstModeImpl.kt    # 实现
├── config/                 # 配置
│   ├── BurstModeConfig.kt  # 配置数据类 + Builder
│   ├── BurstProfile.kt     # 性能档位枚举
│   └── LlmProvider.kt      # LLM 提供方（在 BurstModeConfig.kt 中）
├── preset/                 # 预设
│   └── BurstPreset.kt      # 8 个预设场景
├── monitor/                # 监控
│   └── BurstMetrics.kt     # 指标收集
├── exception/              # 异常
│   └── BurstModeException.kt  # 7 种异常类型
└── build.gradle.kts
```

## 🔌 依赖

- `:core:burst-kernel` — 微内核
- `:plugins:burst-base` — 插件抽象层
- `:domain` — 领域模型

## 📄 License

Apache 2.0
