package com.apex.agent.core.tools.defaultTool.websession.storage

import android.content.Context
import android.content.SharedPreferences
import com.apex.util.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WebSession Agent 登录信息管理
 * 
 * 专为 Agent 自动化场景设计：
 * - 简单本地存储（无加密）
 * - Agent 自动管理和调�?
 * - 支持多网站凭�?
 * - 快速读写性能
 */
internal class WebSessionAgentLoginStorage(
    private val context: Context
) {
    companion object {
        private const val TAG = "WebSessionAgentLogin"
        private const val PREFS_NAME = "websession_agent_login"
        
        @Volatile
        private var instance: WebSessionAgentLoginStorage? = null
        
        fun getInstance(context: Context): WebSessionAgentLoginStorage {
            return instance ?: synchronized(this) {
                instance ?: WebSessionAgentLoginStorage(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val json = Json { 
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    
    /**
     * 保存网站的登�?Cookie
     * 
     * @param siteKey 网站标识（如 "doubao.com"�?
     * @param cookies Cookie 字符�?
     * @param url 来源 URL
     */
    fun saveCookies(siteKey: String, cookies: String, url: String = "") {
        try {
            val key = "cookies_${siteKey}"
            val data = AgentLoginData(
                cookies = cookies,
                url = url,
                timestamp = System.currentTimeMillis()
            )
            val jsonStr = json.encodeToString(data)
            
            prefs.edit()
                .putString(key, jsonStr)
                .apply()
            
            AppLogger.d(TAG, "Saved cookies for: ${siteKey}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to save cookies for: ${siteKey}", e)
        }
    }
    
    /**
     * 获取网站的登�?Cookie
     * 
     * @param siteKey 网站标识
     * @return Cookie 字符串，如果不存在则返回空字符串
     */
    fun getCookies(siteKey: String): String {
        try {
            val key = "cookies_${siteKey}"
            val jsonStr = prefs.getString(key, null) ?: return ""
            
            val data = json.decodeFromString<AgentLoginData>(jsonStr)
            return data.cookies
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to load cookies for: ${siteKey}", e)
            return ""
        }
    }
    
    /**
     * 删除网站的登�?Cookie
     * 
     * @param siteKey 网站标识
     */
    fun deleteCookies(siteKey: String) {
        try {
            val key = "cookies_${siteKey}"
            prefs.edit().remove(key).apply()
            AppLogger.d(TAG, "Deleted cookies for: ${siteKey}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to delete cookies for: ${siteKey}", e)
        }
    }
    
    /**
     * 检查是否有保存�?Cookie
     */
    fun hasCookies(siteKey: String): Boolean {
        val key = "cookies_${siteKey}"
        return prefs.contains(key)
    }
    
    /**
     * 获取最后更新时�?
     */
    fun getLastUpdateTime(siteKey: String): Long {
        try {
            val key = "cookies_${siteKey}"
            val jsonStr = prefs.getString(key, null) ?: return 0L
            
            val data = json.decodeFromString<AgentLoginData>(jsonStr)
            return data.timestamp
        } catch (e: Exception) {
            return 0L
        }
    }
    
    /**
     * 列出所有已保存的网�?
     */
    fun listSavedSites(): List<String> {
        val sites = mutableListOf<String>()
        
        prefs.all.keys.forEach { key ->
            if (key.startsWith("cookies_")) {
                val siteKey = key.removePrefix("cookies_")
                sites.add(siteKey)
            }
        }
        
        return sites
    }
    
    /**
     * 清除所有登录信�?
     */
    fun clearAll() {
        val keysToRemove = prefs.all.keys.filter { it.startsWith("cookies_") }
        prefs.edit().apply {
            keysToRemove.forEach { remove(it) }
            apply()
        }
        AppLogger.d(TAG, "Cleared all agent login data")
    }
    
    /**
     * 获取 Cookie 年龄（小时）
     */
    fun getCookieAgeHours(siteKey: String): Double {
        val lastUpdate = getLastUpdateTime(siteKey)
        if (lastUpdate == 0L) return -1.0
        
        return (System.currentTimeMillis() - lastUpdate) / (1000.0 * 60 * 60)
    }
}

// ==================== 数据模型 ====================

/**
 * Agent 登录数据
 */
@Serializable
data class AgentLoginData(
    val cookies: String = "",
    val url: String = "",
    val timestamp: Long = 0L
)
