package com.apex.agent.core.tools.skill

import android.content.Context
import android.os.Environment
import android.util.Log
import com.apex.agent.R
import com.apex.agent.core.tools.LocalizedText
import com.apex.agent.core.tools.PackagePermission
import com.apex.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class SkillMetadata(
    val name: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val dependencies: List<String> = emptyList(),
    val permissions: List<PackagePermission> = emptyList()
)

class SkillManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"

        @Volatile private var INSTANCE: SkillManager? = null

        fun getInstance(context: Context): SkillManager {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE ?: SkillManager(context.applicationContext).also { INSTANCE = it }
                }
        }
    }

    private val availableSkills = mutableMapOf<String, SkillPackage>()
    private val skillLoadErrors = mutableMapOf<String, String>()

    private val skillLoader by lazy { SkillLoader.getInstance(context) }
    private val skillCache by lazy { SkillCache.getInstance(context) }
    private val skillUnloader by lazy { SkillUnloader.getInstance(context) }

    private fun getSkillsRootDir(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apexDir = File(downloadsDir, "logistra")
        val skillsDir = File(apexDir, "skills")
        if (!skillsDir.exists()) {
            skillsDir.mkdirs()
        }
        return skillsDir
    }

    fun getSkillsDirectoryPath(): String {
        return getSkillsRootDir().absolutePath
    }

    fun refreshAvailableSkills() {
        availableSkills.clear()
        skillLoadErrors.clear()

        val skillsDir = try {
            getSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            skillLoadErrors[context.getString(R.string.skills)] =
                context.getString(R.string.skill_error_cannot_access_dir, e.message ?: "")
            return
        }

        if (!skillsDir.exists() || !skillsDir.isDirectory) {
            return
        }

        val children = skillsDir.listFiles() ?: emptyArray()
        for (child in children) {
            if (!child.isDirectory) continue

            val skillFile = File(child, "SKILL.md").let { primary ->
                if (primary.exists()) primary else File(child, "skill.md")
            }

            if (!skillFile.exists() || !skillFile.isFile) {
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_missing_skill_md,
                    child.absolutePath
                )
                continue
            }

            try {
                val metadata = parseSkillMetadata(skillFile)
                val skillName = metadata.name.ifBlank { child.name }
                val skillDesc = metadata.description.ifBlank { "" }

                if (availableSkills.containsKey(skillName)) {
                    val existingDirName = availableSkills[skillName]?.directory?.name ?: skillName
                    skillLoadErrors[child.name] = context.getString(
                        R.string.skill_error_duplicate_scanned_name,
                        skillName,
                        existingDirName
                    )
                    continue
                }

                availableSkills[skillName] = SkillPackage(
                    name = skillName,
                    description = skillDesc,
                    directory = child,
                    skillFile = skillFile,
                    version = metadata.version,
                    author = metadata.author,
                    dependencies = metadata.dependencies,
                    permissions = metadata.permissions
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error loading skill from ${skillFile.absolutePath}", e)
                skillLoadErrors[child.name] = context.getString(
                    R.string.skill_error_scan_failed,
                    e.message ?: e.javaClass.simpleName
                )
            }
        }
    }

    private fun parseSkillMetadata(skillFile: File): SkillMetadata {
        val lines = skillFile.bufferedReader().use { it.readLines() }

        var name = ""
        var description = ""
        var version = "1.0.0"
        var author = ""
        var dependencies = emptyList<String>()
        var permissions = emptyList<PackagePermission>()

        if (lines.isNotEmpty() && lines[0].trim() == "---") {
            val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (endIndex >= 0) {
                val frontmatter = lines.subList(1, endIndex + 1)

                var inPermissionsBlock = false
                var currentPermission: MutableMap<String, String>? = null
                var currentPermissionList = mutableListOf<PackagePermission>()
                var permissionsDescriptionLang = ""
                var permissionsDescriptionValue = ""

                frontmatter.forEach { lineRaw ->
                    val line = lineRaw.trim()

                    if (inPermissionsBlock) {
                        if (line.startsWith("- name:")) {
                            if (currentPermission != null) {
                                currentPermissionList.add(buildPackagePermission(currentPermission))
                            }
                            currentPermission = mutableMapOf()
                            currentPermission["name"] = line.substring("- name:".length).trim().unquote()
                        } else if (line.startsWith("- ") && currentPermission != null) {
                            if (currentPermission.isNotEmpty()) {
                                currentPermissionList.add(buildPackagePermission(currentPermission))
                            }
                            currentPermission = mutableMapOf()
                            currentPermission["name"] = line.substring(2).trim().unquote()
                        } else if (line.startsWith("description:")) {
                            val descValue = line.substring("description:".length).trim()
                            if (descValue.startsWith("|")) {
                                permissionsDescriptionLang = ""
                                permissionsDescriptionValue = ""
                            } else if (descValue.startsWith("zh:") || descValue.startsWith("en:") || descValue.startsWith("default:")) {
                                val parts = descValue.split(":", limit = 2)
                                if (parts.size == 2) {
                                    if (currentPermission == null) {
                                        currentPermission = mutableMapOf()
                                    }
                                    currentPermission["desc_${parts[0].trim()}"] = parts[1].trim().unquote()
                                }
                            } else {
                                if (currentPermission == null) {
                                    currentPermission = mutableMapOf()
                                }
                                currentPermission["desc_default"] = descValue.unquote()
                            }
                        } else if (line.startsWith("required:")) {
                            val reqValue = line.substring("required:".length).trim()
                            if (currentPermission == null) {
                                currentPermission = mutableMapOf()
                            }
                            currentPermission["required"] = reqValue
                        } else if (line.startsWith("zh:") || line.startsWith("en:") || line.startsWith("default:")) {
                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2) {
                                if (currentPermission == null) {
                                    currentPermission = mutableMapOf()
                                }
                                currentPermission["desc_${parts[0].trim()}"] = parts[1].trim().unquote()
                            }
                        } else if (line.isBlank() || line == "permissions:") {
                        } else if (!line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("-")) {
                            if (currentPermission != null && currentPermission.isNotEmpty()) {
                                currentPermissionList.add(buildPackagePermission(currentPermission))
                                currentPermission = null
                            }
                            inPermissionsBlock = false
                        }
                        return@forEach
                    }

                    val idx = line.indexOf(':')
                    if (idx <= 0) return@forEach
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()

                    when (key.lowercase()) {
                        "name" -> if (name.isBlank()) name = unquote(value)
                        "description" -> if (description.isBlank()) description = unquote(value)
                        "version" -> if (version == "1.0.0") version = unquote(value)
                        "author" -> if (author.isBlank()) author = unquote(value)
                        "dependencies" -> {
                            dependencies = if (value.isBlank()) emptyList() else value.split(",").map { it.trim().unquote() }
                        }
                        "permissions" -> {
                            if (value.isNotBlank() && !value.startsWith("[")) {
                                permissions = value.split(",").map { permName ->
                                    PackagePermission(
                                        name = permName.trim().unquote(),
                                        description = LocalizedText.of(""),
                                        required = false
                                    )
                                }
                            } else if (value.isBlank()) {
                                inPermissionsBlock = true
                            }
                        }
                    }
                }

                if (currentPermission != null && currentPermission.isNotEmpty()) {
                    currentPermissionList.add(buildPackagePermission(currentPermission))
                }

                if (currentPermissionList.isNotEmpty()) {
                    permissions = currentPermissionList
                } else if (permissions.isEmpty()) {
                    permissions = emptyList()
                }
            }
        }

        if (name.isBlank() || description.isBlank() || version == "1.0.0") {
            lines.take(40).forEach { lineRaw ->
                val line = lineRaw.trim()
                val idx = line.indexOf(':')
                if (idx <= 0) return@forEach
                val key = line.substring(0, idx).trim()
                val value = unquote(line.substring(idx + 1).trim())
                when (key.lowercase()) {
                    "name" -> if (name.isBlank()) name = value
                    "description" -> if (description.isBlank()) description = value
                    "version" -> if (version == "1.0.0") version = value
                    "author" -> if (author.isBlank()) author = value
                }
            }
        }

        return SkillMetadata(
            name = name,
            description = description,
            version = version,
            author = author,
            dependencies = dependencies,
            permissions = permissions
        )
    }

    private fun buildPackagePermission(permMap: Map<String, String>): PackagePermission {
        val name = permMap["name"] ?: ""
        val descZh = permMap["desc_zh"] ?: ""
        val descEn = permMap["desc_en"] ?: ""
        val descDefault = permMap["desc_default"] ?: ""
        val required = permMap["required"]?.toBooleanStrictOrNull() ?: false

        val descMap = mutableMapOf<String, String>()
        if (descZh.isNotBlank()) descMap["zh"] = descZh
        if (descEn.isNotBlank()) descMap["en"] = descEn
        if (descDefault.isNotBlank()) descMap["default"] = descDefault

        val description = if (descMap.isNotEmpty()) {
            LocalizedText(descMap)
        } else {
            LocalizedText.of("")
        }

        return PackagePermission(
            name = name,
            description = description,
            required = required
        )
    }

    private fun String.unquote(): String {
        var value = this
        if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith('\'') && value.endsWith('\''))) {
            if (value.length >= 2) value = value.substring(1, value.length - 1)
        }
        return value
    }

    fun getAvailableSkills(): Map<String, SkillPackage> {
        refreshAvailableSkills()
        return availableSkills.toMap()
    }

    fun getAvailableSkillsSnapshot(): Pair<Map<String, SkillPackage>, Map<String, String>> {
        refreshAvailableSkills()
        return availableSkills.toMap() to skillLoadErrors.toMap()
    }

    fun getSkillLoadErrors(): Map<String, String> {
        refreshAvailableSkills()
        return skillLoadErrors.toMap()
    }

    fun readSkillContent(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null
        return try {
            skill.skillFile.readText()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read SKILL.md for ${skillName}", e)
            null
        }
    }

    fun deleteSkill(skillName: String): Boolean {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return false
        return try {
            val ok = skill.directory.deleteRecursively()
            if (ok) {
                availableSkills.remove(skillName)
            }
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete skill ${skillName}", e)
            false
        }
    }

    fun preloadSkill(skillName: String, forceReload: Boolean = false): Boolean {
        refreshAvailableSkills()
        val skillPkg = availableSkills[skillName]
        if (skillPkg == null) {
            Log.w(TAG, "Skill not found: $skillName")
            return false
        }

        // 先加载依赖
        val deps = skillPkg.dependencies
        if (deps.isNotEmpty()) {
            Log.d(TAG, "Loading dependencies for $skillName: $deps")
            val missingDeps = validateSkillDependencies(skillName)
            if (missingDeps.isNotEmpty()) {
                Log.e(TAG, "Cannot load $skillName: missing dependencies $missingDeps")
                return false
            }
            deps.forEach { depName ->
                if (!skillLoader.isLoaded(depName)) {
                    val depOk = skillLoader.loadSkill(depName, this, forceReload) != null
                    if (!depOk) {
                        Log.w(TAG, "Failed to preload dependency $depName for $skillName")
                    }
                }
            }
        }

        return skillLoader.loadSkill(skillName, this, forceReload) != null
    }

    fun preloadSkills(skillNames: List<String>) {
        val resolved = try {
            val allMetadata = availableSkills.values.map { pkg ->
                SkillMetadata(
                    name = pkg.name,
                    description = pkg.description,
                    version = pkg.version,
                    author = pkg.author,
                    dependencies = pkg.dependencies,
                    permissions = pkg.permissions
                )
            }
            DependencyResolver.resolve(allMetadata).map { it.name }
        } catch (e: Exception) {
            Log.w(TAG, "Dependency resolution failed, using original order: ${e.message}")
            skillNames
        }

        val sortedNames = resolved.filter { it in skillNames }
        sortedNames.forEach { name ->
            preloadSkill(name)
        }
    }

    fun unloadSkill(skillName: String): Boolean {
        val result = skillUnloader.unload(skillName)
        return result.success
    }

    fun unloadAllSkills(): SkillUnloader.UnloadAllResult {
        return skillUnloader.unloadAll()
    }

    fun isSkillLoaded(skillName: String): Boolean {
        return skillLoader.isLoaded(skillName)
    }

    fun getLoadedSkillCount(): Int {
        return skillLoader.getLoadedSkillCount()
    }

    fun getLoaderStats(): SkillLoader.LoaderStats {
        return skillLoader.getStats()
    }

    fun getCacheStats(): SkillCache.CacheStats {
        return skillCache.getStats()
    }

    fun getUnloadStats(): SkillUnloader.UnloadStats {
        return skillUnloader.getStats()
    }

    fun getSkillCache(): SkillCache {
        return skillCache
    }

    fun invalidateCache(skillName: String? = null) {
        if (skillName != null) {
            skillCache.invalidateSkill(skillName)
        } else {
            skillCache.invalidateAll()
        }
    }

    fun getSkillSystemPrompt(skillName: String): String? {
        refreshAvailableSkills()
        val skill = availableSkills[skillName] ?: return null

        val loadedSkill = skillLoader.loadSkill(skillName, this) ?: return null
        val content = loadedSkill.content ?: ""

        val sb = StringBuilder()
        sb.appendLine("Using package (Skill): ${skill.name}")
        sb.appendLine("Use Time: ${java.time.LocalDateTime.now()}")
        sb.appendLine("Execution policy:")
        sb.appendLine("Prioritize using the skill-provided instructions and bundled scripts, and complete tasks with terminal-related tools.")
        if (skill.description.isNotBlank()) {
            sb.appendLine("Description: ${skill.description}")
        }
        sb.appendLine("SKILL.md path: ${skill.skillFile.absolutePath}")
        sb.appendLine("Skill directory: ${skill.directory.absolutePath}")
        sb.appendLine("Directory structure:")
        sb.appendLine(buildDirectoryTreeText(skill.directory))
        sb.appendLine()
        sb.appendLine("SKILL.md:")
        sb.appendLine(content)

        return sb.toString()
    }

    private fun buildDirectoryTreeText(rootDir: File): String {
        val sb = StringBuilder()

        fun walk(dir: File, indent: String) {
            val children = dir.listFiles()
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            for (child in children) {
                sb.append(indent)
                sb.append("- ")
                sb.append(child.name)
                if (child.isDirectory) {
                    sb.appendLine("/")
                    walk(child, indent + "  ")
                } else {
                    sb.appendLine()
                }
            }
        }

        walk(rootDir, indent = "")

        if (sb.length == 0) return "(empty directory)"
        return sb.toString().trimEnd()
    }

    /**
     * 解析技能依赖关系，返回拓扑排序后的加载顺序
     * @throws IllegalStateException 存在循环依赖或缺失依赖
     */
    fun resolveSkillDependencies(): List<SkillMetadata> {
        refreshAvailableSkills()
        val allMetadata = availableSkills.values.map { skillPkg ->
            SkillMetadata(
                name = skillPkg.name,
                description = skillPkg.description,
                version = skillPkg.version,
                author = skillPkg.author,
                dependencies = skillPkg.dependencies,
                permissions = skillPkg.permissions
            )
        }
        Log.d(TAG, "Resolving dependencies for ${allMetadata.size} skills")
        return DependencyResolver.resolve(allMetadata)
    }

    /**
     * 检测技能间的循环依赖
     * @return 所有检测到的循环路径列表
     */
    fun detectCircularDependencies(): List<List<String>> {
        refreshAvailableSkills()
        val allMetadata = availableSkills.values.map { skillPkg ->
            SkillMetadata(
                name = skillPkg.name,
                description = skillPkg.description,
                version = skillPkg.version,
                author = skillPkg.author,
                dependencies = skillPkg.dependencies,
                permissions = skillPkg.permissions
            )
        }
        val cycles = DependencyResolver.detectCycles(allMetadata)
        if (cycles.isNotEmpty()) {
            Log.w(TAG, "Circular dependencies detected: $cycles")
        } else {
            Log.d(TAG, "No circular dependencies found")
        }
        return cycles
    }

    /**
     * 验证指定技能的依赖是否完备
     * @param skillName 技能名称
     * @return 缺失的依赖名称列表（空列表表示全部满足）
     */
    fun validateSkillDependencies(skillName: String): List<String> {
        refreshAvailableSkills()
        val skillPkg = availableSkills[skillName] ?: return listOf("Skill not found: $skillName")
        val metadata = SkillMetadata(
            name = skillPkg.name,
            description = skillPkg.description,
            version = skillPkg.version,
            author = skillPkg.author,
            dependencies = skillPkg.dependencies,
            permissions = skillPkg.permissions
        )
        val availableNames = availableSkills.keys
        return DependencyResolver.validateDependencies(metadata, availableNames)
    }

    /**
     * 批量验证所有技能的依赖完整性
     * @return 技能名称到缺失依赖列表的映射
     */
    fun validateAllDependencies(): Map<String, List<String>> {
        refreshAvailableSkills()
        val availableNames = availableSkills.keys
        val result = mutableMapOf<String, List<String>>()

        availableSkills.forEach { (name, skillPkg) ->
            val metadata = SkillMetadata(
                name = skillPkg.name,
                description = skillPkg.description,
                version = skillPkg.version,
                author = skillPkg.author,
                dependencies = skillPkg.dependencies,
                permissions = skillPkg.permissions
            )
            val missing = DependencyResolver.validateDependencies(metadata, availableNames)
            if (missing.isNotEmpty()) {
                result[name] = missing
            }
        }

        if (result.isEmpty()) {
            Log.d(TAG, "All skill dependencies are satisfied")
        } else {
            Log.w(TAG, "Found ${result.size} skills with missing dependencies")
        }
        return result
    }

    /**
     * 获取按依赖排序的加载顺序
     */
    fun getDependencySortedSkills(): List<String> {
        val resolved = resolveSkillDependencies()
        return resolved.map { it.name }
    }

    fun importSkillFromZip(zipFile: File): String {
        return importSkillFromZip(zipFile, null)
    }

    fun importSkillFromZip(zipFile: File, subDirPathInZip: String): String {
        if (!zipFile.exists() || !zipFile.canRead()) {
            return context.getString(R.string.skill_error_cannot_read_file, zipFile.absolutePath)
        }
        if (!zipFile.name.endsWith(".zip", ignoreCase = true)) {
            return context.getString(R.string.skill_error_only_support_zip)
        }

        val skillsRoot = try {
            getSkillsRootDir()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error getting skills directory", e)
            return context.getString(R.string.skill_error_cannot_access_dir, e.message ?: "")
        }

        val tmpDir = File(skillsRoot, ".import_tmp_${System.currentTimeMillis()}")
        if (!tmpDir.mkdirs()) {
            return context.getString(R.string.skill_error_create_tmp_dir_failed, tmpDir.absolutePath)
        }

        fun cleanupTmp() {
            try {
                tmpDir.deleteRecursively()
            } catch (_: Exception) {
            }
        }

        try {
            unzipToDirectory(zipFile, tmpDir)

            val normalizedSubDir = subDirPathInZip
                ?.trim()
                ?.trimStart('/')
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() }

            val zipRootDir = tmpDir
                .listFiles()
                ?.filter { it.isDirectory }
                ?.singleOrNull()
                ?: tmpDir

            val searchRoot: File = if (normalizedSubDir == null) {
                tmpDir
            } else {
                val baseCanonical = zipRootDir.canonicalFile
                val resolved = File(zipRootDir, normalizedSubDir)
                val resolvedCanonical = resolved.canonicalFile
                if (!resolvedCanonical.path.startsWith(baseCanonical.path + File.separator)) {
                    cleanupTmp()
                    return context.getString(R.string.skill_error_import_invalid_path)
                }
                if (!resolvedCanonical.exists()) {
                    cleanupTmp()
                    return context.getString(R.string.skill_error_import_path_not_found, normalizedSubDir)
                }
                resolvedCanonical
            }

            val directSkillFile = if (searchRoot.isDirectory) {
                File(searchRoot, "SKILL.md").let { primary ->
                    if (primary.exists()) primary else File(searchRoot, "skill.md")
                }.takeIf { it.exists() && it.isFile }
            } else {
                null
            }

            val skillMdCandidates = if (directSkillFile != null) {
                listOf(directSkillFile)
            } else {
                searchRoot.walkTopDown()
                    .filter { it.isFile && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("skill.md", ignoreCase = true)) }
                    .take(10)
                    .toList()
            }

            if (skillMdCandidates.isEmpty()) {
                cleanupTmp()
                return if (normalizedSubDir == null) {
                    context.getString(R.string.skill_error_import_no_skill_md)
                } else {
                    context.getString(R.string.skill_error_import_no_skill_md_in_path)
                }
            }

            val selectedSkillFile = skillMdCandidates.first()
            val selectedSkillDir = selectedSkillFile.parentFile ?: run {
                cleanupTmp()
                return context.getString(R.string.skill_error_import_skill_md_path_invalid)
            }

            val metadata = parseSkillMetadata(selectedSkillFile)
            val baseName = metadata.name.ifBlank {
                val isTmpRoot = try {
                    selectedSkillDir.canonicalFile == tmpDir.canonicalFile
                } catch (_: Exception) {
                    selectedSkillDir.absolutePath == tmpDir.absolutePath
                }
                if (isTmpRoot) {
                    zipFile.nameWithoutExtension
                } else {
                    selectedSkillDir.name.ifBlank { zipFile.nameWithoutExtension }
                }
            }
            val finalDir = File(skillsRoot, baseName.trim().ifBlank { "skill" })

            if (finalDir.exists()) {
                cleanupTmp()
                return context.getString(R.string.skill_error_import_duplicate_name, finalDir.name)
            }

            // Copy the detected skill directory to final location
            selectedSkillDir.copyRecursively(finalDir, overwrite = false)
            cleanupTmp()

            // refresh cache
            refreshAvailableSkills()

            val desc = metadata.description.ifBlank { "" }
            return if (desc.isNotBlank()) {
                context.getString(R.string.skill_imported_with_desc, finalDir.name, desc)
            } else {
                context.getString(R.string.skill_imported, finalDir.name)
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to import skill from zip", e)
            cleanupTmp()
            return context.getString(R.string.skill_error_import_failed, e.message ?: "")
        }
    }


    private fun unzipToDirectory(zipFile: File, destinationDir: File) {
        val destCanonical = destinationDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break

                val outFile = File(destinationDir, entry.name)
                val outCanonical = outFile.canonicalFile
                if (!outCanonical.path.startsWith(destCanonical.path + File.separator)) {
                    zis.closeEntry()
                    throw IllegalArgumentException("Zip entry is outside target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                    zis.closeEntry()
                    continue
                }

                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
