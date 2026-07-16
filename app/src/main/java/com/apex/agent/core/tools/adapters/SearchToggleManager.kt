package com.apex.core.tools.adapters

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * 搜索按钮状态管理器
 * 管理搜索按钮的开�关闭状�?
 */
object SearchToggleManager {
    private const val TAG = "SearchToggleManager"
    private const val PREF_SEARCH_ENABLED = "search_toggle_enabled"

    // 状态监听器
    interface OnStateChangeListener {
        fun onStateChanged(enabled: Boolean)
    }

    private var listeners = mutableListOf<OnStateChangeListener>()

    /**
     * 获取搜索按钮状�?
     */
    fun isSearchEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_SEARCH_ENABLED, false)
    }

    /**
     * 设置搜索按钮状�?
     */
    fun setSearchEnabled(context: Context, enabled: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean(PREF_SEARCH_ENABLED, enabled).apply()
        notifyListeners(enabled)
    }

    /**
     * 切换搜索按钮状�?
     */
    fun toggleSearch(context: Context): Boolean {
        val current = isSearchEnabled(context)
        val newState = !current
        setSearchEnabled(context, newState)
        return newState
    }

    /**
     * 添加状态监听器
     */
    fun addOnStateChangeListener(listener: OnStateChangeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * 移除状态监听器
     */
    fun removeOnStateChangeListener(listener: OnStateChangeListener) {
        listeners.remove(listener)
    }

    /**
     * 通知所有监听器
     */
    private fun notifyListeners(enabled: Boolean) {
        listeners.forEach { it.onStateChanged(enabled) }
    }

    /**
     * 获取状态描�?
     */
    fun getStateDescription(context: Context): String {
        return if (isSearchEnabled(context)) {
            "搜索已开启，发送消息时会自动搜�?
        } else {
            "搜索已关闭，点击开�?
        }
    }
}