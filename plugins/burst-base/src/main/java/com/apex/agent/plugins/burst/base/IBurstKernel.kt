package com.apex.agent.plugins.burst.base

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.domain.model.KernelState

/**
 * 内核提供给插件的服务接口
 * 插件通过 BurstSkillContext 获取此接口实例
 */
interface IBurstKernel {
    fun getState(): KernelState
    fun getPluginLoader(): IBurstPluginLoader
    fun getStateManager(): IBurstStateManager
    fun getUtilityProcessor(): UtilityProcessor?
    fun reportSkillResult(skillId: String, result: BurstSkillResult)
    suspend fun executeSkill(skillId: String, task: BurstTask): BurstSkillResult
    fun getAvailableSkills(): List<BurstSkillManifest>
    fun getSkill(skillId: String): IBurstSkill?
}