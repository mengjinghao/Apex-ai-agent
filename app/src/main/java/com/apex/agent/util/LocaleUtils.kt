package com.apex.util

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.apex.data.preferences.preferencesManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 语言工具类，用于管理应用的国际化设置
 *
 * 支持多语言切换、系统语言跟随、语种本地化上下文创建等功能
 */
object LocaleUtils {

    /** 跟随系统语言代码标识 */
    const val AUTO_LANGUAGE_CODE = "system"

    /** 巴西葡萄牙语代码 */
    const val PORTUGUESE_BRAZIL_LANGUAGE_CODE = "pt-BR"

    // 旧语言代码到新语言代码的别名映射
    private val legacyLanguageCodeAliases =
        mapOf("pt" to PORTUGUESE_BRAZIL_LANGUAGE_CODE)

    /**
     * 语言信息数据类
     *
     * @param code 语言代码（如 zh、en、ja）
     * @param displayName 显示名称（英文）
     * @param nativeName 本地名称（语言自身的称呼）
     */
    data class Language(val code: String, val displayName: String, val nativeName: String)

    /**
     * 支持的语言列表（包含"跟随系统"选项）
     */
    private val supportedLanguages =
        listOf(
            Language(AUTO_LANGUAGE_CODE, "Follow system", "跟随系统"),
            Language("zh", "Chinese", "中文"),
            Language("en", "English", "English"),
            Language(
                PORTUGUESE_BRAZIL_LANGUAGE_CODE,
                "Portuguese (Brazil)",
                "Português (Brasil)"
            ),
            Language("ja", "Japanese", "日本語"),
            Language("ko", "Korean", "한국어"),
            Language("fr", "French", "Français"),
            Language("de", "German", "Deutsch"),
            Language("es", "Spanish", "Español"),
            Language("ru", "Russian", "Русский"),
            Language("ar", "Arabic", "العربية"),
            Language("hi", "Hindi", "हिन्दी"),
            Language("it", "Italian", "Italiano")
        )

    /**
     * 受支持语言代码集合（不包含"跟随系统"）
     */
    private val supportedLanguageCodes =
        supportedLanguages.map { it.code }.filter { it != AUTO_LANGUAGE_CODE }.toSet()

    /**
     * 语言代码到国旗 Emoji 的映射
     */
    private val languageEmojiMap = mapOf(
        "zh" to "\uD83C\uDDE8\uD83C\uDDF3",  // 🇨🇳
        "en" to "\uD83C\uDDFA\uD83C\uDDF8",  // 🇺🇸
        PORTUGUESE_BRAZIL_LANGUAGE_CODE to "\uD83C\uDDE7\uD83C\uDDF7", // 🇧🇷
        "ja" to "\uD83C\uDDEF\uD83C\uDDF5",  // 🇯🇵
        "ko" to "\uD83C\uDDF0\uD83C\uDDF7",  // 🇰🇷
        "fr" to "\uD83C\uDDEB\uD83C\uDDF7",  // 🇫🇷
        "de" to "\uD83C\uDDE9\uD83C\uDDEA",  // 🇩🇪
        "es" to "\uD83C\uDDEA\uD83C\uDDF8",  // 🇪🇸
        "ru" to "\uD83C\uDDF7\uD83C\uDDFA",  // 🇷🇺
        "ar" to "\uD83C\uDDF8\uD83C\uDDE6",  // 🇸🇦
        "hi" to "\uD83C\uDDEE\uD83C\uDDF3",  // 🇮🇳
        "it" to "\u83C\uDDEE\uD83C\uDDF9"    // 🇮🇹
    )

    /**
     * 获取支持的语言列表
     *
     * @return 语言信息列表
     */
    fun getSupportedLanguages(): List<Language> {
        return supportedLanguages
    }

    /**
     * 根据语言代码获取对应的 Locale 对象
     *
     * 如果传入 "system" 或空白代码，将使用设备的当前系统语言。
     *
     * @param languageCode 语言代码（如 zh、en、pt-BR）
     * @param context 上下文，用于获取系统语言（可选）
     * @return 对应的 Locale 对象
     */
    fun getLocaleForLanguageCode(languageCode: String, context: Context? = null): Locale {
        val resolvedCode =
            if (languageCode.isBlank() || languageCode == AUTO_LANGUAGE_CODE) {
                context?.let(::getCurrentSystemLanguageCode)
                    ?: resolveSupportedLanguageCode(Locale.getDefault().toLanguageTag())
            } else {
                resolveSupportedLanguageCode(languageCode)
            }
        return Locale.forLanguageTag(resolvedCode)
            .takeIf { it.language.isNotBlank() }
            ?: Locale(resolvedCode)
    }

