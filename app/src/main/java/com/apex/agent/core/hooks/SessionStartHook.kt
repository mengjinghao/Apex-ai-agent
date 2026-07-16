package com.apex.agent.core.hooks

import android.content.Context
import com.apex.util.AppLogger
import com.apex.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话启动钩子实现
 * 负责加载上次会话上下文摘要、检测环境状态并生成环境报告
 */
class SessionStartHook : SessionLifecycleHook {

    companion object {
        private const val TAG = "SessionStartHook"
        private const val SUMMARY_FILE_PREFIX = "session_summary_"
        private const val SUMMARY_FILE_SUFFIX = ".json"
    }

    override suspend fun onSessionStart(context: Context, sessionContext: SessionContext) {
        AppLogger.i(TAG, "Session starting: ${sessionContext.sessionId}")

        val previousSummary = loadPreviousSessionSummary(context)
        val environmentReport = detectEnvironmentState(context)

        AppLogger.i(TAG, "Environment report generated with ${environmentReport.size} entries")
        if (previousSummary != null) {
            AppLogger.d(TAG, "Loaded previous session summary")
        } else {
            AppLogger.d(TAG, "No previous session summary found")
        }
    }

    /**
     * 加载上次会话上下文摘?     * ?filesDir 中查找最新的 session_summary_*.json 文件并解?     * @return 解析后的摘要 JSONObject，若无则返回 null
     */
    private suspend fun loadPreviousSessionSummary(context: Context): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val filesDir = context.filesDir
                val summaryFiles = filesDir.listFiles { file ->
                    file.name.startsWith(SUMMARY_FILE_PREFIX) &&
                        file.name.endsWith(SUMMARY_FILE_SUFFIX)
                } ?: return@withContext null

                if (summaryFiles.isEmpty()) {
                    AppLogger.d(TAG, "No session summary files found in ${filesDir.absolutePath}")
                    return@withContext null
                }

                // 按修改时间倒序，取最新的
                val latestFile = summaryFiles.maxByOrNull { it.lastModified() }
                    ?: return@withContext null

                AppLogger.d(TAG, "Loading latest session summary: ${latestFile.name}")
                val content = latestFile.readText()
                JSONObject(content)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to load previous session summary", e)
                null
            }
        }

    /**
     * 检测当前环境状?     * 包括可用模型列表、当前权限模式、网络连接状?     * @return 环境状态键值对
     */
    private suspend fun detectEnvironmentState(context: Context): Map<String, String> =
        withContext(Dispatchers.IO) {
            val envState = mutableMapOf<String, String>()

            // 检测网络连接状?            try {
                val isNetworkAvailable = NetworkUtils.isNetworkAvailable(context)
                envState["networkAvailable"] = isNetworkAvailable.toString()
                envState["networkType"] = if (isNetworkAvailable) {
                    NetworkUtils.getNetworkType(context)
                } else {
                    "disconnected"
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to detect network state", e)
                envState["networkAvailable"] = "unknown"
            }

            // 检测可用模型列?            try {
                val modelsDir = File(context.filesDir, "models")
                if (modelsDir.exists()) {
                    val modelFiles = modelsDir.listFiles { file ->
                        file.extension == "gguf" || file.extension == "bin" || file.extension == "mnn"
                    } ?: emptyArray()
                    envState["availableModels"] = modelFiles.joinToString(",") { it.nameWithoutExtension }
                    envState["modelCount"] = modelFiles.size.toString()
                } else {
                    envState["availableModels"] = ""
                    envState["modelCount"] = "0"
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to detect available models", e)
                envState["availableModels"] = "error"
            }

            // 检测当前权限模?            try {
                val prefs = context.getSharedPreferences("permission_mode_prefs", Context.MODE_PRIVATE)
                val currentMode = prefs.getString("current_permission_mode", "standard") ?: "standard"
                envState["permissionMode"] = currentMode
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to detect permission mode", e)
                envState["permissionMode"] = "unknown"
            }

            envState
        }

    /**
     * 生成环境报告 JSON 字符串，可用于注入到会话初始上下?     * @param context Android 上下?     * @return 环境报告 JSON 字符?     */
    suspend fun generateEnvironmentReport(context: Context): String = withContext(Dispatchers.IO) {
        val envState = detectEnvironmentState(context)
        val report = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("environment", JSONObject(envState))
        }
        report.toString(2)
    }
}
