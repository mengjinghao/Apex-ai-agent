package com.apex.agent.core.streaming.output

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock

/**
 * 模块化流水输出块系统 — Apex 终端对话式交互的核心输出模型。
 *
 * 设计灵感:OpenAI Codex CLI 的流式事件(message/function_call/reasoning),
 * 但更高级 — 更多模块类型 + 狂暴模式专用复杂块 + 水母形态联动。
 *
 * # 核心概念
 *
 * Agent 的输出不是一整段文本,而是由多个 [OutputBlock] 组成的流水序列。
 * 每个块有明确类型(思考/命令/文件编辑/工具调用/结果等),UI 按类型渲染成不同卡片。
 * 块支持流式更新(增量追加内容),用户能实时看到 Agent 的进度。
 *
 * # 块的生命周期
 *
 * 1. 块被创建(status=RUNNING,内容为空或部分)
 * 2. 块被流式更新(appendDelta 追加内容)
 * 3. 块完成(status=COMPLETED)或失败(status=FAILED)
 *
 * # 水母联动
 *
 * 每种块类型对应一个水母形态(见 [blockToForm]),块状态变化驱动水母切换。
 *
 * # 与 Codex 的区别(更高级)
 *
 * - Codex 只有 4 种事件类型,Apex 有 20+ 种块类型
 * - 狂暴模式有专用块(BerserkBlock):多路径推理/对抗评估/自我修正等复杂样式
 * - 块可嵌套(一个块包含子块,如任务块包含多个命令块)
 * - 块支持回滚和分支(狂暴模式多路径)
 */
sealed class OutputBlock {
    /** 块唯一 ID */
    abstract val id: String
    /** 块状态 */
    abstract val status: BlockStatus
    /** 块创建时间戳(ms) */
    abstract val createdAt: Long
    /** 块完成时间戳(ms,0=未完成) */
    abstract val completedAt: Long

    /** 块状态 */
    enum class BlockStatus(val displayName: String) {
        PENDING("待执行"),
        RUNNING("执行中"),
        COMPLETED("已完成"),
        FAILED("失败"),
        CANCELLED("已取消"),
        SKIPPED("已跳过"),
    }

