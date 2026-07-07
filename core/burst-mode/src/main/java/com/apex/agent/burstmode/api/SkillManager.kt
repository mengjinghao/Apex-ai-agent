package com.apex.agent.burstmode.api

import com.apex.agent.burstmode.exception.BurstModeException
import com.apex.agent.plugins.burst.base.BurstSkillManifest
import com.apex.agent.plugins.burst.base.IBurstSkill

/**
 * 技能管理器。
 *
 * 提供技能的注册、注销、查询能力。业务侧通过此接口管理自定义技能，
 * 无需直接操作底层的 BurstKernel.pluginLoader。
 *
 * # 设计目标
 *
 * - **类型安全**：技能通过 [IBurstSkill] 接口约束
 * - **查询灵活**：按 ID / 标签 / 能力 多维查询
 * - **生命周期感知**：注册的技能随 [BurstMode] 关闭而注销
 * - **线程安全**：所有方法线程安全
 *
 * # 使用示例
 *
 * ```
 * val skillManager = burstMode.skillManager
 *
 * // 注册自定义技能
 * skillManager.register(MyCustomSkill())
 *
 * // 按 ID 查询
 * val skill = skillManager.get("my_custom_skill")
 *
 * // 按标签查询
 * val reasoningSkills = skillManager.getByTag("reasoning")
 *
 * // 列出所有
 * skillManager.getAll().forEach { skill ->
 *     println("${skill.manifest.skillId}: ${skill.manifest.description}")
 * }
 * ```
 */
interface SkillManager {

    /**
     * 注册技能。
     *
     * @param skill 技能实例
     * @return true 注册成功，false 已存在同 ID 技能
     */
    fun register(skill: IBurstSkill): Boolean

    /**
     * 批量注册技能。
     *
     * @param skills 技能列表
     * @return 成功注册的数量（已存在的跳过）
     */
    fun registerAll(skills: List<IBurstSkill>): Int

    /**
     * 注销技能。
     *
     * @param skillId 技能 ID
     * @return true 注销成功，false 技能不存在
     */
    fun unregister(skillId: String): Boolean

    /**
     * 按技能 ID 获取。
     */
    fun get(skillId: String): IBurstSkill?

    /**
     * 获取所有已注册技能。
     */
    fun getAll(): List<IBurstSkill>

    /**
     * 按标签查询技能。
     *
     * @param tag 标签名（如 "reasoning", "speed", "read"）
     * @return 匹配的技能列表
     */
    fun getByTag(tag: String): List<IBurstSkill>

    /**
     * 按能力查询技能。
     *
     * @param capability 能力名（如 "parallel_racing", "first_success_wins"）
     * @return 匹配的技能列表
     */
    fun getByCapability(capability: String): List<IBurstSkill>

    /**
     * 获取所有技能清单。
     *
     * @return 技能 manifest 列表
     */
    fun getAllManifests(): List<BurstSkillManifest>

    /**
     * 检查技能是否已注册。
     */
    fun contains(skillId: String): Boolean

    /**
     * 已注册技能数量。
     */
    fun count(): Int

    /**
     * 清空所有已注册技能。
     */
    fun clear()
}

/**
 * 技能管理器默认实现。
 */
internal class SkillManagerImpl : SkillManager {

    private val skills = java.util.concurrent.ConcurrentHashMap<String, IBurstSkill>()

    override fun register(skill: IBurstSkill): Boolean {
        val id = skill.manifest.skillId
        return skills.putIfAbsent(id, skill) == null
    }

    override fun registerAll(skills: List<IBurstSkill>): Int {
        var count = 0
        for (skill in skills) {
            if (register(skill)) count++
        }
        return count
    }

    override fun unregister(skillId: String): Boolean {
        return skills.remove(skillId) != null
    }

    override fun get(skillId: String): IBurstSkill? = skills[skillId]

    override fun getAll(): List<IBurstSkill> = skills.values.toList()

    override fun getByTag(tag: String): List<IBurstSkill> {
        return skills.values.filter { it.manifest.tags.contains(tag) }
    }

    override fun getByCapability(capability: String): List<IBurstSkill> {
        return skills.values.filter { it.manifest.capabilities.contains(capability) }
    }

    override fun getAllManifests(): List<BurstSkillManifest> {
        return skills.values.map { it.manifest }
    }

    override fun contains(skillId: String): Boolean = skills.containsKey(skillId)

    override fun count(): Int = skills.size

    override fun clear() {
        skills.clear()
    }
}
