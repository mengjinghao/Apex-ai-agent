package com.apex.plugins.skill

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SkillBatchInstaller private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SkillBatchInstaller"

        @Volatile
        private var INSTANCE: SkillBatchInstaller? = null

        fun getInstance(context: Context): SkillBatchInstaller {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SkillBatchInstaller(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    data class InstallResult(
        val successCount: Int,
        val failureCount: Int,
        val failures: List<FailedInstall> = emptyList()
    )

    data class FailedInstall(
        val pluginId: String,
        val reason: String
    )

    data class InstallProgress(
        val current: Int,
        val total: Int,
        val currentPluginId: String,
        val phase: InstallPhase,
        val message: String = ""
    )

    enum class InstallPhase {
        QUEUED, DOWNLOADING, VALIDATING, INSTALLING, COMPLETED, FAILED
    }

    private val marketplace = SkillPluginMarketplace.getInstance(context)
    private val pluginManager = SkillPluginManager.getInstance(context)
    private val loader = SkillPluginLoader.getInstance(context)

    var onProgress: ((InstallProgress) -> Unit)? = null

    suspend fun installPlugins(pluginIds: List<String>): InstallResult = withContext(Dispatchers.IO) {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<FailedInstall>()

        for ((index, pluginId) in pluginIds.withIndex()) {
            onProgress?.invoke(
                InstallProgress(
                    current = index,
                    total = pluginIds.size,
                    currentPluginId = pluginId,
                    phase = InstallPhase.DOWNLOADING,
                    message = "正在下载插件: ${pluginId}"
                )
            )

            try {
                AppLogger.d(TAG, "下载插件: ${pluginId}")
                val downloadedFile = marketplace.downloadPlugin(pluginId)

                onProgress?.invoke(
                    InstallProgress(
                        current = index,
                        total = pluginIds.size,
                        currentPluginId = pluginId,
                        phase = InstallPhase.VALIDATING,
                        message = "正在验证插件: ${pluginId}"
                    )
                )

                val validation = loader.validatePlugin(downloadedFile)
                if (!validation.isValid) {
                    val errors = validation.errors.joinToString("; ")
                    failures.add(FailedInstall(pluginId, "验证失败: ${errors}"))
                    onProgress?.invoke(
                        InstallProgress(
                            current = index,
                            total = pluginIds.size,
                            currentPluginId = pluginId,
                            phase = InstallPhase.FAILED,
                            message = "验证失败: ${errors}"
                        )
                    )
                    continue
                }

                onProgress?.invoke(
                    InstallProgress(
                        current = index,
                        total = pluginIds.size,
                        currentPluginId = pluginId,
                        phase = InstallPhase.INSTALLING,
                        message = "正在安装插件: ${pluginId}"
                    )
                )

                val plugin = loader.loadPlugin(downloadedFile)
                plugin.onLoad(context)
                pluginManager.registerPlugin(plugin)
                pluginManager.enablePlugin(pluginId)

                successes.add(pluginId)
                AppLogger.i(TAG, "插件安装成功: ${pluginId} v${plugin.version}")

                onProgress?.invoke(
                    InstallProgress(
                        current = index,
                        total = pluginIds.size,
                        currentPluginId = pluginId,
                        phase = InstallPhase.COMPLETED,
                        message = "安装完成: ${pluginId}"
                    )
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "插件安装失败: ${pluginId}", e)
                failures.add(FailedInstall(pluginId, e.message ?: "未知错误"))
                onProgress?.invoke(
                    InstallProgress(
                        current = index,
                        total = pluginIds.size,
                        currentPluginId = pluginId,
                        phase = InstallPhase.FAILED,
                        message = "安装失败: ${e.message}"
                    )
                )
            }
        }

        val result = InstallResult(
            successCount = successes.size,
            failureCount = failures.size,
            failures = failures
        )

        AppLogger.i(
            TAG,
            "批量安装完成: 成功 ${result.successCount}, 失败 ${result.failureCount}"
        )

        result
    }

    suspend fun installPlugin(pluginId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = installPlugins(listOf(pluginId))
            if (result.successCount > 0) {
                Result.success(pluginId)
            } else {
                val error = result.failures.firstOrNull()
                Result.failure(Exception(error?.reason ?: "安装失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getInstalledPluginIds(): List<String> = withContext(Dispatchers.IO) {
        pluginManager.getAllPlugins().map { it.id }
    }
}
