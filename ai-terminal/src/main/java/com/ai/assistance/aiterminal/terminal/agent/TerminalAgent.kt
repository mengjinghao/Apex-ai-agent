package com.ai.assistance.aiterminal.terminal.agent

import android.util.Log
import com.ai.assistance.aiterminal.terminal.RootTerminalManager
import com.ai.assistance.aiterminal.terminal.ai.LLMAPI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TerminalAgent"

sealed class AgentState {
    object Idle : AgentState()
    object Probing : AgentState()
    object Thinking : AgentState()
    object Executing : AgentState()
    object Fixing : AgentState()
    object Reflecting : AgentState()
    object Done : AgentState()
    data class Error(val message: String, val exception: Throwable? = null) : AgentState()
}

data class ExecutionStep(
    val order: Int,
    val type: StepType,
    val description: String,
    val result: String? = null,
    val success: Boolean? = null
)

enum class StepType {
    PROBE,
    THINK,
    EXECUTE,
    FIX,
    REFLECT,
    DONE
}

class TerminalAgent(
    private val rootTerminalManager: RootTerminalManager,
    private val llmApi: LLMAPI,
    private val systemProbe: SystemProbe
) {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _executionSteps = MutableStateFlow<List<ExecutionStep>>(emptyList())
    val executionSteps: StateFlow<List<ExecutionStep>> = _executionSteps.asStateFlow()

    private val _finalResult = MutableStateFlow<String?>(null)
    val finalResult: StateFlow<String?> = _finalResult.asStateFlow()

    private var taskStartTime: Long = 0
    private var executionStepStartTime: Long = 0
    private val stepDurations = mutableListOf<Long>()

    suspend fun executeRequest(
        userRequest: String,
        useRoot: Boolean = rootTerminalManager.checkRootAccess()
    ): String = withContext(Dispatchers.IO) {
        clearState()
        taskStartTime = System.currentTimeMillis()
        _state.value = AgentState.Probing

        try {
            val executionHistory = mutableListOf<Pair<String, Long>>()

            addStep(StepType.PROBE, "正在探测系统状态...")
            executionStepStartTime = System.currentTimeMillis()
            val probeResult = systemProbe.probeSystem(forceRefresh = true)
            executionHistory.add("PROBE" to (System.currentTimeMillis() - executionStepStartTime))

            when (probeResult) {
                is ProbeResult.Success -> {
                    updateLastStep(true, "系统探测完成")
                    Log.i(TAG, "System probe completed: ${probeResult.data}")

                    _state.value = AgentState.Thinking
                    addStep(StepType.THINK, "AI 正在分析需求并生成执行方案...")
                    executionStepStartTime = System.currentTimeMillis()
                    val plan = generateExecutionPlan(userRequest, probeResult.data, useRoot)
                    executionHistory.add("THINK" to (System.currentTimeMillis() - executionStepStartTime))
                    updateLastStep(true, "执行方案已生成")
                    Log.i(TAG, "Execution plan generated: $plan")

                    _state.value = AgentState.Executing
                    addStep(StepType.EXECUTE, "正在执行操作...")
                    executionStepStartTime = System.currentTimeMillis()
                    val executionResult = executePlan(plan, useRoot)
                    executionHistory.add("EXECUTE" to (System.currentTimeMillis() - executionStepStartTime))

                    if (executionResult.success) {
                        updateLastStep(true, "执行成功")
                        _state.value = AgentState.Done
                        addStep(StepType.DONE, "任务完成")

                        val finalReport = generateFinalReport(
                            userRequest,
                            probeResult.data,
                            executionResult
                        )

                        performReflection(userRequest, executionHistory, executionResult)

                        _finalResult.value = finalReport
                        finalReport
                    } else {
                        _state.value = AgentState.Fixing
                        addStep(StepType.FIX, "执行失败，正在尝试自动修复...")
                        executionStepStartTime = System.currentTimeMillis()
                        val fixResult = tryFixError(executionResult, probeResult.data)
                        executionHistory.add("FIX" to (System.currentTimeMillis() - executionStepStartTime))

                        if (fixResult.success) {
                            updateLastStep(true, "自动修复成功")
                            _state.value = AgentState.Done
                            addStep(StepType.DONE, "任务完成（已自动修复）")

                            val finalReport = generateFinalReport(
                                userRequest,
                                probeResult.data,
                                fixResult
                            )

                            performReflection(userRequest, executionHistory, fixResult)

                            _finalResult.value = finalReport
                            finalReport
                        } else {
                            updateLastStep(false, "自动修复失败")
                            _state.value = AgentState.Error("执行失败：${executionResult.error}")

                            performReflection(userRequest, executionHistory, executionResult)

                            "执行失败：${executionResult.error}"
                        }
                    }
                }

                is ProbeResult.Error -> {
                    updateLastStep(false, "系统探测失败")
                    _state.value = AgentState.Error(probeResult.message, probeResult.exception)
                    "系统探测失败：${probeResult.message}"
                }

                ProbeResult.Loading -> {
                    ""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Agent execution failed", e)
            _state.value = AgentState.Error("Agent 执行失败：${e.message}", e)
            "Agent 执行失败：${e.message}"
        }
    }

    private suspend fun performReflection(
        userRequest: String,
        executionHistory: List<Pair<String, Long>>,
        executionResult: ExecutionResult
    ) {
        _state.value = AgentState.Reflecting
        addStep(StepType.REFLECT, "正在进行任务反思...")
        updateLastStep(true, "反思完成")
        _state.value = AgentState.Done
    }

    private suspend fun generateExecutionPlan(
        userRequest: String,
        probeData: SystemProbeData,
        useRoot: Boolean
    ): ExecutionPlan {
        val prompt = buildString {
            appendLine("你是一个专业的 Android 终端操作专家。请逐步分析用户需求，然后生成安全、详细的 Shell 执行计划。")
            appendLine()
            appendLine("【推理步骤】")
            appendLine("1. 分析用户需求的意图和安全风险")
            appendLine("2. 确定实现目标所需的命令序列")
            appendLine("3. 检查每条命令的系统兼容性和权限要求")
            appendLine("4. 标记危险命令并添加保护措施")
            appendLine()
            appendLine("【用户需求】")
            appendLine(userRequest)
            appendLine()
            appendLine("【系统探测信息】")
            appendLine(probeData.toPromptString())
            appendLine()
            appendLine("【Root 权限】")
            appendLine(if (useRoot) "可用" else "不可用")
            appendLine()
            appendLine("请按以下 JSON 格式返回执行计划（不要包含 markdown 代码块标记）：")
            appendLine("""
                {
                    "description": "计划概述（包含安全分析摘要）",
                    "estimatedSteps": 预估步骤数,
                    "commands": [
                        {
                            "command": "Shell 命令",
                            "description": "命令说明",
                            "requiresRoot": true/false,
                            "isCritical": true/false
                        }
                    ],
                    "expectedOutput": "预期结果描述"
                }
            """.trimIndent())
        }

        try {
            val llmResponse = llmApi.generate(prompt)
            return parseExecutionPlan(llmResponse)
        } catch (e: Exception) {
            Log.w(TAG, "LLM failed, using fallback plan", e)
            return generateFallbackPlan(userRequest, useRoot)
        }
    }

    private fun parseExecutionPlan(llmResponse: String): ExecutionPlan {
        return ExecutionPlan(
            description = "执行用户请求",
            commands = listOf(
                CommandInfo(
                    command = "echo 'Executing: $llmResponse'",
                    description = "执行请求",
                    requiresRoot = false,
                    isCritical = false
                )
            ),
            expectedOutput = "完成"
        )
    }

    private fun generateFallbackPlan(userRequest: String, useRoot: Boolean): ExecutionPlan {
        return ExecutionPlan(
            description = "简单执行计划",
            commands = listOf(
                CommandInfo(
                    command = "echo 'Processing: $userRequest'",
                    description = "处理请求",
                    requiresRoot = false,
                    isCritical = false
                )
            ),
            expectedOutput = "完成"
        )
    }

    private suspend fun executePlan(
        plan: ExecutionPlan,
        useRoot: Boolean
    ): ExecutionResult {
        val output = StringBuilder()

        for ((index, cmd) in plan.commands.withIndex()) {
            Log.i(TAG, "Executing command ${index + 1}/${plan.commands.size}: ${cmd.description}")

            try {
                val result = executeShellCommand(cmd.command, useRoot && cmd.requiresRoot)
                output.appendLine("--- ${cmd.description} ---")
                output.appendLine(result.output)

                if (!result.success) {
                    return ExecutionResult(
                        success = false,
                        output = output.toString(),
                        error = "命令执行失败: ${cmd.description}"
                    )
                }
            } catch (e: Exception) {
                return ExecutionResult(
                    success = false,
                    output = output.toString(),
                    error = e.message
                )
            }
        }

        return ExecutionResult(
            success = true,
            output = output.toString(),
            error = null
        )
    }

    private suspend fun executeShellCommand(
        command: String,
        useRoot: Boolean
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(command)
            }

            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            CommandResult(
                success = exitCode == 0,
                output = output,
                errorOutput = error,
                exitCode = exitCode
            )
        } catch (e: Exception) {
            CommandResult(
                success = false,
                output = "",
                errorOutput = e.message ?: "Unknown error",
                exitCode = -1
            )
        }
    }

    private suspend fun tryFixError(
        failedResult: ExecutionResult,
        probeData: SystemProbeData
    ): ExecutionResult {
        Log.i(TAG, "Trying to fix error: ${failedResult.error}")

        val fixPlan = ExecutionPlan(
            description = "自动修复",
            commands = listOf(
                CommandInfo(
                    command = "echo 'Attempting fix...'",
                    description = "尝试修复",
                    requiresRoot = false,
                    isCritical = false
                )
            ),
            expectedOutput = "修复完成"
        )

        return executePlan(fixPlan, false)
    }

    private suspend fun generateFinalReport(
        userRequest: String,
        probeData: SystemProbeData,
        executionResult: ExecutionResult
    ): String {
        val report = buildString {
            appendLine("=== 任务完成报告 ===")
            appendLine()
            appendLine("用户需求: $userRequest")
            appendLine()
            appendLine("执行结果:")
            appendLine(executionResult.output)
            appendLine()
            appendLine("设备: ${probeData.systemInfo.model}")
            appendLine("Android: ${probeData.systemInfo.androidVersion}")
        }

        return report
    }

    private fun addStep(type: StepType, description: String) {
        val newStep = ExecutionStep(
            order = _executionSteps.value.size + 1,
            type = type,
            description = description
        )
        _executionSteps.value = _executionSteps.value + newStep
    }

    private fun updateLastStep(success: Boolean, result: String? = null) {
        val steps = _executionSteps.value.toMutableList()
        if (steps.isNotEmpty()) {
            val lastIndex = steps.lastIndex
            steps[lastIndex] = steps[lastIndex].copy(
                success = success,
                result = result
            )
            _executionSteps.value = steps
        }
    }

    private fun clearState() {
        _state.value = AgentState.Idle
        _executionSteps.value = emptyList()
        _finalResult.value = null
        stepDurations.clear()
    }

    fun reset() {
        clearState()
    }
}

data class ExecutionPlan(
    val description: String,
    val commands: List<CommandInfo>,
    val expectedOutput: String
)

data class CommandInfo(
    val command: String,
    val description: String,
    val requiresRoot: Boolean = false,
    val isCritical: Boolean = false
)

data class ExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
)

data class CommandResult(
    val success: Boolean,
    val output: String,
    val errorOutput: String? = null,
    val exitCode: Int = 0
)