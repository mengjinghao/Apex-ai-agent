package com.apex.apk.rage.agent

// ============================================================
// 狂暴模式 Agent 架构师 — 逻辑已移至 :lib:rage
// ============================================================
// 原始实现（4 核心 Agent + 动态扩容 + 黑板 + 容错）已移至
// com.apex.lib.rage.RageAgentArchitect。
//
// 本文件仅保留类型别名，使 APK 内 Facade / UI / Bridge 代码
// 无需修改 import（`import com.apex.apk.rage.agent.*` 仍然有效）。
//
// APK 侧通过 RageServiceFacade.engine（RageEngine）访问架构师能力。
// ============================================================

/** 架构师引擎（委托 com.apex.lib.rage.RageAgentArchitect）。 */
typealias RageAgentArchitect = com.apex.lib.rage.RageAgentArchitect

/** Agent 角色。 */
typealias AgentRole = com.apex.lib.rage.AgentRole

/** 核心 Agent 配置。 */
typealias AgentConfig = com.apex.lib.rage.AgentConfig

/** 动态扩容 Agent 信息。 */
typealias DynamicAgentInfo = com.apex.lib.rage.DynamicAgentInfo

/** Agent 执行结果（内部）。 */
typealias AgentResult = com.apex.lib.rage.AgentResult

/** Agent 执行步骤记录。 */
typealias AgentStepRecord = com.apex.lib.rage.AgentStepRecord

/** 黑板条目。 */
typealias BlackboardEntry = com.apex.lib.rage.BlackboardEntry

/** 任务执行结果。 */
typealias TaskExecutionResult = com.apex.lib.rage.TaskExecutionResult

/** 执行历史记录。 */
typealias ExecutionRecord = com.apex.lib.rage.ExecutionRecord

/** 架构师事件流。 */
typealias ArchitectEvent = com.apex.lib.rage.ArchitectEvent
