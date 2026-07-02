package com.apex.agent.agent.util

import android.content.Context
import android.os.Environment
import java.io.File

object LogistraAgentPaths {

    private const val LOGISTRA_AGENT_DIR_NAME = "apex"
    private const val CLEAN_ON_EXIT_DIR_NAME = "cleanOnExit"
    private const val MCP_PLUGINS_DIR_NAME = "mcp_plugins"
    private const val BRIDGE_DIR_NAME = "bridge"
    private const val EXPORTS_DIR_NAME = "exports"
    private const val WORKSPACE_DIR_NAME = "workspace"
    private const val TEST_DIR_NAME = "test"
    private const val WEBSESSION_DIR_NAME = "websession"
    private const val USERSCRIPTS_DIR_NAME = "userscripts"

    const val VECTOR_INDEX_DIR_NAME = ".vector_index"

    private const val IMAGE_POOL_DIR_NAME = "image_pool"
    const val MEDIA_POOL_DIR_NAME = "media_pool"
    const val SKILL_REPO_ZIP_POOL_DIR_NAME = "skill_repo_zip_pool"
    private const val SKILLS_DIR_NAME = "skills"

    fun downloadsDir(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File("/sdcard/Download")
    }

    fun logistraAgentRootDir(context: Context): File {
        return ensureDir(File(downloadsDir(context), LOGISTRA_AGENT_DIR_NAME))
    }

    fun cleanOnExitDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun cleanOnExitInternalDir(context: Context): File {
        return ensureDir(File(ensureDir(File(context.cacheDir, LOGISTRA_AGENT_DIR_NAME)), CLEAN_ON_EXIT_DIR_NAME))
    }

    fun mcpPluginsDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), MCP_PLUGINS_DIR_NAME))
    }

    fun bridgeDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), BRIDGE_DIR_NAME))
    }

    fun exportsDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), EXPORTS_DIR_NAME))
    }

    fun workspaceDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), WORKSPACE_DIR_NAME))
    }

    fun testDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), TEST_DIR_NAME))
    }

    fun webSessionDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), WEBSESSION_DIR_NAME))
    }

    fun webSessionUserscriptsDir(context: Context): File {
        return ensureDir(File(webSessionDir(context), USERSCRIPTS_DIR_NAME))
    }

    fun vectorIndexDir(context: Context): File {
        return ensureDir(File(context.filesDir, VECTOR_INDEX_DIR_NAME))
    }

    fun imagePoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, IMAGE_POOL_DIR_NAME))
    }

    fun mediaPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, MEDIA_POOL_DIR_NAME))
    }

    fun skillRepoZipPoolDir(baseDir: File): File {
        return ensureDir(File(baseDir, SKILL_REPO_ZIP_POOL_DIR_NAME))
    }

    fun skillsDir(context: Context): File {
        return ensureDir(File(logistraAgentRootDir(context), SKILLS_DIR_NAME))
    }

    fun getSkillsDir(context: Context): File {
        return skillsDir(context)
    }

    fun rawSnapshotExcludedFilesTopLevelDirNames(): Set<String> {
        return setOf(
            VECTOR_INDEX_DIR_NAME,
            IMAGE_POOL_DIR_NAME,
            MEDIA_POOL_DIR_NAME,
            SKILL_REPO_ZIP_POOL_DIR_NAME
        )
    }

    fun logistraAgentRootPathSdcard(): String {
        return "/sdcard/Download/${LOGISTRA_AGENT_DIR_NAME}"
    }

    fun cleanOnExitPathSdcard(): String {
        return "${logistraAgentRootPathSdcard()}/${CLEAN_ON_EXIT_DIR_NAME}"
    }

    fun bridgePathSdcard(): String {
        return "${logistraAgentRootPathSdcard()}/${BRIDGE_DIR_NAME}"
    }

    fun exportsPathSdcard(): String {
        return "${logistraAgentRootPathSdcard()}/${EXPORTS_DIR_NAME}"
    }

    fun workspacePathSdcard(chatId: String): String {
        return "${logistraAgentRootPathSdcard()}/${WORKSPACE_DIR_NAME}/${chatId}"
    }

    fun testPathSdcard(): String {
        return "${logistraAgentRootPathSdcard()}/${TEST_DIR_NAME}"
    }

    fun webSessionUserscriptsPathSdcard(): String {
        return "${logistraAgentRootPathSdcard()}/${WEBSESSION_DIR_NAME}/${USERSCRIPTS_DIR_NAME}"
    }

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
