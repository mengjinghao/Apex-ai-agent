package com.apex.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.apex.agent.R

/**
 * 全局主题管理?
 * 支持默认主题/Gemini主题/Material You主题切换，自动适配深色模式
 */
object ThemeManager {
    // 主题类型枚举
    enum class ThemeType(val value: Int) {
        DEFAULT(0),
        GEMINI(1),
        MATERIAL_YOU(2)
    }

    // 深色模式枚举
    enum class DarkMode(val value: Int) {
        FOLLOW_SYSTEM(0),
        LIGHT(1),
        DARK(2)
    }

    private const val SP_NAME = "Apex_theme_settings"
    private const val KEY_THEME_TYPE = "key_theme_type"
    private const val KEY_DARK_MODE = "key_dark_mode"
    private const val KEY_MATERIAL_YOU_COLOR = "key_material_you_color"

    private lateinit var sp: SharedPreferences

    // 初始化（在Application中调用一次即可）
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        applyDarkMode(getCurrentDarkMode())
    }

    // 获取当前选中的主?
    fun getCurrentTheme(): ThemeType {
        val ordinal = sp.getInt(KEY_THEME_TYPE, ThemeType.MATERIAL_YOU.ordinal)
        return ThemeType.entries.getOrNull(ordinal) ?: ThemeType.MATERIAL_YOU
    }

    // 切换主题
    fun setTheme(themeType: ThemeType) {
        sp.edit().putInt(KEY_THEME_TYPE, themeType.ordinal).apply()
    }

    // 获取/设置 Material You 自定义颜?
    fun getMaterialYouColor(): Int? {
        val color = sp.getInt(KEY_MATERIAL_YOU_COLOR, -1)
        return if (color == -1) null else color
    }

    fun setMaterialYouColor(color: Int) {
        sp.edit().putInt(KEY_MATERIAL_YOU_COLOR, color).apply()
    }

    // 应用主题更改到指定Activity
    fun applyThemeChange(activity: android.app.Activity) {
        activity.recreate()
    }

    // 获取当前深色模式
    fun getCurrentDarkMode(): DarkMode {
        val ordinal = sp.getInt(KEY_DARK_MODE, DarkMode.FOLLOW_SYSTEM.ordinal)
        return DarkMode.entries.getOrNull(ordinal) ?: DarkMode.FOLLOW_SYSTEM
    }

    // 设置深色模式
    fun setDarkMode(darkMode: DarkMode) {
        sp.edit().putInt(KEY_DARK_MODE, darkMode.ordinal).apply()
        applyDarkMode(darkMode)
    }

    // 应用深色模式到全局
    private fun applyDarkMode(darkMode: DarkMode) {
        val nightMode = when (darkMode) {
            DarkMode.FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            DarkMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            DarkMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    // 根据当前主题+深色模式，获取对应的ThemeStyle
    fun getThemeStyleRes(): Int {
        return when (getCurrentTheme()) {
            ThemeType.DEFAULT -> {
                R.style.Theme_Apex
            }
            ThemeType.GEMINI -> {
                when (getCurrentDarkMode()) {
                    DarkMode.DARK -> R.style.Theme_Apex_Gemini_Dark
                    DarkMode.FOLLOW_SYSTEM -> {
                        if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
                            R.style.Theme_Apex_Gemini_Dark
                        } else {
                            R.style.Theme_Apex_Gemini
                        }
                    }
                    else -> R.style.Theme_Apex_Gemini
                }
            }
            ThemeType.MATERIAL_YOU -> {
                R.style.Theme_Apex_MaterialYou
            }
        }
    }
}