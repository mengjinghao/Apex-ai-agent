package com.apex.agent.core.normal.mac

import java.util.concurrent.ConcurrentHashMap

/**
 * F8: 工具调用宏（Tool Macro）
 *
 * 用户可定义"宏"= 工具调用序列（如"读文件→分析→写报告"），
 * 保存为 ToolMacro。对话中说"用我的分析宏处理 X"即可一键执行。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的"宏"是 Agent 协作
 * - 狂暴是策略链
 * - 本功能是**用户自定义工具序列**，体现单 Agent 的用户控制
 */

/**
 * 宏步骤
 */
data class MacroStep(
    val id: String,
    val toolName: String,
    val displayName: String,
    val arguments: Map<String, MacroValue>,
    val description: String = "",
    val condition: String? = null,  // 执行条件表达式
    val onSuccess: String? = null,  // 成功后动作（continue/stop/skip_next）
    val onFailure: String? = "stop" // 失败后动作（continue/stop/retry）
)

/**
 * 宏值（支持固定值或引用上一步输出）
 */
sealed class MacroValue {
    data class Literal(val value: String) : MacroValue()
    data class Reference(val stepId: String, val jsonPath: String? = null) : MacroValue()
    data class InputParam(val paramName: String) : MacroValue()
    data class Template(val template: String) : MacroValue()  // 支持 ${param} 和 ${step.output}

    fun resolve(inputs: Map<String, String>, stepOutputs: Map<String, Any>): String = when (this) {
        is Literal -> value
        is Reference -> {
            val output = stepOutputs[stepId]
            if (jsonPath.isNullOrBlank()) output?.toString() ?: ""
            else resolveJsonPath(output, jsonPath)
        }
        is InputParam -> inputs[paramName] ?: ""
        is Template -> {
            var result = template
            inputs.forEach { (k, v) -> result = result.replace("\${$k}", v) }
            stepOutputs.forEach { (k, v) ->
                result = result.replace("\${$k.output}", v?.toString() ?: "")
                result = result.replace("\${$k}", v?.toString() ?: "")
            }
            result
        }
    }

    private fun resolveJsonPath(obj: Any?, path: String): String {
        var current: Any? = obj
        for (seg in path.split(".")) {
            current = when (current) {
                is Map<*, *> -> current[seg]
                else -> return ""
            }
        }
        return current?.toString() ?: ""
    }
}

/**
 * 工具宏定义
 */
data class ToolMacro(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val steps: List<MacroStep>,
    val inputParams: List<MacroParam> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val usageCount: Int = 0,
    val enabled: Boolean = true
)

/**
 * 宏参数定义
 */
data class MacroParam(
    val name: String,
    val displayName: String,
    val type: ParamType,
    val required: Boolean = true,
    val defaultValue: String? = null,
    val description: String = ""
)

enum class ParamType { STRING, NUMBER, FILE_PATH, URL, JSON, BOOLEAN }

/**
 * 宏执行结果
 */
data class MacroExecutionResult(
    val macroId: String,
    val success: Boolean,
    val stepResults: Map<String, Any>,
    val finalOutput: Any? = null,
    val durationMs: Long,
    val error: String? = null,
    val executedSteps: Int
)

/**
 * 工具宏执行器
 */
class ToolMacroExecutor(
    private val toolExecutor: suspend (toolName: String, args: Map<String, String>) -> Any
) {

    /**
     * 执行宏
     */
    suspend fun execute(macro: ToolMacro, inputs: Map<String, String>): MacroExecutionResult {
        val start = System.currentTimeMillis()
        val stepOutputs = mutableMapOf<String, Any>()
        var executedSteps = 0

        try {
            // 校验必填参数
            val missingParams = macro.inputParams.filter { param ->
                param.required && inputs[param.name].isNullOrBlank() && param.defaultValue.isNullOrBlank()
            }
            if (missingParams.isNotEmpty()) {
                return MacroExecutionResult(
                    macroId = macro.id,
                    success = false,
                    stepResults = emptyMap(),
                    durationMs = System.currentTimeMillis() - start,
                    error = "缺少必填参数: ${missingParams.joinToString { it.name }}",
                    executedSteps = 0
                )
            }

            // 合并默认值
            val effectiveInputs = macro.inputParams.associate { param ->
                param.name to (inputs[param.name] ?: param.defaultValue ?: "")
            } + inputs

            // 按顺序执行步骤
            for (step in macro.steps) {
                // 检查条件
                if (step.condition != null && !evaluateCondition(step.condition, effectiveInputs, stepOutputs)) {
                    continue
                }

                // 解析参数
                val resolvedArgs = step.arguments.mapValues { (_, v) ->
                    v.resolve(effectiveInputs, stepOutputs)
                }

                // 执行工具
                val result = try {
                    toolExecutor(step.toolName, resolvedArgs)
                } catch (e: Exception) {
                    when (step.onFailure ?: "stop") {
                        "continue" -> {
                            stepOutputs[step.id] = mapOf("error" to (e.message ?: ""))
                            executedSteps++
                            continue
                        }
                        "retry" -> {
                            // 简化：重试一次
                            toolExecutor(step.toolName, resolvedArgs)
                        }
                        else -> throw e  // stop
                    }
                }

                stepOutputs[step.id] = result
                executedSteps++

                // 检查成功后动作
                when (step.onSuccess) {
                    "stop" -> break
                    "skip_next" -> continue  // 跳过下一个
                    else -> { /* continue 正常 */ }
                }
            }

            val finalOutput = stepOutputs[macro.steps.last().id]

            return MacroExecutionResult(
                macroId = macro.id,
                success = true,
                stepResults = stepOutputs,
                finalOutput = finalOutput,
                durationMs = System.currentTimeMillis() - start,
                executedSteps = executedSteps
            )
        } catch (e: Exception) {
            return MacroExecutionResult(
                macroId = macro.id,
                success = false,
                stepResults = stepOutputs,
                durationMs = System.currentTimeMillis() - start,
                error = e.message,
                executedSteps = executedSteps
            )
        }
    }

    private fun evaluateCondition(
        condition: String,
        inputs: Map<String, String>,
        outputs: Map<String, Any>
    ): Boolean {
        // 简化条件求值：支持 ${param} == 'value' 格式
        val regex = Regex("\\$\\{([^}]+)}\\s*(==|!=)\\s*'([^']+)'")
        val match = regex.find(condition) ?: return true
        val (ref, op, value) = match.destructured
        val parts = ref.split(".")
        val actual: String = if (parts.size == 1) {
            inputs[parts[0]] ?: (outputs[parts[0]]?.toString() ?: "")
        } else {
            outputs[parts[0]]?.let { resolveJsonPath(it, parts.drop(1).joinToString(".")) } ?: ""
        }
        return when (op) {
            "==" -> actual == value
            "!=" -> actual != value
            else -> true
        }
    }

    private fun resolveJsonPath(obj: Any?, path: String): String {
        var current: Any? = obj
        for (seg in path.split(".")) {
            current = when (current) {
                is Map<*, *> -> current[seg]
                else -> return ""
            }
        }
        return current?.toString() ?: ""
    }
}

