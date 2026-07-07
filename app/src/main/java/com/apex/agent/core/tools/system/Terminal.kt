package com.apex.agent.core.tools.system

import android.content.Context
import android.os.Build
import com.apex.agent.util.AppLogger
import androidx.annotation.RequiresApi
import com.apex.agent.terminal.CommandExecutionEvent
import com.apex.agent.terminal.SessionDirectoryEvent
import com.apex.agent.terminal.TerminalManager
import com.apex.agent.terminal.data.TerminalState
import com.apex.agent.terminal.provider.type.HiddenExecResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import java.util.UUID

/**
 * 终端管理�?* 提供应用程序级别的终端服务管理和访问
 */
@RequiresApi(Build.VERSION_CODES.O)
class Terminal private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: Terminal? = null

        fun getInstance(context: Context): Terminal {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Terminal(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val TAG = "Terminal"
    }

    private val terminalManager = TerminalManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.Main)

    // ，TerminalManager 暴露状态和事件�?   val commandEvents: SharedFlow<CommandExecutionEvent> = terminalManager.commandExecutionEvents
    val directoryEvents: SharedFlow<SessionDirectoryEvent> = terminalManager.directoryChangeEvents
    val terminalState: StateFlow<TerminalState> = terminalManager.terminalState
    val sessions = terminalManager.sessions
    val currentSessionId = terminalManager.currentSessionId
    val currentDirectory = terminalManager.currentDirectory
    val isInteractiveMode = terminalManager.isInteractiveMode
    val interactivePrompt = terminalManager.interactivePrompt
    val isFullscreen = terminalManager.isFullscreen

    /**
     * 初始化终端管理器
     */
    suspend fun initialize(): Boolean {
        return terminalManager.initializeEnvironment()
    }

    /**
     * 销毁终端管理器
     */
    fun destroy() {
        terminalManager.cleanup()
    }

    /**
     * 创建新的终端会话 - 同步等待初始化完�?    */
    suspend fun createSession(title: String? = null): String {
        AppLogger.d(TAG, "Creating new terminal session and waiting for initialization")
        val newSession = terminalManager.createNewSession(title)
        AppLogger.d(TAG, "Session ${newSession.id} initialized successfully")
        return newSession.id
    }
    
    /**
     * 切换到指定会�?    */
    fun switchToSession(sessionId: String) {
        terminalManager.switchToSession(sessionId)
    }

    /**
     * 关闭终端会话
     */
    fun closeSession(sessionId: String) {
        terminalManager.closeSession(sessionId)
    }

    /**
     * 执行命令并等待其完成（不切换当前会话�?    */
    suspend fun executeCommand(sessionId: String, command: String): String? {
        val deferred = CompletableDeferred<String>()
        val output = StringBuilder()
        var completionOutput: String? = null
        
        // 生成命令ID
        val commandId = java.util.UUID.randomUUID().toString()
        
        val collectorReady = CompletableDeferred<Unit>()
        
        // 先开始订阅事件流，然后再发送命�?       val job = scope.launch {
            commandEvents
                .filter { it.sessionId == sessionId && it.commandId == commandId }
                .onStart { collectorReady.complete(Unit) } // 发出信号，表示已准备好收�?               .collect { event ->
                    if (event.isCompleted) {
                        completionOutput = event.outputChunk
                    } else {
                        output.append(event.outputChunk)
                    }
                    if (event.isCompleted) {
                        deferred.complete(completionOutput?.takeIf { it.isNotEmpty() } ?: output.toString())
                    }
                }
        }

        // 等待收集器准备就�?       collectorReady.await()
        
        // 直接向指定会话发送命令，不切换当前会�?       terminalManager.sendCommandToSession(sessionId, command, commandId)

        val result = deferred.await()
        
        job.cancel()
        
        return result
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult {
        return terminalManager.executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    /**
     * 执行命令 - Flow版本
     * 返回命令执行过程中的所有事件，直到命令完成
     */
    fun executeCommandFlow(sessionId: String, command: String): Flow<CommandExecutionEvent> {
        return channelFlow {
            val commandId = UUID.randomUUID().toString()
            val collectorReady = CompletableDeferred<Unit>()

            val collectorJob = launch {
                commandEvents
                    .filter { it.sessionId == sessionId && it.commandId == commandId }
                    .onStart { collectorReady.complete(Unit) }
                    .transformWhile { event ->
                        emit(event)
                        !event.isCompleted
                    }
                    .collect { sentEvent ->
                        send(sentEvent)
                    }
            }

            // 先确保事件收集器就绪，再发送命令，避免快命令输出在订阅前丢失败            collectorReady.await()
            terminalManager.sendCommandToSession(sessionId, command, commandId)
            collectorJob.join()
        }
    }
    
    /**
     * 发送输入到当前会话
     */
    fun sendInput(sessionId: String, input: String) {
        terminalManager.switchToSession(sessionId)
        terminalManager.sendInput(input)
    }

    /**
     * 发送中断信�?Ctrl+C)
     */
    fun sendInterruptSignal(sessionId: String) {
        terminalManager.switchToSession(sessionId)
        terminalManager.sendInterruptSignal()
    }

    /**
     * 检查服务是否已连接 (现在总是返回 true)
     */
    fun isConnected(): Boolean {
        return true
    }
}