    /**
     * 获取包含当前应用语言设置的上下文
     *
     * 对于使用 applicationContext 的单例或服务，这非常有用，
     * 因为它可以确保获取到最新的本地化资源。
     *
     * @param context 基础上下文
     * @return 带有更新后语言配置的新上下文
     */
    fun getLocalizedContext(context: Context): Context {
        val lang = getCurrentLanguage(context)
        val locale = getLocaleForLanguageCode(lang, context)
        val configuration = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
        val localeList = LocaleList(locale)
            configuration.setLocales(localeList)
        } else {
            @Suppress("DEPRECATION")
            configuration.setLocale(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    /**
     * 获取当前应用设置的语言
     *
     * 优先读取本地存储的语言设置，若为"跟随系统"或未设置则返回系统语言。
     *
     * @param context 上下文
     * @return 当前语言代码，如 zh、en、ja
     */
    fun getCurrentLanguage(context: Context): String {
        try {
            val manager = runCatching { preferencesManager }.getOrNull()
        if (manager != null) {
                val savedLanguage = manager.getCurrentLanguage()
        if (savedLanguage.isNotEmpty() && savedLanguage != AUTO_LANGUAGE_CODE) {
                    return resolveSupportedLanguageCode(savedLanguage)
                }
            }
        } catch (e: Exception) {
            // 错误时静默处理
        }
        return getCurrentSystemLanguage(context)
    }

    /**
     * 获取系统当前语言
     *
     * @param context 上下文
     * @return 系统语言代码
     */
    private fun getCurrentSystemLanguage(context: Context): String {
        return getCurrentSystemLanguageCode(context)
    }

    /**
     * 设置应用语言
     *
     * 保存语言偏好到本地存储，并根据 Android 版本使用不同的 API
     * 应用语言设置（Android 13+ 使用 AppCompatDelegate API）。
     *
     * @param context 上下文
     * @param languageCode 语言代码，如 zh、en、pt-BR
     */
    fun setAppLanguage(context: Context, languageCode: String) {

        try {
            val manager = runCatching { preferencesManager }.getOrNull()
        if (manager != null) {
                runBlocking(Dispatchers.IO) {
                    manager.saveAppLanguage(languageCode)
                }
            }
        } catch (e: Exception) {
            // 错误时静默处理
        }
        val localeToSet = getLocaleForLanguageCode(languageCode, context)

        Locale.setDefault(localeToSet)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeList = LocaleListCompat.create(localeToSet)
            AppCompatDelegate.setApplicationLocales(localeList)
        } else {
            try {
                val config = Configuration(context.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val localeList = LocaleList(localeToSet)
                    LocaleList.setDefault(localeList)
                    config.setLocales(localeList)
                } else {
                    config.locale = localeToSet
                }

                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(config, context.resources.displayMetrics)

                try {
                    val ctx = context.applicationContext
                    if (ctx is ContextWrapper) {
                        val baseContext = ctx.baseContext
                        if (baseContext != null) {
                            @Suppress("DEPRECATION")
                            baseContext.resources.updateConfiguration(
                                config,
                                baseContext.resources.displayMetrics
                            )
                        }
                    }
                } catch (e: Exception) {
                    // 忽略无法更新的上下文
                }
            } catch (e: Exception) {
                // 错误时静默处理
            }
        }
    }

    /**
     * 判断指定的语言代码是否在支持的语言列表中
     *
     * @param code 语言代码（如 zh、en、ja）
     * @return true 受支持，false 不受支持
     */
    fun isLanguageSupported(code: String): Boolean {
        return if (code == AUTO_LANGUAGE_CODE) true else code in supportedLanguageCodes
    }

    /**
     * 获取语言代码对应的英文显示名称
     *
     * @param code 语言代码
     * @return 显示名称（英文），若找不到则返回代码本身
     */
    fun getDisplayName(code: String): String {
        if (code == AUTO_LANGUAGE_CODE) return "Follow system"
        return supportedLanguages.firstOrNull { it.code == code }?.displayName ?: code
    }

    /**
     * 获取语言代码对应的本地名称（语言自身的称呼）
     *
     * @param code 语言代码
     * @return 本地名称（如中文、日本語、English），若找不到则返回代码本身
     */
    fun getNativeName(code: String): String {
        if (code == AUTO_LANGUAGE_CODE) return "跟随系统"
        return supportedLanguages.firstOrNull { it.code == code }?.nativeName ?: code
    }

    /**
     * 获取语言代码对应的国旗 Emoji
     *
     * @param code 语言代码（如 zh、en、ja）
     * @return 国旗 Emoji 字符串，若找不到则返回地球 Emoji（🌍）
     */
    fun getLanguageEmoji(code: String): String {
        val normalizedCode = if (code == AUTO_LANGUAGE_CODE) {
            Locale.getDefault().language
        } else {
            code
        }
        return languageEmojiMap[normalizedCode] ?: "\uD83C\uDF0D"  // 🌍
    }

    /**
     * 获取设备默认系统语言代码
     *
     * @param context 上下文
     * @return 系统语言代码（如 zh、en、ja）
     */
    fun getDefaultLanguage(context: Context): String {
        return getCurrentSystemLanguageCode(context)
    }

    /**
     * 获取当前支持的语言数量（包含"跟随系统"选项）
     *
     * @return 语言总数
     */
    fun languageCount(): Int {
        return supportedLanguages.size
    }

    /**
     * 获取当前系统 Locale
     *
     * @param context 上下文
     * @return 当前系统的 Locale 对象
     */
    private fun getCurrentSystemLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
    }

