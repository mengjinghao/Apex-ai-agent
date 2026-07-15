package com.apex.agent.common.extensions

import java.util.Locale

/**
 * [String] 和 [String?] 的扩展函数集合，提供空安全处理、格式转换、校验等便捷方法。
 */

/**
 * 当字符串为 null 时返回默认值。
 *
 * @param default 默认值，默认为空字符串 ""
 * @return 非 null 的字符串
 */
fun String?.orDefault(default: String = ""): String = this ?: default

/**
 * 检查字符串是否为 null 或空白。
 *
 * @return 如果字符串为 null 或仅包含空白字符返回 true
 */
fun String?.isNullOrBlank(): Boolean = this == null || this.all { it.isWhitespace() }

/**
 * 将字符串中的每个单词的首字母大写。
 * 以空格分隔单词。
 *
 * @return 首字母大写后的字符串
 */
fun String.capitalizeWords(): String {
    if (isEmpty()) return this
    return split(" ").joinToString(" ") { word ->
        word.replaceFirstChar {
            if (it.isLowerCase()) it.uppercase() else it.toString()
        }
    }
}

/**
 * 截断字符串到指定长度，超过部分以省略号替代。
 *
 * @param maxLength 最大字符数
 * @param ellipsis  省略号文本，默认 "..."
 * @return 截断后的字符串
 */
fun String.truncate(maxLength: Int, ellipsis: String = "..."): String {
    return if (length <= maxLength) this
    else take(maxLength - ellipsis.length) + ellipsis
}

/**
 * 检查字符串是否为有效的邮箱格式。
 *
 * @return 是有效邮箱返回 true
 */
fun String.isEmail(): Boolean {
    if (isBlank()) return false
    val regex = Regex("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        return regex.matches(this)
}

/**
 * 检查字符串是否为有效的手机号格式。
 *
 * @return 是有效手机号返回 true
 */
fun String.isPhone(): Boolean {
    if (isBlank()) return false
    val cleaned = replace(Regex("[\\s\\-()]"), "")
        return cleaned.length >= 10 && cleaned.all { it.isDigit() }
}

/**
 * 检查字符串是否为有效的 URL 格式。
 *
 * @return 是有效 URL 返回 true
 */
fun String.isUrl(): Boolean {
    if (isBlank()) return false
    val regex = Regex("^(https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]")
        return regex.matches(this)
}

/**
 * 检查字符串是否为纯数字。
 *
 * @return 全部为数字字符返回 true
 */
fun String.isNumeric(): Boolean {
    if (isBlank()) return false
    return all { it.isDigit() }
}

/**
 * 将字符串转换为驼峰命名法（camelCase）。
 * 支持下划线分隔和空格分隔两种格式。
 *
 * @return 驼峰命名字符串
 */
fun String.toCamelCase(): String {
    if (isEmpty()) return this
    val separators = Regex("[_\\s-]")
        val parts = split(separators).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        return parts.first().lowercase() + parts.drop(1).joinToString("") {
        it.replaceFirstChar { c -> c.uppercase() }
    }
}

/**
 * 将字符串转换为蛇形命名法（snake_case）。
 * 驼峰命名的边界处自动插入下划线。
 *
 * @return 蛇形命名字符串
 */
fun String.toSnakeCase(): String {
    if (isEmpty()) return this
    val result = StringBuilder()
        for (char in this) {
        if (char.isUpperCase() && result.isNotEmpty()) {
            result.append('_')
        }
        result.append(char.lowercase())
    }
        return result.toString().replace(Regex("[\\s-]"), "_")
}

/**
 * 统计指定子串在字符串中出现的次数。
 *
 * @param sub 要统计的子串
 * @return 出现次数
 */
fun String.countOccurrences(sub: String): Int {
    if (sub.isEmpty()) return 0
    var count = 0
    var startIndex = 0
    while (true) {
        val index = indexOf(sub, startIndex)
        if (index < 0) break
        count++
        startIndex = index + sub.length
    }
        return count
}

/**
 * 去除字符串中的 HTML 标签。
 *
 * @return 纯文本内容
 */
fun String.stripHtml(): String {
    return replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()
}

/**
 * 安全地将字符串转换为 Int，转换失败时返回默认值。
 *
 * @param default 默认值，默认为 0
 * @return 转换后的 Int 值
 */
fun String.toSafeInt(default: Int = 0): Int {
    return toIntOrNull() ?: default
}

/**
 * 安全地将字符串转换为 Long，转换失败时返回默认值。
 *
 * @param default 默认值，默认为 0L
 * @return 转换后的 Long 值
 */
fun String.toSafeLong(default: Long = 0L): Long {
    return toLongOrNull() ?: default
}

/**
 * 安全地将字符串转换为 Double，转换失败时返回默认值。
 *
 * @param default 默认值，默认为 0.0
 * @return 转换后的 Double 值
 */
fun String.toSafeDouble(default: Double = 0.0): Double {
    return toDoubleOrNull() ?: default
}
