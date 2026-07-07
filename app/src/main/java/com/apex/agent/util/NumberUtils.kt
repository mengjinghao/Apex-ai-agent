package com.apex.util

import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 数字工具类，提供数值运算、格式化、范围处理等常用数学操作
 */
object NumberUtils {

    private val ROMAN_NUMERALS = arrayOf(
        "M" to 1000, "CM" to 900, "D" to 500, "CD" to 400,
        "C" to 100, "XC" to 90, "L" to 50, "XL" to 40,
        "X" to 10, "IX" to 9, "V" to 5, "IV" to 4, "I" to 1
    )

    private val ROMAN_MAP = mapOf(
        'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50,
        'C' to 100, 'D' to 500, 'M' to 1000
    )

    private val UNITS = arrayOf("", "K", "M", "B", "T", "Q")

    private val ORDINAL_SUFFIXES = arrayOf("th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th")

    /**
     * 将整数值限制在指定范围内
     *
     * @param value 原始值
     * @param min 最小值
     * @param max 最大值
     * @return 如果值小于最小值返回最小值，大于最大值返回最大值，否则返回原值
     */
    fun clamp(value: Int, min: Int, max: Int): Int {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    /**
     * 将长整数值限制在指定范围内
     *
     * @param value 原始值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun clamp(value: Long, min: Long, max: Long): Long {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    /**
     * 将浮点数值限制在指定范围内
     *
     * @param value 原始值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    /**
     * 将双精度浮点数值限制在指定范围内
     *
     * @param value 原始值
     * @param min 最小值
     * @param max 最大值
     * @return 限制后的值
     */
    fun clamp(value: Double, min: Double, max: Double): Double {
        require(min <= max) { "min ($min) must be <= max ($max)" }
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }

    /**
     * 线性插值计算，在起始值和结束值之间按比例插值
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 插值比例（0.0 表示起始值，1.0 表示结束值）
     * @return 插值结果
     */
    fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    /**
     * 双精度浮点数线性插值
     *
     * @param start 起始值
     * @param end 结束值
     * @param fraction 插值比例
     * @return 插值结果
     */
    fun lerp(start: Double, end: Double, fraction: Double): Double {
        return start + (end - start) * fraction
    }

    /**
     * 将浮点数四舍五入到指定小数位数
     *
     * @param value 原始浮点数
     * @param decimals 保留的小数位数
     * @return 四舍五入后的浮点数
     */
    fun roundTo(value: Float, decimals: Int): Float {
        val factor = 10.0f.pow(decimals)
        return kotlin.math.round(value * factor) / factor
    }

    /**
     * 将双精度浮点数四舍五入到指定小数位数
     *
     * @param value 原始双精度浮点数
     * @param decimals 保留的小数位数
     * @return 四舍五入后的双精度浮点数
     */
    fun roundTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return kotlin.math.round(value * factor) / factor
    }

