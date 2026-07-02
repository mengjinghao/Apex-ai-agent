package com.apex.agent.core.tools.integration.provider

import com.apex.agent.core.tools.integration.CapabilityType
import com.apex.agent.core.tools.integration.IntegrationCapability
import com.apex.agent.core.tools.integration.IntegrationInfo
import com.apex.agent.core.tools.integration.IntegrationProvider
import com.apex.agent.core.tools.integration.IntegrationSource
import com.apex.agent.core.tools.integration.ItemType
import com.apex.agent.core.tools.integration.UnifiedItem
import com.apex.agent.core.tools.skill.SkillRepoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SkillRepoIntegration : IntegrationProvider {

    private val repoClient = SkillRepoClient.getInstance()
    private val source = IntegrationSource.SKILL_REPO

    override fun getInfo(): IntegrationInfo = IntegrationInfo(
        id = source.id,
        name = source.name,
        description = "官方技能仓�?�?浏览、搜索和安装技�?,
        version = "1.0.0",
        author = "Logistra AI",
        homepage = "https://skill-repo.logistra.ai",
        enabled = true,
        capabilities = listOf(
            IntegrationCapability("browse", "浏览技�?, "浏览技能仓�?, CapabilityType.BROWSE),
            IntegrationCapability("search", "搜索技�?, "搜索技能仓�?, CapabilityType.SEARCH),
            IntegrationCapability("install", "安装技�?, "从仓库安装技�?, CapabilityType.INSTALL),
            IntegrationCapability("detail", "技能详�?, "查看技能详细信�?, CapabilityType.DETAIL),
            IntegrationCapability("categories", "分类浏览", "按分类浏�?, CapabilityType.CATEGORIES)
        ),
        itemCount = 0,
        installedCount = 0
    )

    override fun isAvailable(): Boolean = true

    override suspend fun list(tag: String?, page: Int, pageSize: Int): Result<List<UnifiedItem>> {
        return withContext(Dispatchers.IO) {
            repoClient.getSkillList(page, pageSize, tag).fold(
                onSuccess = { skills ->
                    Result.success(skills.map { it.toUnifiedItem() })
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun search(query: String, filters: Map<String, String>): Result<List<UnifiedItem>> {
        val category = filters["category"]
        val sortBy = filters["sort"]
        return withContext(Dispatchers.IO) {
            repoClient.searchSkills(query = query, page = 1, pageSize = 20, category = category, sortBy = sortBy).fold(
                onSuccess = { result ->
                    Result.success(result.skills.map { it.toUnifiedItem() })
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun getDetail(id: String): Result<UnifiedItem> {
        return withContext(Dispatchers.IO) {
            repoClient.getSkillDetail(id).fold(
                onSuccess = { detail ->
                    Result.success(UnifiedItem(
                        source = source,
                        sourceId = detail.id,
                        name = detail.name,
                        type = ItemType.SKILL,
                        description = detail.description,
                        author = detail.author,
                        version = detail.version,
                        tags = detail.tags,
                        homepage = null,
                        updatedAt = if (detail.updatedAt > 0) detail.updatedAt.toString() else null
                    ))
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun install(item: UnifiedItem): Result<String> {
        return withContext(Dispatchers.IO) {
            val outputFile = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "Apex/skills/repo/${item.sourceId}.zip"
            )
            outputFile.parentFile?.mkdirs()
            repoClient.downloadSkill(item.sourceId, item.version, outputFile).fold(
                onSuccess = { file ->
                    Result.success("成功安装 ${item.name} �?${file.absolutePath}")
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun uninstall(installedId: String): Result<String> {
        return Result.success("卸载功能待实�? $installedId")
    }

    override suspend fun checkUpdate(item: UnifiedItem): Result<UnifiedItem?> {
        return withContext(Dispatchers.IO) {
            repoClient.checkForUpdate(item.sourceId, item.version).fold(
                onSuccess = { update ->
                    if (update.hasUpdate) {
                        Result.success(item.copy(version = update.latestVersion))
                    } else {
                        Result.success(null)
                    }
                },
                onFailure = { Result.failure(it) }
            )
        }
    }

    override suspend fun listInstalled(): Result<List<UnifiedItem>> {
        return Result.success(emptyList())
    }

    override suspend fun getCategories(): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            repoClient.getCategories().fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) }
            )
        }
    }

    private fun SkillRepoClient.SkillInfo.toUnifiedItem() = UnifiedItem(
        source = source,
        sourceId = id,
        name = name,
        type = ItemType.SKILL,
        description = description,
        author = author,
        version = version,
        tags = listOfNotNull(category.takeIf { it.isNotBlank() }),
        homepage = null,
        rating = rating.toDouble(),
        installCount = installCount,
        updatedAt = if (updatedAt > 0) updatedAt.toString() else null
    )
}
