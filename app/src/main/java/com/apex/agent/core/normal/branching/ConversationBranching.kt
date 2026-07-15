package com.apex.agent.core.normal.branching

import java.util.concurrent.ConcurrentHashMap

/**
 * F9: 对话分支与回溯
 *
 * 每条消息可"分叉"出新对话（类似 Git），原对话保留。
 * 支持"回到此处重新提问"。分支树可视化展示。
 *
 * 与多 Agent / 狂暴模式的区别：
 * - 多 Agent 是 Agent 间分支
 * - 狂暴是任务重试
 * - 本功能是**用户对话分支**，体现单 Agent 的对话探索
 */

/**
 * 对话消息节点（分支树中的节点）
 */
data class BranchMessage(
    val id: String,
    val parentId: String?,       // 父消息 ID（null = 根）
    val chatId: String,
    val role: Role,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val childrenIds: MutableList<String> = mutableListOf(),
    val isActive: Boolean = false,  // 是否在当前活跃路径上
    val branchLabel: String? = null,  // 分支标签
    val metadata: Map<String, Any> = emptyMap()
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/**
 * 分支信息
 */
data class ConversationBranch(
    val id: String,
    val chatId: String,
    val fromMessageId: String,   // 从哪条消息分叉
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
    val messageIds: List<String> = emptyList()
)

/**
 * 分支树
 */
data class BranchTree(
    val chatId: String,
    val rootMessages: List<BranchMessage>,
    val allMessages: Map<String, BranchMessage>,
    val branches: List<ConversationBranch>,
    val activePath: List<String>  // 当前活跃路径的消息 ID 列表
)

/**
 * 对话分支管理器
 */
class ConversationBranching {

    private val messages = ConcurrentHashMap<String, MutableMap<String, BranchMessage>>()  // chatId -> (msgId -> msg)
        private val branches = ConcurrentHashMap<String, MutableList<ConversationBranch>>()     // chatId -> branches
    private val activeTips = ConcurrentHashMap<String, String>()                            // chatId -> 当前活跃消息 ID

    /**
     * 添加消息（默认追加到当前活跃路径末尾）
     */
    fun addMessage(chatId: String, role: BranchMessage.Role, content: String, branchLabel: String? = null): BranchMessage {
        val chatMessages = messages.computeIfAbsent(chatId) { mutableMapOf() }
        val parentId = activeTips[chatId]
        val msgId = "msg_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
        val message = BranchMessage(
            id = msgId,
            parentId = parentId,
            chatId = chatId,
            role = role,
            content = content,
            isActive = true,
            branchLabel = branchLabel
        )

        chatMessages[msgId] = message

        // 更新父消息的 children
        parentId?.let { pid ->
            chatMessages[pid]?.let { parent ->
                chatMessages[pid] = parent.copy(childrenIds = (parent.childrenIds + msgId).toMutableList())
            }
        }

        activeTips[chatId] = msgId
        return message
    }

    /**
     * 从某条消息分叉（创建新分支）
     *
     * @param fromMessageId 从哪条消息分叉
     * @param label 分支标签
     * @return 新分支信息
     */
    fun fork(chatId: String, fromMessageId: String, label: String): ConversationBranch? {
        val chatMessages = messages[chatId] ?: return null
        val forkPoint = chatMessages[fromMessageId] ?: return null

        // 标记从分叉点之后的活跃路径为非活跃
        deactivateSubtree(chatId, fromMessageId)

        // 设置新的活跃 tip 为分叉点
        activeTips[chatId] = fromMessageId

        val branch = ConversationBranch(
            id = "branch_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}",
            chatId = chatId,
            fromMessageId = fromMessageId,
            label = label
        )
        branches.computeIfAbsent(chatId) { mutableListOf() }.add(branch)
        return branch
    }

    /**
     * 回到某条消息重新提问
     *
     * 等同于 fork + addMessage
     */
    fun rewindAndAsk(chatId: String, toMessageId: String, newQuestion: String): BranchMessage? {
        val branch = fork(chatId, toMessageId, "回溯到 ${toMessageId.takeLast(8)}")
            ?: return null
        return addMessage(chatId, BranchMessage.Role.USER, newQuestion, branchLabel = branch.label)
    }

    /**
     * 切换到某个分支
     */
    fun switchToBranch(chatId: String, branchId: String): Boolean {
        val chatBranches = branches[chatId] ?: return false
        val branch = chatBranches.find { it.id == branchId } ?: return false

        // 找到分支的第一条消息
    val chatMessages = messages[chatId] ?: return false
        val branchStartMsg = chatMessages.values.find {
            it.parentId == branch.fromMessageId && it.branchLabel == branch.label
        } ?: return false

        // 停用所有
        chatMessages.values.forEach { (id, msg) ->
            chatMessages[id] = msg.copy(isActive = false)
        }

        // 启用从根到分支末尾的路径
    val path = mutableListOf<String>()
        var current: BranchMessage? = branchStartMsg
        while (current != null) {
            path.add(current.id)
            chatMessages[current.id] = current.copy(isActive = true)
            current = current.childrenIds.lastOrNull()?.let { chatMessages[it] }
        }

        activeTips[chatId] = path.lastOrNull() ?: branch.fromMessageId
        return true
    }

    /**
     * 获取活跃路径（当前显示的对话）
     */
    fun getActivePath(chatId: String): List<BranchMessage> {
        val chatMessages = messages[chatId] ?: return emptyList()
        val path = mutableListOf<BranchMessage>()

        // 从活跃 tip 反向追溯到根
    var currentId = activeTips[chatId]
        while (currentId != null) {
            val msg = chatMessages[currentId] ?: break
            path.add(0, msg)
            currentId = msg.parentId
        }
        return path
    }

    /**
     * 获取完整分支树
     */
    fun getBranchTree(chatId: String): BranchTree {
        val chatMessages = messages[chatId] ?: mutableMapOf()
        val roots = chatMessages.values.filter { it.parentId == null }.sortedBy { it.timestamp }
        val chatBranches = branches[chatId]?.toList() ?: emptyList()
        val activePath = getActivePath(chatId).map { it.id }
        return BranchTree(
            chatId = chatId,
            rootMessages = roots,
            allMessages = chatMessages.toMap(),
            branches = chatBranches,
            activePath = activePath
        )
    }

    /**
     * 获取某消息的所有子分支选项
     */
    fun getBranchOptions(chatId: String, messageId: String): List<BranchOption> {
        val chatMessages = messages[chatId] ?: return emptyList()
        val msg = chatMessages[messageId] ?: return emptyList()
        return msg.childrenIds.mapNotNull { childId ->
            val child = chatMessages[childId] ?: return@mapNotNull null
            BranchOption(
                messageId = childId,
                label = child.branchLabel ?: child.content.take(30),
                content = child.content,
                timestamp = child.timestamp,
                isActive = child.isActive
            )
        }
    }

    /**
     * 生成分支可视化文本
     */
    fun visualizeTree(chatId: String): String {
        val tree = getBranchTree(chatId)
        val sb = StringBuilder()
        sb.appendLine("═══ 对话分支树 ═══")
        fun render(msg: BranchMessage, indent: String, isLast: Boolean) {
            val prefix = if (indent.isEmpty()) "" else if (isLast) "└─ " else "├─ "
        val activeMark = if (msg.isActive) " ★" else ""
        val branchMark = msg.branchLabel?.let { "  [$it]" } ?: ""
        val contentPreview = msg.content.take(40).replace("\n", " ")
            sb.appendLine("$indent$prefix[${msg.role}] $contentPreview$branchMark$activeMark")
        val children = msg.childrenIds.mapNotNull { tree.allMessages[it] }
            children.forEachIndexed { i, child ->
                val newIndent = if (indent.isEmpty()) "" else if (isLast) "   " else "│  "
                render(child, indent + newIndent, i == children.size - 1)
            }
        }

        tree.rootMessages.forEach { root ->
            render(root, "", true)
        }
        sb.appendLine("═══════════════════")
        sb.appendLine("★ = 当前活跃路径")
        return sb.toString()
    }

    /**
     * 清空对话
     */
    fun clear(chatId: String) {
        messages.remove(chatId)
        branches.remove(chatId)
        activeTips.remove(chatId)
    }

    // ============ 内部方法 ============
    private fun deactivateSubtree(chatId: String, fromMessageId: String) {
        val chatMessages = messages[chatId] ?: return
        val from = chatMessages[fromMessageId] ?: return
        // 把 from 之后的所有消息标记为非活跃
    val queue: ArrayDeque<String> = ArrayDeque(from.childrenIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
        val msg = chatMessages[id] ?: continue
            chatMessages[id] = msg.copy(isActive = false)
            queue.addAll(msg.childrenIds)
        }
    }

    data class BranchOption(
        val messageId: String,
        val label: String,
        val content: String,
        val timestamp: Long,
        val isActive: Boolean
    )
}
