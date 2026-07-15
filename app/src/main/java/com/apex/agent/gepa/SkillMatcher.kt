package com.apex.gepa

import com.apex.agent.MainTask
import com.apex.agent.SubTask
import com.apex.data.gepa.SkillDao
import com.apex.data.gepa.SkillTemplate
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SkillMatcher(
    private val skillDao: SkillDao,
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
) {

    private val subtaskListType = Types.newParameterizedType(List::class.java, Map::class.java)
        private val subtaskAdapter = moshi.adapter<List<Map<String, Any>>>(subtaskListType)
        fun getBestMatchingSkill(mainTask: MainTask): Flow<MatchedSkill?> {
        return skillDao.getBestSkillsForType(mainTask.taskType)
            .map { skills ->
                skills.firstOrNull()?.let { skill ->
                    createMatchedSkill(skill)
                }
            }
    }
        fun getMatchingSkills(mainTask: MainTask, limit: Int = 5): Flow<List<MatchedSkill>> {
        return skillDao.getBestSkillsForType(mainTask.taskType)
            .map { skills ->
                skills.take(limit).mapNotNull { skill ->
                    createMatchedSkill(skill)
                }
            }
    }
        fun getSkillsByType(taskType: String): Flow<List<SkillTemplate>> {
        return skillDao.getSkillsByType(taskType)
    }
        fun getTopSkills(limit: Int = 10): Flow<List<SkillTemplate>> {
        return skillDao.getTopSkills(limit)
    }
        fun getHighQualitySkills(minRate: Float = 0.8f): Flow<List<SkillTemplate>> {
        return skillDao.getHighQualitySkills(minRate)
    }
        fun getRecentSkills(limit: Int = 20): Flow<List<SkillTemplate>> {
        return skillDao.getRecentSkills(limit)
    }
        fun getAllTaskTypes(): Flow<List<String>> {
        return skillDao.getAllTaskTypes()
    }
        private fun createMatchedSkill(skill: SkillTemplate): MatchedSkill? {
        return try {
            val subtaskList = deserializeSubtasks(skill.subtaskStructure)
        val suggestedSubtasks = subtaskList.mapIndexed { index, map ->
                SubTask(
                    taskId = map["taskId"] as? String ?: "subtask_${index}",
                    taskType = map["taskType"] as? String ?: "general",
                    description = map["description"] as? String ?: "",
                    inputData = (map["inputData"] as? Map<String, Any>) ?: emptyMap(),
                    dependencies = (map["dependencies"] as? List<String>) ?: emptyList(),
                    priority = (map["priority"] as? Number)?.toInt() ?: 0,
                    estimatedTime = (map["estimatedTime"] as? Number)?.toLong() ?: 0
                )
            }

            MatchedSkill(
                skill = skill,
                suggestedSubtasks = suggestedSubtasks,
                matchScore = calculateMatchScore(skill)
            )
        } catch (e: Exception) {
            null
        }
    }
        private fun deserializeSubtasks(json: String): List<Map<String, Any>> {
        return try {
            if (json.isBlank() || json == "[]") {
                emptyList()
            } else {
                subtaskAdapter.fromJson(json) ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
        private fun calculateMatchScore(skill: SkillTemplate): Double {
        val successWeight = 0.6
        val usageWeight = 0.3
        val recencyWeight = 0.1

        val successScore = skill.successRate.toDouble()
        val usageScore = (skill.totalExecutions.coerceAtMost(100) / 100.0)
        val ageInDays = (System.currentTimeMillis() - skill.updatedAt) / (1000 * 60 * 60 * 24)
        val recencyScore = (7 - ageInDays.coerceAtMost(7).toDouble()) / 7.0

        return (successScore * successWeight) +
            (usageScore * usageWeight) +
            (recencyScore * recencyWeight)
    }
        fun getMatchConfidence(matchedSkill: MatchedSkill): MatchConfidence {
        return when {
            matchedSkill.matchScore >= 0.8 -> MatchConfidence.HIGH
            matchedSkill.matchScore >= 0.5 -> MatchConfidence.MEDIUM
            matchedSkill.matchScore >= 0.3 -> MatchConfidence.LOW
            else -> MatchConfidence.VERY_LOW
        }
    }
        fun shouldUseTemplate(matchedSkill: MatchedSkill?, minConfidence: MatchConfidence = MatchConfidence.MEDIUM): Boolean {
        if (matchedSkill == null) return false

        val confidence = getMatchConfidence(matchedSkill)
        return when (minConfidence) {
            MatchConfidence.HIGH -> confidence == MatchConfidence.HIGH
            MatchConfidence.MEDIUM -> confidence == MatchConfidence.HIGH || confidence == MatchConfidence.MEDIUM
            MatchConfidence.LOW -> confidence != MatchConfidence.VERY_LOW
            MatchConfidence.VERY_LOW -> true
        }
    }
}

data class MatchedSkill(
    val skill: SkillTemplate,
    val suggestedSubtasks: List<SubTask>,
    val matchScore: Double = 0.0
)

enum class MatchConfidence {
    VERY_LOW,
    LOW,
    MEDIUM,
    HIGH
}
