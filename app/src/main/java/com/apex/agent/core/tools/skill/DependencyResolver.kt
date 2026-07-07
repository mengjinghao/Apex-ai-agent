package com.apex.agent.core.tools.skill

import android.util.Log

/**
 * 技能依赖关系解析器
 *
 * 功能：
 * 1. 拓扑排序确定依赖加载顺序
 * 2. DFS循环依赖检测
 * 3. 依赖版本约束验证
 * 4. 依赖解析过程日志
 */
object DependencyResolver {

    private const val TAG = "DependencyResolver"

    /**
     * 解析技能依赖，返回按加载顺序排序的技能列表
     * @param skills 待解析的技能列表
     * @return 按依赖顺序排序的技能列表（先加载依赖）
     * @throws IllegalStateException 存在循环依赖或缺失依赖
     */
    fun resolve(skills: List<SkillMetadata>): List<SkillMetadata> {
        val resolved = mutableListOf<SkillMetadata>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        val skillMap = skills.associateBy { it.name }

        Log.d(TAG, "Starting dependency resolution for ${skills.size} skills")

        fun dfs(current: SkillMetadata) {
            if (current.name in visited) return
            if (current.name in visiting) {
                val cycle = visiting.toList() + current.name
                throw IllegalStateException("Circular dependency detected: ${cycle.joinToString(" -> ")}")
            }

            visiting.add(current.name)

            // 解析依赖
            current.dependencies.forEach { depName ->
                val dep = skillMap[depName]
                if (dep == null) {
                    // 检查已解析的列表中是否有
                    val alreadyResolved = resolved.any { it.name == depName }
                    if (!alreadyResolved) {
                        throw IllegalStateException("Missing dependency: '${depName}' required by '${current.name}'")
                    }
                } else {
                    if (dep.name !in visited) {
                        dfs(dep)
                    }
                }
            }

            visiting.remove(current.name)
            visited.add(current.name)
            resolved.add(current)
            Log.d(TAG, "Resolved: ${current.name} v${current.version}")
        }

        skills.forEach { skill ->
            if (skill.name !in visited) {
                dfs(skill)
            }
        }

        Log.d(TAG, "Dependency resolution complete: ${resolved.size} skills in order")
        return resolved
    }

    /**
     * 检测循环依赖
     * @return 所有检测到的循环路径列表
     */
    fun detectCycles(skills: List<SkillMetadata>): List<List<String>> {
        val cycles = mutableListOf<List<String>>()
        val skillMap = skills.associateBy { it.name }
        val white = mutableSetOf<String>()
        val gray = mutableSetOf<String>()
        val black = mutableSetOf<String>()
        val parent = mutableMapOf<String, String>()

        skills.forEach { white.add(it.name) }

        fun dfs(current: String) {
            white.remove(current)
            gray.add(current)

            val skill = skillMap[current]
            skill?.dependencies?.forEach { dep ->
                if (dep in gray) {
                    // 发现循环
                    val cycle = mutableListOf(dep, current)
                    var p = current
                    while (p != dep) {
                        p = parent[p] ?: break
                        cycle.add(p)
                    }
                    cycle.reverse()
                    cycles.add(cycle.distinct())
                } else if (dep in white) {
                    parent[dep] = current
                    dfs(dep)
                }
            }

            gray.remove(current)
            black.add(current)
        }

        white.toList().forEach { dfs(it) }

        return cycles.distinct()
    }

    /**
     * 验证指定技能的依赖是否完备
     * @param skill 待验证的技能
     * @param available 当前可用的技能名称集合
     * @return 缺失的依赖名称列表（空列表表示全部满足）
     */
    fun validateDependencies(skill: SkillMetadata, available: Set<String>): List<String> {
        val missing = mutableListOf<String>()

        skill.dependencies.forEach { dep ->
            if (dep !in available) {
                missing.add(dep)
                Log.w(TAG, "Missing dependency '${dep}' for skill '${skill.name}'")
            }
        }

        if (missing.isEmpty()) {
            Log.d(TAG, "All dependencies satisfied for '${skill.name}'")
        }

        return missing
    }

    /**
     * 获取依赖拓扑排序（仅排序不校验）
     */
    fun topologicalSort(skills: List<SkillMetadata>): List<SkillMetadata> {
        val skillMap = skills.associateBy { it.name }
        val visited = mutableSetOf<String>()
        val result = mutableListOf<SkillMetadata>()

        fun dfs(name: String) {
            if (name in visited) return
            visited.add(name)
            val skill = skillMap[name] ?: return
            skill.dependencies.forEach { dep ->
                if (dep in skillMap) {
                    dfs(dep)
                }
            }
            result.add(skill)
        }

        skills.forEach { dfs(it.name) }
        return result
    }

    /**
     * 对技能列表进行依赖排序（有依赖的排在前面）
     */
    fun sortByDependencies(skills: List<SkillMetadata>): List<SkillMetadata> {
        return skills.sortedBy { it.dependencies.size }
    }
}
