# Multi-Agent APK — 完整能力清单（增强版）

> 7 种协作模式 + 10 种预设角色 + 协作推荐器 + Agent 间消息 + 增强黑板

---

## 1. 7 种协作模式

| # | 模式 | 说明 | 实现状态 |
|---|------|------|:--------:|
| 1 | **PIPELINE** | 顺序流水线（A→B→C），上一步输出作为下一步输入 | ✅ 完整 |
| 2 | **DEBATE** | 多轮辩论 + 主持人裁决 | ✅ 完整 |
| 3 | **ADVERSARIAL** | Generator vs Discriminator 对抗迭代 | ✅ 完整 |
| 4 | **PARALLEL_RACING** | 真实并行（async/await）+ 取置信度最高 | ✅ 完整 |
| 5 | **HIERARCHICAL** | Supervisor 分派 + Workers 并行 + Reviewers 检查 | ✅ 完整 |
| 6 | **VOTING** | 投票表决（多数决，可配置阈值） | ✅ 完整（新增） |
| 7 | **CONSENSUS** | 持续讨论直到所有 Agent 同意 | ✅ 完整（新增） |

### 使用示例

```kotlin
// PIPELINE — 顺序流水线
val result = ApexClient.multiAgent.runCollaboration(
    mode = "PIPELINE",
    agentIds = listOf("builtin.supervisor", "builtin.worker", "builtin.reviewer"),
    initialPrompt = "实现一个排序算法"
).getOrNull()

// DEBATE — 辩论（需要主持人）
val result = ApexClient.multiAgent.runCollaboration(
    mode = "DEBATE",
    agentIds = listOf("builtin.supervisor", "builtin.critic", "builtin.reviewer"),
    initialPrompt = "Kotlin vs Rust 哪个更适合系统编程？",
    moderatorId = "builtin.supervisor",
    maxRounds = 3
).getOrNull()

// ADVERSARIAL — 对抗（Generator + Discriminator）
val result = ApexClient.multiAgent.runCollaboration(
    mode = "ADVERSARIAL",
    agentIds = listOf("builtin.worker", "builtin.reviewer"),
    initialPrompt = "生成高质量代码",
    maxRounds = 5
).getOrNull()

// PARALLEL_RACING — 并行竞速
val result = ApexClient.multiAgent.runCollaboration(
    mode = "PARALLEL_RACING",
    agentIds = listOf("builtin.worker", "builtin.critic"),
    initialPrompt = "快速解决问题"
).getOrNull()

// HIERARCHICAL — 层级（Supervisor + Workers + Reviewers）
val result = ApexClient.multiAgent.runCollaboration(
    mode = "HIERARCHICAL",
    agentIds = listOf("builtin.supervisor", "builtin.worker", "builtin.reviewer"),
    initialPrompt = "完成一个复杂项目",
    maxRounds = 5
).getOrNull()

// VOTING — 投票表决
val result = ApexClient.multiAgent.runCollaboration(
    mode = "VOTING",
    agentIds = listOf("builtin.supervisor", "builtin.worker", "builtin.reviewer", "builtin.critic"),
    initialPrompt = "是否采用方案 A？",
    votingThreshold = 0.5f  // 过半数通过
).getOrNull()

// CONSENSUS — 共识达成
val result = ApexClient.multiAgent.runCollaboration(
    mode = "CONSENSUS",
    agentIds = listOf("builtin.supervisor", "builtin.worker", "builtin.reviewer"),
    initialPrompt = "确定技术栈",
    consensusThreshold = 0.8f,  // 80% 同意即可
    maxRounds = 10
).getOrNull()
```

---

## 2. 10 种预设角色模板

| # | 模板 ID | 名称 | 角色 | 能力 |
|---|---------|------|------|------|
| 1 | `template.code_reviewer` | 代码审查员 | REVIEWER | code_review / quality_check / security_check |
| 2 | `template.test_generator` | 测试生成器 | WORKER | test_generation / unit_test / integration_test |
| 3 | `template.doc_writer` | 文档撰写者 | WORKER | documentation / api_doc / readme |
| 4 | `template.architect` | 架构师 | SUPERVISOR | architecture / design / planning / decomposition |
| 5 | `template.debugger` | 调试专家 | WORKER | debugging / bug_fix / root_cause_analysis |
| 6 | `template.security_auditor` | 安全审计员 | REVIEWER | security_audit / vulnerability_scan / compliance_check |
| 7 | `template.perf_optimizer` | 性能优化师 | WORKER | performance / optimization / profiling / bottleneck |
| 8 | `template.translator` | 翻译官 | WORKER | translation / i18n / localization |
| 9 | `template.summarizer` | 总结者 | WORKER | summarization / extraction / synthesis |
| 10 | `template.creative_advisor` | 创意顾问 | CRITIC | creativity / brainstorming / innovation |

