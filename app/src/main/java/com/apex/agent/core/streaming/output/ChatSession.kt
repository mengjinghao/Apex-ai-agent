package com.apex.agent.core.streaming.output

import com.ai.assistance.aiterminal.terminal.mascot.AuraMascot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 对话会话 — 串联用户输入、Agent 执行、模块化输出。
 *
 * 这是交互页面的核心业务逻辑:
 * 1. 用户发消息 → 加入输出流
 * 2. Agent 接收并执行 → 产生各种 [OutputBlock](思考/命令/工具/结果)
 * 3. 输出流实时更新 → UI 渲染模块化卡片
 * 4. 水母形态跟随当前块自动切换
 *
 * # 交互流程示例
 *
 * ```
 * val session = ChatSession()
 *
 * // 用户发消息
 * session.sendUserMessage("帮我分析 src 目录")
 *
 * // Agent 自动执行(模拟):
 * // 1. 思考 → ReasoningBlock
 * // 2. 执行 ls → CommandBlock + CommandOutputBlock
 * // 3. 分析结果 → TextBlock
 * // 4. 完成 → SuccessBlock
 *
 * // UI 订阅
 * session.stream.blocks.collect { blocks -> render(blocks) }
 * session.stream.formEvents.collect { form -> mascot.setForm(form) }
 * ```
 *
 * # 狂暴模式
 *
 * 狂暴模式开启后,Agent 用更复杂的块(多路径推理/对抗/ToT 等):
 *
 * ```
 * session.enableBerserkMode()
 * session.sendUserMessage("用多路径推理优化这段代码")
 * // Agent 会输出 MultiPathReasoningBlock + AdversarialBlock + SelfCorrectionBlock
 * ```
 */

    /** 当前水母形态(由块驱动) */
    private val _currentForm = MutableStateFlow(AuraMascot.AuraForm.IDLE)
    val currentForm: StateFlow<AuraMascot.AuraForm> = _currentForm.asStateFlow()

    /** 是否狂暴模式 */
    private val _isBerserkMode = MutableStateFlow(false)
    val isBerserkMode: StateFlow<Boolean> = _isBerserkMode.asStateFlow()

    /** 是否正在执行 */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    init {
        // 订阅块的水母形态事件,更新当前形态
        scope.launch {
            stream.formEvents.collectLatest { form ->
                _currentForm.value = form
            }
        }
    }

    /** 启用狂暴模式 */
    fun enableBerserkMode() {
        _isBerserkMode.value = true
        _currentForm.value = AuraMascot.AuraForm.BERSERK
    }

    /** 关闭狂暴模式 */
    fun disableBerserkMode() {
        _isBerserkMode.value = false
        _currentForm.value = AuraMascot.AuraForm.IDLE
    }

    /** 用户发送消息 */
    suspend fun sendUserMessage(text: String) {
        // 1. 加入用户消息块
        stream.emitText(OutputBlock.TextBlock.TextRole.USER, text)

        if (_isBusy.value) return
        _isBusy.value = true

        // 2. Agent 执行(实际接入真实 Agent 逻辑)
        if (_isBerserkMode.value) {
            executeBerserkAgent(text)
        } else {
            executeNormalAgent(text)
        }

        _isBusy.value = false
        _currentForm.value = AuraMascot.AuraForm.IDLE
    }

    /** 普通模式 Agent 执行(模拟,实际接入真实 Agent) */
    private suspend fun executeNormalAgent(userText: String) {
        // 思考
        val reasoningId = stream.startReasoning()
        stream.appendReasoning(reasoningId, "用户想要: $userText\n我先分析需求,然后制定计划...")
        delay(500)
        stream.completeReasoning(reasoningId, confidence = 0.92f)

        // 任务分解
        val taskId = stream.startTask(
            title = "处理用户请求",
            steps = listOf("分析需求", "执行操作", "验证结果")
        )
        delay(300)
        stream.advanceTaskStep(taskId)

        // 执行命令(示例)
        val cmdId = stream.startCommand("ls -la src/", workingDir = "/project")
        delay(800)
        stream.appendCommandOutput(cmdId, "Main.kt\nUtils.kt\nREADME.md\n")
        stream.completeCommand(cmdId, exitCode = 0, durationMs = 800)
        stream.advanceTaskStep(taskId)

        // 工具调用(记忆)
        stream.emitMemory(
            operation = OutputBlock.MemoryBlock.MemoryOperation.WRITE,
            type = OutputBlock.MemoryBlock.MemoryType.SHORT_TERM,
            key = "last_task",
            value = userText
        )

        // 验证
        delay(300)
        stream.advanceTaskStep(taskId)
        stream.completeTask(taskId)

        // 回复
        val textId = stream.startStreamingText()
        val reply = "我已经分析了 src 目录,发现 3 个文件。"
        for (chunk in reply.split("")) {
            stream.appendTextDelta(textId, chunk)
            delay(30)
        }
        stream.completeText(textId)

        // 成功
        stream.emitSuccess("任务完成", mapOf("耗时" to "1.9s", "文件数" to "3"))
    }

    /** 狂暴模式 Agent 执行(模拟,用复杂块) */
    private suspend fun executeBerserkAgent(userText: String) {
        // 多路径推理
        val multiPathId = stream.startMultiPathReasoning(
            paths = listOf("方案A: 直接执行", "方案B: 先分析再执行", "方案C: 并行竞速"),
            strategy = OutputBlock.BerserkBlock.MultiPathReasoningBlock.SelectionStrategy.BEST_OF_ALL
        )
        stream.updateReasoningPath(multiPathId, 0, "快速但可能出错", 0.6f)
        delay(300)
        stream.updateReasoningPath(multiPathId, 1, "稳妥但慢", 0.85f)
        delay(300)
        stream.updateReasoningPath(multiPathId, 2, "最快但耗资源", 0.75f)
        delay(200)
        stream.completeMultiPathReasoning(multiPathId, selectedPathIndex = 1)

        // 技能链
        val skillChainId = stream.startSkillChain(listOf(
            "ReAct" to "🧬", "TreeOfThoughts" to "🌳", "SelfCorrection" to "🔄", "Racing" to "🏁"
        ))
        for (i in 0..3) {
            delay(400)
            stream.advanceSkillChain(skillChainId)
        }
        stream.completeSkillChain(skillChainId)

        // 对抗评估
        val advId = stream.startAdversarial()
        stream.appendAdversarialArg(advId, OutputBlock.BerserkBlock.AdversarialBlock.Side.ATTACKER, "方案B 在边界条件下可能失败")
        delay(300)
        stream.appendAdversarialArg(advId, OutputBlock.BerserkBlock.AdversarialBlock.Side.DEFENDER, "已加边界检查,可处理")
        delay(300)
        stream.appendAdversarialArg(advId, OutputBlock.BerserkBlock.AdversarialBlock.Side.ATTACKER, "但性能损失 15%")
        delay(300)
        stream.completeAdversarial(advId, verdict = "防守方方案可行,性能可接受", winner = OutputBlock.BerserkBlock.AdversarialBlock.Side.DEFENDER, rounds = 2)

        // 命令执行
        val cmdId = stream.startCommand("ls -la src/")
        delay(500)
        stream.appendCommandOutput(cmdId, "Main.kt\nUtils.kt\n")
        stream.completeCommand(cmdId, exitCode = 0)

        // 成功(带指标)
        stream.emitSuccess(
            summary = "狂暴模式任务完成 · 多路径推理 + 对抗评估 + 技能链",
            metrics = mapOf("耗时" to "3.2s", "节省 token" to "73%", "路径数" to "3", "对抗轮数" to "2")
        )
    }

    /** 清空会话 */
    fun clear() {
        stream.clear()
        _currentForm.value = AuraMascot.AuraForm.IDLE
        _isBusy.value = false
    }
}
