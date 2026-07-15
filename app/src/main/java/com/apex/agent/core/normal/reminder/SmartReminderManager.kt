package com.apex.agent.core.normal.reminder

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentPriorityQueue

/**
 * F25: 智能提醒与主动服务
 *
 * 基于对话内容、用户习惯、时间等主动提醒：
 * - 待办事项提醒（从对话中提取）
 * - 跟进提醒（之前的话题未完成）
 * - 习惯性提醒（固定时间）
 * - 上下文提醒（基于当前位置/活动）
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 的提醒是 Agent 间
 * - 狂暴不主动提醒
 * - 本功能是**面向用户的主动服务**，让单 Agent 有"管家"能力
 */

/**
 * 提醒类型
 */
enum class ReminderType {
    TODO,               // 待办事项
    FOLLOW_UP,          // 跟进
    SCHEDULED,          // 定时
    CONTEXTUAL,         // 基于上下文
    HABIT,              // 习惯性
    DEADLINE,           // 截止日期
    INACTIVITY,         // 不活跃
    LEARNING_REVIEW     // 学习复习
}

/**
 * 提醒优先级
 */
enum class ReminderPriority {
    LOW, MEDIUM, HIGH, URGENT
}

/**
 * 提醒
 */
data class Reminder(
    val id: String,
    val type: ReminderType,
    val title: String,
    val description: String,
    val priority: ReminderPriority = ReminderPriority.MEDIUM,
    val scheduledAt: Long,
    val actionSuggestion: String? = null,
    val relatedChatId: String? = null,
    val relatedMessageId: String? = null,
    val recurrence: RecurrencePattern? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val triggered: Boolean = false,
    val dismissed: Boolean = false
)

/**
 * 重复模式
 */
sealed class RecurrencePattern {
    data class Daily(val hour: Int, val minute: Int) : RecurrencePattern()
    data class Weekly(val dayOfWeek: Int, val hour: Int, val minute: Int) : RecurrencePattern()  // 1=周一
    data class Monthly(val dayOfMonth: Int, val hour: Int, val minute: Int) : RecurrencePattern()
    data class Interval(val intervalMs: Long) : RecurrencePattern()
    data object Once : RecurrencePattern()
}

/**
 * 提醒事件
 */
sealed class ReminderEvent {
    data class Triggered(val reminder: Reminder) : ReminderEvent()
    data class Created(val reminder: Reminder) : ReminderEvent()
    data class Dismissed(val reminderId: String) : ReminderEvent()
    data class Snoozed(val reminderId: String, val until: Long) : ReminderEvent()
}

/**
 * 智能提醒管理器
 */
