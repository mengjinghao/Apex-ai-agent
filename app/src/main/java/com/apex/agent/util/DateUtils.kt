package com.apex.util

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.MonthDay
import java.time.Year
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * 日期时间工具类，提供常用日期时间操作和格式化方法
 */
object DateUtils {

    /**
     * 将 LocalDate 格式化为指定模式的字符串
     *
     * @param date 日期对象
     * @param pattern 日期格式模式，默认为 "yyyy-MM-dd"
     * @return 格式化后的日期字符串
     */
    fun formatDate(date: LocalDate, pattern: String = "yyyy-MM-dd"): String {
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return date.format(formatter)
    }

    /**
     * 将 LocalDateTime 格式化为指定模式的字符串
     *
     * @param dateTime 日期时间对象
     * @param pattern 日期时间格式模式，默认为 "yyyy-MM-dd HH:mm:ss"
     * @return 格式化后的日期时间字符串
     */
    fun formatDateTime(dateTime: LocalDateTime, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val formatter = DateTimeFormatter.ofPattern(pattern)
        return dateTime.format(formatter)
    }

    /**
     * 将字符串解析为 LocalDate
     *
     * @param dateString 日期字符串
     * @param pattern 日期格式模式，默认为 "yyyy-MM-dd"
     * @return 解析成功返回 LocalDate，失败返回 null
     */
    fun parseDate(dateString: String, pattern: String = "yyyy-MM-dd"): LocalDate? {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern)
            LocalDate.parse(dateString, formatter)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将字符串解析为 LocalDateTime
     *
     * @param dateTimeString 日期时间字符串
     * @param pattern 日期时间格式模式，默认为 "yyyy-MM-dd HH:mm:ss"
     * @return 解析成功返回 LocalDateTime，失败返回 null
     */
    fun parseDateTime(dateTimeString: String, pattern: String = "yyyy-MM-dd HH:mm:ss"): LocalDateTime? {
        return try {
            val formatter = DateTimeFormatter.ofPattern(pattern)
            LocalDateTime.parse(dateTimeString, formatter)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取当前日期时间
     *
     * @return 当前 LocalDateTime
     */
    fun currentDateTime(): LocalDateTime {
        return LocalDateTime.now()
    }

    /**
     * 获取当前日期
     *
     * @return 当前 LocalDate
     */
    fun currentDate(): LocalDate {
        return LocalDate.now()
    }

    /**
     * 计算两个日期之间相差的天数
     *
     * @param start 开始日期
     * @param end 结束日期
     * @return 相差天数
     */
    fun daysBetween(start: LocalDate, end: LocalDate): Long {
        return ChronoUnit.DAYS.between(start, end)
    }

    /**
     * 计算两个日期之间相差的月数。
     *
     * @param start 开始日期
     * @param end 结束日期
     * @return 相差月数
     */
    fun monthsBetween(start: LocalDate, end: LocalDate): Long {
        return ChronoUnit.MONTHS.between(start, end)
    }

    /**
     * 计算两个日期之间相差的年数。
     *
     * @param start 开始日期
     * @param end 结束日期
     * @return 相差年数
     */
    fun yearsBetween(start: LocalDate, end: LocalDate): Long {
        return ChronoUnit.YEARS.between(start, end)
    }

    /**
     * 计算两个日期时间之间相差的小时数
     *
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 相差小时数
     */
    fun hoursBetween(start: LocalDateTime, end: LocalDateTime): Long {
        return ChronoUnit.HOURS.between(start, end)
    }

    /**
     * 计算两个日期时间之间相差的分钟数。
     *
     * @param start 开始日期时间
     * @param end 结束日期时间
     * @return 相差分钟数
     */
    fun minutesBetween(start: LocalDateTime, end: LocalDateTime): Long {
        return ChronoUnit.MINUTES.between(start, end)
    }

    /**
     * 判断指定年份是否为闰年
     *
     * @param year 年份
     * @return 如果是闰年则返回 true
     */
    fun isLeapYear(year: Int): Boolean {
        return Year.isLeap(year.toLong())
    }

    /**
     * 根据出生日期计算年龄
     *
     * @param birthDate 出生日期
     * @return 年龄（周岁）
     */
    fun getAge(birthDate: LocalDate): Int {
        val today = LocalDate.now()
        var age = today.year - birthDate.year
        if (today.dayOfYear < birthDate.dayOfYear) {
            age--
        }
        return age
    }

    /**
     * 根据出生日期计算年龄（年数）。
     *
     * @param birthDate 出生日期
     * @return 年龄
     */
    fun age(birthDate: LocalDate): Int {
        return getAge(birthDate)
    }

    /**
     * 判断指定日期是否为周末（星期六或星期日）
     *
     * @param date 日期
     * @return 如果是周末则返回 true
     */
    fun isWeekend(date: LocalDate): Boolean {
        val dayOfWeek = date.dayOfWeek
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
    }

    /**
     * 判断指定日期是否为工作日。
     *
     * @param date 日期
     * @return 如果是工作日返回 true
     */
    fun isWeekday(date: LocalDate): Boolean {
        return !isWeekend(date)
    }

    /**
     * 获取指定日期所在月的第一天
     *
     * @param date 日期
     * @return 当月第一天的日期
     */
    fun getFirstDayOfMonth(date: LocalDate): LocalDate {
        return date.withDayOfMonth(1)
    }

    /**
     * 获取指定日期所在月的最后一天
     *
     * @param date 日期
     * @return 当月最后一天的日期
     */
    fun getLastDayOfMonth(date: LocalDate): LocalDate {
        return date.withDayOfMonth(date.lengthOfMonth())
    }

    /**
     * 获取指定日期所在的季度
     *
     * @param date 日期
     * @return 季度值（1-4）
     */
    fun getQuarter(date: LocalDate): Int {
        return when (date.month) {
            Month.JANUARY, Month.FEBRUARY, Month.MARCH -> 1
            Month.APRIL, Month.MAY, Month.JUNE -> 2
            Month.JULY, Month.AUGUST, Month.SEPTEMBER -> 3
            Month.OCTOBER, Month.NOVEMBER, Month.DECEMBER -> 4
        }
    }

    /**
     * 将日期时间转换为相对时间描述（中文）
     *
     * @param dateTime 日期时间
     * @return 相对时间描述字符串，如 "刚刚"、"3分钟前"、"2小时前"、"昨天" 等
     */
    fun toRelativeTime(dateTime: LocalDateTime): String {
        val now = LocalDateTime.now()
        val duration = Duration.between(dateTime, now)

        if (duration.isNegative) return "刚刚"

        val seconds = duration.seconds
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days == 1L -> "昨天"
            days == 2L -> "前天"
            days < 7 -> "${days}天前"
            days < 30 -> "${days / 7}周前"
            days < 365 -> "${days / 30}个月前"
            else -> "${days / 365}年前"
        }
    }

    /**
     * 将时间戳格式化为日期时间字符串
     *
     * @param millis 毫秒时间戳
     * @param pattern 日期时间格式模式，默认为 "yyyy-MM-dd HH:mm:ss"
     * @return 格式化后的日期时间字符串
     */
    fun formatTimestamp(millis: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
        val dateTime = LocalDateTime.ofEpochSecond(millis / 1000, 0, java.time.ZoneOffset.ofHours(8))
        return formatDateTime(dateTime, pattern)
    }

    /**
     * 判断指定日期是否为今天
     *
     * @param date 日期
     * @return 如果是今天则返回 true
     */
    fun isToday(date: LocalDate): Boolean {
        return date == LocalDate.now()
    }

    /**
     * 判断指定日期是否为昨天。
     *
     * @param date 日期
     * @return 如果是昨天返回 true
     */
    fun isYesterday(date: LocalDate): Boolean {
        return date == LocalDate.now().minusDays(1)
    }

    /**
     * 判断指定日期是否为明天。
     *
     * @param date 日期
     * @return 如果是明天返回 true
     */
    fun isTomorrow(date: LocalDate): Boolean {
        return date == LocalDate.now().plusDays(1)
    }

    /**
     * 获取指定日期时间当天的开始时间（00:00:00.000）
     *
     * @param dateTime 日期时间
     * @return 当天开始时间
     */
    fun getStartOfDay(dateTime: LocalDateTime): LocalDateTime {
        return dateTime.toLocalDate().atStartOfDay()
    }

    /**
     * 获取指定日期时间当天的开始时间。
     *
     * @param date 日期
     * @return 当天开始时间
     */
    fun startOfDay(date: LocalDate): LocalDateTime {
        return date.atStartOfDay()
    }

    /**
     * 获取指定日期时间当天的结束时间（23:59:59.999999999）
     *
     * @param dateTime 日期时间
     * @return 当天结束时间
     */
    fun getEndOfDay(dateTime: LocalDateTime): LocalDateTime {
        return dateTime.toLocalDate().atTime(LocalTime.MAX)
    }

    /**
     * 获取指定日期当天的结束时间。
     *
     * @param date 日期
     * @return 当天结束时间
     */
    fun endOfDay(date: LocalDate): LocalDateTime {
        return date.atTime(LocalTime.MAX)
    }

    /**
     * 获取指定日期所在月的开始日期。
     *
     * @param date 日期
     * @return 当月第一天的日期
     */
    fun startOfMonth(date: LocalDate): LocalDate {
        return getFirstDayOfMonth(date)
    }

    /**
     * 获取指定日期所在月的结束日期。
     *
     * @param date 日期
     * @return 当月最后一天的日期
     */
    fun endOfMonth(date: LocalDate): LocalDate {
        return getLastDayOfMonth(date)
    }

    /**
     * 获取指定日期所在周的开始日期（周一）。
     *
     * @param date 日期
     * @return 周一日期
     */
    fun startOfWeek(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }

    /**
     * 获取指定日期所在周的结束日期（周日）。
     *
     * @param date 日期
     * @return 周日日期
     */
    fun endOfWeek(date: LocalDate): LocalDate {
        return date.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    }

    /**
     * 获取指定日期所在年的开始日期。
     *
     * @param date 日期
     * @return 年初日期
     */
    fun startOfYear(date: LocalDate): LocalDate {
        return LocalDate.of(date.year, 1, 1)
    }

    /**
     * 获取指定日期所在年的结束日期。
     *
     * @param date 日期
     * @return 年末日期
     */
    fun endOfYear(date: LocalDate): LocalDate {
        return LocalDate.of(date.year, 12, 31)
    }

    /**
     * 在指定日期上添加工作日（跳过周末）
     *
     * @param date 起始日期
     * @param days 要添加的工作日天数
     * @return 添加工作日后的日期
     */
    fun addBusinessDays(date: LocalDate, days: Int): LocalDate {
        if (days == 0) return date
        var result = date
        var remaining = if (days > 0) days else -days
        val step = if (days > 0) 1 else -1

        while (remaining > 0) {
            result = result.plusDays(step.toLong())
            if (result.dayOfWeek != DayOfWeek.SATURDAY && result.dayOfWeek != DayOfWeek.SUNDAY) {
                remaining--
            }
        }
        return result
    }

    /**
     * 添加指定天数到日期。
     *
     * @param date 原始日期
     * @param n 天数
     * @return 新日期
     */
    fun addDays(date: LocalDate, n: Int): LocalDate = date.plusDays(n.toLong())

    /**
     * 添加指定月数到日期。
     *
     * @param date 原始日期
     * @param n 月数
     * @return 新日期
     */
    fun addMonths(date: LocalDate, n: Int): LocalDate = date.plusMonths(n.toLong())

    /**
     * 添加指定年数到日期。
     *
     * @param date 原始日期
     * @param n 年数
     * @return 新日期
     */
    fun addYears(date: LocalDate, n: Int): LocalDate = date.plusYears(n.toLong())

    /**
     * 添加指定小时数到日期时间。
     *
     * @param dateTime 原始日期时间
     * @param n 小时数
     * @return 新日期时间
     */
    fun addHours(dateTime: LocalDateTime, n: Int): LocalDateTime = dateTime.plusHours(n.toLong())

    /**
     * 添加指定分钟数到日期时间。
     *
     * @param dateTime 原始日期时间
     * @param n 分钟数
     * @return 新日期时间
     */
    fun addMinutes(dateTime: LocalDateTime, n: Int): LocalDateTime = dateTime.plusMinutes(n.toLong())

    /**
     * 添加指定秒数到日期时间。
     *
     * @param dateTime 原始日期时间
     * @param n 秒数
     * @return 新日期时间
     */
    fun addSeconds(dateTime: LocalDateTime, n: Int): LocalDateTime = dateTime.plusSeconds(n.toLong())

    /**
     * 判断 date1 是否在 date2 之前。
     *
     * @param date1 第一个日期
     * @param date2 第二个日期
     * @return 如果在之前返回 true
     */
    fun isBefore(date1: LocalDate, date2: LocalDate): Boolean = date1.isBefore(date2)

    /**
     * 判断 date1 是否在 date2 之后。
     *
     * @param date1 第一个日期
     * @param date2 第二个日期
     * @return 如果在之后返回 true
     */
    fun isAfter(date1: LocalDate, date2: LocalDate): Boolean = date1.isAfter(date2)

    /**
     * 判断两个日期是否为同一天。
     *
     * @param date1 第一个日期
     * @param date2 第二个日期
     * @return 如果是同一天返回 true
     */
    fun isSameDay(date1: LocalDate, date2: LocalDate): Boolean = date1 == date2

    /**
     * 将日期转换为友好格式，如 "Monday, January 1, 2024"。
     *
     * @param date 日期
     * @return 友好日期字符串
     */
    fun toFriendlyDate(date: LocalDate): String {
        val dayOfWeek = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val month = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "$dayOfWeek, $month ${date.dayOfMonth}, ${date.year}"
    }

    /**
     * 将日期时间格式化为 ISO 8601 字符串。
     *
     * @param dateTime 日期时间
     * @return ISO 8601 字符串
     */
    fun toIso8601(dateTime: LocalDateTime): String {
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    /**
     * 从 ISO 8601 字符串解析日期时间。
     *
     * @param s ISO 8601 字符串
     * @return 解析后的 LocalDateTime，失败返回 null
     */
    fun fromIso8601(s: String): LocalDateTime? {
        return try {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将日期时间格式化为 RFC 1123 字符串（用于 HTTP 头）。
     *
     * @param dateTime 日期时间
     * @return RFC 1123 字符串
     */
    fun toRfc1123(dateTime: LocalDateTime): String {
        val zdt = dateTime.atZone(ZoneOffset.UTC)
        return zdt.format(DateTimeFormatter.RFC_1123_DATE_TIME)
    }

    /**
     * 从 RFC 1123 字符串解析日期时间。
     *
     * @param s RFC 1123 字符串
     * @return 解析后的 LocalDateTime，失败返回 null
     */
    fun fromRfc1123(s: String): LocalDateTime? {
        return try {
            val zdt = ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME)
            zdt.toLocalDateTime()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将日期时间转换为 Unix 时间戳（秒）。
     *
     * @param dateTime 日期时间
     * @return Unix 时间戳
     */
    fun toUnixTimestamp(dateTime: LocalDateTime): Long {
        return dateTime.toEpochSecond(ZoneOffset.UTC)
    }

    /**
     * 将 Unix 时间戳（秒）转换为日期时间。
     *
     * @param timestamp Unix 时间戳
     * @return LocalDateTime
     */
    fun fromUnixTimestamp(timestamp: Long): LocalDateTime {
        return LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.UTC)
    }

    /**
     * 获取日期所在的季度（1~4）。
     *
     * @param date 日期
     * @return 季度
     */
    fun quarter(date: LocalDate): Int = getQuarter(date)

    /**
     * 获取日期是当年的第几天。
     *
     * @param date 日期
     * @return 当年的第几天（1~366）
     */
    fun dayOfYear(date: LocalDate): Int = date.dayOfYear

    /**
     * 获取日期是当年的第几周。
     *
     * @param date 日期
     * @return 当年的第几周（1~53）
     */
    fun weekOfYear(date: LocalDate): Int = date.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    /**
     * 判断日期是否在将来。
     *
     * @param date 日期
     * @return 如果在将来返回 true
     */
    fun isInFuture(date: LocalDate): Boolean = date.isAfter(LocalDate.now())

    /**
     * 判断日期是否在过去。
     *
     * @param date 日期
     * @return 如果在过去返回 true
     */
    fun isInPast(date: LocalDate): Boolean = date.isBefore(LocalDate.now())

    /**
     * 获取指定日期之后的下一个指定星期几。
     *
     * @param date 基准日期
     * @param day 目标星期几
     * @return 下一个星期几的日期
     */
    fun nextDayOfWeek(date: LocalDate, day: DayOfWeek): LocalDate {
        return date.with(TemporalAdjusters.next(day))
    }

    /**
     * 获取指定日期之前的上一个指定星期几。
     *
     * @param date 基准日期
     * @param day 目标星期几
     * @return 上一个星期几的日期
     */
    fun previousDayOfWeek(date: LocalDate, day: DayOfWeek): LocalDate {
        return date.with(TemporalAdjusters.previous(day))
    }

    /**
     * 获取指定日期所在月的天数。
     *
     * @param date 日期
     * @return 该月的天数
     */
    fun daysInMonth(date: LocalDate): Int = date.lengthOfMonth()

    /**
     * 获取指定日期所在年的天数。
     *
     * @param date 日期
     * @return 该年的天数（365 或 366）
     */
    fun daysInYear(date: LocalDate): Int = date.lengthOfYear()

    /**
     * 将日期时间舍入到最近的 N 分钟。
     *
     * @param dateTime 原始日期时间
     * @param n 分钟间隔
     * @return 舍入后的日期时间
     */
    fun nearestMinute(dateTime: LocalDateTime, n: Int): LocalDateTime {
        val minute = dateTime.minute
        val nearest = (minute + n / 2) / n * n
        return dateTime.withMinute(nearest.coerceIn(0, 59)).withSecond(0).withNano(0)
    }

    /**
     * 将日期时间向下舍入到最近的 N 分钟。
     *
     * @param dateTime 原始日期时间
     * @param n 分钟间隔
     * @return 向下舍入后的日期时间
     */
    fun floorMinute(dateTime: LocalDateTime, n: Int): LocalDateTime {
        val minute = dateTime.minute / n * n
        return dateTime.withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
    }

    /**
     * 将日期时间向上舍入到最近的 N 分钟。
     *
     * @param dateTime 原始日期时间
     * @param n 分钟间隔
     * @return 向上舍入后的日期时间
     */
    fun ceilMinute(dateTime: LocalDateTime, n: Int): LocalDateTime {
        val minute = (dateTime.minute + n - 1) / n * n
        return dateTime.withMinute(minute.coerceIn(0, 59)).withSecond(0).withNano(0)
    }

    /**
     * 将日期时间转换为倒计时字符串。
     *
     * @param target 目标日期时间
     * @return 倒计时字符串，如 "2d 3h 15m remaining"
     */
    fun toCountdown(target: LocalDateTime): String {
        val now = LocalDateTime.now()
        val duration = Duration.between(now, target)
        if (duration.isNegative) return "expired"
        val totalSeconds = duration.seconds
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts.add("${days}d")
        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        if (seconds > 0 || parts.isEmpty()) parts.add("${seconds}s")
        return "${parts.joinToString(" ")} remaining"
    }

    /**
     * 判断日期是否在指定的开始和结束日期之间（包含边界）。
     *
     * @param date 待判断的日期
     * @param start 开始日期
     * @param end 结束日期
     * @return 如果在范围内返回 true
     */
    fun isBetweenDates(date: LocalDate, start: LocalDate, end: LocalDate): Boolean {
        return !date.isBefore(start) && !date.isAfter(end)
    }

    /**
     * 尝试多个日期格式模式解析字符串，返回第一个成功的结果。
     *
     * @param dateString 日期字符串
     * @param patterns 待尝试的格式模式列表
     * @return 解析成功返回 LocalDate，全部失败返回 null
     */
    fun sortDateFormats(dateString: String, patterns: List<String>): LocalDate? {
        for (pattern in patterns) {
            val result = parseDate(dateString, pattern)
            if (result != null) return result
        }
        return null
    }

    /**
     * 将出生日期转换为详细的年龄字符串。
     *
     * @param birthDate 出生日期
     * @return 年龄字符串，如 "25 years, 3 months, 12 days"
     */
    fun toAgeString(birthDate: LocalDate): String {
        val today = LocalDate.now()
        var years = today.year - birthDate.year
        var months = today.monthValue - birthDate.monthValue
        var days = today.dayOfMonth - birthDate.dayOfMonth
        if (days < 0) {
            months--
            days += birthDate.lengthOfMonth()
        }
        if (months < 0) {
            years--
            months += 12
        }
        val parts = mutableListOf<String>()
        if (years > 0) parts.add("${years} years")
        if (months > 0) parts.add("${months} months")
        if (days > 0) parts.add("${days} days")
        return parts.joinToString(", ")
    }

    /**
     * 计算仅基于日期部分的哈希码（忽略时间）。
     *
     * @param date 日期
     * @return 日期哈希码
     */
    fun hashCodeForDate(date: LocalDate): Int {
        return date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
    }

    /**
     * 将日期格式化为适合商业场景的字符串（如 "2024-01-15"）。
     *
     * @param date 日期
     * @return 商业日期字符串
     */
    fun toBusinessDateString(date: LocalDate): String {
        return formatDate(date, "yyyy-MM-dd")
    }

    /**
     * 将日期格式化为适合技术场景的字符串（如 "20240115"）。
     *
     * @param date 日期
     * @return 技术日期字符串
     */
    fun toTechnicalDateString(date: LocalDate): String {
        return formatDate(date, "yyyyMMdd")
    }
}
