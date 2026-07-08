package com.apex.lib.terminal

import java.util.ArrayDeque

/**
 * 命令历史（按会话隔离）。
 *
 * **能力**：
 *   - 追加命令（trim 后非空才记录）
 *   - 去重：与上一条**完全相同**的命令不重复记录（避免长按回车刷屏）
 *   - 全文搜索（大小写不敏感）
 *   - 上下翻页（cursor 游标）
 *   - 最大条数限制（FIFO 淘汰）
 *
 * **线程安全**：所有公共方法用 `@Synchronized` 保护。
 *
 * @param maxEntries 最大保留条数（默认 500）
 */
class CommandHistory(private val maxEntries: Int = 500) {

    private val entries: ArrayDeque<CommandHistoryEntry> = ArrayDeque()
    /** 翻页游标。-1 = 末尾之后（即"新建命令"位置）。 */
    private var cursor: Int = -1

    /** 追加一条命令。空 / 与上一条相同则跳过。 */
    @Synchronized
    fun append(sessionId: String, command: String, exitCode: Int? = null) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        // 去重：连续相同命令只记录一次
        if (entries.peekLast()?.command == trimmed) {
            return
        }
        val entry = CommandHistoryEntry(
            sessionId = sessionId,
            command = trimmed,
            timestamp = System.currentTimeMillis(),
            exitCode = exitCode
        )
        entries.addLast(entry)
        while (entries.size > maxEntries) entries.removeFirst()
        resetCursor()
    }

    /** 全部历史（旧 → 新）。 */
    @Synchronized
    fun all(): List<CommandHistoryEntry> = entries.toList()

    /** 全文搜索（大小写不敏感，匹配 command 子串）。 */
    @Synchronized
    fun search(query: String): List<CommandHistoryEntry> {
        if (query.isBlank()) return all()
        val q = query.lowercase()
        return entries.filter { it.command.lowercase().contains(q) }
    }

    /** 最近 N 条。 */
    @Synchronized
    fun last(n: Int): List<CommandHistoryEntry> {
        if (n <= 0) return emptyList()
        val list = entries.toList()
        return if (n >= list.size) list else list.takeLast(n)
    }

    /**
     * 上一条（向上翻页）。
     * - 第一次调用返回最后一条
     * - 后续调用逐条向前
     * - 到顶后返回 null（不回卷）
     */
    @Synchronized
    fun previous(): CommandHistoryEntry? {
        if (entries.isEmpty()) return null
        if (cursor < 0) {
            cursor = entries.size - 1
        } else if (cursor > 0) {
            cursor--
        } else {
            return null  // 已到顶
        }
        return entries.toList()[cursor]
    }

    /**
     * 下一条（向下翻页）。
     * - 到末尾后返回 null 并重置游标
     */
    @Synchronized
    fun next(): CommandHistoryEntry? {
        if (entries.isEmpty() || cursor < 0) return null
        cursor++
        return if (cursor in 0 until entries.size) {
            entries[cursor]
        } else {
            resetCursor()
            null
        }
    }

    /** 重置翻页游标到末尾（"新建命令"位置）。 */
    @Synchronized
    fun resetCursor() {
        cursor = -1
    }

    /** 清空全部历史。 */
    @Synchronized
    fun clear() {
        entries.clear()
        resetCursor()
    }

    /** 当前条数。 */
    @Synchronized
    fun size(): Int = entries.size

    /** 是否为空。 */
    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()
}
