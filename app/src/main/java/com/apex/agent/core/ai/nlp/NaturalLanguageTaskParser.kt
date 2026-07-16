package com.apex.agent.core.ai.nlp

import com.apex.agent.core.ai.LlamaEngineInterface
import com.apex.agent.data.burstmode.config.Complexity
import com.apex.agent.data.burstmode.model.BurstTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * 自然语言任务解析器
 *
 * 将用户的自然语言输入转换为结构化 BurstTask。
 * - 优先使用 JSON 解析 LLM 响应，纯文本正则提取作为降级方案
 * - 使用 UUID 生成任务 ID
 * - 工具列表通过构造函数注入，可配置
 */
class NaturalLanguageTaskParser(
    private val promptOptimizer: com.apex.agent.core.ai.prompt.PromptOptimizer,
    private val llamaEngine: LlamaEngineInterface,
    private val availableTools: List<String> = DEFAULT_TOOLS
) {

    companion object {
        private val DEFAULT_TOOLS = listOf(
            "file_scanner", "file_reader", "file_writer",
            "code_analyzer", "web_scraper",
            "image_processor", "data_parser"
        )
    }

    /**
     * 解析自然语言为结构化任务
     */
    suspend fun parseTask(naturalLanguage: String): BurstTask = withContext(Dispatchers.IO) {
        val trimmed = naturalLanguage.trim()
        require(trimmed.isNotBlank()) { "naturalLanguage 不能为空" }

        try {
            val prompt = promptOptimizer.buildNaturalLanguageToTaskPrompt(
                userInput = trimmed,
                availableTools = availableTools
            )

            val response = llamaEngine.generate(prompt)
            val taskData = parseJsonResponse(response, trimmed)

            BurstTask(
                taskId = generateTaskId(),
                goal = taskData.goal,
                complexity = parseComplexity(taskData.estimatedComplexity),
                metadata = mapOf(
                    "original_input" to trimmed,
                    "parsed_at" to System.currentTimeMillis().toString(),
                    "steps" to taskData.steps.joinToString("|||"),
                    "required_tools" to taskData.requiredTools.joinToString(","),
                    "estimated_time_minutes" to taskData.estimatedTimeMinutes.toString(),
                    "potential_challenges" to taskData.potentialChallenges.joinToString("|||")
                )
            )

        } catch (e: Exception) {
            createFallbackTask(trimmed, e)
        }
    }

    /**
     * 批量解析多个任务
     */
    suspend fun parseTasks(inputs: List<String>): List<BurstTask> {
        return inputs.map { parseTask(it) }
    }

    /**
     * 验证解析结果的质量
     */
    fun validateParsedTask(task: BurstTask): ValidationResult {
        val issues = mutableListOf<String>()

        if (task.goal.isBlank()) {
            issues.add("任务目标为空")
        }

        val steps = getStepsFromMetadata(task)
        if (steps.isEmpty()) {
            issues.add("未定义执行步骤")
        }

        val requiredTools = getRequiredToolsFromMetadata(task)
        requiredTools.forEach { tool ->
            if (!availableTools.contains(tool)) {
                issues.add("所需工具 '$tool' 不可用")
            }
        }

        if (steps.size > 10 && task.complexity == Complexity.LOW) {
            issues.add("步骤数 (${steps.size}) 较多但复杂度标记为 LOW")
        }

        return ValidationResult(
            isValid = issues.isEmpty(),
            issues = issues,
            confidence = calculateConfidence(task, issues)
        )
    }

    /**
     * 优先 JSON 解析，失败时降级为正则提取
     */
    private fun parseJsonResponse(response: String, fallbackInput: String): TaskData {
        val trimmed = response.trim()
        // 尝试提取 JSON 块
        val jsonBlock = extractJsonBlock(trimmed)
        if (jsonBlock != null) {
            try {
                return parseJsonObject(jsonBlock)
            } catch (e: Exception) {
                // JSON 解析失败，记录并降级
            }
        }
        return extractTaskDataFromText(trimmed)
    }

    private fun extractJsonBlock(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun parseJsonObject(json: String): TaskData {
        val obj = JSONObject(json)
        return TaskData(
            goal = obj.optString("goal", "未知任务"),
            steps = obj.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList(),
            requiredTools = obj.optJSONArray("required_tools")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList(),
            estimatedComplexity = obj.optString("estimated_complexity", "MEDIUM"),
            estimatedTimeMinutes = obj.optInt("estimated_time_minutes", 10),
            potentialChallenges = obj.optJSONArray("potential_challenges")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() }
            } ?: emptyList()
        )
    }

    private fun extractTaskDataFromText(text: String): TaskData {
        val goal = extractField(text, "goal") ?: "未知任务"
        val steps = extractArray(text, "steps")
        val tools = extractArray(text, "required_tools")
        val complexity = extractField(text, "estimated_complexity") ?: "MEDIUM"
        val timeStr = extractField(text, "estimated_time_minutes") ?: "10"
        val challenges = extractArray(text, "potential_challenges")

        return TaskData(
            goal = goal,
            steps = steps,
            requiredTools = tools,
            estimatedComplexity = complexity,
            estimatedTimeMinutes = timeStr.toIntOrNull() ?: 10,
            potentialChallenges = challenges
        )
    }

    private fun extractField(text: String, fieldName: String): String? {
        val pattern = """"$fieldName"\s*:\s*"([^"]*)"""".toRegex()
        return pattern.find(text)?.groupValues?.get(1)
    }

    private fun extractArray(text: String, fieldName: String): List<String> {
        val pattern = """"$fieldName"\s*:\s*\[([^\]]*)\]""".toRegex()
        val match = pattern.find(text) ?: return emptyList()
        return match.groupValues[1]
            .split(",")
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotBlank() }
    }

    private fun parseComplexity(complexityStr: String): Complexity {
        return when (complexityStr.uppercase()) {
            "LOW" -> Complexity.LOW
            "MEDIUM" -> Complexity.MEDIUM
            "HIGH" -> Complexity.HIGH
            "EXTREME" -> Complexity.EXTREME
            else -> Complexity.MEDIUM
        }
    }

    private fun generateTaskId(): String {
        return "task_${UUID.randomUUID()}"
    }

    private fun createFallbackTask(input: String, error: Exception): BurstTask {
        return BurstTask(
            taskId = generateTaskId(),
            goal = input,
            complexity = Complexity.MEDIUM,
            metadata = mapOf(
                "fallback" to "true",
                "error" to error.message.toString(),
                "steps" to "执行: $input",
                "required_tools" to "",
                "estimated_time_minutes" to "5",
                "potential_challenges" to "解析失败: ${error.message}"
            )
        )
    }

    private fun getStepsFromMetadata(task: BurstTask): List<String> {
        val stepsStr = task.metadata["steps"] ?: return emptyList()
        return if (stepsStr.contains("|||")) {
            stepsStr.split("|||").filter { it.isNotBlank() }
        } else {
            listOf(stepsStr)
        }
    }

    private fun getRequiredToolsFromMetadata(task: BurstTask): List<String> {
        val toolsStr = task.metadata["required_tools"] ?: return emptyList()
        return if (toolsStr.isNotBlank()) {
            toolsStr.split(",").map { it.trim() }.filter { it.isNotBlank() }
        } else {
            emptyList()
        }
    }

    private fun calculateConfidence(task: BurstTask, issues: List<String>): Float {
        var confidence = 1.0f
        confidence -= issues.size * 0.1f

        val steps = getStepsFromMetadata(task)
        if (steps.size > 5) confidence -= 0.1f

        val challenges = task.metadata["potential_challenges"] ?: ""
        if (challenges.isNotBlank()) confidence -= 0.1f

        return maxOf(0.1f, minOf(1.0f, confidence))
    }
}

data class TaskData(
    val goal: String,
    val steps: List<String>,
    val requiredTools: List<String>,
    val estimatedComplexity: String,
    val estimatedTimeMinutes: Int,
    val potentialChallenges: List<String>
)

data class ValidationResult(
    val isValid: Boolean,
    val issues: List<String>,
    val confidence: Float
)