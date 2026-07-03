package com.apex.lib.multiagent

/**
 * 协作模式（增强版 — 7 种）。
 *
 * 原 5 种 + 新增 2 种：
 *   - VOTING: 投票表决（多数决）
 *   - CONSENSUS: 共识达成（持续讨论直到所有 Agent 同意）
 */
enum class CollaborationMode(val displayName: String, val description: String) {
    PIPELINE("顺序流水线", "A → B → C，上一步输出作为下一步输入"),
    DEBATE("辩论模式", "多个 Agent 多轮交锋，主持人裁决"),
    ADVERSARIAL("对抗模式", "Generator vs Discriminator 对抗训练"),
    PARALLEL_RACING("并行竞速", "多 Agent 并行，取置信度最高/最先完成"),
    HIERARCHICAL("层级模式", "Supervisor 分派 + Reviewer 检查"),
    VOTING("投票表决", "每个 Agent 投票，多数决"),
    CONSENSUS("共识达成", "持续讨论直到所有 Agent 同意")
}

/**
 * 协作配置。
 */
data class CollaborationConfig(
    val mode: CollaborationMode,
    val agentIds: List<String>,
    val initialPrompt: String,
    val maxRounds: Int = 10,
    val timeoutMs: Long = 60_000L,
    /** 辩论/对抗模式的主持人 Agent ID（DEBATE/ADVERSARIAL 用）。 */
    val moderatorId: String? = null,
    /** 共识模式需要的同意比例（0.0-1.0，默认 1.0=全部同意）。 */
    val consensusThreshold: Float = 1.0f,
    /** 投票模式需要的多数比例（0.0-1.0，默认 0.5=过半数）。 */
    val votingThreshold: Float = 0.5f,
    /** 是否在 Agent 失败时继续（false=任一失败即中止）。 */
    val continueOnFailure: Boolean = false,
    /** 是否记录详细调用历史。 */
    val recordInvocations: Boolean = true
)