    /** 文本块 — Agent 的思考或回复文本(流式追加) */
    data class TextBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val role: TextRole = TextRole.ASSISTANT,
        val content: String = "",
        val isStreaming: Boolean = true,
    ) : OutputBlock() {
        enum class TextRole(val displayName: String) {
            USER("用户"), ASSISTANT("Aura"), SYSTEM("系统"), AGENT("Agent"),
        }
    }

    /** 推理块 — Agent 的思考过程(Codex 的 reasoning,但更详细) */
    data class ReasoningBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val thoughts: String = "",
        val confidence: Float = 0f,
        val alternativePaths: List<String> = emptyList(),
        val isStreaming: Boolean = true,
    ) : OutputBlock()

    /** 命令块 — Agent 调用终端执行命令(Codex 的 function_call) */
    data class CommandBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val command: String = "",
        val workingDir: String = "",
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = "",
        val durationMs: Long = 0L,
        val isStreaming: Boolean = true,
    ) : OutputBlock()

    /** 命令输出增量(Codex 的 function_call_output) */
    data class CommandOutputBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val parentCommandId: String,
        val output: String = "",
        val stream: StreamType = StreamType.STDOUT,
        val isStreaming: Boolean = true,
    ) : OutputBlock() {
        enum class StreamType { STDOUT, STDERR }
    }

    /** 文件编辑块 — Agent 修改文件(显示 diff) */
    data class FileEditBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val filePath: String = "",
        val operation: FileOperation = FileOperation.EDIT,
        val diff: String = "",
        val oldContent: String = "",
        val newContent: String = "",
        val linesAdded: Int = 0,
        val linesRemoved: Int = 0,
    ) : OutputBlock() {
        enum class FileOperation(val displayName: String) {
            CREATE("创建"), EDIT("编辑"), DELETE("删除"), RENAME("重命名"), MOVE("移动"),
        }
    }

    /** 工具调用块 — Agent 调用工具(文件/进程/网络等) */
    data class ToolCallBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val toolName: String = "",
        val toolCategory: ToolCategory = ToolCategory.GENERAL,
        val arguments: String = "",
        val result: String = "",
        val icon: String = "🔧",
    ) : OutputBlock() {
        enum class ToolCategory(val displayName: String, val icon: String) {
            FILE("文件", "📄"), PROCESS("进程", "⚙️"), NETWORK("网络", "🌐"),
            CODE("代码", "💻"), SYSTEM("系统", "🛠️"), MCP("MCP", "🔗"),
            SKILL("技能", "💎"), MEMORY("记忆", "🧠"), SEARCH("搜索", "🔍"),
            GENERAL("通用", "🔧"),
        }
    }

    /** 记忆块 — Agent 读写记忆系统 */
    data class MemoryBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val operation: MemoryOperation,
        val memoryType: MemoryType,
        val key: String = "",
        val value: String = "",
        val resultCount: Int = 0,
    ) : OutputBlock() {
        enum class MemoryOperation(val displayName: String) {
            READ("读取"), WRITE("写入"), DELETE("删除"), SEARCH("检索"),
        }
        enum class MemoryType(val displayName: String) {
            SHORT_TERM("短期"), LONG_TERM("长期"), TOOL("工具"), SKILL("技能"), WEB("网页"),
        }
    }

    /** 任务块 — 任务分解与执行(可嵌套子块) */
    data class TaskBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val title: String = "",
        val description: String = "",
        val steps: List<TaskStep> = emptyList(),
        val currentStepIndex: Int = 0,
        val childBlockIds: List<String> = emptyList(),
    ) : OutputBlock() {
        data class TaskStep(
            val id: String,
            val title: String,
            val status: BlockStatus = BlockStatus.PENDING,
        )
    }

    /** 进度块 — 长任务进度指示 */
    data class ProgressBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.RUNNING,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = 0L,
        val label: String = "",
        val current: Int = 0,
        val total: Int = 0,
        val unit: String = "",
    ) : OutputBlock()

    /** 错误块 — 执行出错 */
    data class ErrorBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.FAILED,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = Clock.System.now().toEpochMilliseconds(),
        val errorType: ErrorType,
        val message: String,
        val stackTrace: String = "",
        val recoverySuggestion: String = "",
    ) : OutputBlock() {
        enum class ErrorType(val displayName: String) {
            COMMAND_FAILED("命令失败"), TOOL_ERROR("工具错误"), NETWORK_ERROR("网络错误"),
            FILE_NOT_FOUND("文件不存在"), PERMISSION_DENIED("权限不足"),
            TIMEOUT("超时"), PARSING_ERROR("解析错误"), UNKNOWN("未知错误"),
        }
    }

    /** 成功块 — 任务完成总结 */
    data class SuccessBlock(
        override val id: String,
        override val status: BlockStatus = BlockStatus.COMPLETED,
        override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
        override val completedAt: Long = Clock.System.now().toEpochMilliseconds(),
        val summary: String,
        val metrics: Map<String, String> = emptyMap(),
    ) : OutputBlock()

    // ===== 狂暴模式专用块(Codex 没有的高级块) =====

    /** 狂暴块 — 狂暴模式专用复杂输出块 */
    sealed class BerserkBlock : OutputBlock() {

        /** 多路径推理块 — 同时探索多条推理路径 */
        data class MultiPathReasoningBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val paths: List<ReasoningPath> = emptyList(),
            val selectedPathIndex: Int = -1,
            val strategy: SelectionStrategy = SelectionStrategy.BEST_OF_ALL,
        ) : BerserkBlock() {
            data class ReasoningPath(
                val id: String,
                val name: String,
                val reasoning: String = "",
                val confidence: Float = 0f,
                val status: BlockStatus = BlockStatus.PENDING,
            )
            enum class SelectionStrategy(val displayName: String) {
                BEST_OF_ALL("择优"), RACING("竞速"), VOTING("投票"),
                ADVERSARIAL("对抗"), CONSENSUS("共识"),
            }
        }

        /** 对抗评估块 — 红蓝对抗,攻击方 vs 防守方 */
        data class AdversarialBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val attackerArgs: List<String> = emptyList(),
            val defenderArgs: List<String> = emptyList(),
            val rounds: Int = 0,
            val verdict: String = "",
            val winner: Side = Side.NONE,
        ) : BerserkBlock() {
            enum class Side(val displayName: String) { ATTACKER("攻击方"), DEFENDER("防守方"), DRAW("平局"), NONE("未决") }
        }

        /** 自我修正块 — 发现错误并自我修复 */
        data class SelfCorrectionBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val detectedIssue: String = "",
            val correctionAttempts: List<CorrectionAttempt> = emptyList(),
            val finalResult: String = "",
        ) : BerserkBlock() {
            data class CorrectionAttempt(
                val id: String,
                val approach: String,
                val result: String = "",
                val success: Boolean = false,
            )
        }

        /** 思维树块 — ToT 树形推理 */
        data class TreeOfThoughtsBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val rootThought: String = "",
            val branches: List<ThoughtBranch> = emptyList(),
            val bestPath: List<String> = emptyList(),
        ) : BerserkBlock() {
            data class ThoughtBranch(
                val id: String,
                val thought: String,
                val evaluation: Float = 0f,
                val children: List<ThoughtBranch> = emptyList(),
            )
        }

        /** 技能链块 — 多技能串联执行 */
        data class SkillChainBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val skills: List<SkillExecution> = emptyList(),
            val currentSkillIndex: Int = 0,
        ) : BerserkBlock() {
            data class SkillExecution(
                val id: String,
                val name: String,
                val icon: String = "💎",
                val status: BlockStatus = BlockStatus.PENDING,
                val durationMs: Long = 0L,
                val result: String = "",
            )
        }

        /** 竞速块 — 多方案并行竞速,先完成者胜 */
        data class RacingBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val racers: List<Racer> = emptyList(),
            val winnerId: String = "",
        ) : BerserkBlock() {
            data class Racer(
                val id: String,
                val name: String,
                val progress: Float = 0f,
                val status: BlockStatus = BlockStatus.PENDING,
                val result: String = "",
            )
        }

        /** 演化块 — GA 演化迭代 */
        data class EvolutionBlock(
            override val id: String,
            override val status: BlockStatus = BlockStatus.RUNNING,
            override val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
            override val completedAt: Long = 0L,
            val generation: Int = 0,
            val population: List<Individual> = emptyList(),
            val bestFitness: Float = 0f,
            val mutationRate: Float = 0.1f,
        ) : BerserkBlock() {
            data class Individual(
                val id: String,
                val genome: String = "",
                val fitness: Float = 0f,
                val generation: Int = 0,
            )
        }
    }
}

