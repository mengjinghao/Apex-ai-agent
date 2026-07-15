package com.apex.agent.core.ai.analysis

import com.apex.agent.ui.components.burstmode.*
import com.apex.agent.core.codeengine.Dependency
import com.apex.agent.core.normal.multimodal.FileType
import com.apex.agent.core.tools.defaultTool.standard.name

/**
 * 项目结构分析�?
 * 
 * 分析 GitHub 仓库的结构，识别项目类型、关键文件等
 */
class ProjectStructureAnalyzer {
    
    /**
     * 分析项目结构
     */
    suspend fun analyzeProject(files: List<GitHubFileNode>): ProjectAnalysis {
        val allFiles = collectAllFiles(files)
        return ProjectAnalysis(
            projectType = detectProjectType(allFiles),
            mainLanguage = detectMainLanguage(allFiles),
            keyFiles = identifyKeyFiles(allFiles),
            dependencies = extractDependencies(allFiles),
            architecture = inferArchitecture(allFiles),
            complexity = calculateComplexity(allFiles),
            fileCount = allFiles.size,
            totalSize = allFiles.sumOf { it.size }
        )
    }
    
    /**
     * 收集所有文件（递归�?
     */
    private fun collectAllFiles(nodes: List<GitHubFileNode>): List<GitHubFileNode.File> {
        val files = mutableListOf<GitHubFileNode.File>()
        for (node in nodes) {
            when (node) {
                is GitHubFileNode.File -> files.add(node)
                is GitHubFileNode.Directory -> files.addAll(collectAllFiles(node.children))
            }
        }
        return files
    }
    
    /**
     * 检测项目类�?
     */
    private fun detectProjectType(files: List<GitHubFileNode.File>): ProjectType {
        val fileNames = files.map { it.name }.toSet()
        val paths = files.map { it.path }.toSet()
        return when {
            // Android Gradle 项目
            fileNames.contains("build.gradle.kts") || fileNames.contains("build.gradle") -> {
                if (paths.any { it.contains("app/src/main") }) {
                    ProjectType.ANDROID_GRADLE
                } else {
                    ProjectType.JAVA_MAVEN
                }
            }
            
            // Java Maven 项目
            fileNames.contains("pom.xml") -> ProjectType.JAVA_MAVEN
            
            // Node.js 项目
            fileNames.contains("package.json") -> ProjectType.NODE_JS
            
            // Python 项目
            fileNames.contains("requirements.txt") || 
            fileNames.contains("setup.py") ||
            fileNames.contains("pyproject.toml") -> ProjectType.PYTHON
            
            // Rust 项目
            fileNames.contains("Cargo.toml") -> ProjectType.RUST
            
            // Go 项目
            fileNames.contains("go.mod") || fileNames.contains("go.sum") -> ProjectType.GO
            
            else -> ProjectType.UNKNOWN
        }
    }
    
    /**
     * 检测主要编程语言
     */
    private fun detectMainLanguage(files: List<GitHubFileNode.File>): String {
        val languageCount = mutableMapOf<String, Int>()
        
        files.forEach { file ->
            file.language?.let { lang ->
                languageCount[lang] = languageCount.getOrDefault(lang, 0) + 1
            }
        }
        return languageCount.maxByOrNull { it.value }?.key ?: "Unknown"
    }
    
    /**
     * 识别关键文件
     */
    private fun identifyKeyFiles(files: List<GitHubFileNode.File>): List<KeyFile> {
        val keyFiles = mutableListOf<KeyFile>()
        
        files.forEach { file ->
            val keyFile = categorizeAndScoreFile(file)
        if (keyFile != null) {
                keyFiles.add(keyFile)
            }
        }
        return keyFiles.sortedByDescending { it.importance }.take(20)
    }
    
