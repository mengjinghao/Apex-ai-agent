package com.apex.agent.plugins.burst.builtin

import com.apex.agent.domain.model.BurstTask
import com.apex.agent.plugins.burst.base.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 模板管理技能
 * 实现工作空间模板创建、应用和导出
 */
class TemplateManagerSkill : IBurstSkill {
    override lateinit var manifest: BurstSkillManifest
    
    private lateinit var context: BurstSkillContext
    private var isPaused = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val templates = ConcurrentHashMap<String, WorkspaceTemplate>()
    
    init {
        manifest = BurstSkillManifest(
            skillId = "template_manager",
            skillName = "模板管理",
            version = "1.0.0",
            description = "工作空间模板管理，支持模板创建、应用、导出和导入",
            author = "Apex Agent",
            tags = listOf("template", "workspace", "management"),
            priority = 65,
            capabilities = listOf(
                "template_creation",
                "template_application",
                "template_export",
                "template_import"
            )
        )
    }
    
    override fun initialize(context: BurstSkillContext) {
        this.context = context
    }
    
    override fun execute(task: BurstTask): BurstSkillResult = runBlocking {
        val startTime = System.currentTimeMillis()
        
        try {
            val operation = task.metadata["operation"] ?: "create"
            val templateId = task.metadata["templateId"]
            
            when (operation) {
                "create" -> {
                    val templateName = task.metadata["name"] ?: "Untitled"
                    val templateDescription = task.metadata["description"] ?: ""
                    val rawFiles = task.input.text ?: ""
                    val files = if (context.utilityProcessor?.isEnabled == true && rawFiles.isNotBlank()) {
                        val cleaned = runBlocking { context.utilityProcessor!!.cleanResponse(rawFiles) }
                        cleaned.split("\n").filter { it.isNotBlank() }
                    } else {
                        rawFiles.split("\n").filter { it.isNotBlank() }
                    }
                    
                    val template = WorkspaceTemplate(
                        id = "template_${System.currentTimeMillis()}",
                        name = templateName,
                        description = templateDescription,
                        files = files,
                        createdAt = System.currentTimeMillis()
                    )
                    
                    templates[template.id] = template
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Template created:
                            |- ID: ${template.id}
                            |- Name: $templateName
                            |- Files: ${files.size}
                            |- Created at: ${template.createdAt}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "apply" -> {
                    val template = templateId?.let { templates[it] }
                    
                    if (template == null) {
                        return@runBlocking BurstSkillResult(
                            success = false,
                            errorMessage = "Template not found: $templateId"
                        )
                    }
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Template applied:
                            |- ID: ${template.id}
                            |- Name: ${template.name}
                            |- Files to create: ${template.files.size}
                            ${template.files.take(5).joinToString("\n") { "- $it" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = template.files.size
                        )
                    )
                }
                "list" -> {
                    val allTemplates = templates.values.toList()
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Templates (${allTemplates.size}):
                            ${allTemplates.take(10).joinToString("\n") { "- ${it.id}: ${it.name} (${it.files.size} files)" }}
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = allTemplates.size
                        )
                    )
                }
                "export" -> {
                    val template = templateId?.let { templates[it] }
                    
                    if (template == null) {
                        return@runBlocking BurstSkillResult(
                            success = false,
                            errorMessage = "Template not found: $templateId"
                        )
                    }
                    
                    val exported = exportTemplate(template)
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = true,
                        output = """
                            |Template exported:
                            |- ID: ${template.id}
                            |- Name: ${template.name}
                            |- Export size: ${exported.length} chars
                        """.trimMargin(),
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = 1
                        )
                    )
                }
                "delete" -> {
                    val existed = templateId?.let { templates.remove(it) } != null
                    
                    val executionTime = System.currentTimeMillis() - startTime
                    
                    BurstSkillResult(
                        success = existed,
                        output = if (existed) {
                            "Template deleted: $templateId"
                        } else {
                            "Template not found: $templateId"
                        },
                        metrics = SkillMetrics(
                            executionTimeMs = executionTime,
                            stepsCompleted = if (existed) 1 else 0
                        )
                    )
                }
                else -> {
                    BurstSkillResult(
                        success = false,
                        errorMessage = "Unknown operation: $operation"
                    )
                }
            }
        } catch (e: Exception) {
            BurstSkillResult(
                success = false,
                errorMessage = e.message
            )
        }
    }
    
    fun createTemplate(name: String, description: String, files: List<String>): WorkspaceTemplate {
        val template = WorkspaceTemplate(
            id = "template_${System.currentTimeMillis()}",
            name = name,
            description = description,
            files = files,
            createdAt = System.currentTimeMillis()
        )
        templates[template.id] = template
        return template
    }
    
    fun applyTemplate(templateId: String): WorkspaceTemplate? {
        return templates[templateId]
    }
    
    fun exportTemplate(template: WorkspaceTemplate): String {
        return buildString {
            appendLine("# Template: ${template.name}")
            appendLine("# Description: ${template.description}")
            appendLine("# Created: ${template.createdAt}")
            appendLine()
            template.files.forEach { file ->
                appendLine(file)
            }
        }
    }
    
    fun importTemplate(exportedContent: String): WorkspaceTemplate? {
        val lines = exportedContent.split("\n")
        if (lines.isEmpty() || !lines[0].startsWith("# Template:")) {
            return null
        }
        
        val name = lines.getOrNull(0)?.removePrefix("# Template: ")?.trim() ?: "Imported"
        val description = lines.getOrNull(1)?.removePrefix("# Description: ")?.trim() ?: ""
        val createdAt = lines.getOrNull(2)?.removePrefix("# Created: ")?.trim()?.toLongOrNull() ?: System.currentTimeMillis()
        
        val files = lines.drop(4).filter { it.isNotBlank() }
        
        val template = WorkspaceTemplate(
            id = "template_${System.currentTimeMillis()}",
            name = name,
            description = description,
            files = files,
            createdAt = createdAt
        )
        
        templates[template.id] = template
        return template
    }
    
    fun listTemplates(): List<WorkspaceTemplate> {
        return templates.values.toList()
    }
    
    fun deleteTemplate(templateId: String): Boolean {
        return templates.remove(templateId) != null
    }
    
    override fun pause() {
        isPaused = true
    }
    
    override fun resume() {
        isPaused = false
    }
    
    override fun destroy() {
        scope.cancel()
        templates.clear()
    }
    
    override fun mutate(rate: Float): IBurstSkill = this
    
    override fun crossover(other: IBurstSkill): IBurstSkill = this
    
    override fun evaluate(): Float = 0.72f
    
    data class WorkspaceTemplate(
        val id: String,
        val name: String,
        val description: String,
        val files: List<String>,
        val createdAt: Long
    )
}