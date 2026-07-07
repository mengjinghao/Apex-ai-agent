# Enhanced Workflow System（增强工作流系统）

> 融合 LangGraph / Temporal / Airflow / Dify / n8n / PocketFlow 等顶级工作流系统设计模式，
> 为 Apex-auto-agent 打造的下一代工作流引擎。

## 📋 目录

- [概述](#概述)
- [核心特性](#核心特性)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [节点类型](#节点类型)
- [高级模式](#高级模式)
- [API 参考](#api-参考)
- [设计灵感](#设计灵感)

---

## 概述

`enhanced` 包是 Apex-auto-agent 工作流系统的**全新增强版**，通过研究 GitHub 上 10 大顶级 AI Agent 工作流编排系统（LangGraph、Temporal、Airflow、n8n、Dify、Coze、AutoGen、CrewAI、LlamaIndex Workflows、PocketFlow）的设计模式，提炼并实现了 12 项核心增强能力。

本系统**自包含、无外部模块依赖**，与现有工作流代码并存，可渐进式迁移。

## 核心特性

| # | 特性 | 灵感来源 | 状态 |
|---|------|----------|------|
| 1 | DAG 环检测 + 拓扑校验 | Apache Airflow | ✅ |
| 2 | 节点级 RetryPolicy + Circuit Breaker | Temporal + AWS | ✅ |
| 3 | 实时 Observability（Span/Tracer） | OpenTelemetry + LangSmith | ✅ |
| 4 | Checkpoint & Durable Execution | Temporal + LangGraph | ✅ |
| 5 | 并行 Fan-out / Fan-in + Barrier | Dify + PocketFlow + LlamaIndex | ✅ |
| 6 | 子工作流嵌套 | Temporal Child Workflow | ✅ |
| 7 | 循环（Count/ForEach/While/MapReduce） | Dify Iteration + Coze Loop | ✅ |
| 8 | Saga 补偿事务 | Temporal Saga | ✅ |
| 9 | 人在回路审批（interrupt/resume） | LangGraph HITL | ✅ |
| 10 | 事件驱动触发 + EventBus | LlamaIndex + n8n | ✅ |
| 11 | 工作流版本管理 + 灰度发布 | Temporal Versioning | ✅ |
| 12 | 模板市场 | n8n + Dify | ✅ |
| 13 | 定时调度器（Cron/Interval/SpecificTime） | Airflow + n8n + Quartz | ✅ |
| 14 | 表达式引擎（算术/逻辑/三元/方法调用） | Dify + SpEL | ✅ |
| 15 | 旧工作流迁移适配器（5 种格式） | n8n import + Dify DSL | ✅ |
| 16 | 历史回放 + 性能分析 | Temporal replay + Airflow | ✅ |
| 17 | 实时监控仪表盘 + Prometheus 导出 | Prometheus + Grafana | ✅ |
| 18 | 序列化器（JSON/YAML/紧凑格式+校验和） | n8n + Airflow | ✅ |
| 19 | DSL 构建器（类型安全 fluent API） | Gradle Kotlin DSL + Anko | ✅ |

## 架构设计

```
enhanced/
├── EnhancedWorkflowExecutor.kt       # 🔥 主执行器，整合所有特性
├── model/                            # 数据模型
│   ├── EnhancedWorkflow.kt           #   工作流定义
│   ├── EnhancedNode.kt               #   节点定义（13 种节点类型）
│   └── EnhancedConnection.kt         #   连接定义
├── validation/
│   └── WorkflowValidator.kt          # #1 DAG 校验
├── retry/
│   └── RetryExecutor.kt              # #2 重试 + 断路器
├── observability/
│   └── InMemoryTracer.kt             # #3 链路追踪
├── checkpoint/
│   └── CheckpointManager.kt          # #4 持久化恢复
├── parallel/
│   └── ParallelExecutor.kt           # #5 Fan-out/Fan-in
├── subworkflow/
│   └── SubWorkflowExecutor.kt        # #6 子工作流
├── loop/
│   └── LoopExecutor.kt               # #7 循环
├── saga/
│   └── Saga.kt                       # #8 Saga 补偿
├── hitl/
│   └── ApprovalGateway.kt            # #9 人工审批
├── events/
│   └── WorkflowEventBus.kt           # #10 事件总线
├── versioning/
│   └── WorkflowVersionRegistry.kt    # #11 版本管理
├── templates/
│   └── WorkflowTemplateRegistry.kt   # #12 模板市场
├── scheduler/                        # ★ 新增
│   ├── WorkflowScheduler.kt          # #13 定时调度器
│   └── CronParser.kt                 #    Cron 表达式解析
├── expression/                       # ★ 新增
│   └── ExpressionEvaluator.kt        # #14 表达式引擎
├── migration/                        # ★ 新增
│   └── WorkflowMigrationAdapter.kt   # #15 旧格式迁移（5 种源格式）
├── replay/                           # ★ 新增
│   └── WorkflowReplayer.kt           # #16 历史回放 + 性能分析
├── monitor/                          # ★ 新增
│   └── WorkflowMonitor.kt            # #17 监控仪表盘 + Prometheus
├── serializer/                       # ★ 新增
│   └── WorkflowSerializer.kt         # #18 序列化器（JSON/YAML/Compact）
├── dsl/                              # ★ 新增
│   └── WorkflowDsl.kt                # #19 类型安全 DSL 构建器
├── handlers/
│   └── BuiltinHandlers.kt            # 内置动作处理器
├── test/
│   └── EnhancedWorkflowTest.kt       # 单元测试
└── examples/
    └── EnhancedWorkflowExamples.kt   # 使用示例
```

## 新增功能快速指南

### 1. 定时调度（Cron）

```kotlin
val scheduler = WorkflowScheduler(executor)

// 每 5 分钟执行
scheduler.scheduleAtFixedRate(workflow, intervalMs = 5 * 60_000)

// 每天 9:30 执行
scheduler.scheduleDaily(workflow, "09:30")

// Cron 表达式：周一到周五 9 点
scheduler.scheduleCron(workflow, "0 9 * * 1-5")

// 取消调度
scheduler.unschedule(jobId)
```

### 2. 表达式引擎

```kotlin
val eval = ExpressionEvaluator.getInstance()
val ctx = mapOf("user" to mapOf("age" to 25, "name" to "Alice"))

// 复杂条件
eval.evaluateBoolean("\${user.age} > 18 && \${user.name} == 'Alice'", ctx)

// 三元运算
eval.evaluateString("\${user.age} >= 18 ? '成年' : '未成年'", ctx)

// 方法调用
eval.evaluateBoolean("\${user.name} contains 'lic'", ctx)

// 字符串模板
eval.interpolate("Hello, \${user.name}!", ctx)
```

### 3. DSL 构建器

```kotlin
val wf = workflow("订单工作流") {
    description = "Saga 模式订单处理"
    sagaMode = true

    trigger("开始")

    saga("创建订单", "create_order", "cancel_order",
        "product_id" to "123", "qty" to "1"
    ) {
        retryPolicy = RetryPolicyDef(maxAttempts = 3)
    }

    saga("扣款", "process_payment", "refund_payment",
        "amount" to "99.9"
    )

    end("结束")

    chain("开始", "创建订单", "扣款", "结束")
}
```

### 4. 旧工作流迁移

```kotlin
val migrator = WorkflowMigrationAdapter()
val result = migrator.migrateFromJson(oldWorkflowJsonString)
if (result.isSuccess) {
    val newWorkflow = result.workflow!!
    // 使用新的 EnhancedWorkflow
}
```

### 5. 监控仪表盘

```kotlin
val monitor = WorkflowMonitor.getInstance()

// 自动收集（在 executor 事件回调中调用）
monitor.onExecutionStarted(threadId, workflowId, name)
monitor.onExecutionCompleted(threadId, workflowId, name, success, duration, nodes, error)

// 获取快照
val snapshot = monitor.currentSnapshot()
println("总执行: ${snapshot.totals.totalExecutions}")
println("成功率: ${snapshot.totals.successRate}")

// Prometheus 导出
val metrics = monitor.exportPrometheusMetrics()
```

### 6. 历史回放

```kotlin
val replayer = WorkflowReplayer()
val session = replayer.createSession(threadId)
if (session != null) {
    val analysis = replayer.analyze(session)
    println("总耗时: ${analysis.totalDurationMs}ms")
    println("瓶颈节点: ${analysis.bottleneckNodeId}")

    // 导出报告
    val report = replayer.exportSession(session)
    println(report)
}
```

### 7. 序列化

```kotlin
val serializer = WorkflowSerializer.getInstance()

// 导出 JSON（含校验和）
val json = serializer.toJson(workflow, pretty = true)

// 紧凑格式（节省空间）
val compact = serializer.toCompact(workflow)

// 导入
val result = serializer.fromJson(json)
if (result.isSuccess) {
    val wf = result.workflows[0]
}
```

## 快速开始

### 1. 创建执行器

```kotlin
val executor = BuiltinHandlers.registerAll(
    EnhancedWorkflowExecutor.Builder()
        .withDefaultTimeout(30_000)
).build()
```

### 2. 定义工作流

```kotlin
val workflow = EnhancedWorkflow(
    name = "我的工作流",
    nodes = listOf(
        EnhancedNode(
            name = "触发",
            type = EnhancedNodeType.TRIGGER,
            config = EnhancedNodeConfig(
                triggerConfig = TriggerConfigDef(triggerType = TriggerTypeDef.MANUAL)
            )
        ),
        EnhancedNode(
            name = "打印",
            type = EnhancedNodeType.EXECUTE,
            config = EnhancedNodeConfig(
                actionType = "log",
                actionConfig = mapOf("message" to "Hello, World!")
            )
        )
    ),
    connections = listOf(
        EnhancedConnection("触发节点ID", "打印节点ID", ConnectionConditionDef.ON_SUCCESS)
    )
)
```

### 3. 执行

```kotlin
val result = executor.execute(workflow, inputs = mapOf("key" to "value"))
println("成功: ${result.success}, 耗时: ${result.durationMs}ms")
```

## 节点类型

| 类型 | 用途 | 配置 |
|------|------|------|
| `TRIGGER` | 工作流入口 | `TriggerConfigDef` (MANUAL/SCHEDULE/EVENT/INTENT/SPEECH/WEBHOOK) |
| `EXECUTE` | 执行动作 | `actionType` + `actionConfig` |
| `CONDITION` | 条件判断 | `left` + `operator` + `right` |
| `LOGIC` | 逻辑组合 | `operator` (AND/OR/NOT) + `inputs` |
| `EXTRACT` | 数据提取 | `extractMode` (REGEX/JSON/SUBSTRING/...) |
| `FAN_OUT` | 并行扇出 | `FanOutSpecDef` |
| `FAN_IN` | 并行汇合 | `FanInSpecDef` |
| `LOOP` | 循环 | `LoopSpecDef` (COUNT/FOR_EACH/WHILE/MAP_REDUCE) |
| `SUB_WORKFLOW` | 子工作流 | `SubWorkflowConfigDef` |
| `HUMAN_INPUT` | 人工审批 | `HumanInputConfigDef` |
| `SAGA` | Saga 事务 | `actionType` + `compensateActionType` |
| `DELAY` | 延时 | `delayMs` |
| `END` | 结束 | — |

## 高级模式

### Saga 补偿事务

```kotlin
val workflow = EnhancedWorkflow(
    name = "订单 Saga",
    sagaMode = true,
    nodes = listOf(
        EnhancedNode(
            name = "创建订单",
            type = EnhancedNodeType.SAGA,
            config = EnhancedNodeConfig(
                actionType = "create_order",
                compensateActionType = "cancel_order"  // 失败时自动调用
            )
        ),
        // ... 更多 Saga 步骤
    )
)
```

### 节点级重试

```kotlin
EnhancedNode(
    name = "HTTP 调用",
    type = EnhancedNodeType.EXECUTE,
    config = EnhancedNodeConfig(actionType = "http_request", ...),
    retryPolicy = RetryPolicyDef(
        maxAttempts = 5,
        initialIntervalMs = 1000,
        backoffCoefficient = 2.0,
        maxIntervalMs = 60_000,
        jitterRatio = 0.3f
    ),
    timeoutMs = 30_000
)
```

### 并行 Fan-out

```kotlin
EnhancedNode(
    name = "并行处理",
    type = EnhancedNodeType.FAN_OUT,
    config = EnhancedNodeConfig(
        fanOutSpec = FanOutSpecDef(
            itemsExpression = "items",      // 引用输入列表
            maxConcurrency = 5,              // 最大并发
            failFast = false                 // 等所有完成
        )
    )
)
```

### 人工审批

```kotlin
EnhancedNode(
    name = "需要审批",
    type = EnhancedNodeType.HUMAN_INPUT,
    config = EnhancedNodeConfig(
        humanInputConfig = HumanInputConfigDef(
            prompt = "是否批准此操作？",
            options = listOf("approve", "reject"),
            timeoutMs = 24 * 60 * 60_000L
        )
    )
)
```

### 事件驱动触发

```kotlin
EnhancedNode(
    name = "事件触发",
    type = EnhancedNodeType.TRIGGER,
    config = EnhancedNodeConfig(
        triggerConfig = TriggerConfigDef(
            triggerType = TriggerTypeDef.EVENT,
            eventConfig = EventTriggerConfigDef(
                eventType = "user.signup",
                filterExpression = "plan == 'premium'"
            )
        )
    )
)
```

## API 参考

### 主执行器

```kotlin
class EnhancedWorkflowExecutor {
    suspend fun execute(
        workflow: EnhancedWorkflow,
        inputs: Map<String, Any> = emptyMap(),
        parentThreadId: String? = null
    ): ExecutionResult

    suspend fun resume(threadId: String): ExecutionResult?
    suspend fun resumeAll(): List<String>
    fun cancel(threadId: String): Boolean

    val events: SharedFlow<ExecutionEvent>
    val activeExecutions: StateFlow<Map<String, ExecutionState>>
}
```

### Builder

```kotlin
EnhancedWorkflowExecutor.Builder()
    .withTracer(tracer)
    .withCheckpointer(checkpointer)
    .withRetryExecutor(retryExecutor)
    .withDefaultTimeout(ms)
    .withActionHandler(type, handler)
    .withCompensateHandler(type, handler)
    .withTriggerHandler(type, handler)
    .withSubWorkflowExecutor(executor)
    .build()
```

### 全局单例

| 持有者 | 用途 |
|--------|------|
| `TracerHolder` | 追踪器（默认 InMemoryTracer） |
| `EventBusHolder` | 事件总线 |
| `ApprovalGatewayHolder` | 审批网关 |
| `VersionRegistryHolder` | 版本注册表 |
| `TemplateRegistryHolder` | 模板注册表 |
| `CircuitBreakerRegistry` | 断路器注册表 |

## 设计灵感

| 特性 | 参考项目 | 核心思想 |
|------|----------|----------|
| DAG 校验 | Apache Airflow | 编译期三色 DFS 环检测 |
| RetryPolicy | Temporal | 声明式重试 + 指数退避 + 抖动 |
| Circuit Breaker | AWS Prescriptive | 三态熔断（CLOSED/OPEN/HALF_OPEN） |
| Checkpoint | LangGraph + Temporal | 持久化 + replay 恢复 |
| Fan-out/Fan-in | Dify + LlamaIndex | Variable Aggregator + collect_events |
| Sub-workflow | Temporal Child Workflow | 父子线程 + 独立重试 |
| Loop | Dify Iteration + Coze | 4 种循环模式 |
| Saga | Temporal Saga Pattern | LIFO 补偿 + 幂等 |
| HITL | LangGraph interrupt() | CompletableDeferred 挂起/恢复 |
| Event Bus | LlamaIndex Workflows | SharedFlow 事件驱动 |
| Versioning | Temporal Worker Versioning | 灰度比例 + 回滚 |
| Templates | n8n + Dify | 参数化模板 + 一键安装 |

## 与现有系统的关系

Apex-auto-agent 历史上有 3 套工作流子系统：

1. `com.apex.core.workflow.WorkflowExecutor` — 生产主力（依赖已移除的 `com.apex.data.model`，当前无法编译）
2. `com.apex.agent.domain.workflow.WorkflowEngine` — 领域模型版（有 Kahn 拓扑排序）
3. `com.apex.agent.core.tools.skill.WorkflowEngine` — 技能系统版（自包含）

**本 `enhanced` 包是第 4 套**，定位为"下一代统一工作流引擎"：
- 自包含，无缺失依赖
- 实现 12 项顶级模式
- 可通过适配层兼容历史定义
- 建议作为新功能的首选引擎

## License

Apache License 2.0
