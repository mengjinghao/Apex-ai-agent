package com.apex.agent.kernel.burst

import android.content.Context
import android.util.Log
import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import java.util.concurrent.ConcurrentHashMap

class BurstPluginLoader(
    private val context: Context,
    private val scheduler: BurstTaskScheduler,
    private val llmService: ILLMService,
    private val eventBus: SkillEventBusImpl,
    private val configService: IPluginConfigService
) : IBurstPluginLoader {
    private val loadedSkills = ConcurrentHashMap<String, IBurstSkill>()
    private val manifests = ConcurrentHashMap<String, BurstSkillManifest>()
    private val skillDependencies = ConcurrentHashMap<String, List<SkillDependency>>()
    private val skillCapabilities = ConcurrentHashMap<String, List<String>>()

    override fun getSkill(skillId: String): IBurstSkill? = loadedSkills[skillId]

    override suspend fun executeSkill(skillId: String, task: BurstTask): BurstSkillResult {
        val skill = loadedSkills[skillId] ?: return BurstSkillResult(
            success = false, errorMessage = "Skill not loaded: $skillId"
        )
        return skill.execute(task)
    }

    override suspend fun loadSkill(skillId: String): IBurstSkill? {
        return loadedSkills[skillId]
    }

    override suspend fun unloadSkill(skillId: String) {
        loadedSkills[skillId]?.destroy()
        loadedSkills.remove(skillId)
        manifests.remove(skillId)
        skillDependencies.remove(skillId)
        skillCapabilities.remove(skillId)
    }

    override fun getLoadedSkills(): List<String> = loadedSkills.keys.toList()

    override fun getSkillManifest(skillId: String): BurstSkillManifest? = manifests[skillId]

    fun getSkillCapabilities(skillId: String): List<String> = skillCapabilities[skillId] ?: emptyList()

    fun getSkillDependencies(skillId: String): List<SkillDependency> = skillDependencies[skillId] ?: emptyList()

    fun findSkillsByCapability(capability: String): List<String> {
        return skillCapabilities.filter { (_, caps) -> capability in caps }.keys.toList()
    }

    fun registerSkill(skill: IBurstSkill): BurstSkillManifest {
        skill.initialize(BurstSkillContext(
            BurstKernel,
            skill.manifest.skillId,
            llmService,
            eventBus,
            configService,
            BurstKernel.getUtilityProcessor()
        ))
        val manifest = skill.manifest
        loadedSkills[manifest.skillId] = skill
        manifests[manifest.skillId] = manifest
        skillDependencies[manifest.skillId] = manifest.dependencies
        skillCapabilities[manifest.skillId] = manifest.capabilities

        eventBus.publish(
            SkillEvent(
                type = SkillEventTypes.SKILL_LOADED,
                sourceSkillId = manifest.skillId,
                payload = mapOf(
                    "name" to manifest.skillName,
                    "version" to manifest.version,
                    "capabilities" to manifest.capabilities.joinToString(",")
                )
            )
        )
        return manifest
    }

    suspend fun loadBuiltInSkills() {
        val skillClassNames = listOf(
            "com.apex.agent.plugins.burst.builtin.AdaptiveExecutionSkill",
            "com.apex.agent.plugins.burst.builtin.APIClientSkill",
            "com.apex.agent.plugins.burst.builtin.BerserkExecutionSkill",
            "com.apex.agent.plugins.burst.builtin.BruteForceUISkill",
            "com.apex.agent.plugins.burst.builtin.ChainOfThoughtSkill",
            "com.apex.agent.plugins.burst.builtin.CodeQualityAnalyzerSkill",
            "com.apex.agent.plugins.burst.builtin.ExecutionLoggerSkill",
            "com.apex.agent.plugins.burst.builtin.ExtremeReasoningSkill",
            "com.apex.agent.plugins.burst.builtin.FileSearchSkill",
            "com.apex.agent.plugins.burst.builtin.InfiniteContextSkill",
            "com.apex.agent.plugins.burst.builtin.KnowledgeGraphSkill",
            "com.apex.agent.plugins.burst.builtin.MemoryStorageSkill",
            "com.apex.agent.plugins.burst.builtin.MultiHopReasoningSkill",
            "com.apex.agent.plugins.burst.builtin.RAGPipelineSkill",
            "com.apex.agent.plugins.burst.builtin.RacingSkill",
            "com.apex.agent.plugins.burst.builtin.ReActSkill",
            "com.apex.agent.plugins.burst.builtin.RecoveryChainSkill",
            "com.apex.agent.plugins.burst.builtin.RecoverySkill",
            "com.apex.agent.plugins.burst.builtin.RedBlueAdversarialSkill",
            "com.apex.agent.plugins.burst.builtin.ReflexionSkill",
            "com.apex.agent.plugins.burst.builtin.SecurityManagerSkill",
            "com.apex.agent.plugins.burst.builtin.SelfConsistencySkill",
            "com.apex.agent.plugins.burst.builtin.SelfCorrectionSkill",
            "com.apex.agent.plugins.burst.builtin.StreamProcessorSkill",
            "com.apex.agent.plugins.burst.builtin.TaskSchedulerSkill",
            "com.apex.agent.plugins.burst.builtin.TaskGraphSkill",
            "com.apex.agent.plugins.burst.builtin.TemplateManagerSkill",
            "com.apex.agent.plugins.burst.builtin.ThinkingSkill",
            "com.apex.agent.plugins.burst.builtin.ToolFusionSkill",
            "com.apex.agent.plugins.burst.builtin.ToolRecommendationSkill",
            "com.apex.agent.plugins.burst.builtin.TreeOfThoughtsSkill"
        )

        skillClassNames.forEach { className ->
            try {
                val clazz = Class.forName(className)
                val skill = clazz.getDeclaredConstructor().newInstance() as IBurstSkill
                registerSkill(skill)
            } catch (e: Exception) {
                Log.w("BurstPluginLoader", "Failed to load skill: $className - ${e.message}")
            }
        }
    }

    fun unloadAllSkills() {
        loadedSkills.values.forEach { skill ->
            val manifest = skill.manifest
            skill.destroy()
            eventBus.publish(
                SkillEvent(
                    type = SkillEventTypes.SKILL_UNLOADED,
                    sourceSkillId = manifest.skillId,
                    payload = mapOf("name" to manifest.skillName)
                )
            )
        }
        loadedSkills.clear()
        manifests.clear()
        skillDependencies.clear()
        skillCapabilities.clear()
    }
}