    /**
     * 向下取整到指定小数位数。
     *
     * @param value 原始值
     * @param decimals 小数位数
     * @return 向下取整后的值
     */
    fun floorTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return floor(value * factor) / factor
    }

    /**
     * 向上取整到指定小数位数。
     *
     * @param value 原始值
     * @param decimals 小数位数
     * @return 向上取整后的值
     */
    fun ceilTo(value: Double, decimals: Int): Double {
        val factor = 10.0.pow(decimals)
        return ceil(value * factor) / factor
    }

    /**
     * 计算数值占总数的百分比
     *
     * @param value 部分值
     * @param total 总数（不能为 0）
     * @return 百分比值（0-100）
     */
    fun percentage(value: Float, total: Float): Float {
        if (total == 0f) return 0f
        return (value / total) * 100f
    }

    /**
     * 双精度浮点数百分比计算
     *
     * @param value 部分值
     * @param total 总数（不能为 0）
     * @return 百分比值（0-100）
     */
    fun percentage(value: Double, total: Double): Double {
        if (total == 0.0) return 0.0
        return (value / total) * 100.0
    }

    /**
     * 计算数值占总数的百分比。
     *
     * @param value 部分值
     * @param total 总数
     * @return 百分比值（0 ~ 100）
     */
    fun percentageOf(value: Number, total: Number): Double {
        return percentage(value.toDouble(), total.toDouble())
    }

    /**
     * 计算数值相对于前一数值的百分比变化。
     *
     * @param current 当前值
     * @param previous 前一值
     * @return 百分比变化（如 0.1 表示 10% 增长）
     */
    fun percentageChange(current: Number, previous: Number): Double {
        val prev = previous.toDouble()
        if (prev == 0.0) return if (current.toDouble() == 0.0) 0.0 else Double.NaN
        return (current.toDouble() - prev) / prev
    }

    /**
     * 判断整数是否为偶数
     *
     * @param value 待判断的整数
     * @return 如果是偶数返回 true
     */
    fun isEven(value: Int): Boolean {
        return value and 1 == 0
    }

    /**
     * 判断整数是否为奇数
     *
     * @param value 待判断的整数
     * @return 如果是奇数返回 true
     */
    fun isOdd(value: Int): Boolean {
        return value and 1 != 0
    }

    /**
     * 判断整数是否为质数
     *
     * @param value 待判断的正整数
     * @return 如果是质数返回 true
     */
    fun isPrime(value: Int): Boolean {
        if (value < 2) return false
        if (value == 2) return true
        if (value and 1 == 0) return false
        val limit = sqrt(value.toDouble()).toInt()
        for (i in 3..limit step 2) {
            if (value % i == 0) return false
        }
        return true
    }

    /**
     * 查找大于或等于 n 的最小质数。
     *
     * @param n 起始值
     * @return 下一个质数
     */
    fun nextPrime(n: Int): Int {
        var candidate = maxOf(n, 2)
        while (!isPrime(candidate)) candidate++
        return candidate
    }

    /**
     * 查找小于或等于 n 的最大质数。
     *
     * @param n 起始值
     * @return 上一个质数，不存在返回 null
     */
    fun previousPrime(n: Int): Int? {
        var candidate = n
        while (candidate >= 2) {
            if (isPrime(candidate)) return candidate
            candidate--
        }
        return null
    }

    /**
     * 计算 n 的质因数分解。
     *
     * @param n 正整数
     * @return 质因数列表
     */
    fun primeFactors(n: Int): List<Int> {
        val factors = mutableListOf<Int>()
        var remaining = n
        var divisor = 2
        while (remaining > 1 && divisor * divisor <= remaining) {
            while (remaining % divisor == 0) {
                factors.add(divisor)
                remaining /= divisor
            }
            divisor++
        }
        if (remaining > 1) factors.add(remaining)
        return factors
    }

    /**
     * 计算两个整数的最大公约数（辗转相除法）
     *
     * @param a 第一个整数
     * @param b 第二个整数
     * @return 最大公约数
     */
    fun gcd(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val temp = y
            y = x % y
            x = temp
        }
        return x
    }

    /**
     * 计算两个整数的最大公约数（Long 版本）。
     *
     * @param a 第一个整数
     * @param b 第二个整数
     * @return 最大公约数
     */
    fun gcd(a: Long, b: Long): Long {
        var x = abs(a)
        var y = abs(b)
        while (y != 0L) {
            val temp = y
            y = x % y
            x = temp
        }
        return x
    }

    /**
     * 计算整数列表的最大公约数。
     *
     * @param numbers 整数列表
     * @return 最大公约数
     */
    fun gcd(numbers: List<Int>): Int {
        if (numbers.isEmpty()) return 0
        return numbers.reduce { acc, n -> gcd(acc, n) }
    }

    /**
     * 计算整数列表的最小公倍数。
     *
     * @param numbers 整数列表
     * @return 最小公倍数
     */
    fun lcm(numbers: List<Int>): Int {
        if (numbers.isEmpty()) return 0
        return numbers.reduce { acc, n -> lcm(acc, n) }
    }

    /**
     * 计算两个整数的最小公倍数
     *
     * @param a 第一个整数
     * @param b 第二个整数
     * @return 最小公倍数
     */
    fun lcm(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return abs(a) / gcd(a, b) * abs(b)
    }

    /**
     * 计算 n 的阶乘
     *
     * @param n 非负整数
     * @return 阶乘结果
     * @throws IllegalArgumentException 如果 n 为负数
     */
    fun factorial(n: Int): Long {
        require(n >= 0) { "n must be non-negative, got $n" }
        if (n == 0 || n == 1) return 1L
        var result = 1L
        for (i in 2..n) {
            result *= i
        }
        return result
    }

    /**
     * 计算二项式系数 C(n, k)。
     *
     * @param n 总数
     * @param k 选取数
     * @return 组合数
     */
    fun binomial(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        if (k == 0 || k == n) return 1L
        val kk = minOf(k, n - k)
        var result = 1L
        for (i in 1..kk) {
            result = result * (n - kk + i) / i
        }
        return result
    }

    /**
     * 计算排列数 P(n, k)。
     *
     * @param n 总数
     * @param k 选取数
     * @return 排列数
     */
    fun permutations(n: Int, k: Int): Long {
        if (k < 0 || k > n) return 0L
        var result = 1L
        for (i in n downTo n - k + 1) {
            result *= i
        }
        return result
    }

    /**
     * 计算斐波那契数列的第 n 项
     *
     * @param n 非负整数（第几项）
     * @return 斐波那契数
     * @throws IllegalArgumentException 如果 n 为负数
     */
    fun fibonacci(n: Int): Long {
        require(n >= 0) { "n must be non-negative, got $n" }
        if (n == 0) return 0L
        if (n == 1) return 1L
        var a = 0L
        var b = 1L
        for (i in 2..n) {
            val temp = a + b
            a = b
            b = temp
        }
        return b
    }

    /**
     * 生成斐波那契数列的前 n 项。
     *
     * @param n 项数
     * @return 斐波那契数列列表
     */
    fun fibonacciSequence(n: Int): List<Long> {
        if (n <= 0) return emptyList()
        val result = mutableListOf<Long>()
        for (i in 0 until n) {
            result.add(fibonacci(i))
        }
        return result
    }

    /**
     * 将一个数值从原范围映射到目标范围
     *
     * @param value 待映射的值
     * @param fromMin 原范围最小值
     * @param fromMax 原范围最大值
     * @param toMin 目标范围最小值
     * @param toMax 目标范围最大值
     * @return 映射后的值
     */
    fun mapRange(value: Float, fromMin: Float, fromMax: Float, toMin: Float, toMax: Float): Float {
        val fromRange = fromMax - fromMin
        if (fromRange == 0f) return toMin
        val ratio = (value - fromMin) / fromRange
        return toMin + ratio * (toMax - toMin)
    }

    /**
     * 将数值映射到 0~1 范围。
     *
     * @param value 原始值
     * @param min 范围最小值
     * @param max 范围最大值
     * @return 0~1 之间的映射值
     */
    fun normalize(value: Double, min: Double, max: Double): Double {
        if (max == min) return 0.0
        return (value - min) / (max - min)
    }

    /**
     * 将字节数格式化为可读的文件大小字符串
     *
     * @param bytes 字节数
     * @return 格式化后的大小字符串，如 "1.5 KB", "2.3 MB"
     */
    fun formatBytes(bytes: Long): String {
        return formatBytes(bytes, si = false)
    }

    /**
     * 将字节数格式化为可读的文件大小字符串，可选择 SI（1000）或二进制（1024）单位
     *
     * @param bytes 字节数
     * @param si 如果为 true 使用 SI 单位（1000），否则使用二进制单位（1024）
     * @return 格式化后的大小字符串
     */
    fun formatBytes(bytes: Long, si: Boolean): String {
        val unit = if (si) 1000L else 1024L
        if (bytes < unit) return "$bytes B"
        val units = if (si) arrayOf("KB", "MB", "GB", "TB", "PB", "EB") else arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= unit && unitIndex < units.size - 1) {
            value /= unit
            unitIndex++
        }
        val df = DecimalFormat("#.##")
        return "${df.format(value)} ${units[unitIndex]}"
    }

    /**
     * 格式化数字，添加千位分隔符
     *
     * @param number 待格式化的数字
     * @param decimals 小数位数，默认为 0
     * @return 格式化后的字符串
     */
    fun formatNumber(number: Number, decimals: Int = 0): String {
        val nf = NumberFormat.getInstance(Locale.getDefault())
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        return nf.format(number)
    }

    /**
     * 安全地将字符串解析为整数，解析失败返回默认值
     *
     * @param value 待解析的字符串
     * @param default 解析失败时返回的默认值，默认为 0
     * @return 解析后的整数值
     */
    fun parseIntSafe(value: String?, default: Int = 0): Int {
        if (value == null) return default
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            default
        }
    }

    /**
     * 安全地将字符串解析为长整数，解析失败返回默认值
     *
     * @param value 待解析的字符串
     * @param default 解析失败时返回的默认值，默认为 0L
     * @return 解析后的长整数值
     */
    fun parseLongSafe(value: String?, default: Long = 0L): Long {
        if (value == null) return default
        return try {
            value.toLong()
        } catch (e: NumberFormatException) {
            default
        }
    }

    /**
     * 安全地将字符串解析为浮点数，解析失败返回默认值
     *
     * @param value 待解析的字符串
     * @param default 解析失败时返回的默认值，默认为 0f
     * @return 解析后的浮点数值
     */
    fun parseFloatSafe(value: String?, default: Float = 0f): Float {
        if (value == null) return default
        return try {
            value.toFloat()
        } catch (e: NumberFormatException) {
            default
        }
    }

    /**
     * 安全地将字符串解析为双精度浮点数，解析失败返回默认值
     *
     * @param value 待解析的字符串
     * @param default 解析失败时返回的默认值，默认为 0.0
     * @return 解析后的双精度浮点数值
     */
    fun parseDoubleSafe(value: String?, default: Double = 0.0): Double {
        if (value == null) return default
        return try {
            value.toDouble()
        } catch (e: NumberFormatException) {
            default
        }
    }

    /**
     * 判断整数是否在指定范围内（包含边界）
     *
     * @param value 待判断的整数值
     * @param min 范围最小值
     * @param max 范围最大值
     * @return 如果在范围内返回 true
     */
    fun isInRange(value: Int, min: Int, max: Int): Boolean {
        return value in min..max
    }

    /**
     * 判断浮点数是否在指定范围内（包含边界）
     *
     * @param value 待判断的浮点数值
     * @param min 范围最小值
     * @param max 范围最大值
     * @return 如果在范围内返回 true
     */
    fun isInRange(value: Float, min: Float, max: Float): Boolean {
        return value >= min && value <= max
    }

    /**
     * 将数值循环到指定范围内（常用于角度、索引等场景）
     * 例如 wrap(365, 0, 360) 返回 5
     *
     * @param value 原始值
     * @param min 范围最小值（包含）
     * @param max 范围最大值（包含）
     * @return 循环后的值
     */
    fun wrap(value: Int, min: Int, max: Int): Int {
        val range = max - min + 1
        if (range == 0) return min
        var result = (value - min) % range
        if (result < 0) result += range
        return result + min
    }

    /**
     * 将整数转换为罗马数字。
     *
     * @param n 1~3999 之间的整数
     * @return 罗马数字字符串
     */
    fun toRomanNumerals(n: Int): String {
        require(n in 1..3999) { "n must be between 1 and 3999, got $n" }
        var remaining = n
        val result = StringBuilder()
        for ((numeral, value) in ROMAN_NUMERALS) {
            while (remaining >= value) {
                result.append(numeral)
                remaining -= value
            }
        }
        return result.toString()
    }

    /**
     * 将罗马数字解析为整数。
     *
     * @param s 罗马数字字符串
     * @return 整数值
     */
    fun fromRomanNumerals(s: String): Int {
        var result = 0
        var prev = 0
        for (ch in s.reversed()) {
            val value = ROMAN_MAP[ch] ?: throw IllegalArgumentException("Invalid Roman numeral: $ch")
            if (value < prev) result -= value else result += value
            prev = value
        }
        return result
    }

    /**
     * 将整数转换为中文数字（小写）。
     *
     * @param n 非负整数
     * @return 中文数字字符串
     */
    fun toChineseWords(n: Int): String {
        if (n == 0) return "\u96F6"
        val digits = arrayOf("\u96F6", "\u4E00", "\u4E8C", "\u4E09", "\u56DB", "\u4E94", "\u516D", "\u4E03", "\u516B", "\u4E5D")
        val units = arrayOf("", "\u5341", "\u767E", "\u5343", "\u4E07", "\u4EBF")
        var num = n
        val result = StringBuilder()
        var unitIndex = 0
        var needZero = false
        while (num > 0) {
            val digit = num % 10
            if (digit == 0) {
                needZero = true
            } else {
                if (needZero) {
                    result.insert(0, digits[0])
                    needZero = false
                }
                result.insert(0, units[unitIndex])
                result.insert(0, digits[digit])
            }
            num /= 10
            unitIndex++
        }
        return result.toString()
    }

    /**
     * 将数字转换为英文单词。
     *
     * @param n 非负整数
     * @return 英文单词字符串
     */
    fun toEnglishWords(n: Int): String {
        if (n == 0) return "zero"
        val ones = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val scales = arrayOf("", "thousand", "million", "billion", "trillion")

        fun convertHundreds(num: Int): String {
            val result = StringBuilder()
            val h = num / 100
            val remainder = num % 100
            if (h > 0) result.append("${ones[h]} hundred ")
            if (remainder > 0) {
                if (remainder < 20) result.append(ones[remainder])
                else result.append("${tens[remainder / 10]} ${ones[remainder % 10]}")
            }
            return result.toString().trim()
        }

        var num = n
        val result = mutableListOf<String>()
        var scaleIndex = 0
        while (num > 0) {
            val chunk = num % 1000
            if (chunk > 0) {
                val chunkStr = convertHundreds(chunk)
                val scale = scales[scaleIndex]
                result.add(if (scale.isNotEmpty()) "$chunkStr $scale" else chunkStr)
            }
            num /= 1000
            scaleIndex++
        }
        return result.reversed().joinToString(" ")
    }

    /**
     * 将整数转换为序数词（1st, 2nd, 3rd 等）。
     *
     * @param n 正整数
     * @return 序数字符串
     */
    fun toOrdinal(n: Int): String {
        val mod100 = n % 100
        val suffix = when {
            mod100 in 11..13 -> "th"
            else -> ORDINAL_SUFFIXES[n % 10]
        }
        return "$n$suffix"
    }

    /**
     * 格式化持续时间（秒数转换为可读格式）。
     *
     * @param seconds 总秒数
     * @return 格式化字符串，如 "2h 15m 30s"
     */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val parts = mutableListOf<String>()
        var remaining = seconds
        val days = remaining / 86400
        if (days > 0) { parts.add("${days}d"); remaining %= 86400 }
        val hours = remaining / 3600
        if (hours > 0) { parts.add("${hours}h"); remaining %= 3600 }
        val mins = remaining / 60
        if (mins > 0) { parts.add("${mins}m"); remaining %= 60 }
        if (remaining > 0 || parts.isEmpty()) parts.add("${remaining}s")
        return parts.joinToString(" ")
    }

    /**
     * 将数字转换为工程计数法（如 1234 -> 1.234K）。
     *
     * @param value 原始值
     * @return 工程计数法字符串
     */
    fun toEngineering(value: Double): String {
        if (value == 0.0) return "0"
        val prefix = if (value < 0) "-" else ""
        val absVal = abs(value)
        val exp = floor(log10(absVal)).toInt()
        val unitIndex = (exp / 3).coerceIn(0, UNITS.size - 1)
        val scaled = absVal / 10.0.pow(unitIndex * 3)
        val df = DecimalFormat("#.###")
        return "$prefix${df.format(scaled)}${UNITS[unitIndex]}"
    }

    /**
     * 判断整数是否为完全平方数。
     *
     * @param n 待判断的整数
     * @return 如果是完全平方数返回 true
     */
    fun isPerfectSquare(n: Int): Boolean {
        if (n < 0) return false
        val sqrt = sqrt(n.toDouble()).roundToLong()
        return sqrt * sqrt == n.toLong()
    }

    /**
     * 计算数字根（各位数字之和，直到结果为一位数）。
     *
     * @param n 非负整数
     * @return 数字根（0~9）
     */
    fun digitalRoot(n: Int): Int {
        if (n == 0) return 0
        return 1 + (n - 1) % 9
    }

    /**
     * 计算各位数字之和。
     *
     * @param n 非负整数
     * @return 各位数字之和
     */
    fun digitSum(n: Int): Int {
        var remaining = abs(n)
        var sum = 0
        while (remaining > 0) {
            sum += remaining % 10
            remaining /= 10
        }
        return sum
    }

    /**
     * 计算数字的位数（指定进制）。
     *
     * @param n 非负整数
     * @param base 进制，默认 10
     * @return 位数
     */
    fun digitCount(n: Int, base: Int = 10): Int {
        if (n == 0) return 1
        return (floor(log10(n.toDouble()) / log10(base.toDouble())) + 1).toInt()
    }

    /**
     * 判断一个值是否在指定范围内（包含边界）。
     *
     * @param value 待判断的值
     * @param min 范围最小值
     * @param max 范围最大值
     * @return 如果在范围内返回 true
     */
    fun isWithin(value: Number, min: Number, max: Number): Boolean {
        return value.toDouble() >= min.toDouble() && value.toDouble() <= max.toDouble()
    }

    /**
     * 判断一个值是否在 min 和 max 之间（包含边界）。
     *
     * @param value 待判断的值
     * @param min 最小值
     * @param max 最大值
     * @return 如果在之间返回 true
     */
    fun isBetween(value: Number, min: Number, max: Number): Boolean {
        return isWithin(value, min, max)
    }

    /**
     * 计算大于或等于 n 的最小 2 的幂。
     *
     * @param n 正整数
     * @return 2 的幂
     */
    fun nextPowerOfTwo(n: Int): Int {
        if (n <= 0) return 1
        var power = 1
        while (power < n) power = power shl 1
        return power
    }

    /**
     * 计算小于或等于 n 的最大 2 的幂。
     *
     * @param n 正整数
     * @return 2 的幂
     */
    fun previousPowerOfTwo(n: Int): Int {
        if (n <= 0) return 0
        var power = 1
        while (power <= n shr 1) power = power shl 1
        return power
    }

    /**
     * 判断整数是否为 2 的幂。
     *
     * @param n 正整数
     * @return 如果是 2 的幂返回 true
     */
    fun isPowerOfTwo(n: Int): Boolean {
        return n > 0 && (n and (n - 1)) == 0
    }

    /**
     * 将整数转换为指定进制的字符串。
     *
     * @param n 整数
     * @param base 进制（2~36）
     * @return 进制字符串
     */
    fun toBase(n: Int, base: Int): String {
        if (base < 2 || base > 36) throw IllegalArgumentException("base must be 2-36, got $base")
        if (n == 0) return "0"
        return Integer.toString(n, base)
    }

    /**
     * 将指定进制的字符串解析为整数。
     *
     * @param s 进制字符串
     * @param base 进制（2~36）
     * @return 整数值
     */
    fun fromBase(s: String, base: Int): Int {
        if (base < 2 || base > 36) throw IllegalArgumentException("base must be 2-36, got $base")
        return Integer.parseInt(s, base)
    }

    /**
     * 计算数值列表的算术平均值。
     *
     * @param numbers 数值列表
     * @return 平均值
     */
    fun mean(numbers: List<Number>): Double {
        require(numbers.isNotEmpty()) { "list must not be empty" }
        return numbers.sumOf { it.toDouble() } / numbers.size
    }

    /**
     * 计算数值列表的方差。
     *
     * @param numbers 数值列表
     * @return 方差
     */
    fun variance(numbers: List<Number>): Double {
        require(numbers.isNotEmpty()) { "list must not be empty" }
        val m = mean(numbers)
        return numbers.sumOf { (it.toDouble() - m).pow(2) } / numbers.size
    }

    /**
     * 计算数值列表的标准差。
     *
     * @param numbers 数值列表
     * @return 标准差
     */
    fun stddev(numbers: List<Number>): Double {
        return sqrt(variance(numbers))
    }

    /**
     * 计算各位数字的乘积。
     *
     * @param n 非负整数
     * @return 各位数字的乘积
     */
    fun productOfDigits(n: Int): Int {
        var remaining = abs(n)
        var product = 1
        while (remaining > 0) {
            product *= remaining % 10
            remaining /= 10
        }
        return product
    }

    /**
     * 循环左移位。
     *
     * @param value 整数值
     * @param n 移位位数
     * @return 移位后的值
     */
    fun rotateLeft(value: Int, n: Int): Int {
        val bits = n % 32
        return (value shl bits) or (value ushr (32 - bits))
    }

    /**
     * 循环右移位。
     *
     * @param value 整数值
     * @param n 移位位数
     * @return 移位后的值
     */
    fun rotateRight(value: Int, n: Int): Int {
        val bits = n % 32
        return (value ushr bits) or (value shl (32 - bits))
    }

    /**
     * 判断 Double 是否为有限值。
     */
    fun isFinite(value: Double): Boolean = value.isFinite()

    /**
     * 判断 Double 是否为无限值。
     */
    fun isInfinite(value: Double): Boolean = value.isInfinite()

    /**
     * 判断 Double 是否为 NaN。
     */
    fun isNaN(value: Double): Boolean = value.isNaN()

    /**
     * 判断 Float 是否为有限值。
     */
    fun isFinite(value: Float): Boolean = value.isFinite()

    /**
     * 判断 Float 是否为无限值。
     */
    fun isInfinite(value: Float): Boolean = value.isInfinite()

    /**
     * 判断 Float 是否为 NaN。
     */
    fun isNaN(value: Float): Boolean = value.isNaN()

    /**
     * 将数字转换为带符号的字符串（正数前加 +）。
     *
     * @param value 数值
     * @return 带符号的字符串
     */
    fun toSignedString(value: Number): String {
        val v = value.toDouble()
        return if (v > 0) "+$v" else v.toString()
    }

    /**
     * 在指定误差范围内比较两个浮点数。
     *
     * @param a 第一个值
     * @param b 第二个值
     * @param epsilon 误差范围
     * @return 如果在误差范围内相等返回 true
     */
    fun compareToTolerance(a: Double, b: Double, epsilon: Double = 1e-10): Boolean {
        return abs(a - b) <= epsilon
    }

    /**
     * 在指定误差范围内比较两个 Float。
     *
     * @param a 第一个值
     * @param b 第二个值
     * @param epsilon 误差范围
     * @return 如果在误差范围内相等返回 true
     */
    fun compareToTolerance(a: Float, b: Float, epsilon: Float = 1e-6f): Boolean {
        return abs(a - b) <= epsilon
    }
}
