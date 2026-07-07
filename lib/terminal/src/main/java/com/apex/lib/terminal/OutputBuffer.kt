package com.apex.lib.terminal

import java.util.ArrayDeque

/**
 * 输出缓冲（环形）— 按行存储终端 PTY 输出。
 *
 * **特性**：
 *   - 行级缓冲：原始字节流按 `\n` 自动分行
 *   - 回滚行数限制（[maxLines]），FIFO 淘汰旧行
 *   - 同步快照（[snapshot] / [lastN]）
 *   - ANSI 序列保留在行文本中（不做剥离），由上层渲染器解析
 *     （未来可在本类扩展 `strippedSnapshot()` 等方法）
 *
 * **线程安全**：所有公共方法用 `@Synchronized` 保护。
 *
 * @param maxLines 最大保留行数（默认 5000）
 */
class OutputBuffer(private val maxLines: Int = 5000) {

    /** 已完成（带 \n 结束）的行。 */
    private val lines: ArrayDeque<String> = ArrayDeque()
    /** 当前未结束的行（缓冲到下一个 \n）。 */
    private val currentLine: StringBuilder = StringBuilder()
    /** 累计接收字节总数（统计用）。 */
    private var totalBytes: Long = 0L

    /** 追加原始字节（UTF-8 解码后按 \n 分行）。 */
    @Synchronized
    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        val text = String(data, Charsets.UTF_8)
        totalBytes += data.size

        var i = 0
        while (i < text.length) {
            val nl = text.indexOf('\n', i)
            if (nl < 0) {
                currentLine.append(text, i, text.length)
                break
            }
            currentLine.append(text, i, nl)
            lines.addLast(currentLine.toString())
            currentLine.setLength(0)
            trim()
            i = nl + 1
        }
    }

    /** 超过 [maxLines] 时丢弃最旧行。 */
    private fun trim() {
        while (lines.size > maxLines) lines.removeFirst()
    }

    /**
     * 完整快照（已完成行 + 当前未结束行）。
     * 返回的列表是副本，外部可安全修改。
     */
    @Synchronized
    fun snapshot(): List<String> {
        val result = ArrayList<String>(lines.size + 1)
        lines.iterator().forEachRemaining { result.add(it) }
        if (currentLine.isNotEmpty()) result.add(currentLine.toString())
        return result
    }

    /** 最近 N 行（含当前未结束行）。 */
    @Synchronized
    fun lastN(n: Int): List<String> {
        if (n <= 0) return emptyList()
        val all = snapshot()
        return if (n >= all.size) all else all.takeLast(n)
    }

    /** 清空所有缓冲。 */
    @Synchronized
    fun clear() {
        lines.clear()
        currentLine.setLength(0)
        totalBytes = 0L
    }

    /** 当前已完成行数（不含未结束行）。 */
    @Synchronized
    fun lineCount(): Int = lines.size

    /** 累计接收字节总数。 */
    @Synchronized
    fun totalBytes(): Long = totalBytes
}
