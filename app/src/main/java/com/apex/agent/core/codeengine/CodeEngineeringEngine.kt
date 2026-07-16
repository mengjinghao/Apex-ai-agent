package com.apex.agent.core.codeengine

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CodeEngineeringEngine(private val context: Context) {

    private val TAG = "CodeEngineeringEngine"

    enum class CodeTaskType {
        ANALYSIS,
        GENERATION,
        REFACTORING,
        DEBUGGING,
        OPTIMIZATION,
        SECURITY_AUDIT,
        DOCUMENTATION,
        TEST_GENERATION
    }

    enum class CodeLanguage {
        KOTLIN,
        JAVA,
        SWIFT,
        GO,
        PYTHON,
        JAVASCRIPT,
        TYPESCRIPT,
        RUST,
        C,
        CPP,
        CSHARP,
        RUBY,
        PHP,
        UNKNOWN
    }

    data class CodeProject(
        val id: String,
        val rootPath: String,
        val name: String,
        val language: CodeLanguage,
        val structure: ProjectStructure,
        val dependencies: List<Dependency>,
        val buildSystem: BuildSystem,
        val lastAnalyzed: Long = 0
    )

    data class ProjectStructure(
        val files: List<CodeFile>,
        val directories: Map<String, List<String>>,
        val entryPoints: List<String>
    )

    data class CodeFile(
        val path: String,
        val name: String,
        val language: CodeLanguage,
        val sizeBytes: Long,
        val lastModified: Long,
        val complexityScore: Float = 0f,
        val issues: List<CodeIssue> = emptyList()
    )

    data class CodeIssue(
        val id: String,
        val type: IssueType,
        val severity: Severity,
        val lineNumber: Int,
        val description: String,
        val suggestion: String,
        val confidence: Float
    )

    enum class IssueType {
        SYNTAX_ERROR,
        LOGIC_ERROR,
        PERFORMANCE_ISSUE,
        SECURITY_VULNERABILITY,
        CODE_SMELL,
        DOCUMENTATION_MISSING,
        TEST_COVERAGE_LOW,
        DUPLICATE_CODE,
        DEPRECATED_USAGE,
        INCONSISTENT_STYLE
    }

    enum class Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        LOW,
        INFO
    }

    data class Dependency(
        val name: String,
        val version: String,
        val scope: String,
        val isOutdated: Boolean = false,
        val hasSecurityIssue: Boolean = false
    )

    enum class BuildSystem {
        GRADLE,
        MAVEN,
        NPM,
        CARGO,
        GO_MODULES,
        CMAKE,
        PYTHON_SETUP,
        UNKNOWN
    }

    data class RefactoringSuggestion(
        val id: String,
        val type: RefactoringType,
        val file: String,
        val lineStart: Int,
        val lineEnd: Int,
        val originalCode: String,
        val suggestedCode: String,
        val description: String,
        val benefits: List<String>,
        val riskLevel: RiskLevel
    )

    enum class RefactoringType {
        EXTRACT_METHOD,
        EXTRACT_VARIABLE,
        INLINE_METHOD,
        INLINE_VARIABLE,
        RENAME,
        MOVE_METHOD,
        MOVE_FIELD,
        PULL_UP,
        PUSH_DOWN,
        REPLACE_CONDITIONAL_WITH_POLYMORPHISM,
        INTRODUCE_NULL_OBJECT,
        DECOMPOSE_CONDITIONAL,
        CONSOLIDATE_CONDITIONAL,
        REPLACE_MAGIC_NUMBER_WITH_SYMBOLIC_CONSTANT
    }

    enum class RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        EXTREME
    }

    private val projectsDir: File
        get() = File(context.filesDir, "code_projects").also {
            if (!it.exists()) it.mkdirs()
        }

    private val cacheDir: File
        get() = File(context.cacheDir, "code_analysis").also {
            if (!it.exists()) it.mkdirs()
        }

    suspend fun analyzeProject(rootPath: String): CodeProject? = withContext(Dispatchers.IO) {
        AppLogger.d(TAG, "开始分析项? ${rootPath}")

        val rootFile = File(rootPath)
        if (!rootFile.exists() || !rootFile.isDirectory) {
            AppLogger.e(TAG, "项目目录不存? ${rootPath}")
            return@withContext null
        }

        val projectId = rootFile.name + "_" + System.currentTimeMillis().toString()
        val language = detectLanguage(rootFile)
        val buildSystem = detectBuildSystem(rootFile)

        val codeFiles = mutableListOf<CodeFile>()
        val directories = mutableMapOf<String, MutableList<String>>()
        val entryPoints = mutableListOf<String>()

        scanDirectory(rootFile, "", codeFiles, directories, entryPoints)

        val dependencies = analyzeDependencies(rootFile, buildSystem)

        val structure = ProjectStructure(
            files = codeFiles,
            directories = directories,
            entryPoints = entryPoints
        )

        val project = CodeProject(
            id = projectId,
            rootPath = rootPath,
            name = rootFile.name,
            language = language,
            structure = structure,
            dependencies = dependencies,
            buildSystem = buildSystem,
            lastAnalyzed = System.currentTimeMillis()
        )

        saveProject(project)
        AppLogger.d(TAG, "项目分析完成: ${project.name}, ${codeFiles.size} 个文?)

        project
    }

    private fun detectLanguage(rootDir: File): CodeLanguage {
        val extensions = mutableMapOf<CodeLanguage, Int>()

        rootDir.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                when (file.extension.lowercase()) {
                    "kt", "kts" -> extensions[CodeLanguage.KOTLIN] = extensions.getOrDefault(CodeLanguage.KOTLIN, 0) + 1
                    "java" -> extensions[CodeLanguage.JAVA] = extensions.getOrDefault(CodeLanguage.JAVA, 0) + 1
                    "py" -> extensions[CodeLanguage.PYTHON] = extensions.getOrDefault(CodeLanguage.PYTHON, 0) + 1
                    "js" -> extensions[CodeLanguage.JAVASCRIPT] = extensions.getOrDefault(CodeLanguage.JAVASCRIPT, 0) + 1
                    "ts" -> extensions[CodeLanguage.TYPESCRIPT] = extensions.getOrDefault(CodeLanguage.TYPESCRIPT, 0) + 1
                    "swift" -> extensions[CodeLanguage.SWIFT] = extensions.getOrDefault(CodeLanguage.SWIFT, 0) + 1
                    "go" -> extensions[CodeLanguage.GO] = extensions.getOrDefault(CodeLanguage.GO, 0) + 1
                    "rs" -> extensions[CodeLanguage.RUST] = extensions.getOrDefault(CodeLanguage.RUST, 0) + 1
                    "c" -> extensions[CodeLanguage.C] = extensions.getOrDefault(CodeLanguage.C, 0) + 1
                    "cpp", "cc", "cxx" -> extensions[CodeLanguage.CPP] = extensions.getOrDefault(CodeLanguage.CPP, 0) + 1
                }
            }

        return extensions.maxByOrNull { it.value }?.key ?: CodeLanguage.UNKNOWN
    }

    private fun detectBuildSystem(rootDir: File): BuildSystem {
        return when {
            File(rootDir, "build.gradle.kts").exists() ||
            File(rootDir, "build.gradle").exists() -> BuildSystem.GRADLE

            File(rootDir, "pom.xml").exists() -> BuildSystem.MAVEN

            File(rootDir, "package.json").exists() -> BuildSystem.NPM

            File(rootDir, "Cargo.toml").exists() -> BuildSystem.CARGO

            File(rootDir, "go.mod").exists() -> BuildSystem.GO_MODULES

            File(rootDir, "CMakeLists.txt").exists() -> BuildSystem.CMAKE

            File(rootDir, "setup.py").exists() ||
            File(rootDir, "requirements.txt").exists() -> BuildSystem.PYTHON_SETUP

            else -> BuildSystem.UNKNOWN
        }
    }

    private fun scanDirectory(
        dir: File,
        relativePath: String,
        codeFiles: MutableList<CodeFile>,
        directories: MutableMap<String, MutableList<String>>,
        entryPoints: MutableList<String>
    ) {
        val files = dir.listFiles() ?: return

        val dirFiles = mutableListOf<String>()
        files.forEach { file ->
            if (file.isFile) {
                dirFiles.add(file.name)

                if (isCodeFile(file)) {
                    val issues = analyzeFile(file)
                    val complexity = calculateComplexity(file)

                    codeFiles.add(
                        CodeFile(
                            path = if (relativePath.isNotEmpty()) "${relativePath}/${file.name}" else file.name,
                            name = file.name,
                            language = getLanguageFromExtension(file.extension),
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            complexityScore = complexity,
                            issues = issues
                        )
                    )

                    if (isEntryPoint(file)) {
                        entryPoints.add(if (relativePath.isNotEmpty()) "${relativePath}/${file.name}" else file.name)
                    }
                }
            } else if (file.isDirectory && !file.name.startsWith(".")) {
                scanDirectory(
                    file,
                    if (relativePath.isNotEmpty()) "${relativePath}/${file.name}" else file.name,
                    codeFiles,
                    directories,
                    entryPoints
                )
            }
        }

        if (dirFiles.isNotEmpty()) {
            directories[relativePath] = dirFiles
        }
    }

    private fun isCodeFile(file: File): Boolean {
        return file.extension.lowercase() in listOf(
            "kt", "kts", "java", "py", "js", "ts", "swift", "go", "rs",
            "c", "cpp", "cc", "cxx", "cs", "rb", "php"
        )
    }

    private fun isEntryPoint(file: File): Boolean {
        val name = file.name.lowercase()
        return when {
            name.contains("main") -> true
            name == "app.kt" || name == "app.java" -> true
            name == "index.js" || name == "index.ts" -> true
            name == "__main__.py" -> true
            else -> false
        }
    }

    private fun getLanguageFromExtension(extension: String): CodeLanguage {
        return when (extension.lowercase()) {
            "kt", "kts" -> CodeLanguage.KOTLIN
            "java" -> CodeLanguage.JAVA
            "py" -> CodeLanguage.PYTHON
            "js" -> CodeLanguage.JAVASCRIPT
            "ts" -> CodeLanguage.TYPESCRIPT
            "swift" -> CodeLanguage.SWIFT
            "go" -> CodeLanguage.GO
            "rs" -> CodeLanguage.RUST
            "c" -> CodeLanguage.C
            "cpp", "cc", "cxx" -> CodeLanguage.CPP
            "cs" -> CodeLanguage.CSHARP
            "rb" -> CodeLanguage.RUBY
            "php" -> CodeLanguage.PHP
            else -> CodeLanguage.UNKNOWN
        }
    }

    private fun analyzeFile(file: File): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()

        try {
            val content = file.readText()
            val lines = content.lines()

            if (content.length > 50000) {
                issues.add(
                    CodeIssue(
                        id = "FILE_SIZE_${file.name}",
                        type = IssueType.PERFORMANCE_ISSUE,
                        severity = Severity.MEDIUM,
                        lineNumber = 1,
                        description = "文件过长（超?0000字符），建议拆分",
                        suggestion = "将代码拆分为多个小文?,
                        confidence = 0.7f
                    )
                )
            }

            lines.forEachIndexed { index, line ->
                val lineNum = index + 1

                if (line.length > 120) {
                    issues.add(
                        CodeIssue(
                            id = "LONG_LINE_${file.name}_${lineNum}",
                            type = IssueType.CODE_SMELL,
                            severity = Severity.LOW,
                            lineNumber = lineNum,
                            description = "行过长（${line.length}字符?,
                            suggestion = "将长行拆分为多行",
                            confidence = 0.8f
                        )
                    )
                }

                val magicNumbers = line.findMagicNumbers()
                if (magicNumbers.isNotEmpty()) {
                    issues.add(
                        CodeIssue(
                            id = "MAGIC_NUMBER_${file.name}_${lineNum}",
                            type = IssueType.CODE_SMELL,
                            severity = Severity.LOW,
                            lineNumber = lineNum,
                            description = "发现魔法数字: ${magicNumbers}",
                            suggestion = "使用命名常量代替魔法数字",
                            confidence = 0.6f
                        )
                    )
                }

                if (line.contains("TODO") || line.contains("FIXME") || line.contains("XXX")) {
                    issues.add(
                        CodeIssue(
                            id = "TODO_${file.name}_${lineNum}",
                            type = IssueType.DOCUMENTATION_MISSING,
                            severity = Severity.INFO,
                            lineNumber = lineNum,
                            description = "发现待办事项注释",
                            suggestion = "完成代码或移除待办注?,
                            confidence = 0.9f
                        )
                    )
                }
            }

        } catch (e: Exception) {
            AppLogger.w(TAG, "分析文件出错: ${file.name}", e)
        }

        return issues
    }

    private fun String.findMagicNumbers(): List<String> {
        val regex = Regex("""\b(0x[0-9a-fA-F]+|\d+\.?\d*[eE][+-]?\d+|\d+\.\d+|\d+)\b""")
        val matches = regex.findAll(this).map { it.value }.toList()

        return matches.filter { num ->
            num.toDoubleOrNull()?.let { d ->
                d != 0.0 && d != 1.0 && d != -1.0
            } == true
        }
    }

    private fun calculateComplexity(file: File): Float {
        return try {
            val content = file.readText()
            val lines = content.lines()
            val conditionals = lines.count { line ->
                line.contains("if") || line.contains("for") ||
                line.contains("while") || line.contains("case") ||
                line.contains("catch")
            }.toFloat()

            (conditionals / lines.size.coerceAtLeast(1) * 100).coerceAtMost(100f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun analyzeDependencies(rootDir: File, buildSystem: BuildSystem): List<Dependency> {
        val dependencies = mutableListOf<Dependency>()

        try {
            when (buildSystem) {
                BuildSystem.GRADLE -> {
                    val buildFile = File(rootDir, "build.gradle.kts")
                    if (buildFile.exists()) {
                        val content = buildFile.readText()
                        val depRegex = Regex("""implementation\s*\(\s*"([^"]+)"\s*\)""")
                        depRegex.findAll(content).forEach { match ->
                            val depString = match.groupValues[1]
                            if (depString.contains(":")) {
                                val parts = depString.split(":")
                                if (parts.size >= 2) {
                                    dependencies.add(
                                        Dependency(
                                            name = parts[0] + ":" + parts[1],
                                            version = parts.getOrElse(2) { "" },
                                            scope = "implementation"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                BuildSystem.NPM -> {
                    val pkgFile = File(rootDir, "package.json")
                    if (pkgFile.exists()) {
                        val content = pkgFile.readText()
                        try {
                            val json = JSONObject(content)
                            val depsObj = json.optJSONObject("dependencies")
                            depsObj?.keys()?.forEach { key ->
                                dependencies.add(
                                    Dependency(
                                        name = key,
                                        version = depsObj.getString(key),
                                        scope = "dependencies"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "解析 package.json 出错", e)
                        }
                    }
                }

                else -> {}
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "分析依赖出错", e)
        }

        return dependencies
    }

    private suspend fun saveProject(project: CodeProject) = withContext(Dispatchers.IO) {
        try {
            val projectFile = File(projectsDir, "${project.id}.json")
            val json = JSONObject().apply {
                put("id", project.id)
                put("rootPath", project.rootPath)
                put("name", project.name)
                put("language", project.language.name)
                put("buildSystem", project.buildSystem.name)
                put("lastAnalyzed", project.lastAnalyzed)
            }
            projectFile.writeText(json.toString(2))
            AppLogger.d(TAG, "项目信息已保? ${project.id}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "保存项目信息失败", e)
        }
    }

    suspend fun generateRefactoringSuggestions(projectId: String): List<RefactoringSuggestion> =
        withContext(Dispatchers.IO) {
            AppLogger.d(TAG, "生成重构建议: ${projectId}")

            val suggestions = mutableListOf<RefactoringSuggestion>()

            val projectFile = File(projectsDir, "${projectId}.json")
            if (!projectFile.exists()) {
                AppLogger.e(TAG, "项目不存? ${projectId}")
                return@withContext suggestions
            }

            val projectJson = JSONObject(projectFile.readText())
            val rootPath = projectJson.getString("rootPath")
            val rootDir = File(rootPath)

            scanDirectoryForRefactoring(rootDir, "", suggestions)

            AppLogger.d(TAG, "生成 ${suggestions.size} 条重构建?)
            suggestions
        }

    private fun scanDirectoryForRefactoring(
        dir: File,
        relativePath: String,
        suggestions: MutableList<RefactoringSuggestion>
    ) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && isCodeFile(file)) {
                val filePath = if (relativePath.isNotEmpty()) "${relativePath}/${file.name}" else file.name
                analyzeFileForRefactoring(file, filePath, suggestions)
            } else if (file.isDirectory && !file.name.startsWith(".")) {
                scanDirectoryForRefactoring(
                    file,
                    if (relativePath.isNotEmpty()) "${relativePath}/${file.name}" else file.name,
                    suggestions
                )
            }
        }
    }

    private fun analyzeFileForRefactoring(
        file: File,
        filePath: String,
        suggestions: MutableList<RefactoringSuggestion>
    ) {
        try {
            val content = file.readText()
            val lines = content.lines()

            lines.forEachIndexed { index, line ->
                val lineNum = index + 1

                val magicNumbers = line.findMagicNumbers()
                if (magicNumbers.isNotEmpty()) {
                    magicNumbers.forEach { num ->
                        suggestions.add(
                            RefactoringSuggestion(
                                id = "REFACTOR_MAGIC_${file.name}_${lineNum}",
                                type = RefactoringType.REPLACE_MAGIC_NUMBER_WITH_SYMBOLIC_CONSTANT,
                                file = filePath,
                                lineStart = lineNum,
                                lineEnd = lineNum,
                                originalCode = line,
                                suggestedCode = line.replace(num, "CONSTANT_NAME"),
                                description = "用命名常量替换魔法数?${num}",
                                benefits = listOf("提高代码可读?, "便于统一修改", "减少错误"),
                                riskLevel = RiskLevel.LOW
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "分析文件重构建议出错: ${file.name}", e)
        }
    }

    suspend fun generateReport(projectId: String): String = withContext(Dispatchers.IO) {
        val projectFile = File(projectsDir, "${projectId}.json")
        if (!projectFile.exists()) {
            return@withContext "项目不存? ${projectId}"
        }

        val projectJson = JSONObject(projectFile.readText())

        buildString {
            appendLine("=== 代码工程分析报告 ===")
            appendLine()
            appendLine("【项目信息?)
            appendLine("名称: ${projectJson.getString("name")}")
            appendLine("路径: ${projectJson.getString("rootPath")}")
            appendLine("语言: ${projectJson.getString("language")}")
            appendLine("构建系统: ${projectJson.getString("buildSystem")}")
            appendLine("最后分? ${projectJson.getLong("lastAnalyzed")}")
            appendLine()
            appendLine("【建议操作?)
            appendLine("1. 运行详细代码分析")
            appendLine("2. 查看重构建议")
            appendLine("3. 执行安全审计")
        }
    }

    suspend fun cleanupOldProjects(daysToKeep: Int = 60) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

        projectsDir.listFiles()?.forEach { file ->
            try {
                val json = JSONObject(file.readText())
                val analyzed = json.getLong("lastAnalyzed")

                if (analyzed < cutoffTime) {
                    file.delete()
                    AppLogger.d(TAG, "清理旧项? ${file.name}")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "跳过清理文件: ${file.name}", e)
            }
        }
    }
}