    /**
     * 分类和评分文�?
     */
    private fun categorizeAndScoreFile(file: GitHubFileNode.File): KeyFile? {
        val name = file.name.lowercase()
        val path = file.path.lowercase()
        return when {
            // README 文件 - 最高优先级
            name.startsWith("readme") -> KeyFile(
                path = file.path,
                importance = 1.0f,
                category = "Documentation",
                reason = "项目说明文档"
            )
            
            // 构建配置文件
            name == "build.gradle.kts" || name == "build.gradle" -> KeyFile(
                path = file.path,
                importance = 0.9f,
                category = "Build Configuration",
                reason = "Gradle 构建配置"
            )
            
            name == "pom.xml" -> KeyFile(
                path = file.path,
                importance = 0.9f,
                category = "Build Configuration",
                reason = "Maven 构建配置"
            )
            
            name == "package.json" -> KeyFile(
                path = file.path,
                importance = 0.9f,
                category = "Build Configuration",
                reason = "NPM 包配�?
            )
            
            // 主入口文�?
            path.contains("src/main") && (name.endsWith(".kt") || name.endsWith(".java")) -> {
                if (name.contains("Main") || name.contains("App") || name.contains("Application")) {
                    KeyFile(
                        path = file.path,
                        importance = 0.85f,
                        category = "Entry Point",
                        reason = "应用主入�?
                    )
                } else {
                    null
                }
            }
            
            // 配置文件
            name == ".gitignore" -> KeyFile(
                path = file.path,
                importance = 0.7f,
                category = "Configuration",
                reason = "Git 忽略规则"
            )
            
            name == "LICENSE" -> KeyFile(
                path = file.path,
                importance = 0.6f,
                category = "Legal",
                reason = "许可证文�?
            )
            
            // 测试文件
            path.contains("test") || path.contains("spec") -> KeyFile(
                path = file.path,
                importance = 0.5f,
                category = "Test",
                reason = "测试文件"
            )
            
            else -> null
        }
    }
    
    /**
     * 提取依赖信息
     */
    private fun extractDependencies(files: List<GitHubFileNode.File>): List<Dependency> {
        val dependencies = mutableListOf<Dependency>()
        
        // 这里需要实际读取文件内容来解析依赖
        // 暂时返回空列表，后续可以实现
    return dependencies
    }
    
    /**
     * 推断架构模式
     */
    private fun inferArchitecture(files: List<GitHubFileNode.File>): String? {
        val paths = files.map { it.path }.toSet()
        return when {
            // MVVM 架构（Android�?
            paths.any { it.contains("/view/") } &&
            paths.any { it.contains("/viewmodel/") } &&
            paths.any { it.contains("/model/") } -> "MVVM"
            
            // Clean Architecture
            paths.any { it.contains("/domain/") } &&
            paths.any { it.contains("/data/") } &&
            paths.any { it.contains("/presentation/") } -> "Clean Architecture"
            
            // MVC
            paths.any { it.contains("/controller/") } &&
            paths.any { it.contains("/model/") } &&
            paths.any { it.contains("/view/") } -> "MVC"
            
            else -> null
        }
    }
    
    /**
     * 计算复杂�?
     */
    private fun calculateComplexity(files: List<GitHubFileNode.File>): ComplexityScore {
        val fileCount = files.size
        val sourceFiles = files.count { it.type == FileType.SOURCE_CODE }
        return when {
            fileCount > 1000 || sourceFiles > 500 -> ComplexityScore.VERY_HIGH
            fileCount > 500 || sourceFiles > 200 -> ComplexityScore.HIGH
            fileCount > 100 || sourceFiles > 50 -> ComplexityScore.MEDIUM
            else -> ComplexityScore.LOW
        }
    }
    
    /**
     * 生成项目摘要
     */
    fun generateSummary(analysis: ProjectAnalysis): String {
        return buildString {
            appendLine("## 项目分析报告")
            appendLine()
            appendLine("**项目类型**: ${analysis.projectType}")
            appendLine("**主要语言**: ${analysis.mainLanguage}")
            appendLine("**文件数量**: ${analysis.fileCount}")
            appendLine("**总大�?*: ${formatSize(analysis.totalSize)}")
            appendLine("**复杂�?*: ${analysis.complexity}")
            appendLine()
        if (analysis.architecture != null) {
                appendLine("**架构模式**: ${analysis.architecture}")
                appendLine()
            }
            
            appendLine("### 关键文件")
            analysis.keyFiles.take(10).forEach { keyFile ->
                appendLine("- `${keyFile.path}` (${keyFile.category}) - ${keyFile.reason}")
            }
        if (analysis.dependencies.isNotEmpty()) {
                appendLine()
                appendLine("### 主要依赖")
                analysis.dependencies.take(10).forEach { dep ->
                    appendLine("- ${dep.name} ${dep.version ?: ""}")
                }
            }
        }
    }
        private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
