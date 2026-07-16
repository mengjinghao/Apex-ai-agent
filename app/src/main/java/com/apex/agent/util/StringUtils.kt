package com.apex.util

import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * 字符串工具类，提供各种字符串操作和验证方法
 */
object StringUtils {

    private val EMAIL_REGEX = Pattern.compile(
        "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$"
    )

    private val URL_REGEX = Pattern.compile(
        "^(https?://)?([\\w-]+\\.)+[\\w-]+(/[\\w-./?%&=]*)?$"
    )

    private val CHINESE_REGEX = Pattern.compile("[\\u4e00-\\u9fa5]")

    private val HTML_TAG_REGEX = Pattern.compile("<[^>]*>")

    private val EMAIL_EXTRACT_REGEX = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

    private val URL_EXTRACT_REGEX = Pattern.compile(
        "https?://[\\w./?=&%+-]+|[\\w-]+\\.[\\w-]+\\.[\\w-]+(/[\\w./?=&%+-]*)?"
    )

    private val PHONE_REGEX = Pattern.compile("(\\+?\\d{1,3}[-.\\s]?)?\\(?\\d{2,4}\\)?[-.\\s]?\\d{3,4}[-.\\s]?\\d{3,4}")

    private val MENTION_REGEX = Pattern.compile("@(\\w+)")

    /**
     * 计算两个字符串之间的 Levenshtein 编辑距离
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 编辑距离（将一个字符串转换为另一个所需的最少单字符编辑操作次数）
     */
    fun levenshteinDistance(a: String, b: String): Int {
        val aLen = a.length
        val bLen = b.length
        if (aLen == 0) return bLen
        if (bLen == 0) return aLen

        val dp = Array(aLen + 1) { IntArray(bLen + 1) }
        for (i in 0..aLen) dp[i][0] = i
        for (j in 0..bLen) dp[0][j] = j

        for (i in 1..aLen) {
            for (j in 1..bLen) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[aLen][bLen]
    }

    /**
     * 计算两个字符串的相似度比率
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 相似度比率，范围 0.0（完全不同）到 1.0（完全相同）
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val maxLen = maxOf(a.length, b.length)
        val dist = levenshteinDistance(a, b)
        return 1.0 - dist.toDouble() / maxLen.toDouble()
    }

    /**
     * 将驼峰命名字符串转换为蛇形命名（snake_case）
     *
     * @param camelCase 驼峰命名字符串
     * @return 蛇形命名字符串
     */
    fun toSnakeCase(camelCase: String): String {
        if (camelCase.isEmpty()) return ""
        val result = StringBuilder()
        for ((i, ch) in camelCase.withIndex()) {
            if (ch.isUpperCase()) {
                if (i > 0) result.append('_')
                result.append(ch.lowercaseChar())
            } else {
                result.append(ch)
            }
        }
        return result.toString()
    }

    /**
     * 将蛇形命名字符串转换为驼峰命名（camelCase）
     *
     * @param snakeCase 蛇形命名字符串
     * @param upperFirst 是否大写首字母（生成 PascalCase）
     * @return 驼峰命名字符串
     */
    fun toCamelCase(snakeCase: String, upperFirst: Boolean = false): String {
        if (snakeCase.isEmpty()) return ""
        val parts = snakeCase.split("_")
        val result = StringBuilder()
        for ((i, part) in parts.withIndex()) {
            if (i == 0 && !upperFirst) {
                result.append(part.lowercase())
            } else {
                if (part.isNotEmpty()) {
                    result.append(part[0].uppercaseChar())
                    if (part.length > 1) result.append(part.substring(1).lowercase())
                }
            }
        }
        return result.toString()
    }

    /**
     * 将字符串转换为短横线命名（kebab-case）
     *
     * @param input 输入字符串
     * @return 短横线命名字符串
     */
    fun toKebabCase(input: String): String {
        if (input.isEmpty()) return ""
        val snakeCase = toSnakeCase(input)
        return snakeCase.replace('_', '-')
    }

    /**
     * 将字符串转换为帕斯卡命名（PascalCase）。
     *
     * @param input 输入字符串（支持下划线、短横线、空格分隔）
     * @return 帕斯卡命名字符串
     */
    fun toPascalCase(input: String): String {
        if (input.isEmpty()) return ""
        val normalized = input.replace(Regex("[-_\\s]"), "_")
        return toCamelCase(normalized, upperFirst = true)
    }

    /**
     * 截断字符串并在末尾添加省略号
     *
     * @param text 原始文本
     * @param maxLength 最大长度
     * @param ellipsis 省略号字符串，默认为 "..."
     * @return 截断后的字符串
     */
    fun truncate(text: String, maxLength: Int, ellipsis: String = "..."): String {
        if (maxLength < 0) throw IllegalArgumentException("maxLength cannot be negative")
        if (maxLength == 0) return ""
        if (text.length <= maxLength) return text
        val adjustedMax = maxOf(0, maxLength - ellipsis.length)
        if (adjustedMax <= 0) return ellipsis.take(maxLength)
        return text.take(adjustedMax) + ellipsis
    }

    /**
     * 在单词边界处截断字符串并添加省略号。
     *
     * @param text 原始文本
     * @param maxLen 最大长度
     * @return 截断后的字符串
     */
    fun truncateAtWordBoundary(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val truncated = text.take(maxLen)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > 0) truncated.take(lastSpace) + "..." else truncated + "..."
    }

