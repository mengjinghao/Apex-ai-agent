package com.apex.agent.core.hooks

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话结束钩子实现
 * 负责生成会话摘要、持久化存储，并通知技能系统检查可提取模式
 */
class SessionEndHook : SessionLifecycleHook {

    companion object {
        private const val TAG = "SessionEndHook"
        private const val SUMMARY_FILE_PREFIX = "session_summary_"
        private const val SUMMARY_FILE_SUFFIX = ".json"
    }

    override suspend fun onSessionEnd(context: Context, sessionContext: SessionContext) {
        AppLogger.i(TAG, "Session ending: ${sessionContext.sessionId}")

        val summary = generateSessionSummary(sessionContext)
        saveSummary(context, sessionContext.sessionId, summary)

        notifySkillSystem(context, summary)
    }

    /**
     * 生成会话摘要
     * 包括关键决策、学习成果、未完成工作
     * @param sessionContext 会话上下�?     * @return 会话摘要数据
     */
    private fun generateSessionSummary(sessionContext: SessionContext): Map<String, Any> {
        val summary = mutableMapOf<String, Any>()

        summary["sessionId"] = sessionContext.sessionId
        summary["startTime"] = sessionContext.startTime
        summary["endTime"] = System.currentTimeMillis()
        summary["duration"] = System.currentTimeMillis() - sessionContext.startTime
        summary["messageCount"] = sessionContext.messageCount
        summary["tokenUsage"] = sessionContext.tokenUsage

        // 提取关键决策
        val decisions = extractKeyDecisions(sessionContext.environmentState)
        if (decisions.isNotEmpty()) {
            summary["keyDecisions"] = decisions
        }

        // 提取学习成果
        val learnings = extractLearnings(sessionContext.environmentState)
        if (learnings.isNotEmpty()) {
            summary["learnings"] = learnings
        }

        // 提取未完成工�?        val incompleteWork = extractIncompleteWork(sessionContext.environmentState)
        if (incompleteWork.isNotEmpty()) {
            summary["incompleteWork"] = incompleteWork
        }

        // 提取环境状态快�?        summary["environmentSnapshot"] = sessionContext.environmentState

        AppLogger.d(TAG, "Generated session summary with ${summary.keys.size} fields")
        return summary
    }

    /**
     * 提取关键决策记录
     */
    private fun extractKeyDecisions(envState: Map<String, String>): List<Map<String, String>> {
        val decisionsJson = envState["keyDecisions"] ?: return emptyList()
        return try {
            val jsonArray = JSONArray(decisionsJson)
            (0 until jsonArray.length()).map {
                val obj = jsonArray.getJSONObject(it)
                obj.keys().asSequence().associateWith { key -> obj.getString(key) }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse key decisions", e)
            emptyList()
        }
    }

    /**
     * 提取学习成果
     */
    private fun extractLearnings(envState: Map<String, String>): List<String> {
        val learningsJson = envState["learnings"] ?: return emptyList()
        return try {
            val jsonArray = JSONArray(learningsJson)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse learnings", e)
            emptyList()
        }
    }

    /**
     * 提取未完成工�?     */
    private fun extractIncompleteWork(envState: Map<String, String>): List<String> {
        val workJson = envState["incompleteWork"] ?: return emptyList()
        return try {
            val jsonArray = JSONArray(workJson)
            (0 until jsonArray.length()).map { jsonArray.getString(it) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to parse incomplete work", e)
            emptyList()
        }
    }

    /**
     * 持久化会话摘要到文件
     * @param context Android 上下�?     * @param sessionId 会话 ID
     * @param summary 摘要数据
     */
    private suspend fun saveSummary(
        context: Context,
        sessionId: String,
        summary: Map<String, Any>
    ) = withContext(Dispatchers.IO) {
        try {
            val fileName = "${SUMMARY_FILE_PREFIX}${sessionId}${SUMMARY_FILE_SUFFIX}"
            val file = File(context.filesDir, fileName)

            val jsonContent = convertMapToJson(summary)
            file.writeText(jsonContent.toString(2))

            AppLogger.i(TAG, "Session summary saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save session summary for session: ${sessionId}", e)
        }
    }

    /**
     * �?Map 转换�?JSONObject
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
     * 通知技能系统检查可提取模式
     * 调用 AutoSkillExtractor（如果存在）
     * @param context Android 上下�?     * @param summary 会话摘要
     */
    private suspend fun notifySkillSystem(context: Context, summary: Map<String, Any>) {
        try {
            // 尝试动态加�?AutoSkillExtractor �?            val autoSkillExtractorClass = try {
                Class.forName("com.apex.agent.core.skills.AutoSkillExtractor")
            } catch (e: ClassNotFoundException) {
                AppLogger.d(TAG, "AutoSkillExtractor not found, skipping skill extraction")
                return
            }

            // 获取单例实例
            val getInstanceMethod = autoSkillExtractorClass.getMethod("getInstance", Context::class.java)
            val extractor = getInstanceMethod.invoke(null, context)

            // 调用 extractFromSession 方法
            val extractMethod = autoSkillExtractorClass.getMethod(
                "extractFromSession",
                Map::class.java
            )

            AppLogger.i(TAG, "Notifying AutoSkillExtractor to check for extractable patterns")
            extractMethod.invoke(extractor, summary)

        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to notify skill system", e)
        }
    }
}
