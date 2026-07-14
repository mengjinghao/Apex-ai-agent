package com.apex.agent.core.evolution

import android.content.Context
import com.apex.data.model.LogistraSkillSpecV2
import com.apex.util.AppLogger
import com.google.gson.Gson
import java.io.File

class SkillVersionManager(private val context: Context) {
    private val skillDir = File(context.filesDir, "skills_v2")
    private val gson = Gson()

    init {
        if (!skillDir.exists()) skillDir.mkdirs()
    }

    suspend fun saveSkillVersion(skill: LogistraSkillSpecV2) {
        val skillFile = File(skillDir, "${skill.skillId}_${skill.metadata.version}.json")
        skillFile.writeText(skill.toJson())
    }

    suspend fun getCandidateVersions(skillId: String): List<LogistraSkillSpecV2> {
        return skillDir.listFiles { _, name -> name.startsWith(skillId) }
            ?.mapNotNull { file ->
                LogistraSkillSpecV2.fromJson(file.readText())
            } ?: emptyList()
    }

    /**
     * 根据 A/B 测试逻辑选择一个版�?    * 70% 概率选择 Stable 版本
     * 20% 概率选择 Candidate 版本
     * 10% 概率选择 Exploration 版本
     */
    fun routeToVersion(skillId: String): LogistraSkillSpecV2? {
        val versions = skillDir.listFiles { _, name -> name.startsWith(skillId) }
            ?.mapNotNull { LogistraSkillSpecV2.fromJson(it.readText()) }
            ?: return null

        val stable = versions.filter { it.status == LogistraSkillSpecV2.SkillStatus.STABLE }
        val candidates = versions.filter { it.status == LogistraSkillSpecV2.SkillStatus.CANDIDATE }
        val exploration = versions.filter { it.status == LogistraSkillSpecV2.SkillStatus.EXPLORATION }

        val rand = Math.random()
        return when {
            rand < 0.7 && stable.isNotEmpty() -> stable.random()
            rand < 0.9 && candidates.isNotEmpty() -> candidates.random()
            exploration.isNotEmpty() -> exploration.random()
            stable.isNotEmpty() -> stable.random()
            candidates.isNotEmpty() -> candidates.random()
            else -> versions.firstOrNull()
        }
    }

    /**
     * 基于评分更新版本状�?    */
    suspend fun promoteVersions(skillId: String) {
        val allVersions = getCandidateVersions(skillId)
        if (allVersions.isEmpty()) return

        // 简单的优胜劣汰逻辑：如的Candidate 版本的平均评分超的Stable 版本 10%，则晋升
    val stable = allVersions.find { it.status == LogistraSkillSpecV2.SkillStatus.STABLE }
        val bestCandidate = allVersions
            .filter { it.status == LogistraSkillSpecV2.SkillStatus.CANDIDATE }
            .maxByOrNull { it.metadata.fitnessHistory.map { h -> h.score }.average() }

        if (stable != null && bestCandidate != null) {
            val stableScore = stable.metadata.fitnessHistory.map { it.score }.average()
            val candidateScore = bestCandidate.metadata.fitnessHistory.map { it.score }.average()

            if (candidateScore > stableScore * 1.1) {
                AppLogger.d("SkillVersionManager", "Promoting candidate ${bestCandidate.metadata.version} to STABLE for ${skillId}")
                saveSkillVersion(stable.copy(status = LogistraSkillSpecV2.SkillStatus.DEPRECATED))
                saveSkillVersion(bestCandidate.copy(status = LogistraSkillSpecV2.SkillStatus.STABLE))
            }
        } else if (stable == null && bestCandidate != null) {
             saveSkillVersion(bestCandidate.copy(status = LogistraSkillSpecV2.SkillStatus.STABLE))
        }
    }
}