    /**
     * 验证电子邮件地址格式是否有效
     *
     * @param email 电子邮件地址
     * @return 如果格式有效则返回 true
     */
    fun isValidEmail(email: String): Boolean {
        if (email.isBlank()) return false
        return EMAIL_REGEX.matcher(email).matches()
    }

    /**
     * 验证 URL 格式是否有效
     *
     * @param url URL 字符串
     * @return 如果格式有效则返回 true
     */
    fun isValidUrl(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            URL(url)
            true
        } catch (e: Exception) {
            URL_REGEX.matcher(url).matches()
        }
    }

    /**
     * 计算子字符串在文本中出现的次数
     *
     * @param text 原始文本
     * @param sub 要搜索的子字符串
     * @return 出现次数
     */
    fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var startIndex = 0
        while (true) {
            val index = text.indexOf(sub, startIndex)
            if (index < 0) break
            count++
            startIndex = index + sub.length
        }
        return count
    }

    /**
     * 统计字符串中的行数。
     *
     * @param text 原始文本
     * @return 行数
     */
    fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        return text.split(Regex("\\r?\\n|\\r")).size
    }

    /**
     * 统计字符串中的单词数。
     *
     * @param text 原始文本
     * @return 单词数
     */
    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).size
    }

    /**
     * 反转句子中单词的顺序
     *
     * @param sentence 输入句子
     * @return 单词顺序反转后的字符串
     */
    fun reverseWords(sentence: String): String {
        if (sentence.isBlank()) return sentence
        val words = sentence.trim().split("\\s+".toRegex())
        return words.reversed().joinToString(" ")
    }

    /**
     * 移除字符串中多余的空白字符，将连续空白替换为单个空格
     *
     * @param text 原始文本
     * @return 规范化空白后的字符串
     */
    fun removeDuplicateWhitespace(text: String): String {
        if (text.isBlank()) return text.trim()
        return text.trim().replace("\\s+".toRegex(), " ")
    }

    /**
     * 将字符串中每个单词的首字母大写
     *
     * @param text 原始文本
     * @return 每个单词首字母大写后的字符串
     */
    fun capitalizeWords(text: String): String {
        if (text.isBlank()) return text
        val words = text.trim().split("\\s+".toRegex())
        return words.joinToString(" ") { word ->
            if (word.isEmpty()) word
            else word[0].uppercaseChar() + word.substring(1).lowercase(Locale.ROOT)
        }
    }

    /**
     * 使用掩码字符遮盖字符串的中间部分
     *
     * @param input 输入字符串
     * @param start 开始遮盖的位置（包含）
     * @param end 结束遮盖的位置（不包含）
     * @param maskChar 用于遮盖的字符，默认为 '*'
     * @return 遮盖后的字符串
     */
    fun maskString(input: String, start: Int, end: Int, maskChar: Char = '*'): String {
        if (input.isEmpty()) return input
        val actualStart = start.coerceIn(0, input.length)
        val actualEnd = end.coerceIn(actualStart, input.length)
        val maskLength = actualEnd - actualStart
        if (maskLength <= 0) return input
        val mask = maskChar.toString().repeat(maskLength)
        return input.substring(0, actualStart) + mask + input.substring(actualEnd)
    }

    /**
     * 使用指定字符遮盖字符串中指定范围的字符。
     *
     * @param input 输入字符串
     * @param from 开始位置
     * @param to 结束位置
     * @param char 用于遮盖的字符，默认为 '*'
     * @return 遮盖后的字符串
     */
    fun mask(input: String, from: Int = 0, to: Int = input.length, char: Char = '*'): String {
        return maskString(input, from, to, char)
    }

    /**
     * 遮盖电子邮件地址（如 j***@example.com）。
     *
     * @param email 电子邮件地址
     * @return 遮盖后的地址
     */
    fun maskEmail(email: String): String {
        val atIndex = email.indexOf('@')
        if (atIndex <= 1) return email
        return email[0] + "*".repeat(atIndex - 1) + email.substring(atIndex)
    }

    /**
     * 遮盖电话号码（如 138****1234）。
     *
     * @param phone 电话号码
     * @return 遮盖后的号码
     */
    fun maskPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length < 7) return phone
        val visibleStart = digits.take(3)
        val visibleEnd = digits.takeLast(4)
        val masked = digits.drop(3).dropLast(4)
        return phone.replace(digits, "$visibleStart${"*".repeat(masked.length)}$visibleEnd")
    }

    /**
     * 检查字符串是否包含中文字符
     *
     * @param text 输入文本
     * @return 如果包含中文字符则返回 true
     */
    fun containsChinese(text: String): Boolean {
        return CHINESE_REGEX.matcher(text).find()
    }

    /**
     * 从文本中提取所有 URL
     *
     * @param text 输入文本
     * @return URL 列表
     */
    fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matcher = URL_EXTRACT_REGEX.matcher(text)
        val urls = mutableListOf<String>()
        while (matcher.find()) {
            urls.add(matcher.group())
        }
        return urls
    }

    /**
     * 从文本中提取所有电子邮件地址。
     *
     * @param text 输入文本
     * @return 电子邮件地址列表
     */
    fun extractEmails(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matcher = EMAIL_EXTRACT_REGEX.matcher(text)
        val emails = mutableListOf<String>()
        while (matcher.find()) {
            emails.add(matcher.group())
        }
        return emails
    }

    /**
     * 从文本中提取所有电话号码。
     *
     * @param text 输入文本
     * @return 电话号码列表
     */
    fun extractPhoneNumbers(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matcher = PHONE_REGEX.matcher(text)
        val phones = mutableListOf<String>()
        while (matcher.find()) {
            phones.add(matcher.group().trim())
        }
        return phones
    }

    /**
     * 从文本中提取所有 @提及（如 @username）。
     *
     * @param text 输入文本
     * @return 提及的用户名列表
     */
    fun extractMentions(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val matcher = MENTION_REGEX.matcher(text)
        val mentions = mutableListOf<String>()
        while (matcher.find()) {
            mentions.add(matcher.group(1))
        }
        return mentions
    }

    /**
     * 在字符串开头填充指定字符以达到最小长度
     *
     * @param text 原始文本
     * @param minLength 最小长度
     * @param padChar 用于填充的字符，默认为空格
     * @return 填充后的字符串
     */
    fun padStart(text: String, minLength: Int, padChar: Char = ' '): String {
        if (text.length >= minLength) return text
        val padCount = minLength - text.length
        return padChar.toString().repeat(padCount) + text
    }

    /**
     * 在字符串末尾填充指定字符以达到最小长度
     *
     * @param text 原始文本
     * @param minLength 最小长度
     * @param padChar 用于填充的字符，默认为空格
     * @return 填充后的字符串
     */
    fun padEnd(text: String, minLength: Int, padChar: Char = ' '): String {
        if (text.length >= minLength) return text
        val padCount = minLength - text.length
        return text + padChar.toString().repeat(padCount)
    }

    /**
     * 检查字符串是否为纯数字
     *
     * @param text 输入文本
     * @return 如果全部为数字则返回 true
     */
    fun isNumeric(text: String): Boolean {
        if (text.isBlank()) return false
        return text.all { it.isDigit() }
    }

    /**
     * 检查字符串是否为字母数字组合
     *
     * @param text 输入文本
     * @return 如果全部为字母或数字则返回 true
     */
    fun isAlphanumeric(text: String): Boolean {
        if (text.isBlank()) return false
        return text.all { it.isLetterOrDigit() }
    }

    /**
     * 检查字符串是否只包含字母字符。
     *
     * @param text 输入文本
     * @return 如果全部为字母则返回 true
     */
    fun isAlpha(text: String): Boolean {
        if (text.isBlank()) return false
        return text.all { it.isLetter() }
    }

    /**
     * 移除字符串中的 HTML 标签
     *
     * @param html 包含 HTML 标签的字符串
     * @return 移除标签后的纯文本
     */
    fun stripHtml(html: String): String {
        if (html.isBlank()) return html
        return HTML_TAG_REGEX.matcher(html).replaceAll("")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }

    /**
     * 去除字符串首尾及中间多余的空白字符。
     *
     * @param text 原始文本
     * @return 规范化后的字符串
     */
    fun stripExtraWhitespace(text: String): String {
        return text.trim().replace(Regex("\\s+"), " ")
    }

    /**
     * 计算当前字符串与另一字符串的相似度。
     *
     * @param text 当前字符串
     * @param other 另一字符串
     * @return 相似度（0.0 ~ 1.0）
     */
    fun similarityTo(text: String, other: String): Double {
        return similarity(text, other)
    }

    /**
     * 检查字符串是否为回文。
     *
     * @param text 输入文本
     * @return 如果是回文返回 true
     */
    fun isPalindrome(text: String): Boolean {
        val cleaned = text.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)
        return cleaned == cleaned.reversed()
    }

    /**
     * 将字符串转换为标题大小写（每个单词首字母大写，其余小写）。
     *
     * @param text 输入文本
     * @return 标题大小写字符串
     */
    fun toTitleCase(text: String): String {
        return capitalizeWords(text)
    }

    /**
     * 切换字符串中每个字母的大小写。
     *
     * @param text 输入文本
     * @return 大小写切换后的字符串
     */
    fun toggleCase(text: String): String {
        return text.map { ch ->
            when {
                ch.isUpperCase() -> ch.lowercaseChar()
                ch.isLowerCase() -> ch.uppercaseChar()
                else -> ch
            }
        }.joinToString("")
    }

    /**
     * 重复字符串指定次数，并使用分隔符连接。
     *
     * @param text 原始字符串
     * @param n 重复次数
     * @param separator 分隔符
     * @return 重复并连接后的字符串
     */
    fun repeat(text: String, n: Int, separator: String = ""): String {
        if (n <= 0) return ""
        return List(n) { text }.joinToString(separator)
    }

    /**
     * 将字符串分块为指定大小的块。
     *
     * @param text 原始字符串
     * @param size 每块大小
     * @return 块列表
     */
    fun chunk(text: String, size: Int): List<String> {
        if (size <= 0) throw IllegalArgumentException("size must be positive, got $size")
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }

    /**
     * 缩写字符串到指定宽度。
     *
     * @param text 原始文本
     * @param maxWidth 最大宽度
     * @return 缩写后的字符串
     */
    fun abbreviate(text: String, maxWidth: Int): String {
        if (maxWidth < 4) throw IllegalArgumentException("maxWidth must be >= 4")
        if (text.length <= maxWidth) return text
        return text.take(maxWidth - 3) + "..."
    }

    /**
     * 将字符串按指定宽度换行。
     *
     * @param text 原始文本
     * @param width 每行最大宽度
     * @return 换行后的字符串
     */
    fun wrap(text: String, width: Int): String {
        if (width <= 0) throw IllegalArgumentException("width must be positive, got $width")
        if (text.length <= width) return text
        val words = text.split(" ")
        val result = StringBuilder()
        var lineLength = 0
        for (word in words) {
            if (lineLength + word.length > width) {
                if (result.isNotEmpty()) result.append("\n")
                result.append(word)
                lineLength = word.length
            } else {
                if (result.isNotEmpty() && lineLength > 0) result.append(" ")
                result.append(word)
                lineLength += word.length + 1
            }
        }
        return result.toString()
    }

    /**
     * 检查字符串是否同时包含大写和小写字母。
     *
     * @param text 输入文本
     * @return 如果是混合大小写返回 true
     */
    fun isMixedCase(text: String): Boolean {
        return hasUpperCase(text) && hasLowerCase(text)
    }

    /**
     * 检查字符串是否包含大写字母。
     *
     * @param text 输入文本
     * @return 如果包含大写字母返回 true
     */
    fun hasUpperCase(text: String): Boolean {
        return text.any { it.isUpperCase() }
    }

    /**
     * 检查字符串是否包含小写字母。
     *
     * @param text 输入文本
     * @return 如果包含小写字母返回 true
     */
    fun hasLowerCase(text: String): Boolean {
        return text.any { it.isLowerCase() }
    }

    /**
     * 查找两个字符串的最长公共前缀。
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 最长公共前缀
     */
    fun commonPrefixWith(a: String, b: String): String {
        val minLen = minOf(a.length, b.length)
        var i = 0
        while (i < minLen && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    /**
     * 查找两个字符串的最长公共后缀。
     *
     * @param a 第一个字符串
     * @param b 第二个字符串
     * @return 最长公共后缀
     */
    fun commonSuffixWith(a: String, b: String): String {
        var i = 0
        while (i < minOf(a.length, b.length) && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return a.substring(a.length - i)
    }

    /**
     * 规范化变音符号（将é转换为e + combining accent）。
     *
     * @param text 输入文本
     * @return 规范化后的字符串
     */
    fun normalizeDiacritics(text: String): String {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
    }

    /**
     * 移除字符串中的变音符号（如将 café 转换为 cafe）。
     *
     * @param text 输入文本
     * @return 移除变音符号后的字符串
     */
    fun removeDiacritics(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return Regex("[\\p{InCombiningDiacriticalMarks}]").replace(normalized, "")
    }

    /**
     * 提取两个标记之间的子字符串。
     *
     * @param text 原始文本
     * @param open 开始标记
     * @param close 结束标记
     * @return 提取的子字符串列表
     */
    fun substringBetween(text: String, open: String, close: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (true) {
            val openIndex = text.indexOf(open, start)
            if (openIndex < 0) break
            val closeIndex = text.indexOf(close, openIndex + open.length)
            if (closeIndex < 0) break
            result.add(text.substring(openIndex + open.length, closeIndex))
            start = closeIndex + close.length
        }
        return result
    }

    /**
     * 获取指定分隔符之前的子字符串。
     *
     * @param text 原始文本
     * @param delimiter 分隔符
     * @return 分隔符之前的子字符串，未找到分隔符返回原字符串
     */
    fun substringBefore(text: String, delimiter: String): String {
        val index = text.indexOf(delimiter)
        return if (index < 0) text else text.substring(0, index)
    }

    /**
     * 获取指定分隔符之后的子字符串。
     *
     * @param text 原始文本
     * @param delimiter 分隔符
     * @return 分隔符之后的子字符串，未找到分隔符返回原字符串
     */
    fun substringAfter(text: String, delimiter: String): String {
        val index = text.indexOf(delimiter)
        return if (index < 0) text else text.substring(index + delimiter.length)
    }

    /**
     * 检查字符串是否为 null 或空白。
     *
     * @param text 输入文本
     * @return 如果为 null 或空白返回 true
     */
    fun isBlank(text: String?): Boolean {
        return text.isNullOrBlank()
    }

    /**
     * 检查字符串是否不为 null 且不为空白。
     *
     * @param text 输入文本
     * @return 如果非 null 且非空白返回 true
     */
    fun isNotBlank(text: String?): Boolean {
        return !text.isNullOrBlank()
    }

    /**
     * 如果字符串为空白则返回默认值。
     *
     * @param text 输入文本
     * @param default 默认值
     * @return 原字符串或默认值
     */
    fun defaultIfBlank(text: String?, default: String): String {
        return if (text.isNullOrBlank()) default else text
    }

    /**
     * 完全大写每个单词的首字母，其余字母小写。
     *
     * @param text 输入文本
     * @return 格式化后的字符串
     */
    fun capitalizeFully(text: String): String {
        if (text.isBlank()) return text
        return text.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
        }
    }

    /**
     * 将字符串的第一个字母转换为小写。
     *
     * @param text 输入文本
     * @return 首字母小写后的字符串
     */
    fun uncapitalize(text: String): String {
        if (text.isEmpty()) return text
        return text[0].lowercaseChar() + text.substring(1)
    }

    /**
     * 交换字符串中每个字母的大小写。
     *
     * @param text 输入文本
     * @return 大小写交换后的字符串
     */
    fun swapCase(text: String): String {
        return toggleCase(text)
    }

    /**
     * 检查字符串是否包含任意给定的子串。
     *
     * @param text 原始文本
     * @param substrings 要搜索的子串列表
     * @return 如果包含任意子串返回 true
     */
    fun containsAny(text: String, vararg substrings: String): Boolean {
        return substrings.any { text.contains(it) }
    }

    /**
     * 检查字符串是否包含所有给定的子串。
     *
     * @param text 原始文本
     * @param substrings 要搜索的子串列表
     * @return 如果包含所有子串返回 true
     */
    fun containsAll(text: String, vararg substrings: String): Boolean {
        return substrings.all { text.contains(it) }
    }

    /**
     * 将字符串转换为适合用作资源键的格式（字母数字加下划线，小写）。
     *
     * @param text 输入文本
     * @return 资源键字符串
     */
    fun toResourceKey(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    /**
     * 将资源键或代码标识符转换为可读的显示名称。
     *
     * @param key 输入键（如 "user_name"、"camelCase"）
     * @return 显示名称（如 "User Name"）
     */
    fun toDisplayName(key: String): String {
        val withSpaces = key
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("[_-]"), " ")
        return withSpaces.split(" ")
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() }
            }
    }
}
