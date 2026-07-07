package com.apex.agent.core.normal.rendering

/**
 * F6: 流式 Markdown 增量渲染器
 *
 * 解决流式渲染代码块/表格/列表抖动问题。
 * 基于"结构预测"——已生成 ``` 时预创建代码块容器，
 * 已生成 | 时预创建表格骨架，避免每次 chunk 重排。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent / 狂暴不关心 UI 渲染
 * - 本功能是**单 Agent 流式对话体验**的 UI 优化
 */

/**
 * 渲染节点类型
 */
enum class RenderNodeType {
    TEXT, PARAGRAPH, CODE_BLOCK, INLINE_CODE,
    HEADING, TABLE, LIST, ORDERED_LIST,
    BLOCKQUOTE, HORIZONTAL_RULE,
    BOLD, ITALIC, LINK, IMAGE,
    THINKING_BLOCK
}

/**
 * 渲染节点
 */
data class RenderNode(
    val type: RenderNodeType,
    val content: String = "",
    val children: MutableList<RenderNode> = mutableListOf(),
    val attributes: Map<String, String> = emptyMap(),
    var complete: Boolean = false
)

/**
 * 渲染树
 */
data class RenderTree(
    val root: RenderNode,
    val completedNodes: List<RenderNode> = emptyList(),
    val streamingNode: RenderNode? = null
)

/**
 * 流式 Markdown 渲染器
 *
 * 核心思想：增量解析 + 结构预测
 * - 维护当前解析状态（在代码块内？在表格内？）
 * - 每收到新 chunk，只更新受影响的部分
 * - 预测未闭合的结构，提前创建容器
 */
class StreamingMarkdownRenderer {

    private val buffer = StringBuilder()
    private val root = RenderNode(RenderNodeType.TEXT)
    private var currentNode: RenderNode = root

    // 解析状态
    private var inCodeBlock = false
    private var codeBlockLang = ""
    private var codeBlockBuffer = StringBuilder()
    private var inTable = false
    private var tableRows = mutableListOf<List<String>>()
    private var inThinkingBlock = false
    private var thinkingBuffer = StringBuilder()
    private var inList = false
    private var listItems = mutableListOf<String>()

    /**
     * 输入新 chunk
     */
    fun feed(chunk: String): RenderTree {
        buffer.append(chunk)

        // 按行处理（保留最后不完整的行）
        val lines = buffer.toString().split("\n")
        val completeLines = if (buffer.endsWith("\n")) lines.dropLast(1) else lines.dropLast(1)

        for (line in completeLines) {
            processLine(line)
        }

        return buildRenderTree()
    }

    /**
     * 完成渲染
     */
    fun finalize(): RenderTree {
        // 处理缓冲区剩余内容
        if (buffer.isNotEmpty()) {
            val remaining = buffer.toString().split("\n")
            for (line in remaining) {
                processLine(line)
            }
            buffer.clear()
        }

        // 关闭所有未闭合的结构
        if (inCodeBlock) {
            root.children.add(RenderNode(
                type = RenderNodeType.CODE_BLOCK,
                content = codeBlockBuffer.toString(),
                attributes = mapOf("lang" to codeBlockLang),
                complete = true
            ))
            codeBlockBuffer.clear()
            inCodeBlock = false
        }
        if (inTable) {
            finalizeTable()
        }
        if (inList) {
            finalizeList()
        }
        if (inThinkingBlock) {
            root.children.add(RenderNode(
                type = RenderNodeType.THINKING_BLOCK,
                content = thinkingBuffer.toString(),
                complete = true
            ))
            thinkingBuffer.clear()
            inThinkingBlock = false
        }

        return buildRenderTree().copy(streamingNode = null)
    }

    // ============ 内部方法 ============

