package com.apex.agent.core.skills

import android.content.Context
import com.apex.data.model.ApexAgentSkillSpec
import com.apex.util.AppLogger
import com.apex.agent.util.ApexAgentPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/**
 * 技能演化管理器
 * 负责从智能体行为中萃取技能并支持技能的自我迭代优化
 */
class SkillEvolutionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SkillEvolutionManager"
    }
        private val skillDir: File
    
    init {
        // 初始化技能存储目�?      skillDir = File(ApexAgentPaths.getSkillsDir(context))
        if (!skillDir.exists()) {
            skillDir.mkdirs()
            AppLogger.d(TAG, "Created skills directory: ${skillDir.absolutePath}")
        }
    }
    
    /**
     * 从智能体行为轨迹中萃取结构化技�?    * @param agentBehavior 智能体执行任务的行为步骤
     * @param taskType 任务类型
     * @param errorCases 踩坑案例（可选）
     * @return 技能文件路�?   */
    suspend fun extractSkill(
        agentBehavior: List<String>,
        taskType: String,
        errorCases: List<String>? = null
    ): String = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Extracting skill for task type: ${taskType}")
        
        // 生成技能ID
    val skillId = "${taskType}_${Date().time}"
        
        // 提炼可复用操作方案（简化版，实际应该用LLM�?
    val operationSteps = agentBehavior.map { "${agentBehavior.indexOf(it) + 1}. ${it}" }
        val applicableScenarios = listOf(
            "�?{taskType任务执行}",
            "类似�?{taskType的场的},
            "需要{agentBehavior.size}个步骤的任务"
        )
        
        // 创建技能规�?
    val skill = ApexAgentSkillSpec(
            skillId = skillId,
            taskType = taskType,
            operationSteps = operationSteps.toMutableList(),
            applicableScenarios = applicableScenarios.toMutableList(),
            errorCases = (errorCases ?: emptyList()).toMutableList(),
            updateTimestamp = Date().toString()
        )
        
        // 保存技能文�?
    val skillFile = File(skillDir, "${skillId}.json")
        skillFile.writeText(skill.toJson(), Charsets.UTF_8)
        
        AppLogger.d(TAG, "Extracted skill saved to: ${skillFile.absolutePath}")
        skillFile.absolutePath
    }
    
    /**
     * 技能自我迭代优�?    * @param skillId 待迭代的技能ID
     * @param newBehavior 新的执行行为
     * @param newErrorCases 新的踩坑案例
     * @return 迭代后的技能文件路�?   */
    suspend fun evolveSkill(
        skillId: String,
        newBehavior: List<String>,
        newErrorCases: List<String>
    ): String = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "Evolving skill: ${skillId}")
        
        // 加载原有技�?
    val skillFile = File(skillDir, "${skillId}.json")
        if (!skillFile.exists()) {
            throw java.io.FileNotFoundException("Skill ${skillId} not found")
        }
        val skillJson = skillFile.readText(Charsets.UTF_8)
        val skill = ApexAgentSkillSpec.fromJson(skillJson) ?: 
            throw java.lang.IllegalArgumentException("Invalid skill file format")
        
        // 融合新行为优化技能（简化版，实际应该用LLM�?
    val optimizedSteps = skill.operationSteps.toMutableList()
        newBehavior.forEach { behavior ->
            if (!optimizedSteps.contains(behavior)) {
                optimizedSteps.add("${optimizedSteps.size + 1}. ${behavior}")
            }
        }
        val optimizedScenarios = skill.applicableScenarios.toMutableList()
        optimizedScenarios.add("优化后的${skill}.taskType场景")
        
        // 更新技�?       skill.operationSteps = optimizedSteps
        skill.applicableScenarios = optimizedScenarios
        skill.errorCases.addAll(newErrorCases)
        skill.incrementVersion()
        
        // 保存迭代后的技�?       skillFile.writeText(skill.toJson(), Charsets.UTF_8)
        
        AppLogger.d(TAG, "Evolved skill saved to: ${skillFile.absolutePath}, version: ${skill.version}")
        skillFile.absolutePath
    }
    
    /**
     * 获取所有技�?    * @return 技能列�?   */
    suspend fun getAllSkills(): List<ApexAgentSkillSpec> = withContext(Dispatchers.IO) {
        val skills = mutableListOf<ApexAgentSkillSpec>()
        
        skillDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                try {
                    val json = file.readText(Charsets.UTF_8)
        val skill = ApexAgentSkillSpec.fromJson(json)
        if (skill != null) {
                        skills.add(skill)
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Failed to load skill file: ${file.name}", e)
                }
            }
        }
        
        skills
    }
    
    /**
     * 获取指定技�?    * @param skillId 技能ID
     * @return 技能规�?    */
    suspend fun getSkill(skillId: String): ApexAgentSkillSpec? = withContext(Dispatchers.IO) {
        val skillFile = File(skillDir, "${skillId}.json")
        if (!skillFile.exists()) {
            return@withContext null
        }
        
        try {
            val json = skillFile.readText(Charsets.UTF_8)
            ApexAgentSkillSpec.fromJson(json)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load skill: ${skillId}", e)
            null
        }
    }
    
    /**
     * 删除技�?    * @param skillId 技能ID
     * @return 是否删除成功
     */
    suspend fun deleteSkill(skillId: String): Boolean = withContext(Dispatchers.IO) {
        val skillFile = File(skillDir, "${skillId}.json")
        if (skillFile.exists()) {
            skillFile.delete()
            AppLogger.d(TAG, "Deleted skill: ${skillId}")
            true
        } else {
            false
        }
    }
}
