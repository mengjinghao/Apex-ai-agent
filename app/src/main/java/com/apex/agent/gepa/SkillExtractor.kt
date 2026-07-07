package com.apex.gepa

import com.apex.agent.MainTask
import com.apex.agent.SubTask
import com.apex.agent.TaskResult
import com.apex.data.gepa.SkillDao
import com.apex.data.gepa.SkillTemplate
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SkillExtractor(
    private val skillDao: SkillDao,
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
) {

    private val subtaskListType = Types.newParameterizedType(List::class.java, Map::class.java)
    private val subtaskAdapter = moshi.adapter<List<Map<String, Any>>>(subtaskListType)

    suspend fun extractAndSaveSkill(
        mainTask: MainTask,
        subtasks: List<SubTask>,
        taskResult: TaskResult
    ): SkillExtractionResult = withContext(Dispatchers.IO) {
        try {
            val successRate = if (taskResult.subtaskResults.isNotEmpty()) {
                taskResult.subtaskResults.count { it.success }.toFloat() / taskResult.subtaskResults.size
            } else {
                0f
            }

            val avgExecutionTime = if (taskResult.subtaskResults.isNotEmpty()) {
                taskResult.totalExecutionTime / taskResult.subtaskResults.size
            } else {
                0L
            }

            val subtaskJson = serializeSubtasks(subtasks)

            val skill = SkillTemplate(
                taskType = mainTask.taskType,
                taskDescription = mainTask.description,
                subtaskStructure = subtaskJson,
                successRate = successRate,
                executionTime = avgExecutionTime,
                totalExecutions = 1,
                successfulExecutions = if (taskResult.success) 1 else 0,
                tags = generateTags(mainTask.taskType, subtasks)
            )

            val id = skillDao.insertSkill(skill)

            SkillExtractionResult(
                success = true,
                skillId = id.toInt(),
                successRate = successRate,
                message = "Skill extracted and saved successfully"
            )
        } catch (e: Exception) {
            SkillExtractionResult(
                success = false,
                successRate = 0f,
                message = "Failed to extract skill: ${e.message}"
            )
        }
    }

    suspend fun updateExistingSkill(
        skillId: Int,
        taskResult: TaskResult
    ): SkillExtractionResult = withContext(Dispatchers.IO) {
        try {
            val existingSkill = skillDao.getSkillById(skillId)
                ?: return@withContext SkillExtractionResult(
                    success = false,
                    successRate = 0f,
                    message = "Skill not found: ${skillId}"
                )

            val newTotalExecutions = existingSkill.totalExecutions + 1
            val newSuccessfulExecutions = existingSkill.successfulExecutions + if (taskResult.success) 1 else 0
            val newSuccessRate = newSuccessfulExecutions.toFloat() / newTotalExecutions

            skillDao.updateSkillStats(
                id = skillId,
                successRate = newSuccessRate,
                totalExecutions = newTotalExecutions,
                successfulExecutions = newSuccessfulExecutions
            )

            SkillExtractionResult(
                success = true,
                skillId = skillId,
                successRate = newSuccessRate,
                message = "Skill stats updated successfully"
            )
        } catch (e: Exception) {
            SkillExtractionResult(
                success = false,
                successRate = 0f,
                message = "Failed to update skill: ${e.message}"
            )
        }
    }

    suspend fun extractAndMergeSkill(
        mainTask: MainTask,
        subtasks: List<SubTask>,
        taskResult: TaskResult
    ): SkillExtractionResult = withContext(Dispatchers.IO) {
        try {
            val existingSkills = skillDao.getBestSkillsForType(mainTask.taskType).first()

            if (existingSkills.isNotEmpty()) {
                val bestSkill = existingSkills.first()
                return@withContext updateExistingSkill(bestSkill.id, taskResult)
            } else {
                return@withContext extractAndSaveSkill(mainTask, subtasks, taskResult)
            }
        } catch (e: Exception) {
            SkillExtractionResult(
                success = false,
                successRate = 0f,
                message = "Failed to merge skill: ${e.message}"
            )
        }
    }

    private fun serializeSubtasks(subtasks: List<SubTask>): String {
        val subtaskMaps = subtasks.map { subtask ->
            mapOf(
                "taskId" to subtask.taskId,
                "taskType" to subtask.taskType,
                "description" to subtask.description,
                "inputData" to subtask.inputData,
                "dependencies" to subtask.dependencies,
                "priority" to subtask.priority,
                "estimatedTime" to subtask.estimatedTime
            )
        }

        return try {
            subtaskAdapter.toJson(subtaskMaps)
        } catch (e: Exception) {
            "[]"
        }
    }

    private fun generateTags(taskType: String, subtasks: List<SubTask>): String {
        val tags = mutableSetOf<String>()
        tags.add(taskType)

        subtasks.forEach { subtask ->
            tags.add(subtask.taskType)
        }

        if (subtasks.size > 3) {
            tags.add("complex")
        }

        if (taskResult?.success == true) {
            tags.add("verified")
        }

        return tags.joinToString(",")
    }

    suspend fun cleanupLowQualitySkills(minSuccessRate: Float = 0.3f): Int =
        withContext(Dispatchers.IO) {
            try {
                skillDao.deleteLowQualitySkills(minSuccessRate)
                0
            } catch (e: Exception) {
                -1
            }
        }
}

data class SkillExtractionResult(
    val success: Boolean,
    val skillId: Int = 0,
    val successRate: Float,
    val message: String
)
