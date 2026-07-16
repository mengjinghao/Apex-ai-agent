package com.apex.agent.core.streaming.output

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块化流水输出管道。
 *
 * 管理 [OutputBlock] 的生命周期:创建 → 流式更新 → 完成/失败。
 * 对外暴露 [blocks] Flow,UI 订阅后实时渲染块列表。
 *
 * # 核心能力
 *
 * 1. **块管理**:创建/获取/更新/完成块
 * 2. **流式更新**:appendDelta 增量追加内容到块
 * 3. **嵌套支持**:任务块可包含子块
 * 4. **水母联动**:块状态变化时发出 form 事件,驱动水母切换
 * 5. **狂暴模式**:支持复杂块(多路径/对抗/ToT 等)的流式更新
 *
 * # 使用示例
 *
 * ```
 * val stream = OutputBlockStream()
 *
 * // 创建思考块
 * val reasoningId = stream.startReasoning()
 * stream.appendReasoning(reasoningId, "用户想要分析代码,我先用 ls 看结构...")
 * stream.completeReasoning(reasoningId, confidence = 0.9f)
 *
 * // 创建命令块
 * val cmdId = stream.startCommand("ls -la src/")
 * stream.appendCommandOutput(cmdId, "Main.kt\nUtils.kt\n")
 * stream.completeCommand(cmdId, exitCode = 0)
 *
 * // 创建成功块
 * stream.emitSuccess("分析完成,发现 2 个文件", mapOf("耗时" to "1.2s"))
 *
 * // UI 订阅
 * stream.blocks.collect { blocks -> render(blocks) }
 * ```
 */
