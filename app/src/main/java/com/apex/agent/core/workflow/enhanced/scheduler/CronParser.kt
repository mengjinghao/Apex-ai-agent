package com.apex.agent.core.workflow.enhanced.scheduler

import java.util.Calendar
import java.util.TimeZone

/**
 * Cron 表达式解析器
 *
 * 支持标准 5 字段 cron：分 时 日 月 周
 *
 * 字段范围：
 * - 分钟: 0-59
 * - 小时: 0-23
 * - 日:   1-31
 * - 月:   1-12 (或 JAN-DEC)
 * - 周:   0-6  (0=Sunday, 或 SUN-SAT)
 *
 * 支持语法：
 * - `*` 任意值
 * - `5`  固定值
 * - `1,3,5` 列表
 * - `1-5` 范围
 * - `*/2` 步长
 * - `0 9 * * 1-5` 周一到周五 9 点
 * - `*/15 * * * *` 每 15 分钟
 * - `0 0 1 * *` 每月 1 号 0 点
 *
 * 参照 Quartz CronExpression 与 Unix cron
 */
object CronParser {

    private val MONTH_ALIASES = mapOf(
        "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4,
        "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8,
        "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
    )

    private val DAY_ALIASES = mapOf(
        "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3,
        "THU" to 4, "FRI" to 5, "SAT" to 6
    )

    private val FIELD_RANGES = arrayOf(
        intArrayOf(0, 59),   // minute
        intArrayOf(0, 23),   // hour
        intArrayOf(1, 31),   // day of month
        intArrayOf(1, 12),   // month
        intArrayOf(0, 6)     // day of week
    )

    data class CronField(val values: Set<Int>, val isWildcard: Boolean)

    data class CronExpression(
        val minute: CronField,
        val hour: CronField,
        val dayOfMonth: CronField,
        val month: CronField,
        val dayOfWeek: CronField
    ) {
        /**
         * 计算从 fromTime 之后下一次匹配的时间戳
         */
        fun nextRun(fromTime: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = fromTime
            // 加 1 分钟，从下一分钟开始找
            cal.add(Calendar.MINUTE, 1)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            // 最多搜索 4 年（防死循环）
            val maxIterations = 4 * 365 * 24 * 60
            var iter = 0
            while (iter < maxIterations) {
                if (matches(cal)) return cal.timeInMillis
                cal.add(Calendar.MINUTE, 1)
                iter++
            }
            return fromTime
        }

        private fun matches(cal: Calendar): Boolean {
            val min = cal.get(Calendar.MINUTE)
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val mon = cal.get(Calendar.MONTH) + 1
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1  // Calendar: 1=Sunday

            return minute.values.contains(min) &&
                   hour.values.contains(hr) &&
                   month.values.contains(mon) &&
                   (dayOfMonth.values.contains(dom) || dayOfWeek.values.contains(dow))
        }
    }

    /**
     * 解析 cron 表达式
     */
    fun parse(expression: String): CronExpression {
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron 表达式必须有 5 个字段: $expression" }

        return CronExpression(
            minute = parseField(parts[0], 0),
            hour = parseField(parts[1], 1),
            dayOfMonth = parseField(parts[2], 2),
            month = parseField(parts[3], 3, MONTH_ALIASES),
            dayOfWeek = parseField(parts[4], 4, DAY_ALIASES)
        )
    }

    private fun parseField(expr: String, fieldIndex: Int, aliases: Map<String, Int> = emptyMap()): CronField {
        val range = FIELD_RANGES[fieldIndex]
        val result = mutableSetOf<Int>()

        for (part in expr.split(",")) {
            val trimmed = part.trim().uppercase()
            when {
                trimmed == "*" -> {
                    for (i in range[0]..range[1]) result.add(i)
                }
                trimmed.startsWith("*/") -> {
                    val step = trimmed.removePrefix("*/").toIntOrNull()
                        ?: throw IllegalArgumentException("无效步长: $trimmed")
                    for (i in range[0]..range[1] step step) result.add(i)
                }
                trimmed.contains("-") && !trimmed.startsWith("*/") -> {
                    val bounds = trimmed.split("-")
                    require(bounds.size == 2) { "无效范围: $trimmed" }
                    val start = resolveValue(bounds[0], range, aliases)
                    val end = resolveValue(bounds[1], range, aliases)
                    if (start <= end) {
                        for (i in start..end) result.add(i)
                    } else {
                        // 跨范围（如 22-2 表示 22,23,0,1,2）
                        for (i in start..range[1]) result.add(i)
                        for (i in range[0]..end) result.add(i)
                    }
                }
                else -> {
                    val v = resolveValue(trimmed, range, aliases)
                    require(v in range[0]..range[1]) { "值 $v 超出范围 [${range[0]}, ${range[1]}]" }
                    result.add(v)
                }
            }
        }

        val isWildcard = expr.trim() == "*"
        return CronField(result, isWildcard)
    }

    private fun resolveValue(s: String, range: IntArray, aliases: Map<String, Int>): Int {
        if (s.isEmpty()) throw IllegalArgumentException("空值")
        // 数字优先
        s.toIntOrNull()?.let { return it }
        // 别名
        aliases[s.uppercase()]?.let { return it }
        throw IllegalArgumentException("无法解析: $s")
    }

    /**
     * 便捷方法：解析并计算下次运行时间
     */
    fun nextRun(expression: String, fromTime: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        return parse(expression).nextRun(fromTime, timeZone)
    }

    /**
     * 校验 cron 表达式是否合法
     */
    fun isValid(expression: String): Boolean = try {
        parse(expression)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 人类可读描述
     */
    fun describe(expression: String): String {
        if (!isValid(expression)) return "无效的 cron 表达式"
        val parts = expression.trim().split(Regex("\\s+"))
        val min = parts[0]; val hr = parts[1]; val dom = parts[2]; val mon = parts[3]; val dow = parts[4]

        return buildString {
            when {
                min == "*" && hr == "*" -> append("每分钟")
                min.startsWith("*/") -> append("每 ${min.removePrefix("*/")} 分钟")
                hr == "*" -> append("每小时第 $min 分")
                else -> append("在 $hr:$min")
            }
            when {
                dow == "1-5" -> append("，工作日")
                dow == "0,6" || dow == "6,0" -> append("，周末")
                dow != "*" -> append("，每周$dow")
            }
            if (dom != "*" && dom != "*/1") {
                append("，每月 $dom 号")
            }
            if (mon != "*" && mon != "*/1") {
                append("，$mon 月")
            }
        }
    }
}