    /**
     * 获取当前系统语言代码（已解析为支持的格式）
     *
     * @param context 上下文
     * @return 解析后的系统语言代码
     */
    private fun getCurrentSystemLanguageCode(context: Context): String {
        return resolveSupportedLanguageCode(getCurrentSystemLocale(context).toLanguageTag())
    }

    /**
     * 规范化存储的语言代码
     *
     * 将下划线格式替换为连字符格式，处理 -r 区域标记，
     * 并通过 Locale.forLanguageTag 进行规范化。
     *
     * @param languageCode 原始语言代码
     * @return 规范化后的语言代码
     */
    private fun normalizeStoredLanguageCode(languageCode: String): String {
        if (languageCode.isBlank() || languageCode == AUTO_LANGUAGE_CODE) {
            return languageCode
        }
        val normalizedCode = languageCode.replace("_", "-").replace("-r", "-")
        val canonicalCode =
            Locale.forLanguageTag(normalizedCode)
                .takeIf { it.language.isNotBlank() }
                ?.toLanguageTag()
                ?.takeIf { it.isNotBlank() && it != "und" }
                ?: normalizedCode
        return legacyLanguageCodeAliases[canonicalCode] ?: canonicalCode
    }

    /**
     * 解析并匹配受支持的语言代码
     *
     * 如果传入的代码直接受支持则直接返回；
     * 否则尝试匹配语言部分（如 en-US 匹配 en），
     * 或查找同一语言的变体。
     *
     * @param languageCode 待解析的语言代码
     * @return 匹配到的受支持语言代码，或原代码
     */
    private fun resolveSupportedLanguageCode(languageCode: String): String {
        val normalizedCode = normalizeStoredLanguageCode(languageCode)
        if (normalizedCode.isBlank() || normalizedCode == AUTO_LANGUAGE_CODE) {
            return normalizedCode
        }
        if (normalizedCode in supportedLanguageCodes) {
            return normalizedCode
        }
        val locale =
            Locale.forLanguageTag(normalizedCode)
                .takeIf { it.language.isNotBlank() }
                ?: return normalizedCode
        val language = locale.language.lowercase(Locale.ROOT)
        val languageOnlyMatch =
            supportedLanguageCodes.firstOrNull { it.equals(language, ignoreCase = true) }
        if (languageOnlyMatch != null) {
            return languageOnlyMatch
        }
        val sameLanguageVariants =
            supportedLanguageCodes.filter {
                Locale.forLanguageTag(it).language.equals(language, ignoreCase = true)
            }
        if (sameLanguageVariants.size == 1) {
            return sameLanguageVariants.first()
        }
        return normalizedCode
    }
}
