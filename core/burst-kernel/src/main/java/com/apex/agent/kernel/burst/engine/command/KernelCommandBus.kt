package com.apex.agent.kernel.burst.engine.command

import java.util.concurrent.ConcurrentHashMap

/**
 * E9: 内核命令总线
 *
 * 命令模式 + 处理链：
 * - 统一的命令接口
 * - 命令路由
 * - 命令拦截器
 * - 命令历史
 */
class KernelCommandBus {

    sealed class KernelCommand {
        abstract val commandId: String
        abstract val timestamp: Long

        data class ExecuteSkill(override val commandId: String, val skillId: String, val taskDescription: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class CancelTask(override val commandId: String, val taskId: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class PauseKernel(override val commandId: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class ResumeKernel(override val commandId: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class UpdateConfig(override val commandId: String, val key: String, val value: Any, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class ReloadPlugin(override val commandId: String, val pluginId: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class RunDiagnostics(override val commandId: String, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class Shutdown(override val commandId: String, val force: Boolean = false, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
        data class Custom(override val commandId: String, val type: String, val payload: Map<String, Any>, override val timestamp: Long = System.currentTimeMillis()) : KernelCommand()
    }

    data class CommandResult(
        val commandId: String,
        val success: Boolean,
        val result: Any? = null,
        val error: String? = null,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CommandInterceptor(
        val name: String,
        val priority: Int = 0,
        val intercept: (KernelCommand) -> KernelCommand?
    )

    fun interface CommandHandler<T : KernelCommand> {
        suspend fun handle(command: T): CommandResult
    }

    private val handlers = ConcurrentHashMap<Class<out KernelCommand>, CommandHandler<*>>()
    private val interceptors = mutableListOf<CommandInterceptor>()
    private val commandHistory = mutableListOf<Pair<KernelCommand, CommandResult>>()

    fun <T : KernelCommand> registerHandler(type: Class<T>, handler: CommandHandler<T>) {
        handlers[type] = handler
    }

    fun addInterceptor(interceptor: CommandInterceptor) {
        interceptors.add(interceptor)
        interceptors.sortBy { it.priority }
    }

    suspend fun execute(command: KernelCommand): CommandResult {
        val start = System.currentTimeMillis()
        var current: KernelCommand? = command

        // 拦截器链
        for (interceptor in interceptors) {
            current = interceptor.intercept(current ?: return CommandResult(
                command.commandId, false, error = "被拦截器 ${interceptor.name} 拦截",
                durationMs = System.currentTimeMillis() - start
            ))
        }

        val cmd = current ?: return CommandResult(
            command.commandId, false, error = "命令被拦截",
            durationMs = System.currentTimeMillis() - start
        )

        // 路由到处理器
        val handler = handlers[cmd::class.java]
        val result = if (handler != null) {
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as CommandHandler<KernelCommand>).handle(cmd)
            } catch (e: Exception) {
                CommandResult(cmd.commandId, false, error = e.message, durationMs = System.currentTimeMillis() - start)
            }
        } else {
            CommandResult(cmd.commandId, false, error = "无处理器: ${cmd::class.simpleName}", durationMs = System.currentTimeMillis() - start)
        }

        commandHistory.add(cmd to result)
        while (commandHistory.size > 500) commandHistory.removeAt(0)

        return result
    }

    fun getHistory(limit: Int = 100): List<Pair<KernelCommand, CommandResult>> = commandHistory.takeLast(limit)
    fun getRegisteredCommands(): Set<Class<out KernelCommand>> = handlers.keys
    fun getInterceptors(): List<CommandInterceptor> = interceptors.toList()
}
