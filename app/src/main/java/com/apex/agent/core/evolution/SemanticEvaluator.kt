package com.apex.agent.core.evolution

import android.content.Context
import com.apex.agent.api.chat.EnhancedAIService
import com.apex.agent.core.chat.hooks.PromptTurn
import com.apex.agent.core.chat.hooks.PromptTurnKind
import com.apex.util.AppLogger
import com.apex.data.model.FunctionType
import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import java.lang.StringBuilder

data class SemanticEvaluationResult(
    val score: Float,
    val success: Boolean,
    val failureReason: String?,
    val improvementSuggestions: List<String>,
    val detectedPatterns: List<String>
)

class SemanticEvaluator(private val context: Context) {
    private val aiService by lazy { EnhancedAIService.getInstance(context) }
    private val gson = Gson()

    companion object {
        private const val TAG = "SemanticEvaluator"
    }

    suspend fun evaluateExecution(
        taskGoal: String,
        executionLogs: List<String>,
        finalOutput: String
    ): SemanticEvaluationResult {
        val prompt = """
            请作为自进化系统�?语义评估�?，分析以下任务的执行情况�?

            任务目标�?
            ${taskGoal}

            执行日志�?
            ${executionLogs.joinToString("\n")}

            最终输出：
            ${finalOutput}

            请回答以下核心问题：
            1. 任务目标是否真正达成？（给出0-10的评分，10为完美达成）
            2. 如果未达成或有瑕疵，失败的根本原因是什么？
            3. 应该如何改进该技能的逻辑或参数？请给出具体的建议�?
            4. 识别到的执行模式（例如：重复搜索、无效工具调用等）�?

            请以JSON格式返回结果，包含以下字段：
            {
              "score": Float,
              "success": Boolean,
              "failure_reason": String,
              "improvement_suggestions": [String],
              "detected_patterns": [String]
            }
        """.trimIndent()

        return try {
            val fullResponse = StringBuilder()
            aiService.sendMessage(
                message = prompt,
                functionType = FunctionType.CHAT,
                stream = false,
                maxTokens = 1000,
                tokenUsageThreshold = 0.9f
            ).collect { fullResponse.append(it) }

            val jsonOutput = extractJson(fullResponse.toString())
            parseResult(jsonOutput)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Semantic evaluation failed", e)
            fallbackResult()
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start != -1 && end != -1) {
            text.substring(start, end + 1)
        } else {
            text
        }
    }

    private fun parseResult(json: String): SemanticEvaluationResult {
        return try {
            val map = gson.fromJson(json, Map::class.java)
            SemanticEvaluationResult(
                score = (map["score"] as? Number)?.toFloat() ?: 0f,
                success = map["success"] as? Boolean ?: false,
                failureReason = map["failure_reason"] as? String,
                improvementSuggestions = (map["improvement_suggestions"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
                detectedPatterns = (map["detected_patterns"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            )
        } catch (e: Exception) {
            fallbackResult()
        }
    }

    private fun fallbackResult() = SemanticEvaluationResult(
        score = 0f,
        success = false,
        failureReason = "Evaluation failed to parse",
        improvementSuggestions = emptyList(),
        detectedPatterns = emptyList()
    )
}
