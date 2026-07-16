package com.apex.agent.orchestration.agent

import com.apex.agent.core.multiagent.Agent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AgentInstance(private val agent: Agent) {

    enum class Status {
        STOPPED,
        RUNNING,
        PAUSED
    }

    var status: Status = Status.STOPPED
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var isPaused = false
    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    private val taskHistory = mutableListOf<AgentTaskRecord>()

    fun start() {
        if (job?.isActive == true) {
            return
        }

        status = Status.RUNNING
        isPaused = false

        job = scope.launch {
            try {
                initializeAgent()

                while (isActive) {
                    if (!isPaused) {
                        val message = messageChannel.tryReceive().getOrNull()
                        if (message != null) {
                            processMessage(message)
                        } else {
                            performIdleThought()
                        }
                        delay(100)
                    } else {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                taskHistory.add(
                    AgentTaskRecord(
                        task = "error",
                        status = "FAILED",
                        timestamp = System.currentTimeMillis(),
                        result = e.message ?: "Unknown error"
                    )
                )
            } finally {
                status = Status.STOPPED
            }
        }
    }

    fun stop() {
        isPaused = false
        job?.cancel()
        job = null
        status = Status.STOPPED
    }

    fun pause() {
        if (status == Status.RUNNING) {
            isPaused = true
            status = Status.PAUSED
        }
    }

    fun resume() {
        if (status == Status.PAUSED) {
            isPaused = false
            status = Status.RUNNING
        }
    }

    /** 发送消息给 Agent */
    fun sendMessage(message: String) {
        messageChannel.trySend(message)
    }

    /** 获取 Agent 的任务历?*/
    fun getTaskHistory(): List<AgentTaskRecord> {
        return taskHistory.toList()
    }

    /** 初始?Agent 环境 */
    private fun initializeAgent() {
        taskHistory.add(
            AgentTaskRecord(
                task = "initialize",
                status = "COMPLETED",
                timestamp = System.currentTimeMillis(),
                result = "Agent '${agent.name}' initialized with role: ${agent.role.take(50)}"
            )
        )
    }

    /** 处理一条消?*/
    private fun processMessage(message: String) {
        val taskRecord = AgentTaskRecord(
            task = message.take(30),
            status = "PROCESSING",
            timestamp = System.currentTimeMillis()
        )
        taskHistory.add(taskRecord)

        val processedResult = "[Agent ${agent.name}] Processing: ${message.take(50)}"
        taskRecord.status = "COMPLETED"
        taskRecord.result = processedResult
    }

    /** 空闲思?- 轻量级自我检?*/
    private fun performIdleThought() {
        if (System.currentTimeMillis() % 10000 < 100) {
            taskHistory.add(
                AgentTaskRecord(
                    task = "idle_check",
                    status = "COMPLETED",
                    timestamp = System.currentTimeMillis(),
                    result = "Agent '${agent.name}' monitoring..."
                )
            )
        }
    }
}
