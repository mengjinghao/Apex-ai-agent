package com.apex.agent.kernel.burst.utility

import com.apex.agent.domain.model.BurstInput
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.TaskStatus
import com.apex.agent.kernel.burst.BurstKernel
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

class UtilityProcessorImpl(
    private val llmService: ILLMService,
    private val config: LLMConfig,
    private val kernel: BurstKernel
) : UtilityProcessor {

    private val records = ConcurrentLinkedQueue<UtilityRecord>()
    private val maxRecords = 5000

    override val isEnabled: Boolean
        get() = config.enableUtilityProcessor && llmService.isAvailable()

    override val modelName: String
        get() = config.utilityModelName

    override suspend fun classifyIntent(text: String): ClassificationResult {
        return executeSimple(
            UtilityTaskTemplates.CLASSIFY_INTENT,
            text,
            parseSingleWord(),
            fallback = { text.take(50) }
        )?.let { ClassificationResult(it.lowercase().trim(), 0.7f) }
            ?: ClassificationResult("unknown", 0.0f)
    }

    override suspend fun classifyTaskType(text: String): ClassificationResult {
        return executeSimple(
            UtilityTaskTemplates.CLASSIFY_TASK_TYPE,
            text,
            parseSingleWord(),
            fallback = { classifyByKeywords(text) }
        )?.let { ClassificationResult(it.lowercase().trim(), 0.7f) }
            ?: ClassificationResult("unknown", 0.0f)
    }

    override suspend fun extractEntities(text: String, schema: List<String>): EntityExtraction {
        val prompt = buildString {
            appendLine("Text: $text")
            appendLine("Schema: ${schema.joinToString(", ")}")
        }
        val result = executeSimple(
            UtilityTaskTemplates.EXTRACT_ENTITIES,
            prompt,
            parseJson(),
            fallback = { extractByRules(text, schema) }
        )
        val entities = result?.let { parseJsonMap(it) } ?: emptyMap()
        return EntityExtraction(entities, if (entities.isNotEmpty()) 0.6f else 0.0f)
    }

    override suspend fun validateOutput(text: String, formatHint: String): ValidationResult {
        val prompt = "Expected: $formatHint\nOutput: $text"
        val result = executeSimple(
            UtilityTaskTemplates.VALIDATE_OUTPUT,
            prompt,
            parseJson(),
            fallback = { """{"isValid": true, "reason": "fallback"}""" }
        )
        val map = result?.let { parseJsonMap(it) }
        return if (map != null) {
            ValidationResult(
                isValid = map["isValid"]?.toBooleanStrictOrNull() ?: true,
                reason = map["reason"] ?: ""
            )
        } else {
            ValidationResult(true, "rule-based check passed")
        }
    }

    override suspend fun cleanResponse(text: String): String {
        return executeSimple(
            UtilityTaskTemplates.CLEAN_RESPONSE,
            text.take(2000),
            { it },
            fallback = { cleanByRules(text) }
        ) ?: cleanByRules(text)
    }

    override suspend fun formatForContext(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        val prompt = "Max length: $maxLength\nInput: $text"
        return executeSimple(
            UtilityTaskTemplates.FORMAT_FOR_CONTEXT,
            prompt.take(2000),
            { it },
            fallback = { text.take(maxLength) + "..." }
        ) ?: text.take(maxLength) + "..."
    }

    override suspend fun extractToolResult(jsonRaw: String, toolName: String): String {
        val prompt = "Tool: $toolName\nResponse: ${jsonRaw.take(1500)}"
        return executeSimple(
            UtilityTaskTemplates.EXTRACT_TOOL_RESULT,
            prompt,
            { it },
            fallback = { extractToolByRules(jsonRaw, toolName) }
        ) ?: extractToolByRules(jsonRaw, toolName)
    }

    override suspend fun categorizeContent(text: String): ClassificationResult {
        return executeSimple(
            UtilityTaskTemplates.CATEGORIZE_CONTENT,
            text.take(200),
            parseSingleWord(),
            fallback = { categorizeByKeywords(text) }
        )?.let { ClassificationResult(it.lowercase().trim(), 0.6f) }
            ?: ClassificationResult("unknown", 0.0f)
    }

    override suspend fun suggestRecovery(errorMessage: String): List<String> {
        if (!isEnabled) return suggestRecoveryByRules(errorMessage)
        val prompt = UtilityTaskTemplates.SUGGEST_RECOVERY
        val startTime = System.currentTimeMillis()
        return try {
            val input = "Error: ${errorMessage.take(500)}"
            val fullPrompt = "${prompt.systemPrompt}\n\n$input"
            val result = llmService.generate(fullPrompt, prompt.maxTokens)
            val latency = System.currentTimeMillis() - startTime
            val jsonResult = parseJsonArray()(result)
            val steps = jsonResult?.let { parseJsonStringArray(it) } ?: suggestRecoveryByRules(errorMessage)
            recordCall(prompt.name, input, steps.joinToString(", "), true, latency)
            steps
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val steps = suggestRecoveryByRules(errorMessage)
            recordCall(prompt.name, errorMessage.take(500), steps.joinToString(", "), false, latency, e.message)
            steps
        }
    }

    override suspend fun generateStatus(operation: String, outcome: String): String {
        val prompt = "Operation: $operation\nOutcome: $outcome"
        return executeSimple(
            UtilityTaskTemplates.GENERATE_STATUS,
            prompt,
            { it },
            fallback = { "[INF] $operation: $outcome" }
        ) ?: "[INF] $operation: $outcome"
    }

    override suspend fun summarizeStep(stepLog: String): String {
        return executeSimple(
            UtilityTaskTemplates.SUMMARIZE_STEP,
            stepLog.take(1000),
            { it },
            fallback = { stepLog.take(100).replace('\n', ' ') + "..." }
        ) ?: stepLog.take(100).replace('\n', ' ') + "..."
    }

    override suspend fun checkCondition(condition: String, context: String): Boolean {
        val prompt = "Condition: $condition\nContext: ${context.take(500)}"
        val result = executeSimple(
            UtilityTaskTemplates.CHECK_CONDITION,
            prompt,
            parseSingleWord(),
            fallback = { "false" }
        )
        return result?.trim()?.lowercase() == "true"
    }

    override suspend fun getRecords(taskId: String): List<UtilityRecord> {
        return records.filter { it.taskId == taskId }.toList()
    }

    override suspend fun getAllRecords(): List<UtilityRecord> {
        return records.toList()
    }

    private suspend fun executeSimple(
        prompt: UtilityPrompt,
        input: String,
        parse: (String) -> String?,
        fallback: () -> String
    ): String? = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext fallback()
        val startTime = System.currentTimeMillis()
        try {
            val fullPrompt = "${prompt.systemPrompt}\n\nInput: $input"
            val result = llmService.generate(fullPrompt, prompt.maxTokens)
            val latency = System.currentTimeMillis() - startTime
            val parsed = parse(result)
            val output = parsed ?: fallback()
            recordCall(prompt.name, input, output, true, latency)
            return@withContext output
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val output = fallback()
            recordCall(prompt.name, input, output, false, latency, e.message)
            return@withContext output
        }
    }

    private fun recordCall(
        name: String,
        input: String,
        output: String,
        success: Boolean,
        latencyMs: Long,
        errorMessage: String? = null
    ) {
        val record = UtilityRecord(
            utilityName = name,
            taskId = "burst_utility",
            input = input.take(200),
            output = output.take(500),
            success = success,
            latencyMs = latencyMs,
            modelName = modelName,
            errorMessage = errorMessage ?: ""
        )
        records.add(record)
        while (records.size > maxRecords) {
            records.poll()
        }
        if (config.utilityRecordToMemory) {
            persistRecordToMemory(record)
        }
    }

    private fun persistRecordToMemory(record: UtilityRecord) {
        GlobalScope.launch {
            try {
                kernel.executeSkill("memory_storage", BurstTask(
                    id = record.toMemoryKey(),
                    name = "Store utility record",
                    description = "",
                    input = BurstInput(text = record.toMemoryText()),
                    status = TaskStatus.PENDING,
                    metadata = mapOf(
                        "operation" to "store",
                        "key" to record.toMemoryKey(),
                        "level" to "3"
                    )
                ))
            } catch (_: Exception) { }
        }
    }

    private fun parseSingleWord(): (String) -> String? = { raw ->
        raw.trim().split("\\s+".toRegex()).firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(50)
    }

    private fun parseJson(): (String) -> String? = { raw ->
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (cleaned.startsWith("{") || cleaned.startsWith("[")) cleaned else null
    }

    private fun parseJsonMap(json: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val regex = "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        regex.findAll(json).forEach { match ->
            map[match.groupValues[1]] = match.groupValues[2]
        }
        return map
    }

    private fun parseJsonArray(): (String) -> String? = { raw ->
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (cleaned.startsWith("[")) cleaned else null
    }

    private fun parseJsonStringArray(json: String): List<String> {
        val items = mutableListOf<String>()
        val regex = """"([^"]*)"""".toRegex()
        regex.findAll(json).forEach { match ->
            items.add(match.groupValues[1])
        }
        return items
    }

    private fun classifyByKeywords(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("file") || t.contains("config") || t.contains("find") || t.contains("path") -> "file"
            t.contains("code") || t.contains("function") || t.contains("write") || t.contains("implement") -> "code"
            t.contains("search") || t.contains("find") || t.contains("lookup") || t.contains("what") -> "search"
            t.contains("remember") || t.contains("store") || t.contains("save") || t.contains("memory") -> "memory"
            t.contains("check") || t.contains("analyze") || t.contains("verify") || t.contains("validate") -> "analyze"
            t.contains("run") || t.contains("click") || t.contains("execute") || t.contains("start") -> "execute"
            t.contains("plan") || t.contains("step") || t.contains("first") || t.contains("strategy") -> "plan"
            else -> "unknown"
        }
    }

    private fun categorizeByKeywords(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("def ") || t.contains("class ") || t.contains("import ") || t.contains("function") -> "code"
            t.contains("error") || t.contains("warn") || t.contains("info") || t.contains("debug") -> "log"
            t.contains("{") && (t.contains(":") || t.contains(",")) -> "data"
            t.contains(".conf") || t.contains(".ini") || t.contains("=") -> "config"
            else -> "text"
        }
    }

    private fun extractByRules(text: String, schema: List<String>): String {
        val map = mutableMapOf<String, String>()
        val lower = text.lowercase()
        schema.forEach { field ->
            val fl = field.lowercase()
            val patterns = listOf(
                Regex("""$fl[:\s]+(.+?)[,\n]""", RegexOption.IGNORE_CASE),
                Regex("""$fl[\s\S]{0,5}[:\s]+(.{0,50})""", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    map[field] = match.groupValues[1].trim().take(100)
                    break
                }
            }
        }
        return map.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
            .let { if (it.isNotEmpty()) "{$it}" else "{}" }
    }

    private fun cleanByRules(text: String): String {
        return text.trim()
            .replace(Regex("```[a-zA-Z]*\\s*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractToolByRules(json: String, toolName: String): String {
        val t = json.lowercase()
        return when {
            "stdout" in t || "output" in t -> {
                Regex(""""stdout"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
                    ?: Regex(""""output"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
                    ?: json.take(200)
            }
            "content" in t -> {
                Regex(""""content"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
                    ?: json.take(200)
            }
            "result" in t || "results" in t -> {
                Regex(""""result[s]?"\s*:\s*\[([^\]]*)\]""").find(json)?.groupValues?.get(1)
                    ?: Regex(""""result"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
                    ?: json.take(200)
            }
            else -> json.take(200)
        }
    }

    private fun suggestRecoveryByRules(error: String): List<String> {
        val e = error.lowercase()
        val suggestions = mutableListOf<String>()
        when {
            "not found" in e || "does not exist" in e || "no such" in e -> {
                suggestions.add("Verify the path or identifier")
                suggestions.add("Check if the resource exists")
            }
            "timeout" in e || "timed out" in e -> {
                suggestions.add("Increase timeout setting")
                suggestions.add("Check network connectivity")
            }
            "permission" in e || "denied" in e || "unauthorized" in e -> {
                suggestions.add("Check access permissions")
                suggestions.add("Verify authentication credentials")
            }
            "connection" in e || "refused" in e || "unreachable" in e -> {
                suggestions.add("Verify server is running")
                suggestions.add("Check network/firewall settings")
            }
            "invalid" in e || "bad" in e || "malformed" in e -> {
                suggestions.add("Validate input format")
                suggestions.add("Check parameter types and values")
            }
            "memory" in e || "out of" in e || "oom" in e -> {
                suggestions.add("Reduce memory usage")
                suggestions.add("Process data in smaller chunks")
            }
            else -> suggestions.add("Review error details and retry")
        }
        return suggestions
    }
}