class SmartReminderManager(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    private val reminders = ConcurrentHashMap<String, Reminder>()
        private val pendingQueue = ConcurrentPriorityQueue<Reminder>(compareBy { it.scheduledAt })
        private val _events = MutableSharedFlow<ReminderEvent>(extraBufferCapacity = 64)
        val events: SharedFlow<ReminderEvent> = _events.asSharedFlow()
        private var checkerJob: Job? = null

    init {
        startChecker()
    }

    /**
     * 创建提醒
     */
    suspend fun create(reminder: Reminder): Reminder {
        reminders[reminder.id] = reminder
        pendingQueue.add(reminder)
        _events.emit(ReminderEvent.Created(reminder))
        return reminder
    }

    /**
     * 从对话内容中提取待办并创建提醒
     */
    suspend fun extractFromMessage(
        message: String,
        chatId: String,
        messageId: String
    ): List<Reminder> {
        val extracted = mutableListOf<Reminder>()

        // 检测待办模式
    val todoPatterns = mapOf(
            "提醒我(\\d+[点时分小时分钟]*)(.*)" to ReminderType.TODO,
            "记得(.*?)(?:当|在|明天|后天|今天)" to ReminderType.TODO,
            "不要忘了(.*?)" to ReminderType.TODO,
            "待办[：:](.*?)(?:时间[：:]\\s*(.+))?" to ReminderType.TODO,
            "remind me to (.+?) (?:at|on|in) (.+)" to ReminderType.TODO,
            "todo[:\\s]+(.+?)(?:\\s+(?:by|at|on)\\s+(.+))?" to ReminderType.TODO
        )
        for ((pattern, type) in todoPatterns) {
            Regex(pattern, RegexOption.IGNORE_CASE).findAll(message).forEach { match ->
                val title = match.groupValues.getOrElse(1) { "" }.ifBlank { "待办事项" }
        val timeStr = match.groupValues.getOrNull(2) ?: ""
        val scheduledAt = parseTimeString(timeStr) ?: (System.currentTimeMillis() + 60 * 60_000L)  // 默认 1 小时后
    val reminder = Reminder(
                    id = "reminder_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
                    type = type,
                    title = title.trim(),
                    description = "从对话中提取: ${message.take(100)}",
                    priority = ReminderPriority.MEDIUM,
                    scheduledAt = scheduledAt,
                    relatedChatId = chatId,
                    relatedMessageId = messageId
                )
                create(reminder)
                extracted.add(reminder)
            }
        }

        // 检测截止日期
    val deadlinePattern = Regex("(?:截止|deadline|due)[:\\s]+(.+?)(?:\\s+(?:by|at|on|前)\\s+(.+))?", RegexOption.IGNORE_CASE)
        deadlinePattern.findAll(message).forEach { match ->
            val title = match.groupValues[1].ifBlank { "截止任务" }
        val timeStr = match.groupValues.getOrNull(2) ?: ""
        val scheduledAt = parseTimeString(timeStr) ?: (System.currentTimeMillis() + 24 * 60 * 60_000L)
        val reminder = Reminder(
                id = "reminder_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
                type = ReminderType.DEADLINE,
                title = title.trim(),
                description = "截止日期提醒",
                priority = ReminderPriority.HIGH,
                scheduledAt = scheduledAt,
                relatedChatId = chatId
            )
            scope.launch { create(reminder) }
            extracted.add(reminder)
        }
        return extracted
    }

    /**
     * 创建定时提醒
     */
    suspend fun schedule(
        title: String,
        description: String,
        scheduledAt: Long,
        type: ReminderType = ReminderType.SCHEDULED,
        priority: ReminderPriority = ReminderPriority.MEDIUM
    ): Reminder {
        val reminder = Reminder(
            id = "reminder_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            type = type,
            title = title,
            description = description,
            priority = priority,
            scheduledAt = scheduledAt
        )
        return create(reminder)
    }

    /**
     * 创建每日习惯提醒
     */
    suspend fun scheduleDaily(title: String, description: String, hour: Int, minute: Int): Reminder {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        val reminder = Reminder(
            id = "habit_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            type = ReminderType.HABIT,
            title = title,
            description = description,
            priority = ReminderPriority.LOW,
            scheduledAt = cal.timeInMillis,
            recurrence = RecurrencePattern.Daily(hour, minute)
        )
        return create(reminder)
    }

    /**
     * 创建跟进提醒
     */
    suspend fun scheduleFollowUp(
        chatId: String,
        topic: String,
        delayMs: Long = 24 * 60 * 60_000L  // 默认 1 天后
    ): Reminder {
        val reminder = Reminder(
            id = "followup_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            type = ReminderType.FOLLOW_UP,
            title = "跟进: $topic",
            description = "之前讨论的话题可能需要跟进",
            priority = ReminderPriority.MEDIUM,
            scheduledAt = System.currentTimeMillis() + delayMs,
            relatedChatId = chatId
        )
        return create(reminder)
    }

    /**
     * 忽略提醒
     */
    suspend fun dismiss(reminderId: String): Boolean {
        val reminder = reminders[reminderId] ?: return false
        reminders[reminderId] = reminder.copy(dismissed = true)
        _events.emit(ReminderEvent.Dismissed(reminderId))
        return true
    }

    /**
     * 延后提醒
     */
    suspend fun snooze(reminderId: String, delayMs: Long): Boolean {
        val reminder = reminders[reminderId] ?: return false
        val newTime = System.currentTimeMillis() + delayMs
        val updated = reminder.copy(scheduledAt = newTime, triggered = false)
        reminders[reminderId] = updated
        pendingQueue.add(updated)
        _events.emit(ReminderEvent.Snoozed(reminderId, newTime))
        return true
    }

    /**
     * 获取所有提醒
     */
    fun listAll(): List<Reminder> = reminders.values.sortedBy { it.scheduledAt }.toList()

    /**
     * 获取待触发提醒
     */
    fun listPending(): List<Reminder> = reminders.values
        .filter { !it.triggered && !it.dismissed && it.scheduledAt > System.currentTimeMillis() }
        .sortedBy { it.scheduledAt }
        .toList()

    /**
     * 获取已触发提醒
     */
    fun listTriggered(): List<Reminder> = reminders.values
        .filter { it.triggered && !it.dismissed }
        .sortedByDescending { it.scheduledAt }
        .toList()

    /**
     * 获取某聊天相关的提醒
     */
    fun listByChat(chatId: String): List<Reminder> = reminders.values
        .filter { it.relatedChatId == chatId }
        .sortedBy { it.scheduledAt }
        .toList()

    /**
     * 生成提醒 prompt 注入
     */
    fun generateReminderPrompt(): String {
        val pending = listPending().take(3)
        if (pending.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("[待办提醒]")
        pending.forEach { r ->
            val timeStr = java.text.SimpleDateFormat("MM-dd HH:mm").format(java.util.Date(r.scheduledAt))
            sb.appendLine("- [$timeStr] ${r.title} (${r.type})")
        }
        return sb.toString()
    }

    /**
     * 关闭
     */
    fun shutdown() {
        checkerJob?.cancel()
        scope.cancel()
    }

    // ============ 内部方法 ============
    private fun startChecker() {
        checkerJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                while (pendingQueue.isNotEmpty() && pendingQueue.peek().scheduledAt <= now) {
                    val reminder = pendingQueue.poll()
        if (!reminder.dismissed && !reminder.triggered) {
                        triggerReminder(reminder)
                    }
                }
                delay(30_000)  // 每 30 秒检查一次
            }
        }
    }
        private suspend fun triggerReminder(reminder: Reminder) {
        val triggered = reminder.copy(triggered = true)
        reminders[reminder.id] = triggered
        _events.emit(ReminderEvent.Triggered(triggered))

        // 如果是重复提醒，创建下一次
        reminder.recurrence?.let { pattern ->
            val nextTime = computeNextOccurrence(pattern, reminder.scheduledAt)
        if (nextTime != null) {
                val next = reminder.copy(
                    id = "reminder_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
                    scheduledAt = nextTime,
                    triggered = false,
                    createdAt = System.currentTimeMillis()
                )
                create(next)
            }
        }
    }
        private fun computeNextOccurrence(pattern: RecurrencePattern, from: Long): Long? {
        return when (pattern) {
            is RecurrencePattern.Daily -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = from
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, pattern.hour)
                cal.set(java.util.Calendar.MINUTE, pattern.minute)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.timeInMillis
            }
            is RecurrencePattern.Weekly -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = from
                cal.add(java.util.Calendar.DAY_OF_MONTH, 7)
                cal.set(java.util.Calendar.DAY_OF_WEEK, pattern.dayOfWeek + 1)  // Calendar: 1=Sunday
                cal.set(java.util.Calendar.HOUR_OF_DAY, pattern.hour)
                cal.set(java.util.Calendar.MINUTE, pattern.minute)
                cal.timeInMillis
            }
            is RecurrencePattern.Monthly -> {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = from
                cal.add(java.util.Calendar.MONTH, 1)
                cal.set(java.util.Calendar.DAY_OF_MONTH, pattern.dayOfMonth)
                cal.set(java.util.Calendar.HOUR_OF_DAY, pattern.hour)
                cal.set(java.util.Calendar.MINUTE, pattern.minute)
                cal.timeInMillis
            }
            is RecurrencePattern.Interval -> from + pattern.intervalMs
            is RecurrencePattern.Once -> null
        }
    }
        private fun parseTimeString(timeStr: String): Long? {
        if (timeStr.isBlank()) return null
        val now = System.currentTimeMillis()

        // 相对时间
    val relativePatterns = mapOf(
            "明天" to 24 * 60 * 60_000L,
            "后天" to 2 * 24 * 60 * 60_000L,
            "下周" to 7 * 24 * 60 * 60_000L,
            "tomorrow" to 24 * 60 * 60_000L,
            "next week" to 7 * 24 * 60 * 60_000L
        )
        for ((pattern, delta) in relativePatterns) {
            if (timeStr.contains(pattern, ignoreCase = true)) return now + delta
        }

        // "X 小时后" / "X 分钟后"
        Regex("(\\d+)\\s*(小时|hour|h)后").find(timeStr)?.let { m ->
            return now + m.groupValues[1].toLong() * 60 * 60_000L
        }
        Regex("(\\d+)\\s*(分钟|minute|min|m)后").find(timeStr)?.let { m ->
            return now + m.groupValues[1].toLong() * 60_000L
        }

        // 绝对时间 HH:mm
        Regex("(\\d{1,2})[:：](\\d{2})").find(timeStr)?.let { m ->
            val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
            cal.set(java.util.Calendar.MINUTE, minute)
            cal.set(java.util.Calendar.SECOND, 0)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
        }
        return null
    }
}
