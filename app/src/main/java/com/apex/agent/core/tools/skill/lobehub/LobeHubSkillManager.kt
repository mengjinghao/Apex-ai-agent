package com.apex.agent.core.tools.skill.lobehub

import android.content.Context
import android.os.Environment
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LobeHub Skill Manager
 * 
 * Manages LobeHub Skills integration with Apex skill system
 * - Browse LobeHub marketplace
 * - Install skills to local storage
 * - Parse and convert LobeHub SKILL.md to Apex format
 */
class LobeHubSkillManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "LobeHubSkillManager"
        
        // LobeHub skills storage directory
    private const val LOBEHUB_SKILLS_DIR = "LobeHub"

        @Volatile private var INSTANCE: LobeHubSkillManager? = null

        fun getInstance(context: Context): LobeHubSkillManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LobeHubSkillManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
        private val marketplaceClient = LobeHubMarketplaceClient.getInstance()
        private val parser = LobeHubSkillParser()
        private val lobeHubSkillsDir: File
        get() {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val apexDir = File(downloadsDir, "Apex")
        val lobeHubDir = File(apexDir, "skills/${LOBEHUB_SKILLS_DIR}")
        if (!lobeHubDir.exists()) {
                lobeHubDir.mkdirs()
            }
        return lobeHubDir
        }

    /**
     * Browse LobeHub marketplace skills
     */
    suspend fun browseSkills(
        query: String = "",
        agent: String? = null,
        category: String? = null,
        page: Int = 1
    ): Result<List<LobeHubSkillListing>> = withContext(Dispatchers.IO) {
        marketplaceClient.searchSkills(
            LobeHubSearchFilters(
                query = query,
                agent = agent,
                category = category,
                page = page
            )
        )
    }

    /**
     * Get featured skills from LobeHub marketplace
     */
    suspend fun getFeaturedSkills(): Result<List<LobeHubSkillListing>> = withContext(Dispatchers.IO) {
        marketplaceClient.getFeaturedSkills()
    }

    /**
     * Get popular skills from LobeHub marketplace
     */
    suspend fun getPopularSkills(limit: Int = 20): Result<List<LobeHubSkillListing>> = withContext(Dispatchers.IO) {
        marketplaceClient.getPopularSkills(limit)
    }

    /**
     * Get skills compatible with a specific agent
     */
    suspend fun getSkillsByAgent(agent: String): Result<List<LobeHubSkillListing>> = withContext(Dispatchers.IO) {
        marketplaceClient.getSkillsByAgent(agent)
    }

    /**
     * Get skill detail from marketplace
     */
    suspend fun getSkillDetail(skillId: String): Result<LobeHubSkillDetail> = withContext(Dispatchers.IO) {
        marketplaceClient.getSkillDetail(skillId)
    }

    /**
     * Install a LobeHub skill to local storage
     */
    suspend fun installSkill(skillId: String): Result<File> = withContext(Dispatchers.IO) {
        AppLogger.i(TAG, "Installing LobeHub skill: ${skillId}")
        val result = marketplaceClient.downloadSkill(skillId, lobeHubSkillsDir)
        
        result.onSuccess { dir ->
            AppLogger.i(TAG, "Successfully installed skill to: ${dir.absolutePath}")
        }
        
        result.onFailure { error ->
            AppLogger.e(TAG, "Failed to install skill: ${skillId}", error)
        }
        
        result
    }

    /**
     * Get install command for a LobeHub skill
     */
    fun getInstallCommand(skillId: String, agent: String = "apex"): String {
        return marketplaceClient.getInstallCommand(skillId, agent)
    }

    /**
     * Check if a skill is already installed locally
     */
    fun isSkillInstalled(skillId: String): Boolean {
        val skillDir = File(lobeHubSkillsDir, skillId)
        val skillFile = File(skillDir, "SKILL.md")
        return skillFile.exists()
    }

    /**
     * Get locally installed LobeHub skills
     */
    fun getInstalledSkills(): List<File> {
        val skills = mutableListOf<File>()
        lobeHubSkillsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                val skillFile = File(dir, "SKILL.md")
        if (skillFile.exists()) {
                    skills.add(dir)
                }
            }
        }
        return skills
    }

    /**
     * Get installed skill spec
     */
    fun getInstalledSkillSpec(skillId: String): LobeHubSkillSpec? {
        val skillFile = File(lobeHubSkillsDir, "${skillId}/SKILL.md")
        return if (skillFile.exists()) {
            parser.parseSkillFile(skillFile)
        } else {
            null
        }
    }

    /**
     * Delete an installed LobeHub skill
     */
    suspend fun deleteInstalledSkill(skillId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val skillDir = File(lobeHubSkillsDir, skillId)
        if (skillDir.exists()) {
                val deleted = skillDir.deleteRecursively()
                Result.success(deleted)
            } else {
                Result.failure(Exception("Skill not found"))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete skill: ${skillId}", e)
            Result.failure(e)
        }
    }

    /**
     * Get all locally installed LobeHub skill specs
     */
    fun getAllInstalledSkillSpecs(): List<LobeHubSkillSpec> {
        return getInstalledSkills().mapNotNull { dir ->
            val skillFile = File(dir, "SKILL.md")
            parser.parseSkillFile(skillFile)
        }
    }

    /**
     * Get the LobeHub skills directory path
     */
    fun getLobeHubSkillsDirectory(): String {
        return lobeHubSkillsDir.absolutePath
    }

    /**
     * Parse SKILL.md content using LobeHub format
     */
    fun parseSkillContent(content: String): LobeHubSkillSpec {
        return parser.parseSkillMd(content)
    }

    /**
     * Convert LobeHub skill to Apex-compatible format
     */
    fun convertToApexFormat(spec: LobeHubSkillSpec): Map<String, Any> {
        return parser.toApexMetadata(spec)
    }

    /**
     * Generate Apex-compatible SKILL.md from LobeHub spec
     */
    fun generateApexSkillMd(spec: LobeHubSkillSpec): String {
        return parser.generateSkillMd(spec)
    }

    /**
     * Test connection to LobeHub marketplace
     */
    suspend fun testMarketplaceConnection(): Boolean {
        return marketplaceClient.testConnection()
    }

    /**
     * Get curated skills list (when offline)
     */
    fun getCuratedSkills(): List<LobeHubSkillListing> {
        return listOf(
            LobeHubSkillListing(
                id = "lobehub-skills-search-engine",
                name = "Skills Search Engine",
                description = "Your skill search engine. Search the marketplace to find skills for any task.",
                author = "LobeHub",
                version = "1.0.0",
                rating = 4.9f,
                installCount = 50000,
                tags = listOf("search", "marketplace", "discovery"),
                agent = listOf("open-claw", "claude-code", "codex", "cursor"),
                updatedAt = "2026-01-15",
                skillMdUrl = "https://lobehub.com/skills/lobehub-skills-search-engine",
                homepage = "https://lobehub.com/skills"
            ),
            LobeHubSkillListing(
                id = "temmo1004-smart-short-video",
                name = "Smart Short Video",
                description = "智能短视频生成器 - Hybrid AI images with video clips for TikTok/Reels.",
                author = "temmo1004",
                version = "1.0.1",
                rating = 4.7f,
                installCount = 120,
                tags = listOf("video", "tiktok", "ai"),
                agent = listOf("open-claw"),
                updatedAt = "2026-05-15",
                skillMdUrl = "https://lobehub.com/skills/temmo1004-smart-short-video",
                homepage = "https://lobehub.com/skills/temmo1004-smart-short-video"
            ),
            LobeHubSkillListing(
                id = "superpowers-ai-code-review",
                name = "AI Code Review",
                description = "Automated code review with AI-powered analysis and security scanning.",
                author = "Superpowers",
                version = "2.1.0",
                rating = 4.8f,
                installCount = 15000,
                tags = listOf("code-review", "security"),
                agent = listOf("claude-code", "open-claw", "codex"),
                updatedAt = "2026-06-01",
                skillMdUrl = "https://lobehub.com/skills/superpowers-ai-code-review",
                homepage = "https://lobehub.com/skills/superpowers-ai-code-review"
            ),
            LobeHubSkillListing(
                id = "browser-automation",
                name = "Browser Automation",
                description = "Control web browsers for scraping, testing, and automation.",
                author = "BrowserTeam",
                version = "1.5.0",
                rating = 4.4f,
                installCount = 8000,
                tags = listOf("browser", "automation"),
                agent = listOf("open-claw", "claude-code"),
                updatedAt = "2026-05-28",
                skillMdUrl = "https://lobehub.com/skills/browser-automation",
                homepage = "https://lobehub.com/skills/browser-automation"
            ),
            LobeHubSkillListing(
                id = "database-assistant",
                name = "Database Assistant",
                description = "Natural language interface for database queries and data analysis.",
                author = "DataTools",
                version = "1.2.0",
                rating = 4.6f,
                installCount = 12000,
                tags = listOf("database", "sql"),
                agent = listOf("claude-code", "open-claw"),
                updatedAt = "2026-06-05",
                skillMdUrl = "https://lobehub.com/skills/database-assistant",
                homepage = "https://lobehub.com/skills/database-assistant"
            )
        )
    }
}