/**
 * 块类型 → 水母形态映射。
 *
 * UI 渲染块时,根据块类型联动切换水母形态。
 */
fun blockToForm(block: OutputBlock): com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm =
    when (block) {
        is OutputBlock.TextBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.IDLE
        is OutputBlock.ReasoningBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.THINKING
        is OutputBlock.CommandBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.EXECUTING
        is OutputBlock.CommandOutputBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.TYPING
        is OutputBlock.FileEditBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.COMPILING
        is OutputBlock.ToolCallBlock -> when (block.toolCategory) {
            OutputBlock.ToolCallBlock.ToolCategory.MEMORY -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.REMEMBERING
            OutputBlock.ToolCallBlock.ToolCategory.SEARCH -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.ANALYZING
            OutputBlock.ToolCallBlock.ToolCategory.MCP -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.MCPING
            OutputBlock.ToolCallBlock.ToolCategory.SKILL -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.SKILLING
            OutputBlock.ToolCallBlock.ToolCategory.NETWORK -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.NETWORKING
            else -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.TOOLING
        }
        is OutputBlock.MemoryBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.REMEMBERING
        is OutputBlock.TaskBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.PLANNING
        is OutputBlock.ProgressBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.LOADING
        is OutputBlock.ErrorBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.ERROR
        is OutputBlock.SuccessBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.SUCCESS
        // 狂暴块
        is OutputBlock.BerserkBlock.MultiPathReasoningBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.AdversarialBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.SelfCorrectionBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.TreeOfThoughtsBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.SkillChainBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.RacingBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.BERSERK
        is OutputBlock.BerserkBlock.EvolutionBlock -> com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm.LEARNING
    }
