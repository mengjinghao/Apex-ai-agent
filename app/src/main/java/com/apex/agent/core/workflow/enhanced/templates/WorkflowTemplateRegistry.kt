package com.apex.agent.core.workflow.enhanced.templates

import com.apex.agent.core.workflow.enhanced.model.EnhancedWorkflow
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 工作流模板 - 可复用的工作流定义
 *
 * 参照 n8n Template Library、Dify DSL 模板市场
 */

/**
 * 模板参数类型
 */
enum class TemplateParamType {
    STRING, NUMBER, BOOLEAN, SECRET, SELECT, MULTI_SELECT
}

/**
 * 模板参数 - 用户安装模板时需要填写的参数
 */
data class TemplateParameter(
    val key: String,
    val displayName: String,
    val type: TemplateParamType,
    val required: Boolean = true,
    val defaultValue: String? = null,
    val description: String = "",
    val options: List<String>? = null,        // SELECT/MULTI_SELECT 选项
    val placeholder: String? = null,
    val secret: Boolean = false               // 是否敏感（不回显）
)

/**
 * 模板元信息
 */
data class TemplateMeta(
    val name: String,
    val description: String,
    val author: String = "anonymous",
    val category: String = "general",         // automation / rag / agent / data / utility
    val tags: List<String> = emptyList(),
    val version: String = "1.0.0",
    val previewImageUrl: String? = null,
    val requiredPermissions: List<String> = emptyList(),
    val minAppVersion: String? = null
)

/**
 * 工作流模板
 */
data class WorkflowTemplate(
    val id: String = "tpl_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
    val meta: TemplateMeta,
    val definitionJson: String,               // EnhancedWorkflow JSON
    val parameters: List<TemplateParameter> = emptyList(),
    val installCount: Int = 0,
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 模板安装结果
 */
sealed class TemplateInstallResult {
    data class MissingParameters(val missing: List<String>) : TemplateInstallResult()

/**
 * 模板注册表接口
 */
interface WorkflowTemplateRegistry {
    /** 列出模板（可按分类/标签/关键字过滤） */
    suspend fun list(
        category: String? = null,
        query: String? = null,
        tags: List<String> = emptyList(),
        limit: Int = 50
    ): List<WorkflowTemplate>

    /** 获取模板 */
    suspend fun get(templateId: String): WorkflowTemplate?

    /** 安装模板为工作流 */
    suspend fun install(templateId: String, params: Map<String, String>): TemplateInstallResult

    /** 发布新模板 */
    suspend fun publish(definition: EnhancedWorkflow, meta: TemplateMeta, parameters: List<TemplateParameter> = emptyList()): WorkflowTemplate

    /** 评分 */
    suspend fun rate(templateId: String, score: Int): WorkflowTemplate?

    /** 删除模板 */
    suspend fun delete(templateId: String): Boolean

    /** 按热度排序 */
    suspend fun popular(limit: Int = 10): List<WorkflowTemplate>

    /** 按评分排序 */
    suspend fun topRated(limit: Int = 10): List<WorkflowTemplate>
}

/**
 * 内存模板注册表实现
 */
class InMemoryTemplateRegistry : WorkflowTemplateRegistry {

    private val templates = ConcurrentHashMap<String, WorkflowTemplate>()
    private val ratings = ConcurrentHashMap<String, MutableList<Int>>()
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun list(
        category: String?,
        query: String?,
        tags: List<String>,
        limit: Int
    ): List<WorkflowTemplate> {
        return templates.values
            .filter { t ->
                (category == null || t.meta.category == category) &&
                (query.isNullOrBlank() ||
                    t.meta.name.contains(query, ignoreCase = true) ||
                    t.meta.description.contains(query, ignoreCase = true) ||
                    t.meta.tags.any { it.contains(query, ignoreCase = true) }) &&
                (tags.isEmpty() || t.meta.tags.any { it in tags })
            }
            .sortedByDescending { it.installCount }
            .take(limit)
    }

    override suspend fun get(templateId: String): WorkflowTemplate? = templates[templateId]

    override suspend fun install(
        templateId: String,
        params: Map<String, String>
    ): TemplateInstallResult {
        val template = templates[templateId]
            ?: return TemplateInstallResult.Failure(IllegalArgumentException("模板 $templateId 不存在"))

        // 校验必填参数
        val missing = template.parameters.filter { it.required && params[it.key].isNullOrBlank() }.map { it.key }
        if (missing.isNotEmpty()) return TemplateInstallResult.MissingParameters(missing)

        // 解析模板，替换参数占位符
        return try {
            var jsonStr = template.definitionJson
            template.parameters.forEach { p ->
                val value = params[p.key] ?: p.defaultValue ?: ""
                val placeholder = "\${{${p.key}}}"
                jsonStr = jsonStr.replace(placeholder, value)
            }

            val workflow = EnhancedWorkflow.fromJson(jsonStr)
                ?: return TemplateInstallResult.ValidationError(listOf("模板 JSON 解析失败"))

            val validation = workflow.validate()
            if (!validation.isValid) {
                return TemplateInstallResult.ValidationError(validation.errors)
            }

            // 增加安装计数
            val updated = template.copy(installCount = template.installCount + 1)
            templates[templateId] = updated

            TemplateInstallResult.Success(workflow, validation.warnings)
        } catch (e: Exception) {
            TemplateInstallResult.Failure(e)
        }
    }

    override suspend fun publish(
        definition: EnhancedWorkflow,
        meta: TemplateMeta,
        parameters: List<TemplateParameter>
    ): WorkflowTemplate {
        val template = WorkflowTemplate(
            meta = meta,
            definitionJson = definition.toJson(),
            parameters = parameters
        )
        templates[template.id] = template
        return template
    }

    override suspend fun rate(templateId: String, score: Int): WorkflowTemplate? {
        val template = templates[templateId] ?: return null
        val s = score.coerceIn(1, 5)
        ratings.computeIfAbsent(templateId) { mutableListOf() }.add(s)
        val allRatings = ratings[templateId]!!
        val avg = allRatings.average()
        val updated = template.copy(rating = avg, ratingCount = allRatings.size)
        templates[templateId] = updated
        return updated
    }

    override suspend fun delete(templateId: String): Boolean {
        return templates.remove(templateId) != null
    }

    override suspend fun popular(limit: Int): List<WorkflowTemplate> {
        return templates.values.sortedByDescending { it.installCount }.take(limit)
    }

    override suspend fun topRated(limit: Int): List<WorkflowTemplate> {
        return templates.values
            .filter { it.ratingCount > 0 }
            .sortedByDescending { it.rating }
            .take(limit)
    }
}

/**
 * 模板注册表持有者
 */
object TemplateRegistryHolder {
    @Volatile
    private var instance: WorkflowTemplateRegistry = InMemoryTemplateRegistry()

    fun get(): WorkflowTemplateRegistry = instance
    fun set(registry: WorkflowTemplateRegistry) { instance = registry }
}
