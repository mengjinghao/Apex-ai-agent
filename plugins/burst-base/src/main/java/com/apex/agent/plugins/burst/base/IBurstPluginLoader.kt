package com.apex.agent.plugins.burst.base

import com.apex.agent.domain.model.BurstTask

/**
 * 插件加载器接口
 * 内核实现此接口，插件通过上下文获取
 */

interface IBurstPluginLoader {
    suspend fun loadSkill(skillId: String): IBurstSkill?
    suspend fun unloadSkill(skillId: String)
    fun getLoadedSkills(): List<String>
    fun getSkillManifest(skillId: String): BurstSkillManifest?
    fun getSkill(skillId: String): IBurstSkill?
    suspend fun executeSkill(skillId: String, task: BurstTask): BurstSkillResult
}