package com.apex.agent.core.tools.skill

import android.content.Context
import com.apex.agent.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * Skill 依赖管理系统
 *
 * 功能。
 * - 自动解析和安装依。
 * - 版本冲突检。
 * - 循环依赖检。
 * - 依赖图构。
 * - 依赖下载管理
 */
class DependencyManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DependencyManager"
        private const val DEPENDENCIES_CACHE_DIR = "dependencies_cache"
        private const val MAX_INSTALL_RETRIES = 3

        @Volatile private var INSTANCE: DependencyManager? = null

        fun getInstance(context: Context): DependencyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DependencyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ========== 数据结构 ==========

    @Serializable
    data class SkillDependency(
        val skillId: String,
        val skillName: String,
        val version: String,
        val versionRange: String? = null,
        val isRequired: Boolean = true,
        val isInstalled: Boolean = false,
        val isBuiltIn: Boolean = false,
        val source: DependencySource = DependencySource.MARKETPLACE,
        val downloadUrl: String? = null,
        val checksum: String? = null,
        val size: Long = 0,
        val installedPath: String? = null,
        val errorMessage: String? = null
    )

    @Serializable
    enum class DependencySource {
        MARKETPLACE,
        LOCAL,
        BUILT_IN,
        REMOTE_URL
    }

    @Serializable
    data class DependencyGraph(
        val nodes: List<DependencyNode>,
        val edges: List<DependencyEdge>,
        val hasCircularDependency: Boolean = false,
        val circularDependencyPath: List<String>? = null
    )

    @Serializable
    data class DependencyNode(
        val skillId: String,
        val skillName: String,
        val version: String,
        val isInstalled: Boolean,
        val isMissing: Boolean,
        val missingReason: String? = null
    )

    @Serializable
    data class DependencyEdge(
        val from: String,
        val to: String,
        val versionConstraint: String?
    )

    @Serializable
    data class DependencyResolution(
        val success: Boolean,
        val resolvedDependencies: List<SkillDependency>,
        val missingDependencies: List<SkillDependency>,
        val conflictingDependencies: List<DependencyConflict>,
        val circularDependencies: List<List<String>>,
        val resolutionSteps: List<String> = emptyList()
    )

    @Serializable
    data class DependencyConflict(
        val skillId1: String,
        val skillId2: String,
        val skillName1: String,
        val skillName2: String,
        val version1: String,
        val version2: String,
        val reason: String
    )

    @Serializable
    data class DependencyInstallResult(
        val success: Boolean,
        val skillId: String,
        val installedPath: String? = null,
        val errorMessage: String? = null,
        val retryCount: Int = 0
    )

    // ========== 数据结构 ==========

    private val _dependencyGraph = MutableStateFlow<DependencyGraph?>(null)
    val dependencyGraph: StateFlow<DependencyGraph?> = _dependencyGraph.asStateFlow()

    private val _installProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val installProgress: StateFlow<Map<String, Float>> = _installProgress.asStateFlow()

    private val _isResolving = MutableStateFlow(false)
    val isResolving: StateFlow<Boolean> = _isResolving.asStateFlow()

    private val skillManager by lazy { SkillManager.getInstance(context) }
    private val skillRepoClient by lazy { SkillRepoClient.getInstance() }

    // 内置 Skill（不可卸载）
    private val builtInSkills = setOf(
        "core", "system", "utility", "base"
    )

    // 依赖缓存
    private val dependencyCache = mutableMapOf<String, List<SkillDependency>>()
    private val installStatusCache = mutableMapOf<String, Boolean>()

    // ========== 公开 API ==========

    /**
     * 解析 Skill 的所有依赖
     */
    suspend fun resolveDependencies(skillId: String): DependencyResolution = withContext(Dispatchers.IO) {
        _isResolving.value = true

        try {
            val availableSkills = skillManager.getAvailableSkills()
            val targetSkill = availableSkills[skillId]

            if (targetSkill == null) {
                return@withContext DependencyResolution(
                    success = false,
                    resolvedDependencies = emptyList(),
                    missingDependencies = emptyList(),
                    conflictingDependencies = emptyList(),
                    circularDependencies = emptyList(),
                    resolutionSteps = listOf("Skill not found: ${skillId}")
                )
            }

            val resolved = mutableListOf<SkillDependency>()
            val missing = mutableListOf<SkillDependency>()
            val conflicts = mutableListOf<DependencyConflict>()
            val circularDeps = mutableListOf<List<String>>()
            val visited = mutableSetOf<String>()
            val path = mutableListOf<String>()

            // 解析直接依赖
            val directDeps = parseDependencies(targetSkill.dependencies, targetSkill.name)
            val allDeps = mutableMapOf<String, SkillDependency>()

            for (dep in directDeps) {
                resolveDependencyRecursive(
                    dep = dep,
                    availableSkills = availableSkills,
                    resolved = allDeps,
                    missing = missing,
                    visited = visited,
                    path = path,
                    circularDeps = circularDeps
                )
            }

            // 解析直接依赖
            checkVersionConflicts(allDeps.values.toList(), conflicts)

            // 解析直接依赖
            buildDependencyGraph(skillId, allDeps.values.toList())

            val success = missing.isEmpty() && conflicts.isEmpty() && circularDeps.isEmpty()

            DependencyResolution(
                success = success,
                resolvedDependencies = allDeps.values.toList(),
                missingDependencies = missing,
                conflictingDependencies = conflicts,
                circularDependencies = circularDeps,
                resolutionSteps = buildResolutionSteps(success, allDeps.size, missing.size, conflicts.size)
            )
        } finally {
            _isResolving.value = false
        }
    }

    private fun resolveDependencyRecursive(
        dep: SkillDependency,
        availableSkills: Map<String, SkillPackage>,
        resolved: MutableMap<String, SkillDependency>,
        missing: MutableList<SkillDependency>,
        visited: MutableSet<String>,
        path: MutableList<String>,
        circularDeps: MutableList<List<String>>
    ) {
        val skillId = dep.skillId

        // 检测循环依赖
        if (path.contains(skillId)) {
            val cycleStart = path.indexOf(skillId)
            val cycle = path.subList(cycleStart, path.size) + skillId
            circularDeps.add(cycle)
            return
        }

        if (visited.contains(skillId)) {
            return
        }

        visited.add(skillId)
        path.add(skillId)

        // 检查是否已安装
        val isInstalled = availableSkills.containsKey(skillId) || builtInSkills.contains(skillId)

        val resolvedDep = if (isInstalled) {
            val skill = availableSkills[skillId]
            dep.copy(
                isInstalled = true,
                installedPath = skill?.directory?.absolutePath,
                isBuiltIn = builtInSkills.contains(skillId)
            )
        } else {
            dep.copy(isInstalled = false, missingReason = "Not found in local or marketplace")
        }

        resolved[skillId] = resolvedDep

        if (!isInstalled && dep.isRequired) {
            missing.add(resolvedDep)
        }

        // 检测循环依赖
        if (isInstalled) {
            val skill = availableSkills[skillId] ?: return
            val transitiveDeps = parseDependencies(skill.dependencies, skill.name)

            for (transitiveDep in transitiveDeps) {
                if (!resolved.containsKey(transitiveDep.skillId)) {
                    resolveDependencyRecursive(
                        dep = transitiveDep,
                        availableSkills = availableSkills,
                        resolved = resolved,
                        missing = missing,
                        visited = visited,
                        path = path,
                        circularDeps = circularDeps
                    )
                }
            }
        }

        path.removeAt(path.size - 1)
    }

    private fun parseDependencies(dependencies: List<String>, skillName: String): List<SkillDependency> {
        return dependencies.mapNotNull { depStr ->
            try {
                // 支持格式： "skill_id", "skill_id@1.0.0", "skill_id>=1.0.0"
                val parts = depStr.trim().split("@")
                val id = parts[0].trim()
                val version = if (parts.size > 1) parts[1].trim() else "*"

                val versionRange = when {
                    version.startsWith(">=") -> version
                    version.startsWith("<=") -> version
                    version.startsWith(">") -> version
                    version.startsWith("<") -> version
                    version.startsWith("^") -> version
                    version.startsWith("~") -> version
                    version == "*" -> null
                    else -> null
                }

                val resolvedVersion = version.replace(Regex("^[\\^~>=<]+"), "")

                SkillDependency(
                    skillId = id,
                    skillName = id,  // 假设 skillId 即为 name，实际从 marketplace 获取
                    version = resolvedVersion,
                    versionRange = versionRange,
                    source = if (builtInSkills.contains(id)) DependencySource.BUILT_IN else DependencySource.MARKETPLACE
                )
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to parse dependency: ${depStr} from ${skillName}", e)
                null
            }
        }
    }

    private fun checkVersionConflicts(
        dependencies: List<SkillDependency>,
        conflicts: MutableList<DependencyConflict>
    ) {
        // 按 skillId 分组，检查是否有版本冲突
        val bySkillId = dependencies.groupBy { it.skillId }

        for ((skillId, deps) in bySkillId) {
            if (deps.size > 1) {
                val versions = deps.map { it.version }.distinct()
                if (versions.size > 1) {
                    // 发现版本冲突
                    for (i in 0 until deps.size - 1) {
                        for (j in i + 1 until deps.size) {
                            conflicts.add(
                                DependencyConflict(
                                    skillId1 = skillId,
                                    skillId2 = skillId,
                                    skillName1 = deps[i].skillName,
                                    skillName2 = deps[j].skillName,
                                    version1 = deps[i].version,
                                    version2 = deps[j].version,
                                    reason = "Different versions required: ${deps[i].version} vs ${deps[j].version}"
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 构建依赖。
     */
    private fun buildDependencyGraph(rootSkillId: String, dependencies: List<SkillDependency>) {
        val availableSkills = skillManager.getAvailableSkills()
        val nodes = mutableListOf<DependencyNode>()
        val edges = mutableListOf<DependencyEdge>()
        var hasCircular = false
        var circularPath: List<String>? = null

        // 检测循环依赖
        val rootSkill = availableSkills[rootSkillId]
        if (rootSkill != null) {
            nodes.add(
                DependencyNode(
                    skillId = rootSkillId,
                    skillName = rootSkill.name,
                    version = rootSkill.version,
                    isInstalled = true,
                    isMissing = false
                )
            )
        }

        // 添加依赖节点和边
        val visited = mutableSetOf<String>()
        val path = mutableListOf<String>()

        fun buildGraph(skillId: String, parentId: String) {
            if (visited.contains(skillId)) {
                if (path.contains(skillId)) {
                    hasCircular = true
                    val cycleStart = path.indexOf(skillId)
                    circularPath = path.subList(cycleStart, path.size) + skillId
                }
                return
            }

            visited.add(skillId)
            path.add(skillId)

            val skill = availableSkills[skillId]
            val dep = dependencies.find { it.skillId == skillId }

            nodes.add(
                DependencyNode(
                    skillId = skillId,
                    skillName = dep?.skillName ?: skillId,
                    version = dep?.version ?: skill?.version ?: "unknown",
                    isInstalled = skill != null || builtInSkills.contains(skillId),
                    isMissing = skill == null && !builtInSkills.contains(skillId),
                    missingReason = if (skill == null && !builtInSkills.contains(skillId)) "Not installed" else null
                )
            )

            if (parentId != null) {
                edges.add(
                    DependencyEdge(
                        from = parentId,
                        to = skillId,
                        versionConstraint = dep?.versionRange
                    )
                )
            }

            // 解析直接依赖
            skill?.dependencies?.forEach { depStr ->
                val depId = depStr.split("@").first().trim()
                if (depId != skillId) { // 避免自循环
                    buildGraph(depId, skillId)
                }
            }

            path.removeAt(path.size - 1)
        }

        dependencies.forEach { dep ->
            buildGraph(dep.skillId, rootSkillId)
        }

        _dependencyGraph.value = DependencyGraph(
            nodes = nodes.distinctBy { it.skillId },
            edges = edges.distinctBy { "${it.from}->${it.to}" },
            hasCircularDependency = hasCircular,
            circularDependencyPath = circularPath
        )
    }

    /**
     * 安装缺失的依。
     */
    suspend fun installDependencies(skillId: String): List<DependencyInstallResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DependencyInstallResult>()

        val resolution = resolveDependencies(skillId)

        for (missingDep in resolution.missingDependencies) {
            var retryCount = 0
            var success = false
            var installedPath: String? = null
            var errorMessage: String? = null

            while (retryCount < MAX_INSTALL_RETRIES && !success) {
                _installProgress.value = _installProgress.value.toMutableMap().apply {
                    put(missingDep.skillId, 0f)
                }

                val result = downloadAndInstallDependency(missingDep) { progress ->
                    _installProgress.value = _installProgress.value.toMutableMap().apply {
                        put(missingDep.skillId, progress)
                    }
                }

                success = result.success
                installedPath = result.installedPath
                errorMessage = result.errorMessage

                if (!success) {
                    retryCount++
                    if (retryCount < MAX_INSTALL_RETRIES) {
                        kotlinx.coroutines.delay(1000L * retryCount) // 指数退避
                    }
                }
            }

            results.add(
                DependencyInstallResult(
                    success = success,
                    skillId = missingDep.skillId,
                    installedPath = installedPath,
                    errorMessage = errorMessage,
                    retryCount = retryCount
                )
            )
        }

        // 清理进度
        _installProgress.value = emptyMap()

        results
    }

    private suspend fun downloadAndInstallDependency(
        dependency: SkillDependency,
        progressCallback: (Float) -> Unit
    ): DependencyInstallResult = withContext(Dispatchers.IO) {
        try {
            progressCallback(0.1f)

            // 从 Marketplace 获取下载信息
            val detailResult = skillRepoClient.getSkillDetail(dependency.skillId)

            if (detailResult.isFailure) {
                return@withContext DependencyInstallResult(
                    success = false,
                    skillId = dependency.skillId,
                    errorMessage = "Failed to get skill info: ${detailResult.exceptionOrNull()?.message}"
                )
            }

            val detail = detailResult.getOrNull() ?: return@withContext DependencyInstallResult(
                success = false,
                skillId = dependency.skillId,
                errorMessage = "Failed to get skill detail"
            )

            progressCallback(0.3f)

            // 下载
            val cacheDir = File(context.cacheDir, DEPENDENCIES_CACHE_DIR)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val downloadFile = File(cacheDir, "${dependency.skillId}_${detail.version}.zip")

            val downloadResult = skillRepoClient.downloadSkill(
                skillId = dependency.skillId,
                version = detail.version,
                outputFile = downloadFile
            ) { downloaded, total ->
                if (total > 0) {
                    progressCallback(0.3f + 0.5f * (downloaded.toFloat() / total))
                }
            }

            if (downloadResult.isFailure) {
                return@withContext DependencyInstallResult(
                    success = false,
                    skillId = dependency.skillId,
                    errorMessage = "Download failed: ${downloadResult.exceptionOrNull()?.message}"
                )
            }

            progressCallback(0.8f)

            // 导入
            val importResult = skillManager.importSkillFromZip(downloadFile)

            // 清理下载文件
            downloadFile.delete()

            progressCallback(1.0f)

            if (importResult.contains("imported") || importResult.contains("成功")) {
                val installedPath = skillManager.getAvailableSkills()[dependency.skillId]?.directory?.absolutePath
                DependencyInstallResult(
                    success = true,
                    skillId = dependency.skillId,
                    installedPath = installedPath
                )
            } else {
                DependencyInstallResult(
                    success = false,
                    skillId = dependency.skillId,
                    errorMessage = importResult
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to install dependency: ${dependency.skillId}", e)
            DependencyInstallResult(
                success = false,
                skillId = dependency.skillId,
                errorMessage = e.message
            )
        }
    }

    /**
     * 解析 Skill 的所有依赖
     */
    suspend fun validateDependencies(skillId: String): ValidationResult = withContext(Dispatchers.IO) {
        val resolution = resolveDependencies(skillId)

        val missingRequired = resolution.missingDependencies.filter { it.isRequired }
        val hasConflicts = resolution.conflictingDependencies.isNotEmpty()
        val hasCircular = resolution.circularDependencies.isNotEmpty()

        ValidationResult(
            isValid = missingRequired.isEmpty() && !hasConflicts && !hasCircular,
            missingRequired = missingRequired,
            hasConflicts = hasConflicts,
            conflicts = resolution.conflictingDependencies,
            hasCircularDependencies = hasCircular,
            circularDependencies = resolution.circularDependencies,
            message = buildValidationMessage(missingRequired, hasConflicts, hasCircular)
        )
    }

    private fun buildValidationMessage(
        missing: List<SkillDependency>,
        hasConflicts: Boolean,
        hasCircular: Boolean
    ): String {
        return when {
            missing.isNotEmpty() -> "Missing required dependencies: ${missing.joinToString { it.skillId }}"
            hasConflicts -> "Version conflicts detected between dependencies"
            hasCircular -> "Circular dependencies detected"
            else -> "All dependencies are satisfied"
        }
    }

    private fun buildResolutionSteps(
        success: Boolean,
        resolvedCount: Int,
        missingCount: Int,
        conflictCount: Int
    ): List<String> {
        val steps = mutableListOf<String>()
        steps.add("1. Analyzing dependencies for target skill")
        steps.add("2. Resolving direct dependencies: found ${resolvedCount}")
        if (missingCount > 0) {
            steps.add("3. Found ${missingCount} missing dependencies")
        }
        if (conflictCount > 0) {
            steps.add("4. Detected ${conflictCount} version conflicts")
        }
        if (success) {
            steps.add("5. All dependencies resolved successfully")
        } else {
            steps.add("5. Resolution incomplete - action required")
        }
        return steps
    }

    /**
     * 获取技能依赖树（文本格式）
     */
    suspend fun getDependencyTree(skillId: String): String = withContext(Dispatchers.IO) {
        val resolution = resolveDependencies(skillId)
        val sb = StringBuilder()

        fun buildTree(deps: List<SkillDependency>, indent: Int, prefix: String, isLast: Boolean): String {
            val sb = StringBuilder()
            deps.forEachIndexed { index, dep ->
                val isLastItem = index == deps.size - 1
                val currentPrefix = if (indent == 0) "" else if (isLastItem) "└── " else "├── "
                val newPrefix = if (indent == 0) "" else if (isLastItem) "    " else "│   "

                val status = when {
                    !dep.isInstalled && dep.isRequired -> " [MISSING]"
                    !dep.isInstalled -> " [OPTIONAL]"
                    dep.isBuiltIn -> " [BUILT-IN]"
                    else -> ""
                }

                sb.append("${prefix}${currentPrefix}${dep.skillName}@${dep.version}${status}\n")

                // 递归显示传递依赖（限制深度）
                if (indent < 3) {
                    val transitiveDeps = dependencyCache[dep.skillId] ?: emptyList()
                    if (transitiveDeps.isNotEmpty()) {
                        sb.append(buildTree(transitiveDeps, indent + 1, "${prefix}${newPrefix}", isLastItem))
                    }
                }
            }
            return sb.toString()
        }

        sb.appendLine("Dependency Tree for: ${skillId}")
        sb.appendLine("=".repeat(40))
        sb.appendLine()

        if (resolution.resolvedDependencies.isEmpty()) {
            sb.appendLine("No dependencies")
        } else {
            sb.append(buildTree(resolution.resolvedDependencies, 0, "", true))
        }

        if (resolution.missingDependencies.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Missing Dependencies:")
            resolution.missingDependencies.forEach { dep ->
                sb.appendLine("  - ${dep.skillName}: ${dep.missingReason ?: 'Version mismatch'}")
            }
        }

        if (resolution.conflictingDependencies.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Conflicts:")
            resolution.conflictingDependencies.forEach { conflict ->
                sb.appendLine("  - ${conflict.skillName1} vs ${conflict.skillName2}: ${conflict.reason}")
            }
        }

        if (resolution.circularDependencies.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Circular Dependencies:")
            resolution.circularDependencies.forEach { cycle ->
                sb.appendLine("  - ${cycle.joinToString(" -> ")}")
            }
        }

        sb.toString()
    }

    /**
     * 卸载可选依。
     */
    suspend fun uninstallOptionalDependency(skillId: String, dependencyId: String): Boolean = withContext(Dispatchers.IO) {
        val dep = dependencyCache[skillId]?.find { it.skillId == dependencyId }

        if (dep == null) {
            return@withContext false
        }

        if (dep.isRequired) {
            AppLogger.w(TAG, "Cannot uninstall required dependency: ${dependencyId}")
            return@withContext false
        }

        // 检查是否有其他 Skill 依赖此包
        val availableSkills = skillManager.getAvailableSkills()
        val dependents = availableSkills.filter { (id, skill) ->
            id != skillId && skill.dependencies.any { it.startsWith(dependencyId) }
        }

        if (dependents.isNotEmpty()) {
            AppLogger.w(TAG, "Cannot uninstall: ${dependents.keys} depend on ${dependencyId}")
            return@withContext false
        }

        skillManager.deleteSkill(dependencyId)
    }

    /**
     * 检查更。
     */
    suspend fun checkDependencyUpdates(skillId: String): List<UpdateInfo> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<UpdateInfo>()
        val resolution = resolveDependencies(skillId)

        for (dep in resolution.resolvedDependencies) {
            if (!dep.isInstalled || dep.isBuiltIn) continue

            val checkResult = skillRepoClient.checkForUpdate(dep.skillId, dep.version)

            checkResult.getOrNull()?.let { update ->
                if (update.hasUpdate) {
                    updates.add(
                        UpdateInfo(
                            skillId = dep.skillId,
                            skillName = dep.skillName,
                            currentVersion = dep.version,
                            newVersion = update.latestVersion,
                            updateSize = update.updateSize,
                            changelog = update.changelog
                        )
                    )
                }
            }
        }

        updates
    }

    data class UpdateInfo(
        val skillId: String,
        val skillName: String,
        val currentVersion: String,
        val newVersion: String,
        val updateSize: Long,
        val changelog: String
    )

    data class ValidationResult(
        val isValid: Boolean,
        val missingRequired: List<SkillDependency>,
        val hasConflicts: Boolean,
        val conflicts: List<DependencyConflict>,
        val hasCircularDependencies: Boolean,
        val circularDependencies: List<List<String>>,
        val message: String
    )

    /**
     * 导出依赖图为 DOT 格式（用于 GraphViz）
     */
    fun exportDependencyGraphAsDot(skillId: String): String? {
        val graph = _dependencyGraph.value ?: return null
        val sb = StringBuilder()

        sb.appendLine("digraph Dependencies {")
        sb.appendLine("    rankdir=TB;")
        sb.appendLine("    node [shape=box];")
        sb.appendLine()

        // 节点定义
        for (node in graph.nodes) {
            val color = when {
                node.isMissing -> "red"
                node.isBuiltIn -> "blue"
                node.isInstalled -> "green"
                else -> "gray"
            }
            val label = "${node.skillName}\\n(${node.version})"
            sb.appendLine("    \"${node.skillId}\" [label=\"${label}\", color=${color}];")
        }

        sb.appendLine()

        // 检测循环依赖
        for (edge in graph.edges) {
            sb.appendLine("    \"${edge.from}\" -> \"${edge.to}\" [label=\"${edge.versionConstraint ?: ""}\"];")
        }

        // 标记循环依赖
        if (graph.hasCircularDependency && graph.circularDependencyPath != null) {
            sb.appendLine()
            sb.append("    subgraph cluster_cycle {")
            sb.appendLine(" label=\"Circular Dependency\"; style=dashed; color=red;")
            val cyclePath = graph.circularDependencyPath
            for (i in 0 until cyclePath.size - 1) {
                sb.appendLine("    \"${cyclePath[i]}\" -> \"${cyclePath[i + 1]}\";")
            }
            sb.appendLine("    }")
        }

        sb.appendLine("}")

        return sb.toString()
    }
}
