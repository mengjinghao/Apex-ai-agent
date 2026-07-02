package com.apex.agent.plugins.burst.base

import com.apex.agent.domain.model.BurstTask

/**
 * 所有狂暴模式技能必须实现的接口
 */
interface IBurstSkill {
    val manifest: BurstSkillManifest
    
    fun initialize(context: BurstSkillContext)
    fun execute(task: BurstTask): BurstSkillResult
    fun pause()
    fun resume()
    fun destroy()
    
    // 技能演化接口（支持GEPA引擎自动优化）
    fun mutate(rate: Float): IBurstSkill
    fun crossover(other: IBurstSkill): IBurstSkill
    fun evaluate(): Float
}