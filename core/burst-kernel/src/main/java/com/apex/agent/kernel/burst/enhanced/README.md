# 狂暴模式增强系统（Burst Mode Enhancement）

> 15 项高级能力，让狂暴模式"名副其实"——真正的暴力执行/高并发/激进策略/自我进化

## 📋 目录

- [概述](#概述)
- [15 项增强能力](#15-项增强能力)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [暴怒系统详解](#暴怒系统详解)

---

## 概述

在现有 31 个技能 + 核心引擎 + 配置层基础上，新增 15 项高级能力，让狂暴模式具备：
- **RPG 式状态系统**（暴怒值/能量/战利品）
- **自我进化能力**（遗传算法/失败学习/难度自适应）
- **企业级可控性**（配额/断路器/风险评估）
- **调试可观察性**（战斗日志/时间旅行/策略热切换）

## 15 项增强能力

### Phase 1: 核心"狂暴"觉醒
| # | 能力 | 核心特性 |
|---|------|----------|
| B1 | 暴怒值/能量系统 | 4 态状态机(CALM→AGITATED→BERSERK→EXHAUSTED) + 暴怒增益 + 能量管理 |
| B2 | 战斗日志与回放 | 完整帧记录 + 慢放/快放/过滤 + 统计分析 |
| B3 | 失败学习系统 | 跨会话失败记忆 + 模式聚类 + 避坑提示注入 |
| B4 | 任务优先级抢占 | 优先级抢占 + 狂暴抢占 + 截止时间抢占 + 检查点保存 |

### Phase 2: 智能进化
| # | 能力 | 核心特性 |
|---|------|----------|
| B5 | 多阶段进化 | GEPA 遗传算法 + 4 阶段(CALM→TRAINING→EVOLVED→MASTER) |
| B6 | 并行宇宙探索 | 多策略并行 + 质量评估 + 竞速模式 |
| B7 | 实时策略热切换 | 5 种策略 + 指标驱动自动切换 + 平滑迁移 |
| B8 | 自适应难度调节 | 4 档难度(EASY/NORMAL/HARD/NIGHTMARE) + 成功率驱动 |

### Phase 3: 企业级可控
| # | 能力 | 核心特性 |
|---|------|----------|
| B9 | 资源配额管理 | 6 维配额(CPU/网络/磁盘/Token/调用/并发) + 租约机制 |
| B10 | 时间旅行调试 | 帧跳转 + Fork 分支 + What-If 分析 + 差异比较 |
| B11 | 智能断流恢复 | Circuit Breaker + 自动诊断 + 8 种修复动作 |
| B12 | 多模型混合推理 | 模型路由 + 集成投票 + 5 种 Provider |
| B13 | 上下文压缩策略库 | 5 种策略(摘要/关键句/实体/滑动窗口/RAG) |
| B14 | 风险评估与降级 | 7 维风险因素 + 5 级风险 + 6 种降级策略 |

### Phase 4: 游戏化
| # | 能力 | 核心特性 |
|---|------|----------|
| B15 | 战利品/奖励系统 | 6 种战利品 + 4 种稀有度 + 等级/生命/连击 |

## 架构设计

```
enhanced/
├── BurstEnhancementOrchestrator.kt   # 🔥 统一编排器
├── rage/          # B1 暴怒值/能量系统
├── battle/        # B2 战斗日志与回放
├── learning/      # B3 失败学习系统
├── preemption/    # B4 任务优先级抢占
├── evolution/     # B5 多阶段进化
├── universe/      # B6 并行宇宙探索
├── strategy/      # B7 实时策略热切换
├── difficulty/    # B8 自适应难度调节
├── quota/         # B9 资源配额管理
├── timetravel/    # B10 时间旅行调试
├── circuit/       # B11 智能断流恢复
├── ensemble/      # B12 多模型混合推理
├── compression/   # B13 上下文压缩策略库
├── risk/          # B14 风险评估与降级
└── reward/        # B15 战利品/奖励系统
```

## 快速开始

```kotlin
val orchestrator = BurstEnhancementOrchestrator()

// 任务执行前
val before = orchestrator.beforeTaskExecution(
    taskId = "task_001",
    skillId = "reasoning.react",
    input = "分析这段代码",
    taskContext = RiskAssessor.TaskContext(
        taskComplexity = 3, toolDangerLevel = 1,
        historicalFailureRate = 0.2f, resourcePressure = 0.5f,
        isRootRequired = false, affectsUserData = false, isReversible = true
    )
)
// before.injections 可注入到 prompt

// 任务执行后
orchestrator.afterTaskExecution(
    taskId = "task_001",
    skillId = "reasoning.react",
    input = "分析这段代码",
    output = "分析结果...",
    success = true,
    durationMs = 1500,
    startFrame = before.startFrame,
    complexity = 3,
    quality = 0.9f
)

// 生成报告
val report = orchestrator.generateFullReport()
println(report)
```

## 暴怒系统详解

### 状态转换

```
CALM ──(暴怒≥40)──→ AGITATED ──(暴怒≥70)──→ BERSERK ──(能量≤10)──→ EXHAUSTED
  ↑                      |                       |                        |
  └──(暴怒<40)───────────┘                       │                        │
  ↑                                              │                        │
  └────────────────(能量>500)────────────────────┘────────────────────────┘
```

### 暴怒增益

| 状态 | 并发倍率 | 重试倍率 | 熔断 | 推测执行 | 超时倍率 |
|------|----------|----------|------|----------|----------|
| CALM | ×1.0 | ×1 | ✓ | ✗ | ×1.0 |
| AGITATED | ×1.5 | ×2 | ✓ | ✓ | ×1.2 |
| BERSERK | ×2.0 | ×5 | ✗ | ✓ | ×1.5 |
| EXHAUSTED | ×0.5 | ×1 | ✓ | ✗ | ×0.7 |

### 暴怒值变化

- **任务失败**: +5 ~ +50（按严重度）
- **任务超时**: +20
- **任务取消**: +10
- **任务成功**: -10 × quality
- **自然衰减**: 每 10 秒 -1 ~ -8（按状态）

## License

Apache License 2.0
