package com.apex.agent.core.patterns

import java.util.Stack

/**
 * 命令模式 - 支持可逆操作、命令历史与批量执行
 * 用于将请求封装为对象，支持撤销/重做、日志记录和事务性操作
 */

/** 命令执行结果 */
sealed class CommandResult<out T> {
    data class Success<T>(val data: T) : CommandResult<T>()
    data class Failure(val error: String, val cause: Throwable? = null) : CommandResult<Nothing>()
}

/**
 * Agent命令接口
 * @param T 执行结果类型
 */
interface AgentCommand<T> {
    /** 命令唯一标识 */
    val id: String get() = java.util.UUID.randomUUID().toString()
    /** 命令名称 */
    val name: String
    /** 是否支持撤销 */
    val isReversible: Boolean get() = false
    /** 执行命令 */
    suspend fun execute(): CommandResult<T>
    /** 撤销命令 */
    suspend fun undo(): CommandResult<T>
}

/** 命令历史记录项 */
data class CommandEntry<T>(
    val command: AgentCommand<T>,
    val timestamp: Long = System.currentTimeMillis(),
    val result: CommandResult<T>? = null
)

/**
 * 命令历史管理器 - 基于栈的撤销/重做，支持最大大小限制和批量操作
 */
class CommandHistory(private val maxSize: Int = 50) {

    private val undoStack = Stack<CommandEntry<*>>()
        private val redoStack = Stack<CommandEntry<*>>()
        val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /** 推入执行历史 */
    fun push(entry: CommandEntry<*>) {
        if (undoStack.size >= maxSize) {
            undoStack.removeAt(0)
        }
        undoStack.push(entry)
        redoStack.clear()
    }

    /** 弹出用于撤销 */
    fun popUndo(): CommandEntry<*>? = if (undoStack.isNotEmpty()) undoStack.pop() else null

    /** 推入重做栈 */
    fun pushRedo(entry: CommandEntry<*>) {
        if (redoStack.size >= maxSize) {
            redoStack.removeAt(0)
        }
        redoStack.push(entry)
    }

    /** 弹出用于重做 */
    fun popRedo(): CommandEntry<*>? = if (redoStack.isNotEmpty()) redoStack.pop() else null

    /** 获取完整历史 */
    fun getHistory(): List<CommandEntry<*>> = undoStack.toList()

    /** 批量推入事务 */
    fun pushBatch(entries: List<CommandEntry<*>>) {
        entries.forEach { push(it) }
    }

    /** 清空历史 */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }
}

/**
 * 命令调用器 - 统一管理命令的执行、撤销、重做
 */
class CommandInvoker {
    val history = CommandHistory()

    suspend fun <T> execute(command: AgentCommand<T>): CommandResult<T> {
        val result = command.execute()
        history.push(CommandEntry(command, result = result))
        return result
    }

    suspend fun undo(): CommandResult<*>? {
        val entry = history.popUndo() ?: return null
        if (!entry.command.isReversible) {
            history.pushRedo(entry)
        return CommandResult.Failure("Command ${entry.command.name} is not reversible")
        }
        val result = entry.command.undo()
        history.pushRedo(entry.copy(result = result))
        return result
    }

    suspend fun redo(): CommandResult<*>? {
        val entry = history.popRedo() ?: return null
        val result = entry.command.execute()
        history.push(entry.copy(result = result))
        return result
    }
        fun getHistory(): List<CommandEntry<*>> = history.getHistory()
}

/** 发送消息命令 */
class SendMessageCommand(
    private val content: String,
    private val sessionId: String
) : AgentCommand<String> {
    override val name = "SendMessage"
    override val isReversible = false

    override suspend fun execute(): CommandResult<String> {
        return if (content.isNotBlank()) {
            CommandResult.Success("Message sent to session $sessionId: ${content.take(50)}")
        } else {
            CommandResult.Failure("Message content cannot be empty")
        }
    }

    override suspend fun undo(): CommandResult<String> {
        return CommandResult.Failure("Cannot undo message sending")
    }
}

/** 执行工具命令 */
class ExecuteToolCommand(
    private val toolName: String,
    private val params: Map<String, String>
) : AgentCommand<String> {
    override val name = "ExecuteTool:$toolName"
    override val isReversible = true

    override suspend fun execute(): CommandResult<String> {
        return CommandResult.Success("Executed $toolName with ${params.size} params")
    }

    override suspend fun undo(): CommandResult<String> {
        return CommandResult.Success("Undone execution of $toolName")
    }
}

/** 修改配置命令 */
class ModifyConfigCommand(
    private val key: String,
    private val oldValue: String,
    private val newValue: String
) : AgentCommand<String> {
    override val name = "ModifyConfig"
    override val isReversible = true

    override suspend fun execute(): CommandResult<String> {
        return CommandResult.Success("Config $key changed from $oldValue to $newValue")
    }

    override suspend fun undo(): CommandResult<String> {
        return CommandResult.Success("Config $key restored to $oldValue")
    }
}