```kotlin
// 列出所有模板
val templates = ApexClient.multiAgent.listTemplates().getOrNull()

// 协作推荐 — 根据任务自动推荐模式 + Agent
val rec = ApexClient.multiAgent.recommendCollaboration("审查这段代码的安全性").getOrNull()
// {mode: "HIERARCHICAL", templateIds: ["template.architect", "template.code_reviewer", "template.security_auditor"]}
```

---

## 3. 协作推荐器

根据任务描述自动推荐合适的协作模式 + Agent 组合：

```kotlin
val rec = ApexClient.multiAgent.recommendCollaboration("审查代码").getOrNull()
// 推荐：HIERARCHICAL 模式 + 架构师 + 代码审查员 + 安全审计员

val rec = ApexClient.multiAgent.recommendCollaboration("讨论技术选型").getOrNull()
// 推荐：DEBATE 模式 + 架构师 + 创意顾问 + 总结者

val rec = ApexClient.multiAgent.recommendCollaboration("是否采用微服务").getOrNull()
// 推荐：VOTING 模式 + 架构师 + 审查员 + 安全审计员 + 性能优化师
```

---

## 4. Agent 间消息传递

```kotlin
// 发送直接消息
ApexClient.multiAgent.sendMessage(
    sessionId = "session-xxx",
    fromAgentId = "builtin.supervisor",
    toAgentId = "builtin.worker",
    content = "请优先处理任务 A",
    type = "DIRECT"
)

// 广播消息
ApexClient.multiAgent.sendMessage(
    sessionId = "session-xxx",
    fromAgentId = "builtin.supervisor",
    toAgentId = "*",
    content = "所有人暂停，等待新指令",
    type = "BROADCAST"
)

// 获取会话消息历史
val messages = ApexClient.multiAgent.getSessionMessages("session-xxx").getOrNull()
```

---

## 5. 增强黑板

```kotlin
// 写入
ApexClient.multiAgent.writeBlackboard("context.language", "kotlin")

// 读取
val lang = ApexClient.multiAgent.readBlackboard("context.language")

// 列出所有键
val keys = ApexClient.multiAgent.blackboardKeys().getOrNull()

// 清空
ApexClient.multiAgent.clearBlackboard()
```

**黑板增强点**：
- 类型安全 `get<T>()`
- 订阅机制（entries flow）
- TTL 自动过期
- 写入者记录

---

## 6. Agent 多维查询

```kotlin
// 按能力查询
val reviewers = ApexClient.multiAgent.findAgentsByCapability("code_review").getOrNull()

// 按角色查询
val workers = ApexClient.multiAgent.findAgentsByRole("WORKER").getOrNull()
```

---

## 7. 完整 API 速查

### 协作执行（1 个方法，7 种模式）
```kotlin
runCollaboration(mode, agentIds, initialPrompt, maxRounds, timeoutMs,
                 moderatorId, consensusThreshold, votingThreshold, continueOnFailure)
```

### 协作推荐 + 模板（2 个方法）
```kotlin
recommendCollaboration(taskDescription) / listTemplates()
```

### Agent 管理（5 个方法）
```kotlin
registerAgent / unregisterAgent / listAgents
findAgentsByCapability / findAgentsByRole
```

### 消息传递（2 个方法）
```kotlin
sendMessage / getSessionMessages
```

### Blackboard（5 个方法）
```kotlin
readBlackboard / writeBlackboard / blackboardSnapshot
blackboardKeys / clearBlackboard
```

### 会话管理（3 个方法）
```kotlin
listSessions / cancelSession / blackboardSnapshot
```

**总计 18 个方法**（原 11 → 现 18，新增 7 个 + 增强 runCollaboration）。