/**
 * 工具宏注册表
 */
class ToolMacroRegistry {

    private val macros = ConcurrentHashMap<String, ToolMacro>()

    /**
     * 注册宏
     */
    fun register(macro: ToolMacro): ToolMacro {
        macros[macro.id] = macro
        return macro
    }

    /**
     * 创建宏
     */
    fun create(
        name: String,
        displayName: String,
        description: String,
        steps: List<MacroStep>,
        inputParams: List<MacroParam> = emptyList(),
        tags: List<String> = emptyList()
    ): ToolMacro {
        val macro = ToolMacro(
            id = "macro_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            name = name,
            displayName = displayName,
            description = description,
            steps = steps,
            inputParams = inputParams,
            tags = tags
        )
        return register(macro)
    }

    /**
     * 按名称查找
     */
    fun findByName(name: String): ToolMacro? = macros.values.find { it.name == name }

    /**
     * 按 ID 查找
     */
    fun get(id: String): ToolMacro? = macros[id]

    /**
     * 列出所有
     */
    fun list(tag: String? = null): List<ToolMacro> {
        return macros.values
            .filter { it.enabled }
            .filter { tag == null || tag in it.tags }
            .sortedByDescending { it.usageCount }
            .toList()
    }

    /**
     * 删除
     */
    fun delete(id: String): Boolean = macros.remove(id) != null

    /**
     * 增加使用计数
     */
    fun incrementUsage(id: String) {
        macros[id]?.let { macro ->
            macros[id] = macro.copy(usageCount = macro.usageCount + 1)
        }
    }

    /**
     * 预置一些常用宏
     */
    fun registerBuiltinMacros() {
        // 文件分析宏
        create(
            name = "analyze_file",
            displayName = "分析文件",
            description = "读取文件 → 分析内容 → 生成报告",
            steps = listOf(
                MacroStep(
                    id = "read",
                    toolName = "read_file",
                    displayName = "读取文件",
                    arguments = mapOf("path" to MacroValue.InputParam("file_path"))
                ),
                MacroStep(
                    id = "analyze",
                    toolName = "ai_analyze",
                    displayName = "AI 分析",
                    arguments = mapOf(
                        "content" to MacroValue.Reference("read"),
                        "task" to MacroValue.Literal("分析内容并提取关键信息")
                    )
                ),
                MacroStep(
                    id = "report",
                    toolName = "write_file",
                    displayName = "写报告",
                    arguments = mapOf(
                        "path" to MacroValue.Template("\${file_path}.report.md"),
                        "content" to MacroValue.Reference("analyze")
                    )
                )
            ),
            inputParams = listOf(
                MacroParam("file_path", "文件路径", ParamType.FILE_PATH, description = "要分析的文件路径")
            ),
            tags = listOf("文件", "分析")
        )

        // 翻译宏
        create(
            name = "translate",
            displayName = "翻译文本",
            description = "读取文本 → 翻译 → 写入",
            steps = listOf(
                MacroStep(
                    id = "translate",
                    toolName = "ai_translate",
                    displayName = "AI 翻译",
                    arguments = mapOf(
                        "text" to MacroValue.InputParam("text"),
                        "target_lang" to MacroValue.InputParam("target_lang")
                    )
                )
            ),
            inputParams = listOf(
                MacroParam("text", "待翻译文本", ParamType.STRING),
                MacroParam("target_lang", "目标语言", ParamType.STRING, defaultValue = "中文")
            ),
            tags = listOf("翻译")
        )
    }
}
