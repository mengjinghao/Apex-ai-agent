package com.apex.agent.core.hooks

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 压缩前钩子实? * 负责在上下文压缩前提取并保存关键状态，支持压缩后恢? */
class PreCompactHook : SessionLifecycleHook {

    companion object {
        private const val TAG = "PreCompactHook"
        private const val CHECKPOINT_FILE_PREFIX = "session_checkpoint_"
        private const val CHECKPOINT_FILE_SUFFIX = ".json"
    }

    override suspend fun onPreCompact(
        context: Context,
        sessionContext: SessionContext
    ): Map<String, Any> {
        AppLogger.i(TAG, "Pre-compact hook triggered for session: ${sessionContext.sessionId}")

        val checkpointData = extractKeyState(sessionContext)
        saveCheckpoint(context, sessionContext.sessionId, checkpointData)

        return checkpointData
    }

    /**
     * 提取关键状态信?     * 包括未完成任务列表、重要决策记录、关键变量?     * @param sessionContext 当前会话上下?     * @return 提取的关键状态数?     */
    private fun extractKeyState(sessionContext: SessionContext): Map<String, Any> {
        val state = mutableMapOf<String, Any>()

        // 提取会话基本信息
        state["sessionId"] = sessionContext.sessionId
        state["messageCount"] = sessionContext.messageCount
        state["tokenUsage"] = sessionContext.tokenUsage
        state["lastActivity"] = sessionContext.lastActivity

        // 提取环境状?        state["environmentState"] = sessionContext.environmentState

        // 提取未完成任务列表（从环境状态中解析?        val pendingTasks = extractPendingTasks(sessionContext.environmentState)
        if (pendingTasks.isNotEmpty()) {
            state["pendingTasks"] = pendingTasks
        }

        // 提取重要决策记录
        val decisions = extractImportantDecisions(sessionContext.environmentState)
        if (decisions.isNotEmpty()) {
            state["importantDecisions"] = decisions
        }

        // 提取关键变量?        val keyVariables = extractKeyVariables(sessionContext.environmentState)
        if (keyVariables.isNotEmpty()) {
            state["keyVariables"] = keyVariables
        }

        AppLogger.d(TAG, "Extracted key state: ${state.keys.joinToString(", ")}")
        return state
    }

    /**
     * 从未完成任务列表中提取任?     */
    private fun extractPendingTasks(envState: Map<String, String>): List<String> {
        val tasksJson = envState["pendingTasks"] ?: return emptyList()
        return try {
            val jsonArray = JSONArray(tasksJson)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse pending tasks", e)
            emptyList()
        }
    }

    /**
     * 提取重要决策记录
     */
    private fun extractImportantDecisions(envState: Map<String, String>): List<Map<String, String>> {
        val decisionsJson = envState["importantDecisions"] ?: return emptyList()
        return try {
            val jsonArray = JSONArray(decisionsJson)
            (0 until jsonArray.length()).map {
                val obj = jsonArray.getJSONObject(it)
                obj.keys().asSequence().associateWith { key -> obj.getString(key) }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse important decisions", e)
            emptyList()
        }
    }

    /**
     * 提取关键变量?     */
    private fun extractKeyVariables(envState: Map<String, String>): Map<String, String> {
        val variablesJson = envState["keyVariables"] ?: return emptyMap()
        return try {
            val jsonObj = JSONObject(variablesJson)
            jsonObj.keys().asSequence().associateWith { jsonObj.getString(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key variables", e)
            emptyMap()
        }
    }

    /**
     * 序列化保?checkpoint 到文?     * 使用 context.filesDir 存储
     * @param context Android 上下?     * @param sessionId 会话 ID
     * @param checkpointData 要保存的 checkpoint 数据
     */
    private suspend fun saveCheckpoint(
        context: Context,
        sessionId: String,
        checkpointData: Map<String, Any>
    ) = withContext(Dispatchers.IO) {
        try {
            val fileName = "${CHECKPOINT_FILE_PREFIX}${sessionId}${CHECKPOINT_FILE_SUFFIX}"
            val file = File(context.filesDir, fileName)

            val jsonContent = convertMapToJson(checkpointData)
            file.writeText(jsonContent.toString(2))

            AppLogger.i(TAG, "Checkpoint saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save checkpoint for session: ${sessionId}", e)
        }
    }

    /**
     * ?Map 转换?JSONObject
     */
    private fun convertMapToJson(map: Map<String, Any>): JSONObject {
        val json = JSONObject()
        for ((key, value) in map) {
            when (value) {
                is String -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    json.put(key, convertMapToJson(value as Map<String, Any>))
                }
                is List<*> -> {
                    val jsonArray = JSONArray()
                    for (item in value) {
                        when (item) {
                            is String -> jsonArray.put(item)
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                jsonArray.put(convertMapToJson(item as Map<String, Any>))
                            }
                            else -> jsonArray.put(item.toString())
                        }
                    }
                    json.put(key, jsonArray)
                }
                else -> json.put(key, value.toString())
            }
        }
        return json
    }

    /**
     * ?checkpoint 文件恢复状?     * @param context Android 上下?     * @param sessionId 会话 ID
     * @return 恢复的状态数据，若文件不存在则返?null
     */
    suspend fun restoreFromCheckpoint(context: Context, sessionId: String): Map<String, Any>? =
        withContext(Dispatchers.IO) {
            try {
                val fileName = "${CHECKPOINT_FILE_PREFIX}${sessionId}${CHECKPOINT_FILE_SUFFIX}"
                val file = File(context.filesDir, fileName)

                if (!file.exists()) {
                    AppLogger.d(TAG, "No checkpoint file found for session: ${sessionId}")
                    return@withContext null
                }

                AppLogger.d(TAG, "Restoring from checkpoint: ${file.absolutePath}")
                val content = file.readText()
                val json = JSONObject(content)

                parseJsonToMap(json)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to restore from checkpoint for session: ${sessionId}", e)
                null
            }
        }

    /**
     * ?JSONObject 解析?Map
     */
    private fun parseJsonToMap(json: JSONObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is JSONObject -> map[key] = parseJsonToMap(value)
                is JSONArray -> {
                    val list = mutableListOf<Any>()
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        when (item) {
                            is JSONObject -> list.add(parseJsonToMap(item))
                            else -> list.add(item)
                        }
                    }
                    map[key] = list
                }
                else -> map[key] = value
            }
        }
        return map
    }
}