class OutputBlockStream(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    /** 块存储(id → block) */
    private val blockMap = ConcurrentHashMap<String, OutputBlock>()

    /** 块顺序列表(对话流顺序) */
    private val blockOrder = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())

    /** 块更新事件(增量,用于 UI 局部刷新) */
    private val _updates = kotlinx.coroutines.flow.MutableSharedFlow<BlockUpdate>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 水母形态事件(块状态变化驱动) */
    private val _formEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 块列表(有序,UI 订阅这个渲染整个对话流) */
    val blocks: StateFlow<List<OutputBlock>> = blockOrder
        .map { ids -> ids.mapNotNull { blockMap[it] } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 块更新事件流(UI 可订阅做局部刷新优化) */
    val updates: SharedFlow<BlockUpdate> = _updates.asSharedFlow()

    /** 水母形态事件流(驱动水母切换) */
    val formEvents: SharedFlow<com.ai.assistance.aiterminal.terminal.mascot.AuraMascot.AuraForm> = _formEvents.asSharedFlow()

    private val mutex = Mutex()

    /** 块更新事件类型 */
    sealed class BlockUpdate {
        abstract val blockId: String
        data class Updated(override val blockId: String, val block: OutputBlock) : BlockUpdate()

    // ===== 通用块操作 =====

    /** 添加块到流末尾 */
    private suspend fun addBlock(block: OutputBlock) {
        mutex.withLock {
            blockMap[block.id] = block
            blockOrder.value = blockOrder.value + block.id
        }
        _updates.emit(BlockUpdate.Created(block.id, block))
        _formEvents.emit(blockToForm(block))
    }

    /** 更新块(替换整个块对象) */
    private suspend fun updateBlock(block: OutputBlock) {
        blockMap[block.id] = block
        _updates.emit(BlockUpdate.Updated(block.id, block))
    }

    /** 完成块 */
    private suspend fun completeBlock(block: OutputBlock) {
        val completed = when (block) {
            is OutputBlock.TextBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis(), isStreaming = false)
            is OutputBlock.ReasoningBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis(), isStreaming = false)
            is OutputBlock.CommandBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis(), isStreaming = false)
            is OutputBlock.CommandOutputBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis(), isStreaming = false)
            is OutputBlock.FileEditBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.ToolCallBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.MemoryBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.TaskBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.ProgressBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.ErrorBlock -> block
            is OutputBlock.SuccessBlock -> block
            is OutputBlock.BerserkBlock.MultiPathReasoningBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.AdversarialBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.SelfCorrectionBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.TreeOfThoughtsBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.SkillChainBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.RacingBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
            is OutputBlock.BerserkBlock.EvolutionBlock -> block.copy(status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis())
        }
        blockMap[block.id] = completed
        _updates.emit(BlockUpdate.Completed(block.id, completed))
    }

    // ===== 文本块 =====

    suspend fun emitText(role: OutputBlock.TextBlock.TextRole, content: String): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.TextBlock(id = id, role = role, content = content, status = OutputBlock.BlockStatus.COMPLETED, isStreaming = false, completedAt = System.currentTimeMillis()))
        return id
    }

    suspend fun startStreamingText(role: OutputBlock.TextBlock.TextRole = OutputBlock.TextBlock.TextRole.ASSISTANT): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.TextBlock(id = id, role = role, content = "", isStreaming = true))
        return id
    }

    suspend fun appendTextDelta(blockId: String, delta: String) {
        val block = blockMap[blockId] as? OutputBlock.TextBlock ?: return
        updateBlock(block.copy(content = block.content + delta))
    }

    suspend fun completeText(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.TextBlock ?: return
        completeBlock(block)
    }

    // ===== 推理块 =====

    suspend fun startReasoning(): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.ReasoningBlock(id = id))
        return id
    }

    suspend fun appendReasoning(blockId: String, delta: String) {
        val block = blockMap[blockId] as? OutputBlock.ReasoningBlock ?: return
        updateBlock(block.copy(thoughts = block.thoughts + delta))
    }

    suspend fun completeReasoning(blockId: String, confidence: Float, alternatives: List<String> = emptyList()) {
        val block = blockMap[blockId] as? OutputBlock.ReasoningBlock ?: return
        completeBlock(block.copy(confidence = confidence, alternativePaths = alternatives))
    }

    // ===== 命令块 =====

    suspend fun startCommand(command: String, workingDir: String = ""): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.CommandBlock(id = id, command = command, workingDir = workingDir))
        return id
    }

    suspend fun appendCommandOutput(blockId: String, output: String, stream: OutputBlock.CommandOutputBlock.StreamType = OutputBlock.CommandOutputBlock.StreamType.STDOUT) {
        val block = blockMap[blockId] as? OutputBlock.CommandBlock ?: return
        if (stream == OutputBlock.CommandOutputBlock.StreamType.STDOUT) {
            updateBlock(block.copy(stdout = block.stdout + output))
        } else {
            updateBlock(block.copy(stderr = block.stderr + output))
        }
    }

    suspend fun completeCommand(blockId: String, exitCode: Int, durationMs: Long = 0L) {
        val block = blockMap[blockId] as? OutputBlock.CommandBlock ?: return
        completeBlock(block.copy(exitCode = exitCode, durationMs = durationMs))
    }

    // ===== 文件编辑块 =====

    suspend fun emitFileEdit(
        filePath: String,
        operation: OutputBlock.FileEditBlock.FileOperation,
        diff: String = "",
        oldContent: String = "",
        newContent: String = "",
        linesAdded: Int = 0,
        linesRemoved: Int = 0,
    ): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.FileEditBlock(
            id = id, filePath = filePath, operation = operation, diff = diff,
            oldContent = oldContent, newContent = newContent,
            linesAdded = linesAdded, linesRemoved = linesRemoved,
            status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis()
        ))
        return id
    }

    // ===== 工具调用块 =====

    suspend fun startToolCall(toolName: String, category: OutputBlock.ToolCallBlock.ToolCategory, arguments: String = ""): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.ToolCallBlock(id = id, toolName = toolName, toolCategory = category, arguments = arguments, icon = category.icon))
        return id
    }

    suspend fun completeToolCall(blockId: String, result: String) {
        val block = blockMap[blockId] as? OutputBlock.ToolCallBlock ?: return
        completeBlock(block.copy(result = result))
    }

    // ===== 记忆块 =====

    suspend fun emitMemory(operation: OutputBlock.MemoryBlock.MemoryOperation, type: OutputBlock.MemoryBlock.MemoryType, key: String = "", value: String = "", resultCount: Int = 0): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.MemoryBlock(id = id, operation = operation, memoryType = type, key = key, value = value, resultCount = resultCount, status = OutputBlock.BlockStatus.COMPLETED, completedAt = System.currentTimeMillis()))
        return id
    }

    // ===== 任务块 =====

    suspend fun startTask(title: String, description: String = "", steps: List<String> = emptyList()): String {
        val id = UUID.randomUUID().toString()
        val taskSteps = steps.mapIndexed { i, title -> OutputBlock.TaskBlock.TaskStep(id = "${id}_step_$i", title = title) }
        addBlock(OutputBlock.TaskBlock(id = id, title = title, description = description, steps = taskSteps))
        return id
    }

    suspend fun advanceTaskStep(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.TaskBlock ?: return
        val newSteps = block.steps.mapIndexed { i, step ->
            if (i < block.currentStepIndex) step.copy(status = OutputBlock.BlockStatus.COMPLETED)
            else if (i == block.currentStepIndex) step.copy(status = OutputBlock.BlockStatus.COMPLETED)
            else step
        }
        updateBlock(block.copy(steps = newSteps, currentStepIndex = block.currentStepIndex + 1))
    }

    suspend fun completeTask(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.TaskBlock ?: return
        val newSteps = block.steps.map { it.copy(status = OutputBlock.BlockStatus.COMPLETED) }
        completeBlock(block.copy(steps = newSteps, currentStepIndex = block.steps.size))
    }

    // ===== 进度块 =====

    suspend fun startProgress(label: String, total: Int, unit: String = ""): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.ProgressBlock(id = id, label = label, total = total, unit = unit))
        return id
    }

    suspend fun updateProgress(blockId: String, current: Int) {
        val block = blockMap[blockId] as? OutputBlock.ProgressBlock ?: return
        updateBlock(block.copy(current = current))
    }

    suspend fun completeProgress(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.ProgressBlock ?: return
        completeBlock(block)
    }

    // ===== 错误/成功块 =====

    suspend fun emitError(type: OutputBlock.ErrorBlock.ErrorType, message: String, stackTrace: String = "", suggestion: String = ""): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.ErrorBlock(id = id, errorType = type, message = message, stackTrace = stackTrace, recoverySuggestion = suggestion))
        return id
    }

    suspend fun emitSuccess(summary: String, metrics: Map<String, String> = emptyMap()): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.SuccessBlock(id = id, summary = summary, metrics = metrics))
        return id
    }

    // ===== 狂暴模式专用块 =====

    /** 多路径推理 */
    suspend fun startMultiPathReasoning(paths: List<String>, strategy: OutputBlock.BerserkBlock.MultiPathReasoningBlock.SelectionStrategy = OutputBlock.BerserkBlock.MultiPathReasoningBlock.SelectionStrategy.BEST_OF_ALL): String {
        val id = UUID.randomUUID().toString()
        val reasoningPaths = paths.mapIndexed { i, name -> OutputBlock.BerserkBlock.MultiPathReasoningBlock.ReasoningPath(id = "${id}_path_$i", name = name) }
        addBlock(OutputBlock.BerserkBlock.MultiPathReasoningBlock(id = id, paths = reasoningPaths, strategy = strategy))
        return id
    }

    suspend fun updateReasoningPath(blockId: String, pathIndex: Int, reasoning: String, confidence: Float) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.MultiPathReasoningBlock ?: return
        val newPaths = block.paths.mapIndexed { i, path ->
            if (i == pathIndex) path.copy(reasoning = reasoning, confidence = confidence, status = OutputBlock.BlockStatus.RUNNING)
            else path
        }
        updateBlock(block.copy(paths = newPaths))
    }

    suspend fun completeMultiPathReasoning(blockId: String, selectedPathIndex: Int) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.MultiPathReasoningBlock ?: return
        val newPaths = block.paths.mapIndexed { i, path ->
            path.copy(status = if (i == selectedPathIndex) OutputBlock.BlockStatus.COMPLETED else OutputBlock.BlockStatus.SKIPPED)
        }
        completeBlock(block.copy(paths = newPaths, selectedPathIndex = selectedPathIndex))
    }

    /** 对抗评估 */
    suspend fun startAdversarial(): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.BerserkBlock.AdversarialBlock(id = id))
        return id
    }

    suspend fun appendAdversarialArg(blockId: String, side: OutputBlock.BerserkBlock.AdversarialBlock.Side, arg: String) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.AdversarialBlock ?: return
        val newArgs = when (side) {
            OutputBlock.BerserkBlock.AdversarialBlock.Side.ATTACKER -> block.attackerArgs + arg
            OutputBlock.BerserkBlock.AdversarialBlock.Side.DEFENDER -> block.defenderArgs + arg
            else -> block.attackerArgs
        }
        val updated = if (side == OutputBlock.BerserkBlock.AdversarialBlock.Side.ATTACKER) block.copy(attackerArgs = newArgs) else block.copy(defenderArgs = newArgs)
        updateBlock(updated)
    }

    suspend fun completeAdversarial(blockId: String, verdict: String, winner: OutputBlock.BerserkBlock.AdversarialBlock.Side, rounds: Int) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.AdversarialBlock ?: return
        completeBlock(block.copy(verdict = verdict, winner = winner, rounds = rounds))
    }

    /** 自我修正 */
    suspend fun startSelfCorrection(issue: String): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.BerserkBlock.SelfCorrectionBlock(id = id, detectedIssue = issue))
        return id
    }

    suspend fun addCorrectionAttempt(blockId: String, approach: String, result: String = "", success: Boolean = false) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.SelfCorrectionBlock ?: return
        val attempt = OutputBlock.BerserkBlock.SelfCorrectionBlock.CorrectionAttempt(
            id = UUID.randomUUID().toString(), approach = approach, result = result, success = success
        )
        updateBlock(block.copy(correctionAttempts = block.correctionAttempts + attempt))
    }

    suspend fun completeSelfCorrection(blockId: String, finalResult: String) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.SelfCorrectionBlock ?: return
        completeBlock(block.copy(finalResult = finalResult))
    }

    /** 思维树 */
    suspend fun startTreeOfThoughts(rootThought: String): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.BerserkBlock.TreeOfThoughtsBlock(id = id, rootThought = rootThought))
        return id
    }

    suspend fun updateTreeBranches(blockId: String, branches: List<OutputBlock.BerserkBlock.TreeOfThoughtsBlock.ThoughtBranch>) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.TreeOfThoughtsBlock ?: return
        updateBlock(block.copy(branches = branches))
    }

    suspend fun completeTreeOfThoughts(blockId: String, bestPath: List<String>) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.TreeOfThoughtsBlock ?: return
        completeBlock(block.copy(bestPath = bestPath))
    }

    /** 技能链 */
    suspend fun startSkillChain(skills: List<Pair<String, String>>): String {
        val id = UUID.randomUUID().toString()
        val executions = skills.mapIndexed { i, (name, icon) ->
            OutputBlock.BerserkBlock.SkillChainBlock.SkillExecution(id = "${id}_skill_$i", name = name, icon = icon)
        }
        addBlock(OutputBlock.BerserkBlock.SkillChainBlock(id = id, skills = executions))
        return id
    }

    suspend fun advanceSkillChain(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.SkillChainBlock ?: return
        val newSkills = block.skills.mapIndexed { i, skill ->
            when {
                i < block.currentSkillIndex -> skill.copy(status = OutputBlock.BlockStatus.COMPLETED)
                i == block.currentSkillIndex -> skill.copy(status = OutputBlock.BlockStatus.COMPLETED)
                else -> skill
            }
        }
        val nextIndex = block.currentSkillIndex + 1
        val updatedSkills = if (nextIndex < newSkills.size) {
            newSkills.mapIndexed { i, s -> if (i == nextIndex) s.copy(status = OutputBlock.BlockStatus.RUNNING) else s }
        } else newSkills
        updateBlock(block.copy(skills = updatedSkills, currentSkillIndex = nextIndex))
    }

    suspend fun completeSkillChain(blockId: String) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.SkillChainBlock ?: return
        val newSkills = block.skills.map { it.copy(status = OutputBlock.BlockStatus.COMPLETED) }
        completeBlock(block.copy(skills = newSkills, currentSkillIndex = block.skills.size))
    }

    /** 竞速 */
    suspend fun startRacing(racers: List<String>): String {
        val id = UUID.randomUUID().toString()
        val racerList = racers.mapIndexed { i, name ->
            OutputBlock.BerserkBlock.RacingBlock.Racer(id = "${id}_racer_$i", name = name, status = OutputBlock.BlockStatus.RUNNING)
        }
        addBlock(OutputBlock.BerserkBlock.RacingBlock(id = id, racers = racerList))
        return id
    }

    suspend fun updateRacerProgress(blockId: String, racerIndex: Int, progress: Float) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.RacingBlock ?: return
        val newRacers = block.racers.mapIndexed { i, racer ->
            if (i == racerIndex) racer.copy(progress = progress) else racer
        }
        updateBlock(block.copy(racers = newRacers))
    }

    suspend fun completeRacing(blockId: String, winnerIndex: Int, result: String) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.RacingBlock ?: return
        val winnerId = block.racers.getOrNull(winnerIndex)?.id ?: ""
        val newRacers = block.racers.mapIndexed { i, racer ->
            if (i == winnerIndex) racer.copy(status = OutputBlock.BlockStatus.COMPLETED, progress = 1f, result = result)
            else racer.copy(status = OutputBlock.BlockStatus.SKIPPED)
        }
        completeBlock(block.copy(racers = newRacers, winnerId = winnerId))
    }

    /** 演化 */
    suspend fun startEvolution(mutationRate: Float = 0.1f): String {
        val id = UUID.randomUUID().toString()
        addBlock(OutputBlock.BerserkBlock.EvolutionBlock(id = id, mutationRate = mutationRate))
        return id
    }

    suspend fun updateEvolutionGeneration(blockId: String, generation: Int, population: List<OutputBlock.BerserkBlock.EvolutionBlock.Individual>, bestFitness: Float) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.EvolutionBlock ?: return
        updateBlock(block.copy(generation = generation, population = population, bestFitness = bestFitness))
    }

    suspend fun completeEvolution(blockId: String, bestIndividual: OutputBlock.BerserkBlock.EvolutionBlock.Individual) {
        val block = blockMap[blockId] as? OutputBlock.BerserkBlock.EvolutionBlock ?: return
        completeBlock(block.copy(population = listOf(bestIndividual), bestFitness = bestIndividual.fitness))
    }

    // ===== 清理 =====

    fun clear() {
        blockMap.clear()
        blockOrder.value = emptyList()
    }

    fun getBlock(id: String): OutputBlock? = blockMap[id]
}
