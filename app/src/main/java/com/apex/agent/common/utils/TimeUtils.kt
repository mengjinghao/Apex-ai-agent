package com.apex.agent.common.utils

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone

/**
 * 时间工具类，提供常见的时间格式化、解析、计算和相对时间显示功能。
 * 基于 java.time.* 实现（需启用 Android Gradle 插件的 desugaring 支持）。
 */
object TimeUtils {

    private const val DEFAULT_PATTERN = "yyyy-MM-dd HH:mm:ss"
    private val defaultZoneId: ZoneId get() = ZoneId.systemDefault()

    /**
     * 获取当前时间戳（毫秒）。
     *
     * @return 当前时间的毫秒数
     */
    fun currentTimestamp(): Long = System.currentTimeMillis()

    /**
     * 获取当前时间戳的别名方法。
     *
     * @return 当前时间的毫秒数
     */
    fun currentTimeMillis(): Long = System.currentTimeMillis()

    /**
     * 将时间戳按指定格式格式化为日期时间字符串。
     *
     * @param millis  时间戳（毫秒）
     * @param pattern 日期时间格式，默认 "yyyy-MM-dd HH:mm:ss"
     * @return 格式化后的日期时间字符串
     */
    fun formatTimestamp(millis: Long, pattern: String = DEFAULT_PATTERN): String {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), defaultZoneId).format(formatter)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 将时间戳按指定格式和时区格式化为日期时间字符串。
     *
     * @param millis   时间戳（毫秒）
     * @param pattern  日期时间格式
     * @param timeZone 目标时区
     * @return 格式化后的日期时间字符串
     */
    fun formatTimestamp(millis: Long, pattern: String, timeZone: TimeZone): String {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            val zoneId = timeZone.toZoneId()
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zoneId).format(formatter)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * 将日期时间字符串按指定格式解析为时间戳。
     *
     * @param dateString 日期时间字符串
     * @param pattern    日期时间格式，默认 "yyyy-MM-dd HH:mm:ss"
     * @return 解析成功返回时间戳（毫秒），失败返回 null
     */
    fun parseTimestamp(dateString: String, pattern: String = DEFAULT_PATTERN): Long? {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
            val localDateTime = LocalDateTime.parse(dateString, formatter)
            localDateTime.atZone(defaultZoneId).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 将时间戳转换为 [LocalDate]。
     *
     * @param millis 时间戳（毫秒）
     * @return 对应的 LocalDate
     */
    fun toLocalDate(millis: Long): LocalDate {
        return Instant.ofEpochMilli(millis).atZone(defaultZoneId).toLocalDate()
    }

    /**
     * 将时间戳转换为 [LocalDateTime]。
     *
     * @param millis 时间戳（毫秒）
     * @return 对应的 LocalDateTime
     */
    fun toLocalDateTime(millis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), defaultZoneId)
    }

    /**
     * 计算两个时间戳之间相差的天数（按自然日计算）。
     *
     * @param startMillis 起始时间戳
     * @param endMillis   结束时间戳
     * @return 相差的天数（绝对值）
     */
    fun daysBetween(startMillis: Long, endMillis: Long): Int {
        return ChronoUnit.DAYS.between(
            toLocalDate(startMillis),
            toLocalDate(endMillis)
        ).toInt().let { if (it < 0) -it else it }
    }

    /**
     * 计算两个时间戳之间相差的小时数。
     *
     * @param startMillis 起始时间戳
     * @param endMillis   结束时间戳
     * @return 相差的小时数（绝对值）
     */
    fun hoursBetween(startMillis: Long, endMillis: Long): Int {
        return ChronoUnit.HOURS.between(
            Instant.ofEpochMilli(startMillis),
            Instant.ofEpochMilli(endMillis)
        ).toInt().let { if (it < 0) -it else it }
    }

    /**
     * 计算两个时间戳之间相差的分钟数。
     *
     * @param startMillis 起始时间戳
     * @param endMillis   结束时间戳
     * @return 相差的分钟数（绝对值）
     */
    fun minutesBetween(startMillis: Long, endMillis: Long): Int {
        return ChronoUnit.MINUTES.between(
            Instant.ofEpochMilli(startMillis),
            Instant.ofEpochMilli(endMillis)
        ).toInt().let { if (it < 0) -it else it }
    }

    /**
     * 判断两个时间戳是否在同一天。
     *
     * @param millis1 第一个时间戳
     * @param millis2 第二个时间戳
     * @return 如果在同一天返回 true，否则返回 false
     */
    fun isSameDay(millis1: Long, millis2: Long): Boolean {
        return toLocalDate(millis1) == toLocalDate(millis2)
    }

    /**
     * 判断给定时间戳是否为今天。
     *
     * @param millis 时间戳
     * @return 如果是今天返回 true，否则返回 false
     */
    fun isToday(millis: Long): Boolean {
        return toLocalDate(millis) == LocalDate.now(defaultZoneId)
    }

    /**
     * 判断给定时间戳是否为昨天。
     *
     * @param millis 时间戳
     * @return 如果是昨天返回 true，否则返回 false
     */
    fun isYesterday(millis: Long): Boolean {
        return toLocalDate(millis) == LocalDate.now(defaultZoneId).minusDays(1)
    }

    /**
     * 将时间戳转换为相对时间描述（中文），例如"刚刚"、"3分钟前"、"2小时前"、"昨天"、"3天前"等。
     *
     * @param millis 时间戳
     * @return 中文相对时间描述
     */
    fun toRelativeTime(millis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - millis

        if (diff < 0) return "刚刚"

        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days == 1L -> "昨天"
            days < 7 -> "${days}天前"
            days < 30 -> "${days / 7}周前"
            days < 365 -> "${days / 30}个月前"
            else -> "${days / 365}年前"
        }
    }

    /**
     * 获取指定时间戳所在日期的开始时间（00:00:00.000）。
     *
     * @param millis 时间戳
     * @return 当天开始的时间戳（毫秒）
     */
    fun getStartOfDay(millis: Long): Long {
        return toLocalDate(millis).atStartOfDay(defaultZoneId).toInstant().toEpochMilli()
    }

    /**
     * 获取指定时间戳所在日期的结束时间（23:59:59.999）。
     *
     * @param millis 时间戳
     * @return 当天结束的时间戳（毫秒）
     */
    fun getEndOfDay(millis: Long): Long {
        return toLocalDate(millis).atTime(23, 59, 59, 999_000_000)
            .atZone(defaultZoneId).toInstant().toEpochMilli()
    }

    /**
     * 获取当前小时数（0-23）。
     *
     * @return 当前小时数
     */
    fun getCurrentHour(): Int {
        return LocalDateTime.now(defaultZoneId).hour
    }

    /**
     * 判断当前时间是否为夜间（18:00 之后视为夜间）。
     *
     * @return 如果是夜间返回 true，否则返回 false
     */
    fun isNight(): Boolean {
        return getCurrentHour() >= 18
    }
}