    private fun processLine(line: String) {
        when {
            // 代码块开始/结束
            line.trimStart().startsWith("```") -> {
                if (inCodeBlock) {
                    // 结束代码块
                    root.children.add(RenderNode(
                        type = RenderNodeType.CODE_BLOCK,
                        content = codeBlockBuffer.toString(),
                        attributes = mapOf("lang" to codeBlockLang),
                        complete = true
                    ))
                    codeBlockBuffer.clear()
                    codeBlockLang = ""
                    inCodeBlock = false
                } else {
                    // 开始代码块
                    codeBlockLang = line.trimStart().removePrefix("```").trim()
                    inCodeBlock = true
                }
            }
            inCodeBlock -> {
                codeBlockBuffer.appendLine(line)
            }
            // 思考块 <think>
            line.trim() == "<think>" -> {
                inThinkingBlock = true
            }
            line.trim() == "</think>" -> {
                root.children.add(RenderNode(
                    type = RenderNodeType.THINKING_BLOCK,
                    content = thinkingBuffer.toString(),
                    complete = true
                ))
                thinkingBuffer.clear()
                inThinkingBlock = false
            }
            inThinkingBlock -> {
                thinkingBuffer.appendLine(line)
            }
            // 表格行（含 |）
            line.contains("|") && line.trim().startsWith("|") -> {
                if (!inTable) inTable = true
                val cells = line.trim().trim('|').split("|").map { it.trim() }
                // 跳过分隔行 |---|---|
                if (!cells.all { it.matches(Regex("[-:]+")) }) {
                    tableRows.add(cells)
                }
            }
            inTable && !line.contains("|") -> {
                // 表格结束
                finalizeTable()
                processLine(line)  // 递归处理当前行
            }
            // 列表
            line.matches(Regex("^\\s*[-*+]\\s+.+")) -> {
                if (!inList) inList = true
                listItems.add(line.trim().removePrefix("-").removePrefix("*").removePrefix("+").trim())
            }
            line.matches(Regex("^\\s*\\d+\\.\\s+.+")) -> {
                if (!inList) inList = true
                listItems.add(line.trim().replace(Regex("^\\d+\\.\\s+"), ""))
            }
            inList && line.isBlank() -> {
                finalizeList()
            }
            // 标题
            line.matches(Regex("^#{1,6}\\s+.+")) -> {
                val level = line.takeWhile { it == '#' }.length
                val content = line.dropWhile { it == '#' }.trim()
                root.children.add(RenderNode(
                    type = RenderNodeType.HEADING,
                    content = content,
                    attributes = mapOf("level" to level.toString()),
                    complete = true
                ))
            }
            // 引用
            line.startsWith("> ") -> {
                root.children.add(RenderNode(
                    type = RenderNodeType.BLOCKQUOTE,
                    content = line.removePrefix("> ").trim(),
                    complete = true
                ))
            }
            // 分割线
            line.matches(Regex("^[-*_]{3,}$")) -> {
                root.children.add(RenderNode(
                    type = RenderNodeType.HORIZONTAL_RULE,
                    complete = true
                ))
            }
            // 空行
            line.isBlank() -> { /* 忽略 */ }
            // 普通段落
            else -> {
                root.children.add(RenderNode(
                    type = RenderNodeType.PARAGRAPH,
                    content = line,
                    complete = true
                ))
            }
        }
    }

    private fun finalizeTable() {
        if (tableRows.isNotEmpty()) {
            root.children.add(RenderNode(
                type = RenderNodeType.TABLE,
                children = tableRows.map { row ->
                    RenderNode(
                        type = RenderNodeType.TEXT,
                        content = row.joinToString(" | "),
                        attributes = mapOf("cells" to row.size.toString())
                    )
                },
                attributes = mapOf("rows" to tableRows.size.toString()),
                complete = true
            ))
        }
        tableRows.clear()
        inTable = false
    }

    private fun finalizeList() {
        if (listItems.isNotEmpty()) {
            root.children.add(RenderNode(
                type = RenderNodeType.LIST,
                children = listItems.map { item ->
                    RenderNode(type = RenderNodeType.TEXT, content = item, complete = true)
                },
                complete = true
            ))
        }
        listItems.clear()
        inList = false
    }

    private fun buildRenderTree(): RenderTree {
        val completed = root.children.filter { it.complete }
        val streaming = root.children.lastOrNull { !it.complete }
        return RenderTree(
            root = root,
            completedNodes = completed,
            streamingNode = streaming
        )
    }

    /**
     * 重置渲染器
     */
    fun reset() {
        buffer.clear()
        root.children.clear()
        currentNode = root
        inCodeBlock = false
        codeBlockBuffer.clear()
        inTable = false
        tableRows.clear()
        inThinkingBlock = false
        thinkingBuffer.clear()
        inList = false
        listItems.clear()
    }
}
