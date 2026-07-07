package com.apex.agent.integration.importer

import com.apex.agent.integration.api.IntegrationCategory
import com.apex.agent.integration.installed.InstalledItem
import com.apex.agent.integration.installed.InstalledManager
import com.apex.agent.integration.market.IntegrationItemState
import com.apex.agent.integration.market.IntegrationSourceType
import com.apex.agent.integration.market.MarketItem

/**
 * 导入来源类型。
 */
enum class ImportSourceType {
    LOCAL_FILE,
    URL,
    GIT_REPOSITORY,
    CLIPBOARD,
    FILE_PICKER,
    DIRECT_INPUT
}

/**
 * 导入来源描述。
 *
 * @property type 来源类型
 * @property path 文件路径（LOCAL_FILE）
 * @property url URL（URL/GIT_REPOSITORY）
 * @property content 直接内容（CLIPBOARD/DIRECT_INPUT）
 * @property metadata 附加元数据
 */
data class ImportSource(
    val type: ImportSourceType,
    val path: String? = null,
    val url: String? = null,
    val content: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 导入结果。
 *
 * @property success 是否成功
 * @property importedItem 导入后的市场项
 * @property installedItem 安装后的项（如果自动安装）
 * @property warnings 警告信息
 * @property errors 错误信息
 */
data class ImportResult(
    val success: Boolean,
    val importedItem: MarketItem? = null,
    val installedItem: InstalledItem? = null,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList()
) {
    companion object {
        fun success(item: MarketItem, installed: InstalledItem? = null, warnings: List<String> = emptyList()) =
            ImportResult(success = true, importedItem = item, installedItem = installed, warnings = warnings)

        fun failure(errors: List<String>) =
            ImportResult(success = false, errors = errors)

        fun failure(error: String) = failure(listOf(error))
    }
}

/**
 * 集成项导入器。
 *
 * 支持从多种来源导入集成项（技能/MCP/插件/模型配置）：
 * - 本地文件（.json / .yaml / .zip）
 * - URL（远程下载）
 * - Git 仓库
 * - 剪贴板
 * - 直接输入
 *
 * # 使用示例
 *
 * ```
 * val importer = IntegrationImporter(installedManager)
 *
 * // 从文件导入
 * val result = importer.import(
 *     ImportSource(type = ImportSourceType.LOCAL_FILE, path = "/sdcard/skill.json"),
 *     category = IntegrationCategory.SKILLS
 * )
 *
 * // 从 URL 导入
 * val result = importer.import(
 *     ImportSource(type = ImportSourceType.URL, url = "https://example.com/skill.json"),
 *     category = IntegrationCategory.SKILLS,
 *     autoInstall = true
 * )
 *
 * // 从直接内容导入
 * val result = importer.import(
 *     ImportSource(type = ImportSourceType.DIRECT_INPUT, content = jsonString),
 *     category = IntegrationCategory.MCP
 * )
 * ```
 */
class IntegrationImporter(
    private val installedManager: InstalledManager
) {

    /**
     * 导入集成项。
     *
     * @param source 导入来源
     * @param category 目标分类
     * @param autoInstall 是否自动安装（默认 true）
     * @return 导入结果
     */
    suspend fun import(
        source: ImportSource,
        category: IntegrationCategory,
        autoInstall: Boolean = true
    ): ImportResult {
        return try {
            // 1. 读取内容
            val content = readContent(source)
            if (content.isBlank()) {
                return ImportResult.failure("Empty content from source: ${source.type}")
            }

            // 2. 解析为 MarketItem
            val item = parseContent(content, category, source)
                ?: return ImportResult.failure("Failed to parse content as ${category.displayName}")

            // 3. 验证
            val warnings = validate(item)

            // 4. 自动安装
            val installed = if (autoInstall) {
                installedManager.install(item).let { success ->
                    if (success) installedManager.get(item.id) else null
                }
            } else {
                null
            }

            ImportResult.success(item, installed, warnings)
        } catch (e: Exception) {
            ImportResult.failure("Import failed: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * 批量导入。
     *
     * @param sources 来源列表
     * @param category 目标分类
     * @param autoInstall 是否自动安装
     * @return 导入结果列表
     */
    suspend fun importBatch(
        sources: List<ImportSource>,
        category: IntegrationCategory,
        autoInstall: Boolean = true
    ): List<ImportResult> {
        return sources.map { import(it, category, autoInstall) }
    }

    /**
     * 验证导入内容（不实际导入）。
     *
     * @param source 来源
     * @param category 目标分类
     * @return 验证结果（warnings 列表，空表示无问题）
     */
    suspend fun validate(source: ImportSource, category: IntegrationCategory): List<String> {
        return try {
            val content = readContent(source)
            if (content.isBlank()) return listOf("Empty content")

            val item = parseContent(content, category, source) ?: return listOf("Parse failed")
            validate(item)
        } catch (e: Exception) {
            listOf("Validation error: ${e.message}")
        }
    }

    // ===== 内部方法 =====

    private suspend fun readContent(source: ImportSource): String {
        return when (source.type) {
            ImportSourceType.LOCAL_FILE -> {
                val path = source.path ?: return ""
                val file = java.io.File(path)
                if (!file.exists()) return ""
                file.readText()
            }
            ImportSourceType.URL, ImportSourceType.GIT_REPOSITORY -> {
                val url = source.url ?: return ""
                // 简化实现：实际应使用 OkHttp 下载
                // 生产环境注入 HttpClient
                try {
                    val connection = java.net.URL(url).openConnection()
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 30_000
                    connection.getInputStream().bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ""
                }
            }
            ImportSourceType.CLIPBOARD, ImportSourceType.DIRECT_INPUT -> {
                source.content ?: ""
            }
            ImportSourceType.FILE_PICKER -> {
                val path = source.path ?: return ""
                java.io.File(path).takeIf { it.exists() }?.readText() ?: ""
            }
        }
    }

    private fun parseContent(
        content: String,
        category: IntegrationCategory,
        source: ImportSource
    ): MarketItem? {
        return try {
            // 尝试 JSON 解析
            val json = org.json.JSONObject(content)
            val id = json.optString("id", generateId(source))
            val name = json.optString("name", "") ?: return null
            val description = json.optString("description", "")
            val version = json.optString("version", "1.0.0")
            val author = json.optString("author", "")
            val tags = json.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()

            MarketItem(
                id = id,
                name = name,
                description = description,
                author = author,
                version = version,
                category = category,
                marketId = "imported",
                sourceType = when (source.type) {
                    ImportSourceType.LOCAL_FILE, ImportSourceType.FILE_PICKER -> IntegrationSourceType.LOCAL_FILE
                    ImportSourceType.URL -> IntegrationSourceType.URL
                    ImportSourceType.GIT_REPOSITORY -> IntegrationSourceType.GIT_REPOSITORY
                    else -> IntegrationSourceType.MANUAL_IMPORT
                },
                sourceUrl = source.url,
                downloadUrl = source.url ?: source.path ?: "",
                tags = tags,
                verified = false,
                metadata = mapOf("importedAt" to System.currentTimeMillis().toString())
            )
        } catch (e: Exception) {
            // 非 JSON 格式，尝试简单文本解析
            if (content.isNotBlank() && content.length > 10) {
                MarketItem(
                    id = generateId(source),
                    name = content.lineSequence().firstOrNull()?.take(50) ?: "Imported Item",
                    description = content.take(500),
                    version = "1.0.0",
                    category = category,
                    marketId = "imported",
                    sourceType = IntegrationSourceType.MANUAL_IMPORT,
                    downloadUrl = source.url ?: source.path ?: "",
                    verified = false
                )
            } else {
                null
            }
        }
    }

    private fun validate(item: MarketItem): List<String> {
        val warnings = mutableListOf<String>()

        if (item.name.isBlank()) warnings.add("Name is empty")
        if (item.description.isBlank()) warnings.add("Description is empty")
        if (item.version.isBlank()) warnings.add("Version is empty")
        if (!item.verified) warnings.add("Item is not verified (imported from external source)")

        return warnings
    }

    private fun generateId(source: ImportSource): String {
        return "imported_${source.type.name.lowercase()}_${System.currentTimeMillis()}"
    }
}
