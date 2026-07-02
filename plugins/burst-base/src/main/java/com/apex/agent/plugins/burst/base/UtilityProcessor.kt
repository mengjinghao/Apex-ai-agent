package com.apex.agent.plugins.burst.base

data class UtilityRecord(
    val id: String = java.util.UUID.randomUUID().toString(),
    val utilityName: String,
    val taskId: String,
    val input: String,
    val output: String,
    val success: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long = 0,
    val modelName: String = "",
    val errorMessage: String = ""
) {
    fun toMemoryKey(): String = "utility_${utilityName}_${id}"
    fun toMemoryText(): String = buildString {
        appendLine("=== Utility Record: $utilityName ===")
        appendLine("Time: $timestamp")
        appendLine("Task: $taskId")
        appendLine("Input: $input")
        appendLine("Output: $output")
        appendLine("Success: $success")
        appendLine("Latency: ${latencyMs}ms")
        if (errorMessage.isNotEmpty()) appendLine("Error: $errorMessage")
    }
}

data class ValidationResult(
    val isValid: Boolean,
    val reason: String = "",
    val suggestions: List<String> = emptyList()
)

data class EntityExtraction(
    val entities: Map<String, String>,
    val confidence: Float = 1.0f
)

data class ClassificationResult(
    val label: String,
    val confidence: Float = 1.0f
)

interface UtilityProcessor {
    val isEnabled: Boolean
    val modelName: String

    suspend fun classifyIntent(text: String): ClassificationResult
    suspend fun classifyTaskType(text: String): ClassificationResult
    suspend fun extractEntities(text: String, schema: List<String>): EntityExtraction
    suspend fun validateOutput(text: String, formatHint: String): ValidationResult
    suspend fun cleanResponse(text: String): String
    suspend fun formatForContext(text: String, maxLength: Int): String
    suspend fun extractToolResult(jsonRaw: String, toolName: String): String
    suspend fun categorizeContent(text: String): ClassificationResult
    suspend fun suggestRecovery(errorMessage: String): List<String>
    suspend fun generateStatus(operation: String, outcome: String): String
    suspend fun summarizeStep(stepLog: String): String
    suspend fun checkCondition(condition: String, context: String): Boolean
    suspend fun getRecords(taskId: String): List<UtilityRecord>
    suspend fun getAllRecords(): List<UtilityRecord>
}
