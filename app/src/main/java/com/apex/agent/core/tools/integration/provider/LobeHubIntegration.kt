package com.apex.agent.core.tools.integration.provider

import android.content.Context
import com.apex.agent.core.tools.integration.CapabilityType
import com.apex.agent.core.tools.integration.IntegrationCapability
import com.apex.agent.core.tools.integration.IntegrationInfo
import com.apex.agent.core.tools.integration.IntegrationProvider
import com.apex.agent.core.tools.integration.IntegrationSource
import com.apex.agent.core.tools.integration.ItemType
import com.apex.agent.core.tools.integration.UnifiedItem
import com.apex.agent.core.tools.skill.lobehub.LobeHubSkillListing
import com.apex.agent.core.tools.skill.lobehub.LobeHubSkillManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LobeHubIntegration(private val context: Context) : IntegrationProvider {

    private val skillManager = LobeHubSkillManager.getInstance(context)
        private val source = IntegrationSource.LOBE_HUB

    override fun getInfo(): IntegrationInfo = IntegrationInfo(
        id = source.id,
        name = source.name,
        description = "LobeHub 技能市�?�浏览、搜索和安装 330,000+ AI Skills",
        version = "1.0.0",
        author = "LobeHub",
        homepage = "https://lobehub.com/skills",
        logoUrl = null,
        enabled = true,
        capabilities = listOf(
            IntegrationCapability("browse", "浏览技�?, "浏览 LobeHub 技能市�?, CapabilityType.BROWSE),
            IntegrationCapability("search", "搜索技�?, "搜索 LobeHub 上的技�?, CapabilityType.SEARCH),
            IntegrationCapability("featured", "精选技�?, "获取精选技能推�?, CapabilityType.FEATURED),
            IntegrationCapability("popular", "热门技�?, "获取热门技能排�?, CapabilityType.POPULAR),
            IntegrationCapability("install", "安装技�?, "�?LobeHub 安装技�?, CapabilityType.INSTALL),
            IntegrationCapability("uninstall", "卸载技�?, "卸载已安装的技�?, CapabilityType.UNINSTALL),
            IntegrationCapability("detail", "技能详�?, "查看技能的详细信息", CapabilityType.DETAIL),
            IntegrationCapability("categories", "分类浏览", "按代理类型分类浏�?, CapabilityType.CATEGORIES),
            IntegrationCapability("list_installed", "已安装列�?, "列出已安装的 LobeHub 技�?, CapabilityType.BROWSE)
        ),
        itemCount = 330000,
        installedCount = skillManager.getInstalledSkills().size
    )

    override fun isAvailable(): Boolean = true

    override suspend fun list(tag: String?, page: Int, pageSize: Int): Result<List<UnifiedItem>> {
        return when (tag?.lowercase()) {
            "featured" -> skillManager.getFeaturedSkills().map { list -> list.map { it.toUnifiedItem() } }
            "popular" -> skillManager.getPopularSkills(pageSize).map { list -> list.map { it.toUnifiedItem() } }
            else -> {
                val category = if (tag != null && tag !in listOf("featured", "popular", "latest")) tag else null
                val agent = if (category != null) category else null
                skillManager.browseSkills(agent = agent, page = page).map { list ->
                    list.map { it.toUnifiedItem() }
                }
            }
        }
    }

    override suspend fun search(query: String, filters: Map<String, String>): Result<List<UnifiedItem>> {
        val agent = filters["agent"]
        val category = filters["category"]
        return skillManager.browseSkills(query = query, agent = agent, category = category).map { list ->
            list.map { it.toUnifiedItem() }
        }
    }

    override suspend fun getDetail(id: String): Result<UnifiedItem> {
        return skillManager.getSkillDetail(id).map { it.toUnifiedItem() }
    }

    override suspend fun install(item: UnifiedItem): Result<String> {
        return skillManager.installSkill(item.sourceId).map { dir ->
            "成功安装 ${item.name} �?${dir.absolutePath}"
        }
    }

    override suspend fun uninstall(installedId: String): Result<String> {
        val id = installedId.removePrefix("lobehub_")
        return skillManager.deleteInstalledSkill(id).map {
            "已卸�? $id"
        }
    }

    override suspend fun checkUpdate(item: UnifiedItem): Result<UnifiedItem?> {
        return Result.success(null)
    }

    override suspend fun listInstalled(): Result<List<UnifiedItem>> {
        return withContext(Dispatchers.IO) {
            val specs = skillManager.getAllInstalledSkillSpecs()
            Result.success(specs.map { spec ->
                UnifiedItem(
                    source = source,
                    sourceId = spec.identifier,
                    name = spec.name,
                    type = ItemType.SKILL,
                    description = spec.description,
                    author = spec.author,
                    version = spec.version,
                    tags = spec.tags,
                    installedId = "lobehub_${spec.identifier}",
                    isInstalled = true,
                    homepage = spec.homepage
                )
            })
        }
    }

    override suspend fun getCategories(): Result<List<String>> {
        return Result.success(listOf(
            "open-claw", "claude-code", "codex", "cursor", "github-copilot"
        ))
    }
        private fun LobeHubSkillListing.toUnifiedItem() = UnifiedItem(
        source = source,
        sourceId = id,
        name = name,
        type = ItemType.SKILL,
        description = description,
        author = author,
        version = version,
        tags = tags,
        isInstalled = skillManager.isSkillInstalled(id),
        homepage = homepage,
        rating = rating?.toDouble(),
        installCount = installCount,
        updatedAt = updatedAt
    )
}
