package com.apex.agent.core.hooks

import android.content.Context
import com.apex.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 会话上下文数据类，保存会话的运行时状态信�? */
data class SessionContext(
    val sessionId: String,
    val startTime: Long,
    val lastActivity: Long,
    val messageCount: Int,
    val tokenUsage: Long,
    val environmentState: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionId", sessionId)
        put("startTime", startTime)
        put("lastActivity", lastActivity)
        put("messageCount", messageCount)
        put("tokenUsage", tokenUsage)
        put("environmentState", JSONObject(environmentState))
    }

    companion object {
        fun fromJson(json: JSONObject): SessionContext = SessionContext(
            sessionId = json.getString("sessionId"),
            startTime = json.getLong("startTime"),
            lastActivity = json.getLong("lastActivity"),
            messageCount = json.getInt("messageCount"),
            tokenUsage = json.getLong("tokenUsage"),
            environmentState = json.optJSONObject("environmentState")?.let { env ->
                env.keys().asSequence().associateWith { env.getString(it) }
            } ?: emptyMap()
        )
    }
}

/**
 * 会话生命周期钩子接口
 * 所有钩子方法使�?suspend 函数以支持异步操�? */
interface SessionLifecycleHook {

    /** 会话开始时触发 */
    suspend fun onSessionStart(context: Context, sessionContext: SessionContext) {}

    /** 上下文压缩前触发，用于保存关键状�?*/
    suspend fun onPreCompact(context: Context, sessionContext: SessionContext): Map<String, Any> = emptyMap()

    /** 会话结束时触�?*/
    suspend fun onSessionEnd(context: Context, sessionContext: SessionContext) {}

    /** 会话空闲时触�?*/
    suspend fun onIdle(context: Context, sessionContext: SessionContext) {}
}

/**
 * 钩子注册表单例，支持注册、注销和触发钩�? */
object HookRegistry {

    private const val TAG = "HookRegistry"

    private val hooks = CopyOnWriteArrayList<SessionLifecycleHook>()
    private val mutex = Mutex()

    /** 注册一个会话生命周期钩�?*/
    suspend fun register(hook: SessionLifecycleHook) {
        mutex.withLock {
            if (hooks.none { it::class == hook::class }) {
                hooks.add(hook)
                AppLogger.d(TAG, "Registered hook: ${hook::class.simpleName}")
            } else {
                AppLogger.w(TAG, "Hook already registered: ${hook::class.simpleName}")
            }
        }
    }

    /** 注销一个会话生命周期钩�?*/
    suspend fun unregister(hook: SessionLifecycleHook) {
        mutex.withLock {
            hooks.remove(hook)
            AppLogger.d(TAG, "Unregistered hook: ${hook::class.simpleName}")
        }
    }

    /** 触发 onSessionStart 钩子 */
    suspend fun triggerSessionStart(context: Context, sessionContext: SessionContext) {
        AppLogger.d(TAG, "Triggering onSessionStart for session: ${sessionContext.sessionId}")
        hooks.forEach { hook ->
            try {
                hook.onSessionStart(context, sessionContext)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in onSessionStart hook: ${hook::class.simpleName}", e)
            }
        }
    }

    /** 触发 onPreCompact 钩子，收集所有钩子返回的状态数�?*/
    suspend fun triggerPreCompact(context: Context, sessionContext: SessionContext): Map<String, Any> {
        AppLogger.d(TAG, "Triggering onPreCompact for session: ${sessionContext.sessionId}")
        val collected = mutableMapOf<String, Any>()
        hooks.forEach { hook ->
            try {
                val data = hook.onPreCompact(context, sessionContext)
                collected.putAll(data)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in onPreCompact hook: ${hook::class.simpleName}", e)
            }
        }
        return collected
    }

    /** 触发 onSessionEnd 钩子 */
    suspend fun triggerSessionEnd(context: Context, sessionContext: SessionContext) {
        AppLogger.d(TAG, "Triggering onSessionEnd for session: ${sessionContext.sessionId}")
        hooks.forEach { hook ->
            try {
                hook.onSessionEnd(context, sessionContext)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in onSessionEnd hook: ${hook::class.simpleName}", e)
            }
        }
    }

    /** 触发 onIdle 钩子 */
    suspend fun triggerIdle(context: Context, sessionContext: SessionContext) {
        AppLogger.d(TAG, "Triggering onIdle for session: ${sessionContext.sessionId}")
        hooks.forEach { hook ->
            try {
                hook.onIdle(context, sessionContext)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error in onIdle hook: ${hook::class.simpleName}", e)
            }
        }
    }

    /** 清除所有已注册的钩�?*/
    suspend fun clearAll() {
        mutex.withLock {
            hooks.clear()
            AppLogger.d(TAG, "All hooks cleared")
        }
    }
}
