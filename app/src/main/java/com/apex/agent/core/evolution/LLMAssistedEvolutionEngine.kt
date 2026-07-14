package com.apex.agent.core.evolution

import android.content.Context
import com.apex.agent.api.chat.EnhancedAIService
import com.apex.data.model.FitnessRecord
import com.apex.data.model.EvolutionNode
import com.apex.data.model.EvolutionNodeType
import com.apex.data.model.EvolutionMetadata
import com.apex.data.model.LogistraSkillSpecV2
import com.apex.util.AppLogger
import com.google.gson.Gson
import kotlinx.coroutines.flow.toList
import com.apex.agent.core.tools.defaultTool.standard.name

class LLMAssistedEvolutionEngine(
    private val context: Context,
    private val evaluator: SemanticEvaluator
) {
    private val aiService by lazy { EnhancedAIService.getInstance(context) }
    private val gson = Gson()

    companion object {
        private const val TAG = "LLMAssistedEvolution"
    }

    suspend fun evolveSkill(
        currentSkill: LogistraSkillSpecV2,
        taskGoal: String,
        executionLogs: List<String>,
        finalOutput: String,
        evaluationResult: SemanticEvaluationResult
    ): LogistraSkillSpecV2 {
        // 只有分数低于 8.0 才触的LLM 进化
    if (evaluationResult.score >= 8.0f) {
            AppLogger.d(TAG, "Score ${evaluationResult.score} is high enough, skipping LLM evolution")
            return currentSkill
        }

        val prompt = """
            作为自进化系统的"技能重构官"，请根据以下信息优化或重写技能规格：

            当前技能：
            ${gson.toJson(currentSkill.rootNode)}

            任务目标�?            ${taskGoal}

            本次执行评估�?            - 评分析{evaluationResult.score}/10
            - 成功能{evaluationResult.success}
            - 失败原因的{evaluationResult.failureReason}
            - 改进建议的{evaluationResult.improvementSuggestions.joinToString(", ")}
            - 发现模方式{evaluationResult.detectedPatterns.joinToString(", ")}

            请以 JSON 格式返回优化后的 EvolutionNode 结构。支持以下节点类型：
            - ACTION：执行某个工�?content = 工具名，parameters = 参数�?
            - CONDITION：条件判�?content = 判断描述，children = [true分支, false分支])
            - LOOP：循�?content = 循环描述，children = 循环�?
            - PARALLEL：并行执�?children = 多个分支�?
            - COMPOSITE：复合技�?content = 技能ID)

            返回格方�?            {
              "skill": {
                "name": String,
                "description": String,
                "rootNode": EvolutionNode
              }
            }

            优化原则�?            1. 调整动作参数使其更合�?            2. 增加必要的条件判断或循环
            3. 移除无效或冗余步�?            4. 保持逻辑的清晰可理解
        """.trimIndent()

        return try {
            val fullResponse = StringBuilder()
            aiService.sendMessage(
                message = prompt,
                functionType = com.apex.data.model.FunctionType.CHAT,
                stream = false,
                maxTokens = 2000,
                tokenUsageThreshold = 0.9f
            ).collect { fullResponse.append(it) }

            val newSkill = parseLLMResponse(fullResponse.toString(), currentSkill)
            AppLogger.d(TAG, "Generated new skill version: ${newSkill.metadata.version}")
            newSkill
        } catch (e: Exception) {
            AppLogger.e(TAG, "LLM evolution failed", e)
            currentSkill
        }
    }

    private fun parseLLMResponse(
        response: String,
        original: LogistraSkillSpecV2
    ): LogistraSkillSpecV2 {
        val jsonStart = response.indexOf("{")
        val jsonEnd = response.lastIndexOf("}")
        val json = if (jsonStart != -1 && jsonEnd != -1) {
            response.substring(jsonStart, jsonEnd + 1)
        } else {
            response
        }

        val map = try {
            gson.fromJson(json, Map::class.java)
        } catch (e: Exception) {
            return original.copy(
                metadata = original.metadata.copy(
                    version = incrementVersion(original.metadata.version)
                )
            )
        }

        val skillSection = map["skill"] as? Map<*, *> ?: return original

        val newName = (skillSection["name"] as? String) ?: original.name
        val newDescription = (skillSection["description"] as? String) ?: original.description
        val newRootNode = (skillSection["rootNode"] as? Map<*, *>)?.let { parseNode(it) }
            ?: original.rootNode

        return LogistraSkillSpecV2(
            skillId = original.skillId,
            name = newName,
            description = newDescription,
            rootNode = newRootNode,
            metadata = EvolutionMetadata(
                version = incrementVersion(original.metadata.version),
                parentVersion = original.metadata.version,
                evolutionMethod = "LLM_MUTATION"
            ),
            status = LogistraSkillSpecV2.SkillStatus.CANDIDATE,
            taskType = original.taskType,
            tags = original.tags
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNode(nodeMap: Map<*, *>): EvolutionNode {
        val id = (nodeMap["id"] as? String) ?: "node_${System.currentTimeMillis()}"
        val typeStr = (nodeMap["type"] as? String) ?: "ACTION"
        val type = EvolutionNodeType.values().find {
            it.name.equals(typeStr, ignoreCase = true)
        } ?: EvolutionNodeType.ACTION
        val content = (nodeMap["content"] as? String) ?: ""
        val parameters = (nodeMap["parameters"] as? Map<String, Any?>) ?: emptyMap()
        val children = (nodeMap["children"] as? List<Map<*, *>>)?.map { parseNode(it) } ?: emptyList()

        return EvolutionNode(
            id = id,
            type = type,
            content = content,
            parameters = parameters,
            children = children
        )
    }

    private fun incrementVersion(current: String): String {
        val parts = current.split(".")
        if (parts.size == 3) {
            val major = parts[0].toIntOrNull() ?: 1
            val minor = parts[1].toIntOrNull() ?: 0
            val patch = (parts[2].toIntOrNull() ?: 0) + 1
            return "${major}.${minor}.${patch}"
        }
        return "1.0.${System.currentTimeMillis()}"
    }
}
